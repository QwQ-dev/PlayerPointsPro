package org.black_ixx.playerpoints;

import org.black_ixx.playerpoints.event.PlayerPointsChangeEvent;
import org.black_ixx.playerpoints.event.PlayerPointsResetEvent;
import org.black_ixx.playerpoints.manager.DataManager;
import org.black_ixx.playerpoints.manager.LocaleManager;
import org.black_ixx.playerpoints.models.DetailedPointsBalance;
import org.black_ixx.playerpoints.models.SortedPlayer;
import org.black_ixx.playerpoints.models.TransactionType;
import org.black_ixx.playerpoints.util.PointsUtils;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The API for the PlayerPoints plugin.
 * Used to manipulate a player's points balance.
 *
 * <p>Note: This API does not send any messages. Balance mutations return only after their database
 * transaction finishes, so callers using a remote database should run them asynchronously.
 */
public class PlayerPointsAPI {

    private final PlayerPoints plugin;

    public PlayerPointsAPI(PlayerPoints plugin) {
        this.plugin = plugin;
    }

    /**
     * Gets the name of the currency based on the amount of points.
     *
     * @param points The amount of points being represented
     * @return The name of the currency
     */
    public String getCurrencyName(int points) {
        return this.plugin.getManager(LocaleManager.class).getCurrencyName(points);
    }

    /**
     * @return The name of the currency when singular
     */
    public String getCurrencyNameSingular() {
        return this.plugin.getManager(LocaleManager.class).getCurrencyName(1);
    }

    /**
     * @return The name of the currency when plural
     */
    public String getCurrencyNamePlural() {
        return this.plugin.getManager(LocaleManager.class).getCurrencyName(2);
    }

    /**
     * Gives a player a specified amount of points
     *
     * @param playerId The player to give points to
     * @param amount The amount of points to give
     * @return true if the transaction was successful, false otherwise
     */
    public boolean give(@NotNull UUID playerId, int amount) {
        return this.give(playerId, null, amount);
    }

    /**
     * Gives a player a specified amount of points
     *
     * @param playerId The player to give points to
     * @param sourceId The player giving the points, nullable
     * @param amount The amount of points to give
     * @return true if the transaction was successful, false otherwise
     */
    public boolean give(@NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        Objects.requireNonNull(playerId);

        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(playerId, amount, TransactionType.OFFSET);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return false;

        if (amount == 0)
            return true;

        return this.plugin.getManager(DataManager.class).offsetPoints(TransactionType.OFFSET, playerId, amount > 0 ? "Give" : "Take", sourceId, event.getChange());
    }

    /**
     * Gives a player points that expire at a specified time.
     *
     * @param playerId  The player to give points to
     * @param amount    The amount of points to give, must be positive
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return true if the transaction was successful, false otherwise
     */
    public boolean giveTemporary(@NotNull UUID playerId, int amount, long expiresAt) {
        return this.giveTemporaryAndGetAmount(playerId, null, amount, expiresAt).isPresent();
    }

    /**
     * Gives a player points that expire at a specified time.
     *
     * @param playerId  The player to give points to
     * @param sourceId  The player giving the points, nullable
     * @param amount    The amount of points to give, must be positive
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return true if the transaction was successful, false otherwise
     */
    public boolean giveTemporary(@NotNull UUID playerId, @Nullable UUID sourceId, int amount, long expiresAt) {
        return this.giveTemporaryAndGetAmount(playerId, sourceId, amount, expiresAt).isPresent();
    }

    /**
     * Gives a player temporary points and returns the amount accepted by event listeners.
     *
     * @param playerId  The player to give points to
     * @param amount    The requested amount, must be positive
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return the amount granted, or empty if the transaction was rejected
     */
    public OptionalInt giveTemporaryAndGetAmount(@NotNull UUID playerId, int amount, long expiresAt) {
        return this.giveTemporaryAndGetAmount(playerId, null, amount, expiresAt);
    }

    /**
     * Gives a player temporary points and returns the amount accepted by event listeners.
     *
     * @param playerId  The player to give points to
     * @param sourceId  The player giving the points, nullable
     * @param amount    The requested amount, must be positive
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return the amount granted, or empty if the transaction was rejected
     */
    public OptionalInt giveTemporaryAndGetAmount(@NotNull UUID playerId, @Nullable UUID sourceId,
                                                 int amount, long expiresAt) {
        Objects.requireNonNull(playerId);
        if (amount <= 0 || expiresAt <= System.currentTimeMillis())
            return OptionalInt.empty();

        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(playerId, amount, TransactionType.TEMPORARY);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getChange() <= 0)
            return OptionalInt.empty();

