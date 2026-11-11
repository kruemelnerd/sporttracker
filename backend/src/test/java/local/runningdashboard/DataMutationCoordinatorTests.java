package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class DataMutationCoordinatorTests {
    @Autowired
    private ApplicationContext context;
    @Autowired private DataMutationCoordinator mutations;

    @Test
    void sharedWriterCoordinatorIsAvailableToImportAndSyncServices() {
        assertTrue(context.containsBean("dataMutationCoordinator"));
        assertNotNull(context.getBean("dataMutationCoordinator"));
    }

    @Test
    void nestedWriterLeaseIsReentrantButConcurrentDeleteCannotAcquireUntilWriterFinishes() throws Exception {
        try (var outer = mutations.acquire(); var nested = mutations.acquire();
             var reentrant = mutations.tryAcquire().orElseThrow(); var executor = Executors.newSingleThreadExecutor()) {
            assertFalse(executor.submit(() -> mutations.tryAcquire().isPresent()).get(2, TimeUnit.SECONDS));
        }
        try (var available = mutations.tryAcquire().orElseThrow()) { assertNotNull(available); }
    }
}
