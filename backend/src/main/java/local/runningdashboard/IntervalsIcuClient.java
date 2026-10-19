package local.runningdashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class IntervalsIcuClient {
    private final RestClient client;
    private final int maxDownloadBytes;

    public IntervalsIcuClient(IntervalsIcuProperties properties, ImportProperties importProperties) {
        this.maxDownloadBytes = (int) Math.min(importProperties.maxRequestBytes(), Integer.MAX_VALUE - 1L);
        String credentials = Base64.getEncoder().encodeToString(("API_KEY:" + properties.apiKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.client = RestClient.builder().baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.USER_AGENT, "running-dashboard/1.0")
                .build();
    }

    public List<Activity> listActivities(LocalDate oldest, LocalDate newest) {
        try {
            List<Activity> activities = client.get()
                    .uri(uri -> uri.path("/api/v1/athlete/0/activities").queryParam("oldest", oldest).queryParam("newest", newest).build())
                    .retrieve().onStatus(HttpStatusCode::isError, (request, response) -> { throw exception(response.getStatusCode().value(), response.getHeaders().getFirst("Retry-After")); })
                    .body(new org.springframework.core.ParameterizedTypeReference<>() { });
            return activities == null ? List.of() : activities;
        } catch (IntervalsIcuClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new IntervalsIcuClientException("NETWORK_ERROR", null);
        } catch (RestClientResponseException exception) {
            throw exception(exception.getStatusCode().value(), exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After"));
        }
    }

    public DownloadedFile downloadActivityFile(String activityId) {
        try {
            return client.get().uri("/api/v1/activity/{id}/file", activityId).exchange((request, response) -> {
                if (response.getStatusCode().isError()) throw exception(response.getStatusCode().value(), response.getHeaders().getFirst("Retry-After"));
                byte[] body;
                try { body = response.getBody().readNBytes(maxDownloadBytes + 1); }
                catch (java.io.IOException failure) { throw new IntervalsIcuClientException("NETWORK_ERROR", null); }
                if (body.length > maxDownloadBytes) throw new IntervalsIcuClientException("FILE_TOO_LARGE", null);
                return new DownloadedFile(body, response.getHeaders().getContentType() == null ? null : response.getHeaders().getContentType().toString(), response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING));
            });
        } catch (IntervalsIcuClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new IntervalsIcuClientException("NETWORK_ERROR", null);
        }
    }

    private static IntervalsIcuClientException exception(int status, String retryAfter) {
        String code = switch (status) {
            case 401 -> "INVALID_API_KEY";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "FILE_NOT_AVAILABLE";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "INTERVALS_ERROR" : "NETWORK_ERROR";
        };
        return new IntervalsIcuClientException(code, "RATE_LIMITED".equals(code) ? parseRetryAfter(retryAfter) : null);
    }

    private static Duration parseRetryAfter(String value) {
        if (value == null) return null;
        try { return Duration.ofSeconds(Math.max(0, Long.parseLong(value))); }
        catch (NumberFormatException ignored) {
            try {
                Duration duration = Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return duration.isNegative() ? Duration.ZERO : duration;
            }
            catch (DateTimeParseException invalid) { return null; }
        }
    }

    public record Activity(String id, String type, @JsonProperty("start_date_local") String startDateLocal) { }
    public record DownloadedFile(byte[] body, String contentType, String contentEncoding) { }
}
