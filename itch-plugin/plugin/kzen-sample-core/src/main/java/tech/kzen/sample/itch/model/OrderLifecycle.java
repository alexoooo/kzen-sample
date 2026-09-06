package tech.kzen.sample.itch.model;

import tech.kzen.sample.itch.message.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * One displayed order from its add to its end, immutable: each event yields a new lifecycle value with the
 * event appended (events per order are few, so an array copy beats a persistent vector here — measured in HS06).
 * A replace ({@code U}) ends this lifecycle and starts the successor's, which records {@link #replacedFrom}.
 */
public record OrderLifecycle(
        long reference,
        Side side,
        long originalShares,
        long price,
        long addOrdinal,
        long addTimestampNanos,
        String attribution,
        long replacedFrom,
        List<OrderEvent> events,
        long remainingShares,
        State state
) {
    public static final long noPredecessor = 0;

    public enum State { ACTIVE, FILLED, DELETED, REPLACED }


    public static OrderLifecycle added(
            long reference, Side side, long shares, long price, long addOrdinal, long addTimestampNanos,
            String attribution, long replacedFrom
    ) {
        return new OrderLifecycle(reference, side, shares, price, addOrdinal, addTimestampNanos, attribution,
                replacedFrom, List.of(), shares, State.ACTIVE);
    }


    public OrderLifecycle executed(OrderEvent.Executed event) {
        long remaining = reduce(event.shares(), event.ordinal());
        return with(event, remaining, remaining == 0 ? State.FILLED : State.ACTIVE);
    }

    public OrderLifecycle cancelled(OrderEvent.Cancelled event) {
        long remaining = reduce(event.shares(), event.ordinal());
        return with(event, remaining, remaining == 0 ? State.DELETED : State.ACTIVE);
    }

    public OrderLifecycle deleted(long ordinal, long timestampNanos) {
        return with(new OrderEvent.Deleted(ordinal, timestampNanos, remainingShares), 0, State.DELETED);
    }

    public OrderLifecycle replaced(OrderEvent.Replaced event) {
        return with(event, 0, State.REPLACED);
    }


    public long executedShares() {
        long total = 0;
        for (OrderEvent event : events) {
            if (event instanceof OrderEvent.Executed executed) {
                total += executed.shares();
            }
        }
        return total;
    }

    public int executionCount() {
        int count = 0;
        for (OrderEvent event : events) {
            if (event instanceof OrderEvent.Executed) {
                count++;
            }
        }
        return count;
    }

    /** Shares executed over shares originally displayed, in [0, 1]. */
    public double fillRatio() {
        return originalShares == 0 ? 0 : (double) executedShares() / originalShares;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** Ordinal of the event that ended the order, or -1 while active. */
    public long endOrdinal() {
        return state == State.ACTIVE ? -1 : events.getLast().ordinal();
    }


    private long reduce(long shares, long ordinal) {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Order " + reference + " is " + state + " at ordinal " + ordinal);
        }
        long remaining = remainingShares - shares;
        if (remaining < 0) {
            throw new IllegalStateException("Order " + reference + " over-reduced by " + shares
                    + " with " + remainingShares + " remaining at ordinal " + ordinal);
        }
        return remaining;
    }

    private OrderLifecycle with(OrderEvent event, long remaining, State newState) {
        List<OrderEvent> appended = new ArrayList<>(events.size() + 1);
        appended.addAll(events);
        appended.add(event);
        return new OrderLifecycle(reference, side, originalShares, price, addOrdinal, addTimestampNanos, attribution,
                replacedFrom, Collections.unmodifiableList(appended), remaining, newState);
    }
}
