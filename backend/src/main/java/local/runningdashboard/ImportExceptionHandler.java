package local.runningdashboard;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class ImportExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ImportController.ImportResponse requestTooLarge() {
        return new ImportController.ImportResponse(List.of(), new ImportController.BatchError("REQUEST_TOO_LARGE", "The upload exceeds the configured request limit."));
    }
}
