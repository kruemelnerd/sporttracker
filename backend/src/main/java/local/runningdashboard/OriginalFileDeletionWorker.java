package local.runningdashboard;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OriginalFileDeletionWorker {
    private final ObjectProvider<DataManagementRepository> repositories;
    private final ObjectProvider<DataMutationCoordinator> coordinators;
    private final ObjectProvider<JdbcTemplate> jdbcTemplates;
    private final DataManagementRepository directRepository;
    private final DataMutationCoordinator directCoordinator;
    private final Path dataDirectory;

    OriginalFileDeletionWorker(DataManagementRepository repository, DataMutationCoordinator mutations,
                                      @Value("${running-dashboard.data-directory}") String dataDirectory) {
        this.repositories = null;
        this.coordinators = null;
        this.jdbcTemplates = null;
        this.directRepository = repository;
        this.directCoordinator = mutations;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
    }

    OriginalFileDeletionWorker(DataManagementRepository repository, DataMutationCoordinator mutations, Path dataDirectory) {
        this.repositories = null;
        this.coordinators = null;
        this.jdbcTemplates = null;
        this.directRepository = repository;
        this.directCoordinator = mutations;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    @Autowired
    OriginalFileDeletionWorker(ObjectProvider<DataManagementRepository> repositories,
                               ObjectProvider<DataMutationCoordinator> coordinators,
                               ObjectProvider<JdbcTemplate> jdbcTemplates,
                               @Value("${running-dashboard.data-directory}") String dataDirectory) {
        this.repositories = repositories;
        this.coordinators = coordinators;
        this.jdbcTemplates = jdbcTemplates;
        this.directRepository = null;
        this.directCoordinator = null;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        processPending();
    }

    public void processPending() {
        processPendingWithReport();
    }

    public CleanupReport processPendingWithReport() {
        if (directRepository == null && jdbcTemplates.getIfAvailable() == null) return new CleanupReport(0, 0, 0);
        DataManagementRepository repository = directRepository != null ? directRepository : repositories.getIfAvailable();
        DataMutationCoordinator mutations = directCoordinator != null ? directCoordinator : coordinators.getIfAvailable();
        if (repository == null || mutations == null) return new CleanupReport(0, 0, 0);
        var lease = mutations.tryAcquire();
        if (lease.isEmpty()) return new CleanupReport(0, repository.pendingFileDeletionCount(), 0);
        int deleted = 0;
        int failed = 0;
        try (var ignored = lease.get()) {
            Path root = dataDirectory.toRealPath();
            for (var job : repository.pendingFileDeletions()) {
                try {
                    Path relative = Path.of(job.relativePath());
                    if (relative.isAbsolute() || relative.getNameCount() < 2 || !"originals".equals(relative.getName(0).toString())) {
                        throw new IOException("Path is not a managed original FIT file");
                    }
                    for (Path part : relative) {
                        if (".".equals(part.toString()) || "..".equals(part.toString())) throw new IOException("Path traversal is not allowed");
                    }
                    if (repository.fileIsReferenced(job.relativePath())) {
                        repository.fileDeletionDone(job.id());
                        continue;
                    }
                    if (deleteSafely(root, relative)) deleted++;
                    repository.fileDeletionDone(job.id());
                } catch (IOException | RuntimeException failure) {
                    repository.fileDeletionFailed(job.id(), failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                    failed++;
                }
            }
        } catch (IOException failure) {
            for (var job : repository.pendingFileDeletions()) {
                repository.fileDeletionFailed(job.id(), "Data directory unavailable");
                failed++;
            }
        }
        return new CleanupReport(deleted, repository.pendingFileDeletionCount(), failed);
    }

    private static boolean deleteSafely(Path root, Path relative) throws IOException {
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
            if (!(rootStream instanceof SecureDirectoryStream<?>)) {
                throw new IOException("Filesystem does not support secure directory traversal");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) rootStream;
            List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
            opened.add(secureRoot);
            try {
                SecureDirectoryStream<Path> parent = secureRoot;
                for (int index = 0; index < relative.getNameCount() - 1; index++) {
                    parent = parent.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS);
                    opened.add(parent);
                }
                Path fileName = relative.getFileName();
                BasicFileAttributes attributes;
                try {
                    BasicFileAttributeView view = parent.getFileAttributeView(fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    attributes = view.readAttributes();
                } catch (NoSuchFileException missing) {
                    return false;
                }
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("Only regular files can be removed");
                parent.deleteFile(fileName);
                return true;
            } finally {
                for (int index = opened.size() - 1; index > 0; index--) opened.get(index).close();
            }
        }
    }

    public record CleanupReport(int deleted, long pending, int failed) { }
}
