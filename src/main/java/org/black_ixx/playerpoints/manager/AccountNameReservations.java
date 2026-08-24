package org.black_ixx.playerpoints.manager;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class AccountNameReservations {

    private final Map<String, CompletableFuture<UUID>> reservations;

    AccountNameReservations() {
        this.reservations = new ConcurrentHashMap<>();
    }

    private static UUID await(CompletableFuture<UUID> reservation) {
        try {
            return reservation.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException) cause;
            if (cause instanceof Error)
                throw (Error) cause;
            throw failure;
        }
    }

    static String normalize(String accountName) {
        return Objects.requireNonNull(accountName, "accountName").toLowerCase(Locale.ROOT);
    }

    UUID getOrCreate(String accountName, Supplier<UUID> creator) {
        Objects.requireNonNull(creator, "creator");
        String normalizedName = normalize(accountName);
        CompletableFuture<UUID> reservation = new CompletableFuture<>();
        CompletableFuture<UUID> existing =
                this.reservations.putIfAbsent(normalizedName, reservation);
        if (existing != null)
            return await(existing);

        try {
            UUID accountId = Objects.requireNonNull(creator.get(), "creator returned null");
            reservation.complete(accountId);
            return accountId;
        } catch (RuntimeException | Error failure) {
            this.reservations.remove(normalizedName, reservation);
            reservation.completeExceptionally(failure);
            throw failure;
        }
    }

    UUID get(String accountName) {
        CompletableFuture<UUID> reservation = this.reservations.get(normalize(accountName));
        return reservation == null ? null : await(reservation);
    }

    void remove(String accountName, UUID accountId) {
        String normalizedName = normalize(accountName);
        this.reservations.computeIfPresent(normalizedName, (ignored, reservation) ->
                accountId.equals(reservation.getNow(null)) ? null : reservation);
    }

    void remove(UUID accountId) {
        for (Map.Entry<String, CompletableFuture<UUID>> entry : this.reservations.entrySet()) {
            if (accountId.equals(entry.getValue().getNow(null)))
                this.reservations.remove(entry.getKey(), entry.getValue());
        }
    }

    void clear() {
        this.reservations.clear();
    }

}
