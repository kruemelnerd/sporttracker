package local.runningdashboard;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

@Service
public class VersionService {
    private static final Duration SUCCESS_TTL = Duration.ofHours(6);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(1);
    private final GhcrRegistryClient registry;
    private final Clock clock;
    private final String applicationVersion;
    private final String installedCommit;
    private final String installedDigest;
    private VersionResponse cached;

    @Autowired
    public VersionService(GhcrRegistryClient registry, BuildProperties build,
                          @Value("${SPORTTRACKER_IMAGE_DIGEST:}") String installedDigest) {
        this(registry, build, installedDigest, Clock.systemUTC());
    }

    VersionService(GhcrRegistryClient registry, BuildProperties build, String installedDigest, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        applicationVersion = build.getVersion() == null || build.getVersion().isBlank() ? "development" : build.getVersion();
        String commit = build.get("commit");
        installedCommit = commit != null && commit.matches("[0-9a-f]{40}") ? commit : null;
        this.installedDigest = installedDigest != null && installedDigest.matches("sha256:[0-9a-f]{64}") ? installedDigest : null;
    }

    // One lazy check at a time; queued refreshes also observe the completed check's cooldown.
    public synchronized VersionResponse version(boolean refresh) {
        var now = clock.instant();
        if (cached != null) {
            Duration ttl = cached.errorStatus() == null ? SUCCESS_TTL : FAILURE_TTL;
            if (now.isBefore(cached.lastCheckedAt().plus(ttl))
                    && (!refresh || now.isBefore(cached.lastCheckedAt().plus(REFRESH_INTERVAL)))) {
                return cached;
            }
        }

        GhcrRegistryClient.Release latest = null;
        GhcrRegistryClient.ErrorStatus error = null;
        try {
            latest = registry.fetchStable();
        } catch (GhcrRegistryClient.RegistryException failure) {
            error = failure.status();
        }
        String latestCommit = latest == null ? null : latest.commit();
        String latestDigest = latest == null ? null : latest.digest();
        cached = new VersionResponse(applicationVersion, installedCommit, installedDigest, latestCommit, latestDigest,
                VersionStatus.compare(installedDigest, latestDigest, installedCommit, latestCommit), clock.instant(), error);
        return cached;
    }
}
