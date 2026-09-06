package tech.kzen.sample.plugin.analysis;

import tech.kzen.sample.itch.day.SymbolDay;


/**
 * What the store-backed Workers consume: a materialized {@link SymbolDay} per element, owned by the run (E9) —
 * the Worker reads it and never closes it; a mis-wired upstream fails by name on the first element.
 */
final class SymbolDayElements {
    private SymbolDayElements() {}


    static SymbolDay require(Object element, String worker) {
        if (element instanceof SymbolDay day) {
            if (!day.isOpen()) {
                throw new IllegalStateException(worker + " received a closed symbol-day " + day.symbol());
            }
            return day;
        }
        throw new IllegalArgumentException(worker + " expects SymbolDay elements (SymbolDays.of(store)), not "
                + (element == null ? "null" : element.getClass().getName()));
    }
}
