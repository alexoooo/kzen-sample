package tech.kzen.sample.itch.model;


/** Aggregate displayed interest at one price: total shares and number of resting orders. */
public record BookLevel(
        long price,
        long shares,
        int orders
) {
    public BookLevel plus(long addedShares) {
        return new BookLevel(price, shares + addedShares, orders + 1);
    }

    /** Reduces displayed shares; [removesOrder] when the order leaves the level. Null when the level empties. */
    public BookLevel minus(long removedShares, boolean removesOrder) {
        long remainingShares = shares - removedShares;
        int remainingOrders = removesOrder ? orders - 1 : orders;
        if (remainingShares < 0 || remainingOrders < 0) {
            throw new IllegalStateException("Level " + price + " under-flowed: " + this
                    + " minus " + removedShares + (removesOrder ? " (order removed)" : ""));
        }
        return remainingOrders == 0 ? null : new BookLevel(price, remainingShares, remainingOrders);
    }
}
