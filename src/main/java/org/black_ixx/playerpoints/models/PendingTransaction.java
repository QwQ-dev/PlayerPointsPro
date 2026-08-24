package org.black_ixx.playerpoints.models;

import java.util.UUID;

public class PendingTransaction {

    private final UpdateType updateType;
    private final TransactionType transactionType;
    private final String sourceDescription;
    private final UUID source;
    private final int amount;
    private final long expiresAt;
    private final UUID temporaryGrantId;

    public PendingTransaction(UpdateType updateType, TransactionType transactionType, String sourceDescription, UUID source, int amount) {
        this(updateType, transactionType, sourceDescription, source, amount, 0L, null);
    }

    private PendingTransaction(UpdateType updateType, TransactionType transactionType, String sourceDescription, UUID source,
                               int amount, long expiresAt, UUID temporaryGrantId) {
        this.updateType = updateType;
        this.transactionType = transactionType;
        this.sourceDescription = sourceDescription;
        this.source = source;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.temporaryGrantId = temporaryGrantId;
    }

    public static PendingTransaction temporary(String description, UUID source, int amount, long expiresAt) {
        return new PendingTransaction(UpdateType.TEMPORARY, TransactionType.TEMPORARY, description, source,
                amount, expiresAt, UUID.randomUUID());
    }

    public static PendingTransaction setTemporary(String description, UUID source,
                                                  int amount, long expiresAt) {
        return new PendingTransaction(UpdateType.SET_TEMPORARY,
                TransactionType.SET_TEMPORARY, description, source,
                amount, expiresAt, UUID.randomUUID());
    }

    public UpdateType getUpdateType() {
        return this.updateType;
    }

    public TransactionType getTransactionType() {
        return this.transactionType;
    }

    public String getSourceDescription() {
        return this.sourceDescription;
    }

    public UUID getSource() {
        return this.source;
    }

    public int getAmount() {
        return this.amount;
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    public UUID getTemporaryGrantId() {
        return this.temporaryGrantId;
    }

}
