package local.runningdashboard;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads metadata only. This is not a replacement for release attestation verification. */
@Service
public class GhcrRegistryClient {
    private static final String REPOSITORY = "/v2/kruemelnerd/sporttracker/";
    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    private static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    private static final String DOCKER_INDEX = "application/vnd.docker.distribution.manifest.list.v2+json";
    private static final String ACCEPT = String.join(", ", OCI_MANIFEST, DOCKER_MANIFEST, OCI_INDEX, DOCKER_INDEX);
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;
    private final long readTimeoutNanos;

    @Autowired
    public GhcrRegistryClient(
            @Value("${running-dashboard.registry.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${running-dashboard.registry.read-timeout:PT5S}") Duration readTimeout) {
        this(RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout)), readTimeout);
    }

    GhcrRegistryClient(RestClient.Builder builder) {
        this(builder, Duration.ofSeconds(5));
    }

    GhcrRegistryClient(RestClient.Builder builder, Duration readTimeout) {
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("Registry read timeout must be positive");
        }
        client = builder.baseUrl("https://ghcr.io").build();
        readTimeoutNanos = readTimeout.toNanos();
    }

    static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        if (connectTimeout == null || readTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("Registry timeouts must be positive");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public Release fetchStable() {
        try {
            var session = new Session();
            Response response = session.get(REPOSITORY + "manifests/stable", ACCEPT);
            requireSuccess(response);
            String digest = digest(response.body());
            checkDigest(response, digest);
            JsonNode manifest = manifest(response);
            if (isIndex(response)) {
                JsonNode selected = null;
                if (!manifest.path("manifests").isArray()) throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
                for (JsonNode descriptor : manifest.path("manifests")) {
                    JsonNode platform = descriptor.path("platform");
                    if ("linux".equals(platform.path("os").asString()) && "amd64".equals(platform.path("architecture").asString())) {
                        if (selected != null) return new Release(digest, null);
                        selected = descriptor;
                    }
                }
                if (selected == null) return new Release(digest, null);
                String platformDigest = selected.path("digest").asString("");
                requireDigest(platformDigest);
                response = session.get(REPOSITORY + "manifests/" + platformDigest, ACCEPT);
                requireSuccess(response);
                checkDigest(response, platformDigest);
                manifest = manifest(response);
                if (isIndex(response)) return new Release(digest, null);
            }
            // CI deploys linux/amd64; never substitute this child's digest for the top-level identity.
            return new Release(digest, revision(manifest, session));
        } catch (ResourceAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                    throw new RegistryException(ErrorStatus.TIMEOUT);
                }
            }
            throw new RegistryException(ErrorStatus.NETWORK_ERROR);
        } catch (RestClientException | JacksonException | IllegalArgumentException failure) {
            throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
        }
    }

    private String revision(JsonNode manifest, Session session) {
        JsonNode config = manifest.path("config");
        String configType = config.path("mediaType").asString("");
        if (!configType.equals("application/vnd.oci.image.config.v1+json")
                && !configType.equals("application/vnd.docker.container.image.v1+json")) {
            throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
        }
        String configDigest = config.path("digest").asString("");
        requireDigest(configDigest);
        Response response = session.get(REPOSITORY + "blobs/" + configDigest, "application/json");
        if (response.status() == 307) {
            String location = response.headers().getFirst(HttpHeaders.LOCATION);
            URI target = URI.create(location == null ? "" : location);
            if (!"https".equals(target.getScheme()) || !"pkg-containers.githubusercontent.com".equals(target.getRawAuthority())
                    || target.getRawFragment() != null || target.getRawPath() == null
                    || !target.getRawPath().matches("/ghcr[a-z0-9]+/blobs/" + Pattern.quote(configDigest))) {
                throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
            }
            // GHCR redirects config blobs to signed storage URLs; never forward its pull token.
            response = get(target, "application/json", null);
        }
        requireSuccess(response);
        checkDigest(response, configDigest);
        String revision = object(response.body()).path("config").path("Labels")
                .path("org.opencontainers.image.revision").asString("");
        return revision.matches("[0-9a-f]{40}") ? revision : null;
    }

    private Response get(String path, String accept, String token) {
        return get(URI.create("https://ghcr.io" + path), accept, token);
    }

    private Response get(URI target, String accept, String token) {
        long started = System.nanoTime();
        try {
            return client.get().uri(target).header(HttpHeaders.ACCEPT, accept)
                    .headers(headers -> { if (token != null) headers.setBearerAuth(token); })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] body = status == 200 ? response.getBody().readNBytes(MAX_METADATA_BYTES + 1) : new byte[0];
                        if (body.length > MAX_METADATA_BYTES) throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
                        return new Response(status, response.getHeaders(), body);
                    });
        } catch (ResourceAccessException failure) {
            // Spring may close a timed-out response body without an HttpTimeoutException cause.
            if (System.nanoTime() - started >= readTimeoutNanos) throw new RegistryException(ErrorStatus.TIMEOUT);
            throw failure;
        }
    }

    private final class Session {
        private String token;

        Response get(String path, String accept) {
            Response response = GhcrRegistryClient.this.get(path, accept, token);
            if (response.status() != 401 || token != null) return response;
            validateChallenge(response.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE));
            Response auth = GhcrRegistryClient.this.get(
                    "/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull", "application/json", null);
            requireSuccess(auth);
            JsonNode credentials = object(auth.body());
            String value = credentials.path("token").asString(credentials.path("access_token").asString(""));
            if (!value.matches("[A-Za-z0-9._~+/-]+=*")) throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
            token = value;
            return GhcrRegistryClient.this.get(path, accept, token);
        }
    }

    private static void validateChallenge(String challenge) {
        if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new RegistryException(ErrorStatus.AUTHENTICATION_REQUIRED);
        }
        var parameters = new HashMap<String, String>();
        var matcher = Pattern.compile("\\s*([a-zA-Z][a-zA-Z0-9_-]*)=\"([^\"]*)\"\\s*(?:,|$)").matcher(challenge.substring(7));
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end || parameters.put(matcher.group(1).toLowerCase(java.util.Locale.ROOT), matcher.group(2)) != null) {
                throw new RegistryException(ErrorStatus.AUTHENTICATION_REQUIRED);
            }
            end = matcher.end();
        }
        // Never follow an arbitrary auth realm or request write scopes from a remote challenge.
        if (end != challenge.length() - 7 || !"https://ghcr.io/token".equals(parameters.get("realm"))
                || !"ghcr.io".equals(parameters.get("service"))
                || (parameters.containsKey("scope") && !"repository:kruemelnerd/sporttracker:pull".equals(parameters.get("scope")))) {
            throw new RegistryException(ErrorStatus.AUTHENTICATION_REQUIRED);
        }
    }

    private static JsonNode manifest(Response response) {
        String contentType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!type.equals(OCI_MANIFEST) && !type.equals(DOCKER_MANIFEST) && !type.equals(OCI_INDEX) && !type.equals(DOCKER_INDEX)) {
            throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
        }
        JsonNode node = object(response.body());
        if (node.path("schemaVersion").asInt() != 2
                || (node.has("mediaType") && !type.equals(node.path("mediaType").asString()))) {
            throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
        }
        return node;
    }

    private static boolean isIndex(Response response) {
        String type = response.headers().getFirst(HttpHeaders.CONTENT_TYPE).split(";", 2)[0].trim();
        return type.equals(OCI_INDEX) || type.equals(DOCKER_INDEX);
    }

    private static JsonNode object(byte[] bytes) {
        JsonNode node = JSON.readTree(bytes);
        if (node == null || !node.isObject()) throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
        return node;
    }

    private static void checkDigest(Response response, String expected) {
        String header = response.headers().getFirst("Docker-Content-Digest");
        if (!digest(response.body()).equals(expected) || (header != null && !header.equals(expected))) {
            throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
        }
    }

    private static void requireDigest(String digest) {
        if (!digest.matches("sha256:[0-9a-f]{64}")) throw new RegistryException(ErrorStatus.INVALID_RESPONSE);
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void requireSuccess(Response response) {
        if (response.status() == 200) return;
        throw new RegistryException(switch (response.status()) {
            case 401 -> ErrorStatus.AUTHENTICATION_REQUIRED;
            case 403 -> ErrorStatus.ACCESS_DENIED;
            case 404 -> ErrorStatus.NOT_FOUND;
            case 429 -> ErrorStatus.RATE_LIMITED;
            default -> response.status() >= 500 ? ErrorStatus.REGISTRY_UNAVAILABLE : ErrorStatus.INVALID_RESPONSE;
        });
    }

    /** Top-level stable digest; commit may be absent (index commits refer to CI's linux/amd64 platform). */
    public record Release(String digest, String commit) { }
    public enum ErrorStatus { AUTHENTICATION_REQUIRED, ACCESS_DENIED, NOT_FOUND, RATE_LIMITED, REGISTRY_UNAVAILABLE, TIMEOUT, NETWORK_ERROR, INVALID_RESPONSE }

    /** Deliberately contains no remote body, token, URL, or original exception. */
    public static final class RegistryException extends RuntimeException {
        private final ErrorStatus status;

        RegistryException(ErrorStatus status) {
            super(status.name(), null, false, true);
            this.status = status;
        }

        public ErrorStatus status() { return status; }
    }

    private record Response(int status, HttpHeaders headers, byte[] body) { }
}
