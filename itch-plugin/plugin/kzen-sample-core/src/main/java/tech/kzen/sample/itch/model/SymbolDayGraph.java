package tech.kzen.sample.itch.model;

import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * The analytical graph of one symbol-day: every book state after each book-affecting message (a persistent
 * history), every displayed order's lifecycle, every trade event, and the broken match numbers. Built by one
 * fold over the symbol's messages in feed order; the result is immutable and its historical states never change.
 *
 * The active order index (reference → lifecycle) exists only inside the fold and only for live orders — it is
 * the per-symbol-day reconstruction state, bounded by the symbol's live depth, not a full-day map. All
 * lifecycles, live or ended, are retained in {@link #orders} in add order.
 */
public final class SymbolDayGraph {
    private final List<BookSnapshot> bookHistory;
    private final List<OrderLifecycle> orders;
    private final Map<Long, Integer> orderIndexByReference;
    private final List<TradeEvent> trades;
    private final Set<Long> brokenMatches;
    private final int peakActiveOrders;


    private SymbolDayGraph(
            List<BookSnapshot> bookHistory,
            List<OrderLifecycle> orders,
            Map<Long, Integer> orderIndexByReference,
            List<TradeEvent> trades,
            Set<Long> brokenMatches,
            int peakActiveOrders
    ) {
        this.bookHistory = bookHistory;
        this.orders = orders;
        this.orderIndexByReference = orderIndexByReference;
        this.trades = trades;
        this.brokenMatches = brokenMatches;
        this.peakActiveOrders = peakActiveOrders;
    }


    //-----------------------------------------------------------------------------------------------------------------
    /** Book states in feed order, one per book-affecting message; the first entry is the empty pre-open book. */
    public List<BookSnapshot> bookHistory() {
        return bookHistory;
    }

    /** Every order of the day in add order, each in its final state. */
    public List<OrderLifecycle> orders() {
        return orders;
    }

    public OrderLifecycle order(long reference) {
        Integer index = orderIndexByReference.get(reference);
        if (index == null) {
            throw new IllegalArgumentException("Unknown order reference " + reference);
        }
        return orders.get(index);
    }

    public List<TradeEvent> trades() {
        return trades;
    }

    public Set<Long> brokenMatches() {
        return brokenMatches;
    }

    /** Peak number of simultaneously live orders: the size the active index reached during the fold. */
    public int peakActiveOrders() {
        return peakActiveOrders;
    }


    /** The last book state whose ordinal is below [ordinal]: the depth a message at [ordinal] saw. */
    public BookSnapshot bookBefore(long ordinal) {
        int low = 0;
        int high = bookHistory.size() - 1;
        int found = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (bookHistory.get(mid).ordinal() < ordinal) {
                found = mid;
                low = mid + 1;
            }
            else {
                high = mid - 1;
            }
        }
        return bookHistory.get(found);
    }

    /** Whether the trade still stands as a print (printable and not broken). */
    public boolean stands(TradeEvent trade) {
        return trade.printable() && !brokenMatches.contains(trade.matchNumber());
    }

    /**
     * Standing printed trades and their shares — the same named metric the raw fold computes: one match number is
     * one trade, so an execution reported once per displayed order it hit (both sides resting, as a real day does
     * at times) counts once, its shares once.
     */
    public long[] standingTradeEventsAndShares() {
        long events = 0;
        long shares = 0;
        java.util.Set<Long> counted = new java.util.HashSet<>();
        for (TradeEvent trade : trades) {
            if (stands(trade) && counted.add(trade.matchNumber())) {
                events++;
                shares += trade.shares();
            }
        }
        return new long[] {events, shares};
    }


    //-----------------------------------------------------------------------------------------------------------------
    public static Builder builder() {
        return new Builder();
    }


    /** Mutable fold state; historical values it has already emitted are never touched again. */
    public static final class Builder {
        private static final Comparator<Long> bidOrder = Comparator.reverseOrder();
        private static final Comparator<Long> askOrder = Comparator.naturalOrder();

        private final List<BookSnapshot> bookHistory = new ArrayList<>();
        private final List<OrderLifecycle> orders = new ArrayList<>();
        private final Map<Long, Integer> orderIndexByReference = new HashMap<>();
        private final Map<Long, OrderLifecycle> active = new HashMap<>();
        private final List<TradeEvent> trades = new ArrayList<>();
        private final Set<Long> brokenMatches = new HashSet<>();
        private PersistentSortedMap<Long, BookLevel> bids = PersistentSortedMap.empty(bidOrder);
        private PersistentSortedMap<Long, BookLevel> asks = PersistentSortedMap.empty(askOrder);
        private boolean bookChanged;
        private int peakActive = 0;

        private Builder() {
            bookHistory.add(new BookSnapshot(-1, -1, bids, asks));
        }

        public void observe(ItchMessage message) {
            long ordinal = message.header().ordinal();
            long timestamp = message.header().timestampNanos();
            switch (message) {
                case ItchMessage.AddOrder m -> add(m.orderReference(), m.side(), m.shares(), m.price(), ordinal,
                        timestamp, m.attribution(), OrderLifecycle.noPredecessor);

                case ItchMessage.OrderExecuted m -> {
                    OrderLifecycle order = live(m.orderReference(), ordinal);
                    execute(order, new OrderEvent.Executed(ordinal, timestamp, m.executedShares(), order.price(),
                            m.matchNumber(), true));
                }

                case ItchMessage.OrderExecutedWithPrice m -> {
                    OrderLifecycle order = live(m.orderReference(), ordinal);
                    execute(order, new OrderEvent.Executed(ordinal, timestamp, m.executedShares(),
                            m.executionPrice(), m.matchNumber(), m.printable()));
                }

                case ItchMessage.OrderCancel m -> {
                    OrderLifecycle order = live(m.orderReference(), ordinal);
                    OrderLifecycle updated = order.cancelled(new OrderEvent.Cancelled(ordinal, timestamp, m.cancelledShares()));
                    reduceLevel(order.side(), order.price(), m.cancelledShares(), !updated.isActive());
                    settle(updated);
                }

                case ItchMessage.OrderDelete m -> {
                    OrderLifecycle order = live(m.orderReference(), ordinal);
                    reduceLevel(order.side(), order.price(), order.remainingShares(), true);
                    settle(order.deleted(ordinal, timestamp));
                }

                case ItchMessage.OrderReplace m -> {
                    OrderLifecycle order = live(m.originalOrderReference(), ordinal);
                    reduceLevel(order.side(), order.price(), order.remainingShares(), true);
                    settle(order.replaced(new OrderEvent.Replaced(ordinal, timestamp, m.newOrderReference(),
                            m.shares(), m.price())));
                    add(m.newOrderReference(), order.side(), m.shares(), m.price(), ordinal, timestamp,
                            order.attribution(), order.reference());
                }

                case ItchMessage.Trade m -> trades.add(new TradeEvent(TradeEvent.Kind.NON_DISPLAYED, ordinal,
                        timestamp, m.matchNumber(), m.shares(), m.price(), TradeEvent.noOrderReference, true));

                case ItchMessage.CrossTrade m -> trades.add(new TradeEvent(TradeEvent.Kind.CROSS, ordinal, timestamp,
                        m.matchNumber(), m.shares(), m.crossPrice(), TradeEvent.noOrderReference, m.shares() > 0));

                case ItchMessage.BrokenTrade m -> brokenMatches.add(m.matchNumber());

                default -> {}
            }
            if (bookChanged) {
                bookHistory.add(new BookSnapshot(ordinal, timestamp, bids, asks));
                bookChanged = false;
            }
        }

        public SymbolDayGraph build() {
            return new SymbolDayGraph(
                    Collections.unmodifiableList(bookHistory),
                    Collections.unmodifiableList(orders),
                    Collections.unmodifiableMap(orderIndexByReference),
                    Collections.unmodifiableList(trades),
                    Collections.unmodifiableSet(brokenMatches),
                    peakActive);
        }

        /** Live orders at this point of the fold (what a partial day leaves resting). */
        public int activeOrders() {
            return active.size();
        }

        //-------------------------------------------------------------------------------------------------------------
        private void add(long reference, Side side, long shares, long price, long ordinal, long timestamp,
                String attribution, long replacedFrom) {
            if (orderIndexByReference.containsKey(reference)) {
                throw new IllegalStateException("Order reference " + reference + " added twice, at ordinal " + ordinal);
            }
            OrderLifecycle order = OrderLifecycle.added(reference, side, shares, price, ordinal, timestamp,
                    attribution, replacedFrom);
            orderIndexByReference.put(reference, orders.size());
            orders.add(order);
            active.put(reference, order);
            peakActive = Math.max(peakActive, active.size());
            PersistentSortedMap<Long, BookLevel> levels = levels(side);
            BookLevel level = levels.get(price);
            store(side, levels.put(price, level == null ? new BookLevel(price, shares, 1) : level.plus(shares)));
        }

        private OrderLifecycle live(long reference, long ordinal) {
            OrderLifecycle order = active.get(reference);
            if (order == null) {
                String reason = orderIndexByReference.containsKey(reference)
                        ? "is " + orders.get(orderIndexByReference.get(reference)).state()
                        : "was never added";
                throw new IllegalStateException("Order reference " + reference + " " + reason + ", at ordinal " + ordinal);
            }
            return order;
        }

        private void execute(OrderLifecycle order, OrderEvent.Executed event) {
            OrderLifecycle updated = order.executed(event);
            reduceLevel(order.side(), order.price(), event.shares(), !updated.isActive());
            trades.add(new TradeEvent(TradeEvent.Kind.EXECUTED, event.ordinal(), event.timestampNanos(),
                    event.matchNumber(), event.shares(), event.price(), order.reference(), event.printable()));
            settle(updated);
        }

        private void settle(OrderLifecycle updated) {
            orders.set(orderIndexByReference.get(updated.reference()), updated);
            if (updated.isActive()) {
                active.put(updated.reference(), updated);
            }
            else {
                active.remove(updated.reference());
            }
        }

        private void reduceLevel(Side side, long price, long shares, boolean removesOrder) {
            PersistentSortedMap<Long, BookLevel> levels = levels(side);
            BookLevel level = levels.get(price);
            if (level == null) {
                throw new IllegalStateException("No " + side + " level at " + price + " to reduce");
            }
            BookLevel reduced = level.minus(shares, removesOrder);
            store(side, reduced == null ? levels.remove(price) : levels.put(price, reduced));
        }

        private PersistentSortedMap<Long, BookLevel> levels(Side side) {
            return side == Side.BUY ? bids : asks;
        }

        private void store(Side side, PersistentSortedMap<Long, BookLevel> levels) {
            if (side == Side.BUY) {
                bids = levels;
            }
            else {
                asks = levels;
            }
            bookChanged = true;
        }
    }
}
