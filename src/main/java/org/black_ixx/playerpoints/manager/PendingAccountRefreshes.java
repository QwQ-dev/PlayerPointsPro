package org.black_ixx.playerpoints.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class PendingAccountRefreshes {

    private final Set<UUID> pending;

    PendingAccountRefreshes() {
        this.pending = new LinkedHashSet<>();
    }

    synchronized void markPending(Collection<UUID> accountIds) {
        this.pending.addAll(accountIds);
    }

    void sendPending(Consumer<UUID> sender) {
        Objects.requireNonNull(sender, "sender");
        List<UUID> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(this.pending);
        }

        for (UUID accountId : snapshot) {
            synchronized (this) {
                if (!this.pending.remove(accountId))
                    continue;
            }

            try {
                sender.accept(accountId);
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    this.pending.add(accountId);
                }
                throw failure;
            }
        }
    }

}
