package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionStatusTest {
    private static final String FIRST = "sha256:" + "a".repeat(64);
    private static final String SECOND = "sha256:" + "b".repeat(64);
    private static final String COMMIT = "c".repeat(40);

    @Test
    void comparesOnlyValidIdentities() {
        assertEquals(VersionStatus.CURRENT, VersionStatus.compare(FIRST, FIRST, null, null));
        assertEquals(VersionStatus.REBUILD_AVAILABLE, VersionStatus.compare(FIRST, SECOND, COMMIT, COMMIT));
        assertEquals(VersionStatus.DIFFERENT_RELEASE, VersionStatus.compare(FIRST, SECOND, COMMIT, "d".repeat(40)));
        assertEquals(VersionStatus.DIFFERENT_RELEASE, VersionStatus.compare(FIRST, SECOND, null, null));
        assertEquals(VersionStatus.UNKNOWN, VersionStatus.compare("invalid", SECOND, COMMIT, COMMIT));
    }

    @Test
    void newerReleaseRequiresProvenAncestryAndValidDistinctIdentities() {
        String otherCommit = "d".repeat(40);
        assertEquals(VersionStatus.UPDATE_AVAILABLE, VersionStatus.compare(FIRST, SECOND, COMMIT, otherCommit, true));
        assertEquals(VersionStatus.DIFFERENT_RELEASE, VersionStatus.compare(FIRST, SECOND, COMMIT, otherCommit, false));
        assertEquals(VersionStatus.CURRENT, VersionStatus.compare(FIRST, FIRST, COMMIT, otherCommit, true));
        assertEquals(VersionStatus.REBUILD_AVAILABLE, VersionStatus.compare(FIRST, SECOND, COMMIT, COMMIT, true));
        assertEquals(VersionStatus.UNKNOWN, VersionStatus.compare(null, SECOND, COMMIT, otherCommit, true));
        assertEquals(VersionStatus.DIFFERENT_RELEASE, VersionStatus.compare(FIRST, SECOND, null, otherCommit, true));
        assertEquals(VersionStatus.DIFFERENT_RELEASE, VersionStatus.compare(FIRST, SECOND, COMMIT, "short", true));
    }
}
