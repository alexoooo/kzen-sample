package tech.kzen.sample.plugin.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import tech.kzen.sample.plugin.support.SyntheticStores;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.columns;


/** The hand-authored AAPL scenario, projected: buy filled in two, sell replaced, replacement partly done. */
class OrderLifecycleRowsTest {
    @TempDir
    Path temp;


    @Test
    void scriptedOrdersProjectToTypedRows() throws IOException {
        ItchStore store = SyntheticStores.build(SyntheticItchDay.generate(1L, 0), temp, "scripted");
        List<Map<String, Object>> rows;
        try (SymbolDay aapl = SymbolDay.materialize(store, SyntheticItchDay.aaplLocate)) {
            rows = OrderLifecycleRows.rows(aapl).stream().map(row -> columns(row)).toList();
        }
        assertEquals(3, rows.size());

        Map<String, Object> buy = rows.get(0);
        assertEquals(SyntheticItchDay.aapl, buy.get(OrderLifecycleRows.symbol));
        assertEquals(1L, buy.get(OrderLifecycleRows.reference));
        assertEquals("BUY", buy.get(OrderLifecycleRows.side));
        assertEquals(100L, buy.get(OrderLifecycleRows.originalShares));
        assertEquals(150.0, buy.get(OrderLifecycleRows.price));
        assertEquals("FILLED", buy.get(OrderLifecycleRows.state));
        assertEquals(100L, buy.get(OrderLifecycleRows.executedShares));
        assertEquals(2L, buy.get(OrderLifecycleRows.executionCount));
        assertEquals(1.0, buy.get(OrderLifecycleRows.fillRatio));
        assertEquals(2_000_000_000L, buy.get(OrderLifecycleRows.restingNanos), "added at 34218 s, last fill at 34220 s");
        assertNull(buy.get(OrderLifecycleRows.replacedFrom));

        Map<String, Object> sell = rows.get(1);
        assertEquals("REPLACED", sell.get(OrderLifecycleRows.state));
        assertEquals(150.5, sell.get(OrderLifecycleRows.price));

        Map<String, Object> replacement = rows.get(2);
        assertEquals(3L, replacement.get(OrderLifecycleRows.reference));
        assertEquals(2L, replacement.get(OrderLifecycleRows.replacedFrom));
        assertEquals("DELETED", replacement.get(OrderLifecycleRows.state));
        assertEquals(30L, replacement.get(OrderLifecycleRows.executedShares));
        assertEquals(80L, replacement.get(OrderLifecycleRows.originalShares));
        assertTrue((Double) replacement.get(OrderLifecycleRows.fillRatio) < 1.0);
        assertEquals(12, LiteralRecords.columns(OrderLifecycleRows.contract).size(), "twelve declared columns");
    }
}
