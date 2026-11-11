package local.runningdashboard;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@Profile("!container & !production & !prod")
@ConditionalOnProperty(prefix = "running-dashboard.data-management", name = "enabled", havingValue = "true")
@RequestMapping("/api/data-management")
public class DataManagementController {
    private final DataManagementService service;
    private final SyncPauseRepository pauses;
    private final DataMutationCoordinator mutations;

    public DataManagementController(DataManagementService service, SyncPauseRepository pauses, DataMutationCoordinator mutations) {
        this.service = service;
        this.pauses = pauses;
        this.mutations = mutations;
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DataManagementService.Preview preview(@RequestBody ActionRequest body, HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 @RequestHeader(value = "Origin", required = false) String origin,
                                                 @RequestHeader(value = "Sec-Fetch-Site", required = false) String fetchSite) {
        guard(request, origin, fetchSite);
        response.setHeader("Cache-Control", "no-store");
        return service.preview(action(body.action()));
    }

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DataManagementService.Result confirm(@RequestBody ConfirmRequest body, HttpServletRequest request,
                                                HttpServletResponse response,
                                                @RequestHeader(value = "Origin", required = false) String origin,
                                                @RequestHeader(value = "Sec-Fetch-Site", required = false) String fetchSite) {
        guard(request, origin, fetchSite);
        response.setHeader("Cache-Control", "no-store");
        return service.confirm(action(body.action()), body.token(), body.phrase());
    }

    @PostMapping(value = "/sync/{provider}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> resume(@PathVariable String provider, HttpServletRequest request,
                                       @RequestHeader(value = "Origin", required = false) String origin,
                                       @RequestHeader(value = "Sec-Fetch-Site", required = false) String fetchSite) {
        guard(request, origin, fetchSite);
        if (!"GARMIN".equals(provider) && !"INTERVALS_ICU".equals(provider)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var lease = mutations.tryAcquire().orElseThrow(() -> new DataManagementService.DataManagementException(409, "WRITE_IN_PROGRESS"));
        try (lease) { pauses.resume(provider); }
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(DataManagementService.DataManagementException.class)
    public ResponseEntity<ErrorResponse> handle(DataManagementService.DataManagementException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code()));
    }

    private static DataManagementService.Action action(String action) {
        try { return DataManagementService.Action.valueOf(action); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown deletion action"); }
    }

    private static void guard(HttpServletRequest request, String origin, String fetchSite) {
        if (origin == null || origin.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origin required");
        if (fetchSite != null && !"same-origin".equalsIgnoreCase(fetchSite)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Same-origin request required");
        }
        try {
            URI supplied = URI.create(origin);
            String hostHeader = request.getHeader("Host");
            URI requestOrigin = hostHeader == null
                    ? URI.create(request.getScheme() + "://" + request.getServerName() + (request.getServerPort() < 0 ? "" : ":" + request.getServerPort()))
                    : URI.create(request.getScheme() + "://" + hostHeader);
            int requestPort = effectivePort(requestOrigin);
            int suppliedPort = supplied.getPort() < 0 ? ("https".equalsIgnoreCase(supplied.getScheme()) ? 443 : 80) : supplied.getPort();
            boolean validOrigin = supplied.getScheme() != null && supplied.getHost() != null && supplied.getRawUserInfo() == null
                    && (supplied.getRawPath() == null || supplied.getRawPath().isEmpty() || "/".equals(supplied.getRawPath()))
                    && supplied.getRawQuery() == null && supplied.getRawFragment() == null;
            if (!validOrigin || requestOrigin.getScheme() == null || requestOrigin.getHost() == null
                    || !requestOrigin.getScheme().equalsIgnoreCase(supplied.getScheme())
                    || !requestOrigin.getHost().equalsIgnoreCase(supplied.getHost()) || requestPort != suppliedPort) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Same-origin request required");
            }
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid Origin");
        }
        String contentType = request.getContentType();
        MediaType parsedType;
        try { parsedType = contentType == null ? null : MediaType.parseMediaType(contentType); }
        catch (IllegalArgumentException invalid) { parsedType = null; }
        if (parsedType == null || !MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(parsedType.getType() + "/" + parsedType.getSubtype())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JSON required");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public record ActionRequest(String action) { }
    public record ConfirmRequest(String action, String token, String phrase) { }
    public record ErrorResponse(String error) { }
}
