package local.runningdashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final GitHubCompareClient commits;
    private final Clock clock;
    private final String applicationVersion;
    private final String installedCommit;
    private final String installedDigest;
    private VersionResponse cached;
    private Instant expiresAt;

    @Autowired
    public VersionService(GhcrRegistryClient registry, GitHubCompareClient commits, BuildProperties build,
                          @Value("${SPORTTRACKER_IMAGE_DIGEST:}") String installedDigest) {
        this(registry, commits, build, installedDigest, Clock.systemUTC());
    }

    VersionService(GhcrRegistryClient registry, GitHubCompareClient commits, BuildProperties build, String installedDigest, Clock clock) {
        this.registry = registry;
        this.commits = commits;
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
            if (now.isBefore(expiresAt)
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
        var comparison = GitHubCompareClient.Comparison.NOT_AHEAD;
        if (VersionStatus.compare(installedDigest, latestDigest, installedCommit, latestCommit) == VersionStatus.DIFFERENT_RELEASE
                && VersionStatus.validCommit(installedCommit) && VersionStatus.validCommit(latestCommit)) {
            comparison = commits.compare(installedCommit, latestCommit);
        }
        cached = new VersionResponse(applicationVersion, installedCommit, installedDigest, latestCommit, latestDigest,
                VersionStatus.compare(installedDigest, latestDigest, installedCommit, latestCommit,
                        comparison == GitHubCompareClient.Comparison.AHEAD), clock.instant(), error);
        expiresAt = cached.lastCheckedAt().plus(error != null || comparison == GitHubCompareClient.Comparison.UNAVAILABLE
                ? FAILURE_TTL : SUCCESS_TTL);
        return cached;
    }
}
