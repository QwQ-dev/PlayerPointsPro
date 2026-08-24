package org.black_ixx.playerpoints.util;

import org.black_ixx.playerpoints.models.DetailedPointsBalance;
import org.black_ixx.playerpoints.models.PendingTransaction;
import org.black_ixx.playerpoints.models.PointsBalance;
import org.black_ixx.playerpoints.models.TemporaryPointGrant;
import org.black_ixx.playerpoints.models.TransactionType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class PointsBalanceCalculator {

    private PointsBalanceCalculator() {

    }

    public static PointsBalance calculate(int permanent, Collection<TemporaryPointGrant> grants,
                                          Collection<PendingTransaction> pending, long now) {
        Calculation calculation = calculateAvailable(
                permanent, grants, pending, now);
        return toPointsBalance(calculation.permanent, calculation.available);
    }

    public static DetailedPointsBalance calculateDetailed(
            UUID playerId, int permanent, Collection<TemporaryPointGrant> grants,
            Collection<PendingTransaction> pending, long now) {
        Calculation calculation = calculateAvailable(
                permanent, grants, pending, now);
        PointsBalance balance = toPointsBalance(
                calculation.permanent, calculation.available);
        sortAvailable(calculation.available);

        List<TemporaryPointGrant> remainingGrants = new ArrayList<>();
        for (AvailableGrant grant : calculation.available) {
            if (grant.amount > 0) {
                remainingGrants.add(new TemporaryPointGrant(
                        grant.grantId, playerId, grant.amount, grant.expiresAt));
            }
        }
        return new DetailedPointsBalance(balance.getPermanent(), remainingGrants);
    }

    private static Calculation calculateAvailable(
            long permanent, Collection<TemporaryPointGrant> grants,
            Collection<PendingTransaction> pending, long now) {
        List<AvailableGrant> available = new ArrayList<>();
        for (TemporaryPointGrant grant : grants) {
            if (grant.getExpiresAt() > now) {
                available.add(new AvailableGrant(
                        grant.getGrantId(), grant.getAmount(), grant.getExpiresAt()));
            }
        }

        for (PendingTransaction transaction : pending) {
            switch (transaction.getUpdateType()) {
                case SET:
                    permanent = transaction.getAmount();
                    available.clear();
                    break;
                case SET_PERMANENT:
                    permanent = transaction.getAmount();
                    break;
                case SET_TEMPORARY:
                    available.clear();
                    if (transaction.getAmount() > 0
                            && transaction.getExpiresAt() > now) {
                        available.add(new AvailableGrant(
                                transaction.getTemporaryGrantId(),
                                transaction.getAmount(), transaction.getExpiresAt()));
                    }
                    break;
                case CLEAR_TEMPORARY:
                    available.clear();
                    break;
                case TEMPORARY:
                    if (transaction.getExpiresAt() > now) {
                        available.add(new AvailableGrant(transaction.getTemporaryGrantId(),
                                transaction.getAmount(), transaction.getExpiresAt()));
                    }
                    break;
                case OFFSET:
                    int amount = transaction.getAmount();
                    TransactionType transactionType = transaction.getTransactionType();
                    if (transactionType == TransactionType.PERMANENT_OFFSET
                            || amount >= 0
                            || transactionType == TransactionType.PAY_SENDER) {
                        permanent = Math.addExact(permanent, amount);
                        break;
                    }

                    long requested = -(long) amount;
                    long fromTemporary = consumeAvailable(available, requested);
                    if (transactionType == TransactionType.TEMPORARY_OFFSET) {
                        if (fromTemporary != requested)
                            throw new ArithmeticException("temporary balance would become negative");
                        break;
                    }
                    permanent -= requested - fromTemporary;
                    break;
                default:
                    throw new IllegalStateException("Invalid update type");
            }
        }

        return new Calculation(permanent, available);
    }

    private static PointsBalance toPointsBalance(long permanent, List<AvailableGrant> available) {
        long temporary = 0;
        for (AvailableGrant grant : available)
            temporary = Math.addExact(temporary, grant.amount);
        return toPointsBalance(permanent, temporary);
    }

    private static PointsBalance toPointsBalance(long permanent, long temporary) {
        if (permanent < 0 || permanent > Integer.MAX_VALUE || temporary < 0 || temporary > Integer.MAX_VALUE)
            throw new ArithmeticException("balance component is outside the integer range");
        return new PointsBalance((int) permanent, (int) temporary);
    }

    private static long consumeAvailable(List<AvailableGrant> available, long amount) {
        sortAvailable(available);
        long remaining = amount;
        for (AvailableGrant grant : available) {
            int consumed = (int) Math.min(remaining, grant.amount);
            grant.amount -= consumed;
            remaining -= consumed;
            if (remaining == 0)
                break;
        }
        return amount - remaining;
    }

    private static void sortAvailable(List<AvailableGrant> available) {
        available.sort(Comparator.comparingLong((AvailableGrant grant) -> grant.expiresAt)
                .thenComparing(grant -> grant.grantId.toString()));
    }

    private static class AvailableGrant {

        private final UUID grantId;
        private final long expiresAt;
        private int amount;

        private AvailableGrant(UUID grantId, int amount, long expiresAt) {
            this.grantId = grantId;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }

    }

    private static class Calculation {

        private final long permanent;
        private final List<AvailableGrant> available;

        private Calculation(long permanent, List<AvailableGrant> available) {
            this.permanent = permanent;
            this.available = available;
        }

    }

}
