package tech.kzen.sample.itch.day;

import tech.kzen.sample.itch.store.ItchStore;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


/**
 * Lazily materialized symbol-days of a store in lexical symbol order, one at a time, each acquired from
 * [budget] on {@code next()}. The stream itself is closeable and single-use: {@link #iterator} may be called
 * once, iteration after {@link #close} fails by name, and the days it hands out belong to the caller, who
 * closes each (the framework's E9 ownership does this on the kzen route). Closing the stream does not close a
 * day already handed out.
 */
public final class SymbolDays implements Iterable<SymbolDay>, AutoCloseable {
    private final ItchStore store;
    private final MaterializationBudget budget;
    private boolean iterated;
    private volatile boolean closed;
    private SymbolDaySession session;


    public static SymbolDays of(ItchStore store) {
        return of(store, MaterializationBudget.unlimited());
    }

    public static SymbolDays of(ItchStore store, MaterializationBudget budget) {
        return new SymbolDays(store, budget);
    }

    public SymbolDays(ItchStore store, MaterializationBudget budget) {
        this.store = store;
        this.budget = budget;
    }


    @Override
    public synchronized Iterator<SymbolDay> iterator() {
        if (closed) {
            throw new IllegalStateException("SymbolDays of " + store.root() + " is closed");
        }
        if (iterated) {
            throw new IllegalStateException("SymbolDays of " + store.root() + " is single-use");
        }
        iterated = true;
        session = new SymbolDaySession(store, java.util.List.copyOf(store.symbols().values()), budget);
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return !closed && session.hasNext();
            }

            @Override
            public SymbolDay next() {
                if (closed) {
                    throw new IllegalStateException("SymbolDays of " + store.root() + " is closed");
                }
                if (!session.hasNext()) {
                    throw new NoSuchElementException();
                }
                try {
                    return session.next(MaterializationProgress.none);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while materializing", e);
                }
            }
        };
    }


    public Stream<SymbolDay> stream() {
        return StreamSupport.stream(spliterator(), false).onClose(this::close);
    }


    @Override
    public void close() {
        closed = true;
        if (session != null) session.close();
    }
}
