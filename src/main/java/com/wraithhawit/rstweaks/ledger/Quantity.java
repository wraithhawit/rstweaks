package com.wraithhawit.rstweaks.ledger;

/** An amount of one resource. Production has no fate to carry, so it needs no slot. */
public record Quantity(int resource, long amount) {
    public Quantity {
        if (resource < 0) {
            throw new IllegalArgumentException("not a resource: " + resource);
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("producing " + amount + " is not production");
        }
    }
}
