package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.model.SymbolDayGraph;

import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.model.BookLevel;
import tech.kzen.sample.itch.model.BookSnapshot;

import java.util.List;
import java.util.function.Function;


/**
 * {@link OrderBookService} over the host's day: {@link #withSymbolDay} materializes the symbol fresh under the
 * budget, runs the processing and closes the day in a {@code finally} — the lease and the native storage go
 * back whether the processing returned, threw, or the thread was interrupted after the acquisition.
 */
public final class GovernedOrderBookService implements OrderBookService {
    private final HostDay day;


    public GovernedOrderBookService(HostDay day) {
        this.day = day;
    }


    @Override
    public boolean available() {
        return day.available();
    }


    @Override
    public <R> R withSymbolDay(String symbol, Function<SymbolDay, R> processing) {
        SymbolDay materialized;
        try {
            materialized = day.materialize(symbol);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the budget to admit " + symbol, e);
        }
        try (materialized) {
            return processing.apply(materialized);
        }
    }


    @Override
    public BookTop bookTop(String symbol, int levels) {
        if (levels <= 0) {
            throw new IllegalArgumentException("levels must be positive: " + levels);
        }
        return withSymbolDay(symbol, symbolDay -> top(symbolDay, levels));
    }

    public static BookTop top(SymbolDay symbolDay, int levels) {
        if (levels <= 0) throw new IllegalArgumentException("Levels must be positive");
        List<BookSnapshot> history = SymbolDayGraph.build(symbolDay).bookHistory();
        BookSnapshot last = history.isEmpty() ? BookSnapshot.empty() : history.getLast();
        return new BookTop(symbolDay.symbol(),
                last.bidDepth(levels).stream().map(GovernedOrderBookService::level).toList(),
                last.askDepth(levels).stream().map(GovernedOrderBookService::level).toList(), last.spread());
    }


    private static Level level(BookLevel level) {
        return new Level(level.price(), level.shares(), level.orders());
    }
}
