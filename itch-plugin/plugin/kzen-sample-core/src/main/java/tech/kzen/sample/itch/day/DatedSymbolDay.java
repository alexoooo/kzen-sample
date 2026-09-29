package tech.kzen.sample.itch.day;

import java.util.Objects;

public record DatedSymbolDay(String date, String symbol, String sourceUrl, SymbolDay day) implements AutoCloseable {
    public DatedSymbolDay {
        Objects.requireNonNull(date);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(sourceUrl);
        Objects.requireNonNull(day);
    }

    @Override
    public void close() { day.close(); }
}
