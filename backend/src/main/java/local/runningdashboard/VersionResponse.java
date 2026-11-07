package local.runningdashboard;

import java.time.Instant;

public record VersionResponse(String applicationVersion, String installedCommit, String installedDigest,
                              String latestCommit, String latestDigest, VersionStatus updateStatus,
                              Instant lastCheckedAt, GhcrRegistryClient.ErrorStatus errorStatus) { }
