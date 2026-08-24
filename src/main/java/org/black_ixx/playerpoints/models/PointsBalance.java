package org.black_ixx.playerpoints.models;

public class PointsBalance {

    private final int permanent;
    private final int temporary;

    public PointsBalance(int permanent, int temporary) {
        if (permanent < 0)
            throw new IllegalArgumentException("permanent must not be negative");
        if (temporary < 0)
            throw new IllegalArgumentException("temporary must not be negative");
        try {
            // noinspection ResultOfMethodCallIgnored
            Math.addExact(permanent, temporary);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("balance total exceeds Integer.MAX_VALUE", ex);
        }
        this.permanent = permanent;
        this.temporary = temporary;
    }

    public int getPermanent() {
        return this.permanent;
    }

    public int getTemporary() {
        return this.temporary;
    }

    public int getTotal() {
        return this.permanent + this.temporary;
    }

}
