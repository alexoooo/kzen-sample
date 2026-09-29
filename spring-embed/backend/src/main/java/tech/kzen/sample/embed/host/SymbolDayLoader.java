package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.day.SymbolDay;

import java.util.Iterator;


/**
 * The governed way into the host's market data for kzen: a fresh cursor of freshly materialized
 * {@link SymbolDay}s per call, each admitted by the host's budget before it allocates and closed by whoever
 * received it (a kzen run owns what it pulls — E9); closing the cursor releases the store handles it holds.
 * Registered under this interface as a {@code @Service}.
 */
public interface SymbolDayLoader {
    /** Whether a day is loaded at all (the host may be configured without one). */
    boolean available();

    /** The loaded day's symbols in symbol order. */
    java.util.List<String> symbols();

    /** A new pass over every symbol-day; the caller closes it. */
    SymbolDayCursor open();


    interface SymbolDayCursor extends Iterator<SymbolDay>, AutoCloseable {
        @Override
        void close();
    }
}