        int acceptedAmount = event.getChange();
        boolean granted = this.plugin.getManager(DataManager.class).addTemporaryPoints(
                playerId, "Temporary Give", sourceId, acceptedAmount, expiresAt);
        return granted ? OptionalInt.of(acceptedAmount) : OptionalInt.empty();
    }

    /**
     * Gives a collection of players a specified amount of points
     *
     * @param playerIds The players to give points to
     * @param amount The amount of points to give
     * @return true if any transaction was successful, false otherwise
     */
    @NotNull
    public boolean giveAll(@NotNull Collection<UUID> playerIds, int amount) {
        return this.giveAll(playerIds, null, amount);
    }

    /**
     * Gives a collection of players a specified amount of points
     *
     * @param playerIds The players to give points to
     * @param sourceId The player giving the points, nullable
     * @param amount The amount of points to give
     * @return true if any transaction was successful, false otherwise
     */
    @NotNull
    public boolean giveAll(@NotNull Collection<UUID> playerIds, @Nullable UUID sourceId, int amount) {
        Objects.requireNonNull(playerIds);

        boolean success = false;
        for (UUID uuid : playerIds)
            success |= this.give(uuid, sourceId, amount);

        return success;
    }

    /**
     * Takes a specified amount of points from a player. Temporary points are spent before
     * permanent points, in expiration order.
     *
     * @param playerId The player to take points from
     * @param amount The amount of points to take
     * @return true if the transaction was successful, false otherwise
     */
    public boolean take(@NotNull UUID playerId, int amount) {
        return this.takeAndGetAmount(playerId, null, amount).isPresent();
    }

    /**
     * Takes a specified amount of points from a player. Temporary points are spent before
     * permanent points, in expiration order.
     *
     * @param playerId The player to take points from
     * @param sourceId The player taking the points, nullable
     * @param amount The amount of points to take
     * @return true if the transaction was successful, false otherwise
     */
    public boolean take(@NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.takeAndGetAmount(playerId, sourceId, amount).isPresent();
    }

    public OptionalInt takeAndGetAmount(@NotNull UUID playerId, @Nullable UUID sourceId,
                                        int amount) {
        return this.takeAndGetAmount(playerId, sourceId, amount, TakeMode.COMBINED);
    }

    /**
     * Takes points only from active temporary grants.
     */
    public boolean takeTemporary(@NotNull UUID playerId, int amount) {
        return this.takeTemporaryAndGetAmount(playerId, null, amount).isPresent();
    }

    public boolean takeTemporary(@NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.takeTemporaryAndGetAmount(playerId, sourceId, amount).isPresent();
    }

    public OptionalInt takeTemporaryAndGetAmount(@NotNull UUID playerId,
                                                 @Nullable UUID sourceId, int amount) {
        return this.takeAndGetAmount(playerId, sourceId, amount, TakeMode.TEMPORARY);
    }

    /**
     * Takes points only from the permanent balance.
     */
    public boolean takePermanent(@NotNull UUID playerId, int amount) {
        return this.takePermanentAndGetAmount(playerId, null, amount).isPresent();
    }

    public boolean takePermanent(@NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.takePermanentAndGetAmount(playerId, sourceId, amount).isPresent();
    }

    public OptionalInt takePermanentAndGetAmount(@NotNull UUID playerId,
                                                 @Nullable UUID sourceId, int amount) {
        return this.takeAndGetAmount(playerId, sourceId, amount, TakeMode.PERMANENT);
    }

    private OptionalInt takeAndGetAmount(@NotNull UUID playerId, @Nullable UUID sourceId,
                                         int amount, TakeMode mode) {
        Objects.requireNonNull(playerId);
        if (amount <= 0)
            return OptionalInt.empty();

        TransactionType transactionType = mode == TakeMode.TEMPORARY
                ? TransactionType.TEMPORARY_OFFSET
                : mode == TakeMode.PERMANENT
                ? TransactionType.PERMANENT_OFFSET
                : TransactionType.OFFSET;
        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(
                playerId, -amount, transactionType);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getChange() >= 0
                || event.getChange() == Integer.MIN_VALUE) {
            return OptionalInt.empty();
        }

        int acceptedAmount = -event.getChange();
        DataManager dataManager = this.plugin.getManager(DataManager.class);
        boolean taken;
        switch (mode) {
            case TEMPORARY:
                taken = dataManager.takeTemporaryPoints(
                        playerId, "Temporary Take", sourceId, acceptedAmount);
                break;
            case PERMANENT:
                taken = dataManager.takePermanentPoints(
                        playerId, "Permanent Take", sourceId, acceptedAmount);
                break;
            default:
                taken = dataManager.offsetPoints(TransactionType.OFFSET,
                        playerId, "Take", sourceId, -acceptedAmount);
                break;
        }
        return taken ? OptionalInt.of(acceptedAmount) : OptionalInt.empty();
    }

    /**
     * Looks at the number of points a player has
     *
     * @param playerId The player to give points to
     * @return the amount of points a player has
     */
    public int look(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId);

        return this.plugin.getManager(DataManager.class).getEffectivePoints(playerId);
    }

    /**
     * Gets a player's permanent balance and each active temporary grant.
     *
     * @param playerId The player to look up
     * @return an immutable snapshot of the effective balance
     */
    public DetailedPointsBalance lookDetailed(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId);

        return this.plugin.getManager(DataManager.class).getDetailedBalance(playerId);
    }

    /**
     * Looks at the active temporary portion of a player's points.
     *
     * @param playerId The player to look up
     * @return the amount of active temporary points the player has
     */
    public int lookTemporary(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId);

        return this.plugin.getManager(DataManager.class).getEffectiveTemporaryPoints(playerId);
    }

    /**
     * Looks at the permanent portion of a player's points.
     *
     * @param playerId The player to look up
     * @return the amount of permanent points the player has
     */
    public int lookPermanent(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId);

        return this.plugin.getManager(DataManager.class).getEffectivePermanentPoints(playerId);
    }

    /**
     * Looks at the number of points a player has formatted with number separators
     *
     * @param playerId The player to give points to
     * @return the amount of points a player has
     */
    public String lookFormatted(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId);

        return PointsUtils.formatPoints(this.plugin.getManager(DataManager.class).getEffectivePoints(playerId));
    }

    /**
     * Looks at the number of points a player has formatted as shorthand notation
     *
     * @param playerId The player to give points to
     * @return the amount of points a player has
     */
    public String lookShorthand(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId);

        return PointsUtils.formatPointsShorthand(this.plugin.getManager(DataManager.class).getEffectivePoints(playerId));
    }

    /**
     * Takes permanent points from a source player and gives permanent points to a target player.
     * Temporary points are included in displayed balances but cannot be transferred.
     *
     * @param sourceId The player to take points from
     * @param targetId The player to give points to
     * @param amount The amount of points to take/give, must be positive
     * @return true if the transaction was successful, false otherwise
     */
    public boolean pay(@NotNull UUID sourceId, @NotNull UUID targetId, int amount) {
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(targetId);

        PlayerPointsChangeEvent takeEvent = new PlayerPointsChangeEvent(sourceId, -amount, TransactionType.PAY_SENDER);
        Bukkit.getPluginManager().callEvent(takeEvent);
        if (takeEvent.isCancelled() || -takeEvent.getChange() <= 0) // If the giving amount is now 0 or negative, cancel the payment
            return false;

        PlayerPointsChangeEvent giveEvent = new PlayerPointsChangeEvent(targetId, amount, TransactionType.PAY_RECEIVER);
        Bukkit.getPluginManager().callEvent(giveEvent);
        if (giveEvent.isCancelled() || giveEvent.getChange() <= 0)
            return false;

        long sourceAmount = -(long) takeEvent.getChange();
        if (sourceAmount > Integer.MAX_VALUE)
            return false;
        return this.plugin.getManager(DataManager.class).transferPoints(
                sourceId, targetId, (int) sourceAmount, giveEvent.getChange());
    }

    /**
     * Sets a player's permanent points to a specified amount and clears all temporary points.
     *
     * @param playerId The player to set the points of
     * @param amount The amount of points to set to
     * @return true if the transaction was successful, false otherwise
     */
    public boolean set(@NotNull UUID playerId, int amount) {
        return this.set(playerId, null, amount);
    }

    /**
     * Sets a player's permanent points to a specified amount and clears all temporary points.
     *
     * @param playerId The player to set the points of
     * @param sourceId The player taking the points, nullable
     * @param amount The amount of points to set to
     * @return true if the transaction was successful, false otherwise
     */
    public boolean set(@NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        Objects.requireNonNull(playerId);

        DataManager dataManager = this.plugin.getManager(DataManager.class);
        int points = dataManager.getEffectivePoints(playerId);
        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(playerId, amount - points, TransactionType.SET);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return false;

        return dataManager.setPoints(TransactionType.SET, playerId, "Set", sourceId, points + event.getChange());
    }

    /**
     * Sets only a player's permanent balance and preserves temporary grants.
     */
    public boolean setPermanent(@NotNull UUID playerId, int amount) {
        return this.setPermanent(playerId, null, amount);
    }

    public boolean setPermanent(@NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.setPermanent(
                playerId, sourceId, amount, TransactionType.SET_PERMANENT, "Permanent Set");
    }

    private boolean setPermanent(@NotNull UUID playerId, @Nullable UUID sourceId,
                                 int amount, TransactionType transactionType,
                                 String sourceDescription) {
        Objects.requireNonNull(playerId);
        if (amount < 0)
            return false;

        DataManager dataManager = this.plugin.getManager(DataManager.class);
        int current = dataManager.getEffectivePermanentPoints(playerId);
        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(
                playerId, amount - current, transactionType);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return false;

        long adjusted = (long) current + event.getChange();
        if (adjusted < 0 || adjusted > Integer.MAX_VALUE)
            return false;
        return dataManager.setPermanentPoints(transactionType, playerId,
                sourceDescription, sourceId, (int) adjusted);
    }

    /**
     * Replaces all temporary grants with one grant containing the requested amount and expiry.
     */
    public boolean setTemporary(@NotNull UUID playerId, int amount, long expiresAt) {
        return this.setTemporary(playerId, null, amount, expiresAt);
    }

    public boolean setTemporary(@NotNull UUID playerId, @Nullable UUID sourceId,
                                int amount, long expiresAt) {
        Objects.requireNonNull(playerId);
        if (amount <= 0 || expiresAt <= System.currentTimeMillis())
            return false;

        DataManager dataManager = this.plugin.getManager(DataManager.class);
        int current = dataManager.getEffectiveTemporaryPoints(playerId);
        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(
                playerId, amount - current, TransactionType.SET_TEMPORARY);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return false;

        long adjusted = (long) current + event.getChange();
        if (adjusted <= 0 || adjusted > Integer.MAX_VALUE)
            return false;
        return dataManager.replaceTemporaryPoints(playerId,
                "Temporary Set", sourceId, (int) adjusted, expiresAt);
    }

    /**
     * Sets a player's permanent points to zero and clears all temporary points.
     *
     * @param playerId The player to reset the points of
     * @return true if the transaction was successful, false otherwise
     */
    public boolean reset(@NotNull UUID playerId) {
        return this.reset(playerId, null);
    }

    /**
     * Sets a player's permanent points to zero and clears all temporary points.
     *
     * @param playerId The player to reset the points of
     * @param sourceId The player resetting the points, nullable
     * @return true if the transaction was successful, false otherwise
     */
    public boolean reset(@NotNull UUID playerId, @Nullable UUID sourceId) {
        Objects.requireNonNull(playerId);

        PlayerPointsResetEvent event = new PlayerPointsResetEvent(playerId);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return false;

        return this.plugin.getManager(DataManager.class).setPoints(TransactionType.SET, playerId, "Reset", sourceId, 0);
    }

    /**
     * Clears only a player's permanent balance.
     */
    public boolean resetPermanent(@NotNull UUID playerId, @Nullable UUID sourceId) {
        Objects.requireNonNull(playerId);

        DataManager dataManager = this.plugin.getManager(DataManager.class);
        int current = dataManager.getEffectivePermanentPoints(playerId);
        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(
                playerId, -current, TransactionType.RESET_PERMANENT);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getChange() != -current)
            return false;
        return dataManager.setPermanentPoints(TransactionType.RESET_PERMANENT,
                playerId, "Permanent Reset", sourceId, 0);
    }

    public boolean resetPermanent(@NotNull UUID playerId) {
        return this.resetPermanent(playerId, null);
    }

    /**
     * Clears all temporary grants and preserves the permanent balance.
     */
    public boolean resetTemporary(@NotNull UUID playerId, @Nullable UUID sourceId) {
        Objects.requireNonNull(playerId);

        DataManager dataManager = this.plugin.getManager(DataManager.class);
        int current = dataManager.getEffectiveTemporaryPoints(playerId);
        PlayerPointsChangeEvent event = new PlayerPointsChangeEvent(
                playerId, -current, TransactionType.RESET_TEMPORARY);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getChange() != -current)
            return false;
        return dataManager.clearTemporaryPoints(
                playerId, "Temporary Reset", sourceId);
    }

    public boolean resetTemporary(@NotNull UUID playerId) {
        return this.resetTemporary(playerId, null);
    }

    /**
     * Executes {@link #give(UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player to give points to
     * @param amount   The amount of points to give
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> giveAsync(@NotNull UUID playerId, int amount) {
        return this.supplyAsync(() -> this.give(playerId, amount));
    }

    /**
     * Executes {@link #give(UUID, UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player to give points to
     * @param sourceId The player giving the points, nullable
     * @param amount   The amount of points to give
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> giveAsync(@NotNull UUID playerId,
                                                @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.give(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #giveTemporary(UUID, int, long)} on the plugin's asynchronous scheduler.
     *
     * @param playerId  The player to give temporary points to
     * @param amount    The amount of points to give
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> giveTemporaryAsync(@NotNull UUID playerId,
                                                         int amount, long expiresAt) {
        return this.supplyAsync(() -> this.giveTemporary(playerId, amount, expiresAt));
    }

    /**
     * Executes {@link #giveTemporary(UUID, UUID, int, long)} on the plugin's asynchronous scheduler.
     *
     * @param playerId  The player to give temporary points to
     * @param sourceId  The player giving the points, nullable
     * @param amount    The amount of points to give
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> giveTemporaryAsync(@NotNull UUID playerId,
                                                         @Nullable UUID sourceId,
                                                         int amount, long expiresAt) {
        return this.supplyAsync(() -> this.giveTemporary(
                playerId, sourceId, amount, expiresAt));
    }

    /**
     * Executes {@link #giveTemporaryAndGetAmount(UUID, int, long)} asynchronously.
     *
     * @param playerId  The player to give temporary points to
     * @param amount    The requested amount
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return a future containing the accepted amount, or empty if rejected
     */
    public CompletableFuture<OptionalInt> giveTemporaryAndGetAmountAsync(
            @NotNull UUID playerId, int amount, long expiresAt) {
        return this.supplyAsync(() -> this.giveTemporaryAndGetAmount(
                playerId, amount, expiresAt));
    }

    /**
     * Executes {@link #giveTemporaryAndGetAmount(UUID, UUID, int, long)} asynchronously.
     *
     * @param playerId  The player to give temporary points to
     * @param sourceId  The player giving the points, nullable
     * @param amount    The requested amount
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return a future containing the accepted amount, or empty if rejected
     */
    public CompletableFuture<OptionalInt> giveTemporaryAndGetAmountAsync(
            @NotNull UUID playerId, @Nullable UUID sourceId, int amount, long expiresAt) {
        return this.supplyAsync(() -> this.giveTemporaryAndGetAmount(
                playerId, sourceId, amount, expiresAt));
    }

    /**
     * Executes {@link #giveAll(Collection, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerIds The players to give points to
     * @param amount    The amount of points to give
     * @return a future containing whether any transaction committed
     */
    public CompletableFuture<Boolean> giveAllAsync(@NotNull Collection<UUID> playerIds,
                                                   int amount) {
        return this.supplyAsync(() -> this.giveAll(playerIds, amount));
    }

    /**
     * Executes {@link #giveAll(Collection, UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerIds The players to give points to
     * @param sourceId  The player giving the points, nullable
     * @param amount    The amount of points to give
     * @return a future containing whether any transaction committed
     */
    public CompletableFuture<Boolean> giveAllAsync(@NotNull Collection<UUID> playerIds,
                                                   @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.giveAll(playerIds, sourceId, amount));
    }

    /**
     * Executes {@link #take(UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player to take points from
     * @param amount   The amount of points to take
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> takeAsync(@NotNull UUID playerId, int amount) {
        return this.supplyAsync(() -> this.take(playerId, amount));
    }

    /**
     * Executes {@link #take(UUID, UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player to take points from
     * @param sourceId The player taking the points, nullable
     * @param amount   The amount of points to take
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> takeAsync(@NotNull UUID playerId,
                                                @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.take(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #takeAndGetAmount(UUID, UUID, int)} asynchronously.
     *
     * @param playerId The player to take points from
     * @param sourceId The player taking the points, nullable
     * @param amount   The requested amount
     * @return a future containing the accepted amount, or empty if rejected
     */
    public CompletableFuture<OptionalInt> takeAndGetAmountAsync(
            @NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.takeAndGetAmount(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #takeTemporary(UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player to take temporary points from
     * @param amount   The amount of points to take
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> takeTemporaryAsync(@NotNull UUID playerId, int amount) {
        return this.supplyAsync(() -> this.takeTemporary(playerId, amount));
    }

    /**
     * Executes {@link #takeTemporary(UUID, UUID, int)} asynchronously.
     *
     * @param playerId The player to take temporary points from
     * @param sourceId The player taking the points, nullable
     * @param amount   The amount of points to take
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> takeTemporaryAsync(@NotNull UUID playerId,
                                                         @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.takeTemporary(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #takeTemporaryAndGetAmount(UUID, UUID, int)} asynchronously.
     *
     * @param playerId The player to take temporary points from
     * @param sourceId The player taking the points, nullable
     * @param amount   The requested amount
     * @return a future containing the accepted amount, or empty if rejected
     */
    public CompletableFuture<OptionalInt> takeTemporaryAndGetAmountAsync(
            @NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.takeTemporaryAndGetAmount(
                playerId, sourceId, amount));
    }

    /**
     * Executes {@link #takePermanent(UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player to take permanent points from
     * @param amount   The amount of points to take
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> takePermanentAsync(@NotNull UUID playerId, int amount) {
        return this.supplyAsync(() -> this.takePermanent(playerId, amount));
    }

    /**
     * Executes {@link #takePermanent(UUID, UUID, int)} asynchronously.
     *
     * @param playerId The player to take permanent points from
     * @param sourceId The player taking the points, nullable
     * @param amount   The amount of points to take
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> takePermanentAsync(@NotNull UUID playerId,
                                                         @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.takePermanent(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #takePermanentAndGetAmount(UUID, UUID, int)} asynchronously.
     *
     * @param playerId The player to take permanent points from
     * @param sourceId The player taking the points, nullable
     * @param amount   The requested amount
     * @return a future containing the accepted amount, or empty if rejected
     */
    public CompletableFuture<OptionalInt> takePermanentAndGetAmountAsync(
            @NotNull UUID playerId, @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.takePermanentAndGetAmount(
                playerId, sourceId, amount));
    }

    /**
     * Executes {@link #pay(UUID, UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param sourceId The player to take points from
     * @param targetId The player to give points to
     * @param amount   The amount to transfer
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> payAsync(@NotNull UUID sourceId,
                                               @NotNull UUID targetId, int amount) {
        return this.supplyAsync(() -> this.pay(sourceId, targetId, amount));
    }

    /**
     * Executes {@link #set(UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose balance to set
     * @param amount   The new balance
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> setAsync(@NotNull UUID playerId, int amount) {
        return this.supplyAsync(() -> this.set(playerId, amount));
    }

    /**
     * Executes {@link #set(UUID, UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose balance to set
     * @param sourceId The player setting the points, nullable
     * @param amount   The new balance
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> setAsync(@NotNull UUID playerId,
                                               @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.set(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #setPermanent(UUID, int)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose permanent balance to set
     * @param amount   The new permanent balance
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> setPermanentAsync(@NotNull UUID playerId, int amount) {
        return this.supplyAsync(() -> this.setPermanent(playerId, amount));
    }

    /**
     * Executes {@link #setPermanent(UUID, UUID, int)} asynchronously.
     *
     * @param playerId The player whose permanent balance to set
     * @param sourceId The player setting the points, nullable
     * @param amount   The new permanent balance
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> setPermanentAsync(@NotNull UUID playerId,
                                                        @Nullable UUID sourceId, int amount) {
        return this.supplyAsync(() -> this.setPermanent(playerId, sourceId, amount));
    }

    /**
     * Executes {@link #setTemporary(UUID, int, long)} asynchronously.
     *
     * @param playerId  The player whose temporary balance to set
     * @param amount    The new temporary balance
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> setTemporaryAsync(@NotNull UUID playerId,
                                                        int amount, long expiresAt) {
        return this.supplyAsync(() -> this.setTemporary(playerId, amount, expiresAt));
    }

    /**
     * Executes {@link #setTemporary(UUID, UUID, int, long)} asynchronously.
     *
     * @param playerId  The player whose temporary balance to set
     * @param sourceId  The player setting the points, nullable
     * @param amount    The new temporary balance
     * @param expiresAt The expiration deadline in epoch milliseconds
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> setTemporaryAsync(@NotNull UUID playerId,
                                                        @Nullable UUID sourceId,
                                                        int amount, long expiresAt) {
        return this.supplyAsync(() -> this.setTemporary(
                playerId, sourceId, amount, expiresAt));
    }

    /**
     * Executes {@link #reset(UUID)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose balance to reset
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> resetAsync(@NotNull UUID playerId) {
        return this.supplyAsync(() -> this.reset(playerId));
    }

    /**
     * Executes {@link #reset(UUID, UUID)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose balance to reset
     * @param sourceId The player resetting the points, nullable
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> resetAsync(@NotNull UUID playerId,
                                                 @Nullable UUID sourceId) {
        return this.supplyAsync(() -> this.reset(playerId, sourceId));
    }

    /**
     * Executes {@link #resetPermanent(UUID)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose permanent balance to reset
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> resetPermanentAsync(@NotNull UUID playerId) {
        return this.supplyAsync(() -> this.resetPermanent(playerId));
    }

    /**
     * Executes {@link #resetPermanent(UUID, UUID)} asynchronously.
     *
     * @param playerId The player whose permanent balance to reset
     * @param sourceId The player resetting the points, nullable
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> resetPermanentAsync(@NotNull UUID playerId,
                                                          @Nullable UUID sourceId) {
        return this.supplyAsync(() -> this.resetPermanent(playerId, sourceId));
    }

    /**
     * Executes {@link #resetTemporary(UUID)} on the plugin's asynchronous scheduler.
     *
     * @param playerId The player whose temporary balance to reset
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> resetTemporaryAsync(@NotNull UUID playerId) {
        return this.supplyAsync(() -> this.resetTemporary(playerId));
    }

    /**
     * Executes {@link #resetTemporary(UUID, UUID)} asynchronously.
     *
     * @param playerId The player whose temporary balance to reset
     * @param sourceId The player resetting the points, nullable
     * @return a future containing whether the transaction committed
     */
    public CompletableFuture<Boolean> resetTemporaryAsync(@NotNull UUID playerId,
                                                          @Nullable UUID sourceId) {
        return this.supplyAsync(() -> this.resetTemporary(playerId, sourceId));
    }

    /**
     * Gets a List of a maximum number of players sorted by the number of points they have.
     *
     * @param limit The maximum number of players to get
     * @return a List of all players sorted by the number of points they have.
     */
    public List<SortedPlayer> getTopSortedPoints(int limit) {
        return this.plugin.getManager(DataManager.class).getTopSortedPoints(limit);
    }

    /**
     * @return a List of all players sorted by the number of points they have.
     */
    public List<SortedPlayer> getTopSortedPoints() {
        return this.plugin.getManager(DataManager.class).getTopSortedPoints();
    }

    /**
     * Gets a known PlayerPoints account UUID by its name.
     * If the name is for a Player account, returns the Player UUID.
     * Returns {@code null} for accounts that do not exist.
     *
     * @param name The name of the player/account
     * @return The UUID of the account
     */
    public UUID getAccountUUIDByName(@NotNull String name) {
        Objects.requireNonNull(name);

        return this.plugin.getManager(DataManager.class).lookupCachedUUID(name);
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            this.plugin.getScheduler().runTaskAsync(() -> {
                try {
                    future.complete(operation.get());
                } catch (RuntimeException | Error failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    private enum TakeMode {

        COMBINED,
        TEMPORARY,
        PERMANENT

    }

}
