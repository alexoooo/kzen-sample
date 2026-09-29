package tech.kzen.sample.plugin.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.analysis.TradeVolumeFold;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.day.SymbolDays;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import tech.kzen.sample.itch.wire.ItchReader;
import tech.kzen.sample.plugin.support.SyntheticStores;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.columns;


/**
 * The three-path agreement on the fixture: the raw route (decoded messages through the core's fold, what
 * {@link ItchTradeVolumeWorker} does) and the store route (materialized symbol-days, what
 * {@link SymbolDayTradeVolumeWorker} does) each produce the one named result — per-symbol standing trade events
 * and shares — and both equal the generator's independent tally. The two routes share no representation.
 */
class TradeVolumeRowsTest {
    private static final long seed = 20260905L;

    @TempDir
    Path temp;


    @Test
    void rawAndStoreRoutesAgreeWithTheGeneratorsTally() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 25);
        SortedMap<String, List<Long>> expected = tally(day.expectedTrades());
        assertFalse(expected.isEmpty());

        // Raw route: the file's decoded stream folded, rows at end of stream.
        Path source = SyntheticStores.writeDay(day, temp, "raw");
        TradeVolumeFold fold = new TradeVolumeFold();
        new ItchReader(source).forEach(fold::observe);
        SortedMap<String, List<Long>> raw = new TreeMap<>();
        for (var row : TradeVolumeRows.rows(fold.summaries())) {
            Map<String, Object> columns = columns(row);
            raw.put((String) columns.get(TradeVolumeRows.symbol),
                    List.of((Long) columns.get(TradeVolumeRows.tradeEvents), (Long) columns.get(TradeVolumeRows.shares)));
        }
        assertEquals(expected, raw);

        // Store route: one row per materialized symbol-day, from its graph; a symbol without trades is a zero row.
        ItchStore store = SyntheticStores.build(day, temp, "store");
        SortedMap<String, List<Long>> materialized = new TreeMap<>();
        try (SymbolDays days = SymbolDays.of(store)) {
            for (SymbolDay symbolDay : days) {
                try (symbolDay) {
                    Map<String, Object> columns = columns(TradeVolumeRows.row(symbolDay));
                    long events = (Long) columns.get(TradeVolumeRows.tradeEvents);
                    if (events > 0) {
                        materialized.put((String) columns.get(TradeVolumeRows.symbol),
                                List.of(events, (Long) columns.get(TradeVolumeRows.shares)));
                    }
                    else {
                        assertEquals(SyntheticItchDay.quiet, columns.get(TradeVolumeRows.symbol));
                    }
                }
            }
        }
        assertEquals(expected, materialized);
        assertEquals(List.of(TradeVolumeRows.symbol, TradeVolumeRows.tradeEvents, TradeVolumeRows.shares),
                tech.kzen.sample.plugin.value.LiteralRecords.columns(TradeVolumeRows.contract));
    }


    private static SortedMap<String, List<Long>> tally(SortedMap<String, SymbolTradeSummary> summaries) {
        SortedMap<String, List<Long>> tally = new TreeMap<>();
        summaries.forEach((symbol, summary) -> tally.put(symbol, List.of(summary.tradeEvents(), summary.shares())));
        return tally;
    }
}
