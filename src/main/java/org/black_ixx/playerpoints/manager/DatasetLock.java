package org.black_ixx.playerpoints.manager;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

final class DatasetLock {

    private final ReentrantReadWriteLock lock;

    DatasetLock() {
        this.lock = new ReentrantReadWriteLock(true);
    }

    <T> T withRead(Supplier<T> action) {
        return this.withLock(this.lock.readLock(), action);
    }

    void runRead(Runnable action) {
        this.withRead(() -> {
            action.run();
            return null;
        });
    }

    <T> T withWrite(Supplier<T> action) {
        return this.withLock(this.lock.writeLock(), action);
    }

    void runWrite(Runnable action) {
        this.withWrite(() -> {
            action.run();
            return null;
        });
    }

    private <T> T withLock(Lock selectedLock, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        selectedLock.lock();
        try {
            return action.get();
        } finally {
            selectedLock.unlock();
        }
    }

}
