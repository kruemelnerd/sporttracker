package local.runningdashboard;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class DataMutationCoordinator {
    private final ReentrantLock lock = new ReentrantLock(true);

    public Lease acquire() {
        lock.lock();
        return new Lease(lock);
    }

    public Optional<Lease> tryAcquire() {
        return lock.tryLock() ? Optional.of(new Lease(lock)) : Optional.empty();
    }

    public static final class Lease implements AutoCloseable {
        private final ReentrantLock lock;
        private boolean closed;

        private Lease(ReentrantLock lock) { this.lock = lock; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }
}
