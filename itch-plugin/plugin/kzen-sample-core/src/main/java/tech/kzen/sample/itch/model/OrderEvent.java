package tech.kzen.sample.itch.model;


/** One event in an order's lifecycle after its add, in feed order. */
public sealed interface OrderEvent {
    long ordinal();
    long timestampNanos();

    /** Order Executed ({@code E}) or Executed With Price ({@code C}); [price] is the resting price for {@code E}. */
    record Executed(
            long ordinal, long timestampNanos, long shares, long price, long matchNumber, boolean printable
    ) implements OrderEvent {}

    /** Partial cancel ({@code X}). */
    record Cancelled(long ordinal, long timestampNanos, long shares) implements OrderEvent {}

    /** Delete ({@code D}): the remaining shares left the book. */
    record Deleted(long ordinal, long timestampNanos, long remainingShares) implements OrderEvent {}

    /** Replace ({@code U}): this order ended and [newReference] continues it with new shares and price. */
    record Replaced(long ordinal, long timestampNanos, long newReference, long shares, long price) implements OrderEvent {}
}
