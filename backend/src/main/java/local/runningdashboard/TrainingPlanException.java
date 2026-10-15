package local.runningdashboard;

import org.springframework.http.HttpStatus;

class TrainingPlanException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    TrainingPlanException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }
}
