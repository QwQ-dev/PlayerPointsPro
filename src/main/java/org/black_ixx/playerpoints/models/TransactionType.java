package org.black_ixx.playerpoints.models;

public enum TransactionType {

    /**
     * Used for /points give, giveall, and take
     */
    OFFSET,
    /**
     * Used for the /points pay sender
     */
    PAY_SENDER,
    /**
     * Used for the /points pay receiver
     */
    PAY_RECEIVER,
    /**
     * Used for /points set
     */
    SET,
    /**
     * Records a grant that expires at a later time.
     */
    TEMPORARY,
    /**
     * Removes points only from active temporary grants.
     */
    TEMPORARY_OFFSET,
    /**
     * Changes only the permanent balance.
     */
    PERMANENT_OFFSET,
    /**
     * Sets only the permanent balance.
     */
    SET_PERMANENT,
    /**
     * Replaces active temporary grants with a new grant.
     */
    SET_TEMPORARY,
    /**
     * Clears only the permanent balance.
     */
    RESET_PERMANENT,
    /**
     * Clears all temporary grants.
     */
    RESET_TEMPORARY

}
