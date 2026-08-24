package org.black_ixx.playerpoints.manager;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class StripedAccountLock {

    private final Object[] stripes;

    StripedAccountLock(int stripeCount) {
        if (stripeCount <= 0)
            throw new IllegalArgumentException("stripeCount must be positive");
        this.stripes = new Object[stripeCount];
        for (int index = 0; index < stripeCount; index++)
            this.stripes[index] = new Object();
    }

    <T> T withLock(UUID accountId, Supplier<T> action) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(action, "action");
        synchronized (this.stripes[this.stripeIndex(accountId)]) {
            return action.get();
        }
    }

    void runWithLock(UUID accountId, Runnable action) {
        this.withLock(accountId, () -> {
            action.run();
            return null;
        });
    }

    <T> T withLocks(Collection<UUID> accountIds, Supplier<T> action) {
        Objects.requireNonNull(accountIds, "accountIds");
        Objects.requireNonNull(action, "action");
        boolean[] selected = new boolean[this.stripes.length];
        int selectedCount = 0;
        for (UUID accountId : accountIds) {
            int index = this.stripeIndex(Objects.requireNonNull(accountId, "accountId"));
            if (!selected[index]) {
                selected[index] = true;
                selectedCount++;
            }
        }

        int[] indexes = new int[selectedCount];
        int offset = 0;
        for (int index = 0; index < selected.length; index++) {
            if (selected[index])
                indexes[offset++] = index;
        }
        return this.withIndexes(indexes, 0, action);
    }

    void runWithLocks(Collection<UUID> accountIds, Runnable action) {
        this.withLocks(accountIds, () -> {
            action.run();
            return null;
        });
    }

    <T> T withAllLocks(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        int[] indexes = new int[this.stripes.length];
        for (int index = 0; index < indexes.length; index++)
            indexes[index] = index;
        return this.withIndexes(indexes, 0, action);
    }

    void runWithAllLocks(Runnable action) {
        this.withAllLocks(() -> {
            action.run();
            return null;
        });
    }

    private <T> T withIndexes(int[] indexes, int offset, Supplier<T> action) {
        if (offset == indexes.length)
            return action.get();
        synchronized (this.stripes[indexes[offset]]) {
            return this.withIndexes(indexes, offset + 1, action);
        }
    }

    private int stripeIndex(UUID accountId) {
        return Math.floorMod(accountId.hashCode(), this.stripes.length);
    }

}
