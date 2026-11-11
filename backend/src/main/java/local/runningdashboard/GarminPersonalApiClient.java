package local.runningdashboard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class GarminPersonalApiClient {
    private static final String TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token";
    private static final String SEARCH_PATH = "/activitylist-service/activities/search/activities";
    private static final String DOWNLOAD_PATH = "/download-service/files/activity/";
    private static final String ACTIVITY_DETAIL_PATH = "/activity-service/activity/";
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(23);
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(6);

    private final GarminPersonalApiProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Token> token = new AtomicReference<>();

    public GarminPersonalApiClient(GarminPersonalApiProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public ActivitySearch listActivities(LocalDate oldest, LocalDate newest) {
        List<Activity> activities = new ArrayList<>();
        for (int page = 0; page < properties.maxPages(); page++) {
            int start = Math.multiplyExact(page, properties.pageSize());
            String query = "start=" + start + "&limit=" + properties.pageSize()
                    + "&startDate=" + encode(oldest.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    + "&endDate=" + encode(newest.format(DateTimeFormatter.ISO_LOCAL_DATE));
            JsonNode root = getJson(SEARCH_PATH + "?" + query);
            if (!root.isArray()) throw new GarminClientException("INVALID_RESPONSE");
            if (root.size() > properties.pageSize()) throw new GarminClientException("INVALID_RESPONSE");
            for (JsonNode item : root) {
                JsonNode id = item.path("activityId");
                JsonNode type = item.path("activityType").path("typeKey");
                if (!id.canConvertToLong() || id.asLong() < 1 || !type.isTextual()) {
                    throw new GarminClientException("INVALID_RESPONSE");
                }
                activities.add(new Activity(Long.toString(id.asLong()), type.asText()));
            }
            if (root.size() < properties.pageSize()) return new ActivitySearch(List.copyOf(activities), true);
        }
        throw new GarminClientException("PAGINATION_INCOMPLETE");
    }

    public Download downloadActivity(String externalId) {
        if (externalId == null || !externalId.matches("[1-9][0-9]{0,19}")) throw new GarminClientException("INVALID_EXTERNAL_ID");
        return authorizedRequest(DOWNLOAD_PATH + externalId, "application/zip, application/x-zip-compressed, application/octet-stream");
    }

    public TrainingMetrics activityTrainingMetrics(String externalId) {
        if (externalId == null || !externalId.matches("[1-9][0-9]{0,19}")) throw new GarminClientException("INVALID_EXTERNAL_ID");
        JsonNode detail = getJson(ACTIVITY_DETAIL_PATH + externalId);
        JsonNode summary = detail.path("summaryDTO");
        if (!summary.isObject()) throw new GarminClientException("INVALID_RESPONSE");
        return new TrainingMetrics(text(detail.path("activityName"), 200), metric(summary.path("averageSpeed"), 0, 20),
                metric(summary.path("trainingEffect"), 0, 5),
                metric(summary.path("anaerobicTrainingEffect"), 0, 5),
                metric(summary.path("activityTrainingLoad"), 0, 10_000));
    }

    private static String text(JsonNode value, int maximumLength) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= maximumLength ? value.asText() : null;
    }

    private static Double metric(JsonNode value, double minimum, double maximum) {
        if (!value.isNumber()) return null;
        double number = value.asDouble();
        return Double.isFinite(number) && number >= minimum && number <= maximum ? number : null;
    }

    private JsonNode getJson(String path) {
        Download response = authorizedRequest(path, "application/json");
        try { return json.readTree(response.body()); }
        catch (RuntimeException invalid) { throw new GarminClientException("INVALID_RESPONSE"); }
    }

    private Download authorizedRequest(String path, String accept) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String bearer = getToken();
            Download response = request(path, "GET", "Bearer " + bearer, accept, null);
            if (response.status() == 401 && attempt == 0) {
                token.set(null);
                continue;
            }
            if (response.status() != 200) throw error(response.status(), response.retryAfter());
            return response;
        }
        throw new GarminClientException("INVALID_API_KEY");
    }

    private String getToken() {
        Token cached = token.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();
        synchronized (token) {
            cached = token.get();
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();
            Download response = request(TOKEN_PATH, "POST", "ApiKey " + properties.apiKey(), "*/*", new byte[0]);
            if (response.status() != 200) throw error(response.status(), response.retryAfter());
            String value = new String(response.body(), StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) throw new GarminClientException("INVALID_RESPONSE");
            token.set(new Token(value, Instant.now().plus(TOKEN_LIFETIME)));
            return value;
        }
    }

    private Download request(String path, String method, String authorization, String accept, byte[] body) {
        long deadline = System.nanoTime() + properties.responseTimeout().toNanos();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
                    .timeout(properties.responseTimeout()).header("Authorization", authorization)
                    .header("Accept", accept).header("User-Agent", "running-dashboard/1.0");
            HttpRequest request = method.equals("POST") ? builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build() : builder.GET().build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                byte[] bytes = readBody(input, deadline);
                if (bytes.length > properties.maxResponseBytes()) throw new GarminClientException("RESPONSE_TOO_LARGE");
                return new Download(response.statusCode(), bytes,
                        response.headers().firstValue("Content-Type").orElse(null),
                        retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
            }
        } catch (GarminClientException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GarminClientException("NETWORK_ERROR");
        } catch (IOException | IllegalArgumentException failure) {
            throw new GarminClientException("NETWORK_ERROR");
        }
    }

    private byte[] readBody(java.io.InputStream input, long deadline) throws IOException, InterruptedException {
        var read = new java.util.concurrent.FutureTask<>(() -> input.readNBytes(properties.maxResponseBytes() + 1));
        Thread.ofVirtual().start(read);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
            return read.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            read.cancel(true);
            try { input.close(); } catch (IOException ignored) { }
            throw new IOException("Response body timed out", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof IOException ioFailure) throw ioFailure;
            throw new IOException(failure.getCause());
        }
    }

    private static GarminClientException error(int status, Duration retryAfter) {
        return switch (status) {
            case 401 -> new GarminClientException("INVALID_API_KEY");
            case 403 -> new GarminClientException("ACCESS_DENIED");
            case 404 -> new GarminClientException("FILE_NOT_AVAILABLE");
            case 429 -> new GarminClientException("RATE_LIMITED", retryAfter);
            default -> new GarminClientException(status >= 500 ? "GARMIN_UNAVAILABLE" : "REQUEST_REJECTED");
        };
    }

    private static Duration retryAfter(String value) {
        if (value == null) return null;
        try { return Duration.ofSeconds(Math.min(MAX_RETRY_AFTER.toSeconds(), Math.max(0, Long.parseLong(value)))); }
        catch (NumberFormatException ignored) {
            try {
                Duration delay = Duration.between(Instant.now(), java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return delay.isNegative() ? Duration.ZERO : delay.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : delay;
            } catch (java.time.format.DateTimeParseException invalid) { return null; }
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public record Activity(String id, String type) { }
    public record ActivitySearch(List<Activity> activities, boolean complete) { }
    public record Download(int status, byte[] body, String contentType, Duration retryAfter) { }
    public record TrainingMetrics(String activityName, Double averageSpeedMetersPerSecond,
                                  Double aerobicTrainingEffect,
                                  Double anaerobicTrainingEffect, Double trainingLoad) {
        public TrainingMetrics(Double aerobicTrainingEffect, Double anaerobicTrainingEffect, Double trainingLoad) {
            this(null, null, aerobicTrainingEffect, anaerobicTrainingEffect, trainingLoad);
        }
    }
    private record Token(String value, Instant expiresAt) { }
}
