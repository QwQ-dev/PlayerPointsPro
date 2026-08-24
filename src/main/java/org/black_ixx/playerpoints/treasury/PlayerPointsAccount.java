package org.black_ixx.playerpoints.treasury;

import me.lokka30.treasury.api.economy.account.PlayerAccount;
import me.lokka30.treasury.api.economy.currency.Currency;
import me.lokka30.treasury.api.economy.response.EconomyException;
import me.lokka30.treasury.api.economy.response.EconomyFailureReason;
import me.lokka30.treasury.api.economy.response.EconomySubscriber;
import me.lokka30.treasury.api.economy.transaction.EconomyTransaction;
import me.lokka30.treasury.api.economy.transaction.EconomyTransactionInitiator;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.manager.DataManager;

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerPointsAccount implements PlayerAccount {

    private final PlayerPoints plugin;
    private final UUID uuid;

    public PlayerPointsAccount(PlayerPoints plugin, UUID uuid) {
        this.plugin = plugin;
        this.uuid = uuid;
    }

    @Override
    public UUID getUniqueId() {
        return this.uuid;
    }

    static int toWholePoints(BigDecimal amount) {
        return amount.intValueExact();
    }

    private static EconomyException invalidAmount(ArithmeticException cause) {
        return new EconomyException(EconomyFailureReason.OTHER_FAILURE,
                "PlayerPoints only supports whole amounts within the signed 32-bit range", cause);
    }

    static <T> void runAsync(PlayerPoints plugin, EconomySubscriber<T> subscription,
                             TreasuryOperation<T> task) {
        plugin.getScheduler().runTaskAsync(() -> completeSafely(subscription, task));
    }

    static <T> void completeSafely(EconomySubscriber<T> subscription,
                                   TreasuryOperation<T> task) {
        T result;
        try {
            result = task.execute();
        } catch (EconomyException failure) {
            subscription.fail(failure);
            return;
        } catch (RuntimeException failure) {
            subscription.fail(new EconomyException(EconomyFailureReason.OTHER_FAILURE,
                    "PlayerPoints could not complete the request", failure));
            return;
        }
        subscription.succeed(result);
    }

    @Override
    public Optional<String> getName() {
        return Optional.of(this.plugin.getManager(DataManager.class)
                .lookupCachedUsername(this.uuid));
    }

    @Override
    public void retrieveBalance(Currency currency, EconomySubscriber<BigDecimal> subscription) {
        Objects.requireNonNull(currency);
        Objects.requireNonNull(subscription);

        if (!currency.isPrimary()) {
            subscription.fail(new EconomyException(EconomyFailureReason.CURRENCY_NOT_FOUND, "Currency is not supported"));
            return;
        }

        this.runAsync(subscription, () ->
                BigDecimal.valueOf(this.plugin.getAPI().look(this.uuid)));
    }

    @Override
    public void setBalance(BigDecimal amount, EconomyTransactionInitiator<?> initiator, Currency currency, EconomySubscriber<BigDecimal> subscription) {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(initiator);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(subscription);

        if (!currency.isPrimary()) {
            subscription.fail(new EconomyException(EconomyFailureReason.CURRENCY_NOT_FOUND, "Currency is not supported"));
            return;
        }

        if (amount.signum() < 0) {
            subscription.fail(new EconomyException(EconomyFailureReason.NEGATIVE_BALANCES_NOT_SUPPORTED,
                    "PlayerPoints does not support negative balances"));
            return;
        }

        int points;
        try {
            points = toWholePoints(amount);
        } catch (ArithmeticException e) {
            subscription.fail(invalidAmount(e));
            return;
        }

        this.runAsync(subscription, () -> {
            if (!this.plugin.getAPI().set(this.uuid, points))
                throw new EconomyException(EconomyFailureReason.OTHER_FAILURE,
                        "PlayerPoints rejected the balance change");
            return BigDecimal.valueOf(this.lookAfterCommitOrFallback(points));
        });
    }

    @Override
    public void doTransaction(EconomyTransaction economyTransaction, EconomySubscriber<BigDecimal> subscription) {
        Objects.requireNonNull(economyTransaction);
        Objects.requireNonNull(subscription);

        BigDecimal requestedAmount = economyTransaction.getTransactionAmount();
        if (requestedAmount.signum() < 0) {
            subscription.fail(new EconomyException(EconomyFailureReason.NEGATIVE_AMOUNT_SPECIFIED,
                    "Transaction amount must not be negative"));
            return;
        }

        int points;
        try {
            points = toWholePoints(requestedAmount);
        } catch (ArithmeticException e) {
            subscription.fail(invalidAmount(e));
            return;
        }
        this.runAsync(subscription, () -> {
            int previousBalance = this.plugin.getAPI().look(this.uuid);
            if (points == 0)
                return BigDecimal.valueOf(previousBalance);

            int fallback;
            switch (economyTransaction.getTransactionType()) {
                case DEPOSIT:
                    if (!this.plugin.getAPI().give(this.uuid, points))
                        throw new EconomyException(EconomyFailureReason.OTHER_FAILURE,
                                "PlayerPoints rejected the deposit");
                    long expected = (long) previousBalance + points;
                    fallback = expected <= Integer.MAX_VALUE
                            ? (int) expected : previousBalance;
                    break;

                case WITHDRAWAL:
                    if (!this.plugin.getAPI().take(this.uuid, points)) {
                        if (previousBalance < points) {
                            throw new EconomyException(
                                    EconomyFailureReason.NEGATIVE_BALANCES_NOT_SUPPORTED,
                                    "Insufficient balance");
                        }
                        throw new EconomyException(EconomyFailureReason.OTHER_FAILURE,
                                "PlayerPoints rejected the withdrawal");
                    }
                    fallback = Math.max(0, previousBalance - points);
                    break;

                default:
                    throw new EconomyException(
                            EconomyFailureReason.FEATURE_NOT_SUPPORTED,
                            "Transaction type not supported");
            }
            return BigDecimal.valueOf(this.lookAfterCommitOrFallback(fallback));
        });
    }

    private int lookAfterCommitOrFallback(int fallback) {
        try {
            return this.plugin.getAPI().look(this.uuid);
        } catch (RuntimeException failure) {
            this.plugin.getLogger().log(Level.WARNING,
                    "Unable to read the committed PlayerPoints balance for " + this.uuid,
                    failure);
            return fallback;
        }
    }

    private <T> void runAsync(EconomySubscriber<T> subscription, TreasuryOperation<T> task) {
        runAsync(this.plugin, subscription, task);
    }

    @Override
    public void deleteAccount(EconomySubscriber<Boolean> subscription) {
        Objects.requireNonNull(subscription);

        this.runAsync(subscription, () -> this.plugin.getAPI().reset(this.uuid));
    }

    @Override
    public void retrieveHeldCurrencies(EconomySubscriber<Collection<String>> subscription) {
        Objects.requireNonNull(subscription);

        subscription.fail(new EconomyException(EconomyFailureReason.FEATURE_NOT_SUPPORTED, "Only a primary currency is supported"));
    }

    @Override
    public void retrieveTransactionHistory(int transactionCount, Temporal from, Temporal to, EconomySubscriber<Collection<EconomyTransaction>> subscription) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(subscription);

        subscription.fail(new EconomyException(EconomyFailureReason.FEATURE_NOT_SUPPORTED, "Transaction history is not supported"));
    }

    @FunctionalInterface
    interface TreasuryOperation<T> {

        T execute() throws EconomyException;

    }

}
