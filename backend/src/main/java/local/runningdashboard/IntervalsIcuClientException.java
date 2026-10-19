package local.runningdashboard;

import java.time.Duration;

public class IntervalsIcuClientException extends RuntimeException {
    private final String code;
    private final Duration retryAfter;

    public IntervalsIcuClientException(String code, Duration retryAfter) {
        super(message(code));
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String code() { return code; }
    public Duration retryAfter() { return retryAfter; }

    private static String message(String code) {
        return switch (code) {
            case "INVALID_API_KEY" -> "Intervals.icu rejected the API key (401).";
            case "ACCESS_DENIED" -> "Intervals.icu denied access (403).";
            case "FILE_NOT_AVAILABLE" -> "The Intervals.icu activity or file is not available (404).";
            case "RATE_LIMITED" -> "Intervals.icu rate limit reached (429).";
            case "INTERVALS_ERROR" -> "Intervals.icu returned a server error.";
            default -> "Could not connect to Intervals.icu.";
        };
    }
}
