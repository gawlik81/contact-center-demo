package com.contactcenter.support;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;

import java.util.Optional;

/**
 * ShedLock {@link LockProvider} that always grants the lock (BE-172) - for unit tests that build a job object
 * with {@code new} and only need a working {@link LockingTaskExecutor}. Real lock semantics are covered by the
 * Testcontainers tests ({@code ShedLockJobsIntegrationTest}).
 */
public final class NoOpLockProvider implements LockProvider {

    @Override
    public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
        return Optional.of(() -> { });
    }

    /** Executor that runs every task (never skips). */
    public static LockingTaskExecutor executor() {
        return new DefaultLockingTaskExecutor(new NoOpLockProvider());
    }
}
