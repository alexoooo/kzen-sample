package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.day.SymbolDays;

import java.util.Iterator;
import java.util.List;


/**
 * {@link SymbolDayLoader} over the host's day and budget: each {@code open()} is a new {@link SymbolDays}
 * pass — every symbol-day materialized fresh under the budget as it is pulled, never cached — and closing the
 * cursor ends that pass. The interruption of a blocked acquire (a cancelled kzen run interrupts its blocking
 * pull) surfaces as the core's named failure, having acquired nothing.
 */
public final class GovernedSymbolDayLoader implements SymbolDayLoader {
    private final HostDay day;


    public GovernedSymbolDayLoader(HostDay day) {
        this.day = day;
    }


    @Override
    public boolean available() {
        return day.available();
    }


    @Override
    public List<String> symbols() {
        return day.symbols();
    }


    @Override
    public SymbolDayCursor open() {
        SymbolDays days = new SymbolDays(day.store(), day.budget());
        Iterator<SymbolDay> iterator = days.iterator();
        return new SymbolDayCursor() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public SymbolDay next() {
                return iterator.next();
            }

            @Override
            public void close() {
                days.close();
            }
        };
    }
}
