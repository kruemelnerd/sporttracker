package local.runningdashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class TrainingPlanExceptionHandler {
    @ExceptionHandler(TrainingPlanException.class)
    ResponseEntity<ErrorResponse> handle(TrainingPlanException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    record ErrorResponse(String code, String message) { }
}
