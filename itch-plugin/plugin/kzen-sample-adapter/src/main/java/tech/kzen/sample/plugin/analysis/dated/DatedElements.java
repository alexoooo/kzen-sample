package tech.kzen.sample.plugin.analysis.dated;

import tech.kzen.sample.itch.day.DatedSymbolDay;

final class DatedElements {
    private DatedElements() {}
    static DatedSymbolDay require(Object element) {
        if (!(element instanceof DatedSymbolDay dated)) throw new IllegalArgumentException("Connect a dated ITCH symbol-day source");
        if (!dated.day().isOpen()) throw new IllegalStateException("The symbol-day is already closed");
        return dated;
    }
}
