package tech.kzen.sample.itch.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;


/**
 * The displayed book after the message at [ordinal]: bids best-first (highest price), asks best-first (lowest).
 * Immutable; successive snapshots share unchanged levels through {@link PersistentSortedMap}.
 */
public record BookSnapshot(
        long ordinal,
        long timestampNanos,
        PersistentSortedMap<Long, BookLevel> bids,
        PersistentSortedMap<Long, BookLevel> asks
) {
    private static final Comparator<Long> bidOrder = Comparator.reverseOrder();
    private static final Comparator<Long> askOrder = Comparator.naturalOrder();


    public static BookSnapshot empty() {
        return new BookSnapshot(-1, -1, PersistentSortedMap.empty(bidOrder), PersistentSortedMap.empty(askOrder));
    }


    public BookLevel bestBid() {
        Map.Entry<Long, BookLevel> entry = bids.firstEntry();
        return entry == null ? null : entry.getValue();
    }

    public BookLevel bestAsk() {
        Map.Entry<Long, BookLevel> entry = asks.firstEntry();
        return entry == null ? null : entry.getValue();
    }

    /** Best [levels] bids as plain records, best first. */
    public List<BookLevel> bidDepth(int levels) {
        return bids.first(levels).stream().map(Map.Entry::getValue).toList();
    }

    public List<BookLevel> askDepth(int levels) {
        return asks.first(levels).stream().map(Map.Entry::getValue).toList();
    }

    /** Spread in raw price units, or -1 when one side is empty. */
    public long spread() {
        BookLevel bid = bestBid();
        BookLevel ask = bestAsk();
        return bid == null || ask == null ? -1 : ask.price() - bid.price();
    }
}
