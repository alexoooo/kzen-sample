package tech.kzen.sample.plugin.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import tech.kzen.sample.plugin.support.SyntheticStores;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.columns;


/** The scripted AAPL book, one message per second from 34218 s, sampled once a second one level deep. */
class BookSnapshotRowsTest {
    private static final long second = 1_000_000_000L;

    @TempDir
    Path temp;


    @Test
    void scriptedBookSamplesOncePerSecond() throws IOException {
        ItchStore store = SyntheticStores.build(SyntheticItchDay.generate(1L, 0), temp, "scripted");
        List<Map<String, Object>> rows;
        try (SymbolDay aapl = SymbolDay.materialize(store, SyntheticItchDay.aaplLocate)) {
            rows = BookSnapshotRows.rows(aapl, second, 1).stream().map(row -> columns(row)).toList();
        }
        assertEquals(7, rows.size(), "34218 s (two adds) through 34224 s (delete), one sample each");
        assertEquals(List.of(34219L, 34220L, 34221L, 34222L, 34223L, 34224L, 34225L),
                rows.stream().map(row -> (Long) row.get(BookSnapshotRows.intervalEndNanos) / second).toList());

        Map<String, Object> afterAdds = rows.get(0);
        assertEquals(SyntheticItchDay.aapl, afterAdds.get(BookSnapshotRows.symbol));
        assertEquals(150.0, afterAdds.get(BookSnapshotRows.bidPrice));
        assertEquals(100L, afterAdds.get(BookSnapshotRows.bidShares));
        assertEquals(150.5, afterAdds.get(BookSnapshotRows.askPrice));
        assertEquals(100L, afterAdds.get(BookSnapshotRows.askShares));
        assertEquals(0.5, afterAdds.get(BookSnapshotRows.spread));
        assertEquals(1L, afterAdds.get(BookSnapshotRows.bidLevels));

        assertEquals(60L, rows.get(1).get(BookSnapshotRows.bidShares), "first fill of 40");
        assertNull(rows.get(2).get(BookSnapshotRows.bidPrice), "full fill empties the bid");
        assertNull(rows.get(2).get(BookSnapshotRows.spread));
        assertEquals(0L, rows.get(2).get(BookSnapshotRows.bidDepthShares));
        assertEquals(150.4, rows.get(3).get(BookSnapshotRows.askPrice), "replace moved the ask");
        assertEquals(80L, rows.get(3).get(BookSnapshotRows.askShares));
        assertEquals(30L, rows.get(5).get(BookSnapshotRows.askShares));
        assertNull(rows.get(6).get(BookSnapshotRows.askPrice), "delete empties the ask");
    }
}
