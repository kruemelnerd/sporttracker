package local.runningdashboard;

import java.time.Duration;

public class GarminClientException extends RuntimeException {
    private final String code;
    private final Duration retryAfter;
    private final boolean complete;

    public GarminClientException(String code) { this(code, null, false); }
    public GarminClientException(String code, Duration retryAfter) { this(code, retryAfter, false); }
    private GarminClientException(String code, Duration retryAfter, boolean complete) {
        super(code);
        this.code = code;
        this.retryAfter = retryAfter;
        this.complete = complete;
    }

    public String code() { return code; }
    public Duration retryAfter() { return retryAfter; }
    public boolean complete() { return complete; }
}
