package org.black_ixx.playerpoints.manager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class PendingRefresh {

    private final AtomicBoolean pending;

    PendingRefresh() {
        this.pending = new AtomicBoolean();
    }

    void markPending() {
        this.pending.set(true);
    }

    boolean sendIfPending(Runnable sender) {
        Objects.requireNonNull(sender, "sender");
        if (!this.pending.compareAndSet(true, false))
            return false;

        try {
            sender.run();
            return true;
        } catch (RuntimeException | Error failure) {
            this.pending.set(true);
            throw failure;
        }
    }

}
