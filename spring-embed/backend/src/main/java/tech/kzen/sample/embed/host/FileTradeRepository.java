package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.analysis.TradeVolumeFold;
import tech.kzen.sample.itch.wire.ItchReader;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * {@link TradeRepository} over the day file through the plain core: one sequential decode into the message
 * fold, computed once on first use and kept as plain records (no native storage, nothing to govern). This is
 * the host's own report path — what the host would run for itself with no kzen in the picture.
 */
public final class FileTradeRepository implements TradeRepository {
    private final HostDay day;
    private volatile SortedMap<String, SymbolTradeSummary> summaries;


    public FileTradeRepository(HostDay day) {
        this.day = day;
    }


    @Override
    public boolean available() {
        return day.available();
    }


    @Override
    public SortedMap<String, SymbolTradeSummary> tradeVolume() {
        SortedMap<String, SymbolTradeSummary> loaded = summaries;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (summaries == null) {
                if (!day.available()) {
                    throw new IllegalStateException("No ITCH day file is configured (kzen.host.day-file)");
                }
                TradeVolumeFold fold = new TradeVolumeFold();
                new ItchReader(day.dayFile().orElseThrow()).forEach(fold::observe);
                summaries = Collections.unmodifiableSortedMap(new TreeMap<>(fold.summaries()));
            }
            return summaries;
        }
    }
}
