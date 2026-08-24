package org.black_ixx.playerpoints.models;

import java.util.Objects;
import java.util.UUID;

public class TemporaryPointGrant {

    private final UUID grantId;
    private final UUID playerId;
    private final int amount;
    private final long expiresAt;

    public TemporaryPointGrant(UUID grantId, UUID playerId, int amount, long expiresAt) {
        this.grantId = Objects.requireNonNull(grantId, "grantId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        if (amount <= 0)
            throw new IllegalArgumentException("amount must be positive");
        if (expiresAt <= 0)
            throw new IllegalArgumentException("expiresAt must be positive");
        this.amount = amount;
        this.expiresAt = expiresAt;
    }

    public UUID getGrantId() {
        return this.grantId;
    }

    public UUID getPlayerId() {
        return this.playerId;
    }

    public int getAmount() {
        return this.amount;
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof TemporaryPointGrant))
            return false;
        TemporaryPointGrant other = (TemporaryPointGrant) obj;
        return this.amount == other.amount
                && this.expiresAt == other.expiresAt
                && this.grantId.equals(other.grantId)
                && this.playerId.equals(other.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.grantId, this.playerId, this.amount, this.expiresAt);
    }

}
