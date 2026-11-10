package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class OriginalFileDeletionWorkerTests {
    @Autowired private DataManagementRepository repository;
    @Autowired private DataMutationCoordinator mutations;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void deletesOnlyUnreferencedFilesAndResumesIdempotentlyAfterFileWasAlreadyRemoved(@TempDir Path root) throws Exception {
        String relative = "originals/synthetic.fit";
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "synthetic FIT fixture");
        long activity = jdbc.queryForObject("INSERT INTO activity (sport, start_time) VALUES ('running', CURRENT_TIMESTAMP) RETURNING id", Long.class);
        String hash = String.format("%064x", activity);
        jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes, original_path) VALUES (?, ?, 20, ?)", activity, hash, relative);
        repository.enqueueFiles(java.util.List.of(relative));
        var worker = new OriginalFileDeletionWorker(repository, mutations, root);

        worker.processPending();
        assertTrue(Files.exists(target), "a path referenced by import_file must be retained");
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = ?", String.class, relative));

        jdbc.update("DELETE FROM activity WHERE id = ?", activity);
        repository.enqueueFiles(java.util.List.of(relative));
        worker.processPending();
        assertFalse(Files.exists(target));
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = ?", String.class, relative));

        repository.enqueueFiles(java.util.List.of(relative));
        worker.processPending();
        assertFalse(Files.exists(target));
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = ?", String.class, relative));
    }

    @Test
    void refusesSymlinkTraversalAndPersistsFailureForRetry(@TempDir Path root) throws Exception {
        Path outside = Files.createTempFile("synthetic-fit-outside", ".fit");
        Path link = root.resolve("originals/outside.fit");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        repository.enqueueFiles(java.util.List.of("originals/outside.fit"));
        var worker = new OriginalFileDeletionWorker(repository, mutations, root);

        worker.processPending();

        assertTrue(Files.exists(outside));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = 'originals/outside.fit'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pending_file_deletion WHERE relative_path IN ('originals/outside.fit', 'originals/../outside.fit') AND status = 'FAILED'", Integer.class));

        Path inside = root.resolve("outside.fit");
        Files.writeString(inside, "must not be traversed");
        repository.enqueueFiles(java.util.List.of("originals/../outside.fit"));
        worker.processPending();
        assertTrue(Files.exists(inside));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = 'originals/../outside.fit'", String.class));

        String absolute = outside.toAbsolutePath().toString();
        repository.enqueueFiles(java.util.List.of(absolute));
        worker.processPending();
        assertTrue(Files.exists(outside));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = ?", String.class, absolute));
    }

    @Test
    void resumesAfterCrashBetweenFilesystemDeleteAndQueueCompletion(@TempDir Path root) throws Exception {
        String relative = "originals/crash-retry.fit";
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "synthetic FIT fixture");
        repository.enqueueFiles(java.util.List.of(relative));
        DataManagementRepository interrupted = new DataManagementRepository(jdbc) {
            @Override public void fileDeletionDone(long id) { throw new IllegalStateException("simulated process interruption"); }
        };

        new OriginalFileDeletionWorker(interrupted, mutations, root).processPending();
        assertFalse(Files.exists(target));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = ?", String.class, relative));

        new OriginalFileDeletionWorker(repository, mutations, root).processPending();
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM pending_file_deletion WHERE relative_path = ?", String.class, relative));
    }
}
