package tech.kzen.sample.itch.analysis;


/**
 * The named comparison result of the sample: per symbol, the count of printed trade events and the shares they
 * carried, after breaks. Printed trade events are Order Executed ({@code E}), Order Executed With Price marked
 * printable ({@code C}), non-cross Trade ({@code P}) and Cross Trade with non-zero shares ({@code Q}); a Broken
 * Trade ({@code B}) removes the event its match number names. Non-printable executions are excluded because
 * their shares are re-published in a later bulk print. This is a fixture metric for cross-route equality, not
 * an exchange volume figure.
 */
public record SymbolTradeSummary(
        String symbol,
        long tradeEvents,
        long shares
) {
    public static SymbolTradeSummary empty(String symbol) {
        return new SymbolTradeSummary(symbol, 0, 0);
    }

    public SymbolTradeSummary plus(long eventShares) {
        return new SymbolTradeSummary(symbol, tradeEvents + 1, shares + eventShares);
    }

    public SymbolTradeSummary minus(long eventShares) {
        return new SymbolTradeSummary(symbol, tradeEvents - 1, shares - eventShares);
    }
}
