package tech.kzen.sample.itch.analysis;

import tech.kzen.sample.itch.message.ItchMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * Folds a message stream into {@link SymbolTradeSummary} per symbol. Executions ({@code E} / {@code C}) name no
 * symbol, so they are attributed through the message's Stock Locate and the {@link SymbolCatalog}; breaks are
 * resolved through the match number of the event they cancel, which requires remembering every execution's
 * match number for the day (bounded by the day's trade count, not its order count). A non-printable execution
 * and an empty cross are remembered but not counted, so a break naming them is a no-op rather than an error.
 */
public final class TradeVolumeFold {
    private record MatchedEvent(String symbol, long shares, boolean counted) {}

    /** A match number is unique within a symbol's day; across symbols the same number recurs (2019-12-30 shows it). */
    private record MatchKey(String symbol, long matchNumber) {}

    private final SymbolCatalog catalog;
    private final Map<String, SymbolTradeSummary> summaries = new HashMap<>();
    private final Map<MatchKey, MatchedEvent> eventsByMatch = new HashMap<>();


    public TradeVolumeFold() {
        this(new SymbolCatalog());
    }


    /** Uses a catalog the caller also feeds; directory messages passed here are registered on it too. */
    public TradeVolumeFold(SymbolCatalog catalog) {
        this.catalog = catalog;
    }


    public SymbolCatalog catalog() {
        return catalog;
    }


    public void observe(ItchMessage message) {
        switch (message) {
            case ItchMessage.StockDirectory directory -> catalog.register(directory);

            case ItchMessage.OrderExecuted executed -> matched(
                    catalog.symbol(executed.header().stockLocate()), executed.matchNumber(),
                    executed.executedShares(), true, executed.header().ordinal());

            case ItchMessage.OrderExecutedWithPrice executed -> matched(
                    catalog.symbol(executed.header().stockLocate()), executed.matchNumber(),
                    executed.executedShares(), executed.printable(), executed.header().ordinal());

            case ItchMessage.Trade trade ->
                    matched(trade.stock(), trade.matchNumber(), trade.shares(), true, trade.header().ordinal());

            case ItchMessage.CrossTrade cross -> matched(
                    cross.stock(), cross.matchNumber(), cross.shares(), cross.shares() > 0, cross.header().ordinal());

            case ItchMessage.BrokenTrade broken -> {
                int locate = broken.header().stockLocate();
                MatchedEvent event = catalog.contains(locate)
                        ? eventsByMatch.remove(new MatchKey(catalog.symbol(locate), broken.matchNumber()))
                        : null;
                if (event == null) {
                    throw new IllegalStateException("Broken Trade at ordinal " + broken.header().ordinal()
                            + " names match number " + broken.matchNumber() + ", which no execution carried");
                }
                if (event.counted()) {
                    summaries.compute(event.symbol(), (symbol, summary) -> summary.minus(event.shares()));
                }
            }

            default -> {}
        }
    }


    /**
     * One match number is one trade of one symbol; a real day reports it once per displayed order it executed
     * against (a resting order on each side, at ordinal 5 265 120 of 2019-12-30 for the first time), so a repeat
     * of the number within the symbol is the same trade's other side and is not counted again — the tally is per
     * trade, not per side — while the same number on another symbol is that symbol's own trade.
     */
    private void matched(String symbol, long matchNumber, long shares, boolean counted, long ordinal) {
        MatchKey key = new MatchKey(symbol, matchNumber);
        MatchedEvent previous = eventsByMatch.putIfAbsent(key, new MatchedEvent(symbol, shares, counted));
        if (previous != null) {
            // The other side of a trade already seen: it prints once — count it if this side prints and the
            // first did not (a non-printable C against a displayed order's E, on 2019-12-30 twelve times for AAPL).
            if (counted && !previous.counted()) {
                eventsByMatch.put(key, new MatchedEvent(symbol, shares, true));
                summaries.compute(symbol, (s, summary) ->
                        (summary == null ? SymbolTradeSummary.empty(s) : summary).plus(shares));
            }
            return;
        }
        if (counted) {
            summaries.compute(symbol, (s, summary) ->
                    (summary == null ? SymbolTradeSummary.empty(s) : summary).plus(shares));
        }
    }


    /** Symbols in lexical order; a symbol with no printed event is absent. */
    public SortedMap<String, SymbolTradeSummary> summaries() {
        return new TreeMap<>(summaries);
    }
}
