package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.day.SymbolDay;

import java.util.function.Function;


/**
 * The host's book queries: a question about one symbol is answered over a freshly materialized symbol-day
 * admitted by the budget, and the day is closed — lease returned, native storage released — before the answer
 * leaves, whether the processing returned or threw. Nothing materialized is cached or shared.
 */
public interface OrderBookService {
    boolean available();

    /** Runs [processing] over the symbol's day; the day is valid only inside the call. */
    <R> R withSymbolDay(String symbol, Function<SymbolDay, R> processing);

    /** The top [levels] of the book at the end of the day: price, shares and orders per side. */
    BookTop bookTop(String symbol, int levels);


    record BookTop(String symbol, java.util.List<Level> bids, java.util.List<Level> asks, long spreadPrice4) {}

    record Level(long price4, long shares, int orders) {}
}
