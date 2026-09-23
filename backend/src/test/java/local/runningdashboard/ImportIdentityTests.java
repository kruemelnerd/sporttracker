package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-identity-tests")
class ImportIdentityTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM activity WHERE start_time = ?", java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")));
    }

    @Test
    void keepsDifferentActivitiesWithTheSameStartTime() {
        AtomicInteger calls = new AtomicInteger();
        GarminFitFixtureAdapter adapter = new GarminFitFixtureAdapter() {
            @Override
            public DecodedActivity decode(InputStream ignored) {
                boolean first = calls.getAndIncrement() == 0;
                return new DecodedActivity("running", null, Instant.parse("2026-01-01T10:00:00Z"),
                        first ? 600d : 1800d, first ? 600d : 1800d, first ? 1000d : 5000d,
                        first ? 1.66d : 2.77d, null, null, null, null, null, List.of(), List.of());
            }
        };
        ImportService imports = new ImportService(adapter, jdbc, new TransactionTemplate(transactionManager),
                Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-identity-tests").toString(),
                new ImportProperties(10, 10_485_760, 104_857_600));

        assertEquals("IMPORTED", imports.importFiles(List.of(file("first.fit", new byte[] {1}))).outcomes().getFirst().status());
        assertEquals("IMPORTED", imports.importFiles(List.of(file("second.fit", new byte[] {2}))).outcomes().getFirst().status());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM activity WHERE start_time = ?", Integer.class,
                java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z"))));
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", bytes);
    }
}
