package local.runningdashboard;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FixtureSummaryController {
    private final GarminFitFixtureAdapter adapter;

    public FixtureSummaryController(GarminFitFixtureAdapter adapter) {
        this.adapter = adapter;
    }

    @GetMapping("/api/fixture-summary")
    FixtureSummary summary() {
        return adapter.decodeFixture();
    }

    @ExceptionHandler(FixtureDecodeException.class)
    ResponseEntity<Map<String, String>> fixtureDecodeFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "message", "Fixture could not be decoded"));
    }
}
