package local.runningdashboard;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {
    private final VersionService versions;

    public VersionController(VersionService versions) {
        this.versions = versions;
    }

    @GetMapping("/api/version")
    public ResponseEntity<VersionResponse> version(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(versions.version(refresh));
    }
}
