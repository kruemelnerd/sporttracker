package local.runningdashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class TrainingPlanExceptionHandler {
    @ExceptionHandler(TrainingPlanException.class)
    ResponseEntity<ErrorResponse> handle(TrainingPlanException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> invalidJson() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_TRAINING_PLAN", "JSON-Import ist ungültig oder enthält unbekannte Felder."));
    }

    record ErrorResponse(String code, String message) { }
}
