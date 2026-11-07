package local.runningdashboard;

enum VersionStatus {
    CURRENT, UPDATE_AVAILABLE, REBUILD_AVAILABLE, DIFFERENT_RELEASE, UNKNOWN;

    static VersionStatus compare(String installedDigest, String latestDigest, String installedCommit, String latestCommit) {
        if (!validDigest(installedDigest) || !validDigest(latestDigest)) return UNKNOWN;
        if (installedDigest.equals(latestDigest)) return CURRENT;
        if (validCommit(installedCommit) && installedCommit.equals(latestCommit)) return REBUILD_AVAILABLE;
        return DIFFERENT_RELEASE;
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("sha256:[0-9a-f]{64}");
    }

    private static boolean validCommit(String commit) {
        return commit != null && commit.matches("[0-9a-f]{40}");
    }
}
