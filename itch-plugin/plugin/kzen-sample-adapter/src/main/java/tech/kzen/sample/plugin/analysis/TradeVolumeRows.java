package tech.kzen.sample.plugin.analysis;

import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static tech.kzen.sample.plugin.value.LiteralRecords.field;
import static tech.kzen.sample.plugin.value.LiteralRecords.integer;
import static tech.kzen.sample.plugin.value.LiteralRecords.text;


/**
 * The one named result every route produces: per symbol, the standing printed trade events and their shares
 * ({@code P}, {@code E}, {@code C}, {@code Q}, less {@code B} breaks). The raw route folds the message stream
 * ({@link tech.kzen.sample.itch.analysis.TradeVolumeFold}); the store route reads a materialized
 * {@link SymbolDay}'s graph; both project to this row.
 */
public final class TradeVolumeRows {
    public static final String symbol = "symbol";
    public static final String tradeEvents = "tradeEvents";
    public static final String shares = "shares";

    public static final DataContract contract = LiteralRecords.contract(List.of(
            field(symbol, text),
            field(tradeEvents, integer),
            field(shares, integer)));


    private TradeVolumeRows() {}


    public static DataValue row(String symbolName, long events, long sharesTraded) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put(symbol, symbolName);
        columns.put(tradeEvents, events);
        columns.put(shares, sharesTraded);
        return LiteralRecords.row(contract, columns);
    }


    /** The raw fold's summaries, one row per symbol in symbol order. */
    public static List<DataValue> rows(Map<String, SymbolTradeSummary> summaries) {
        List<DataValue> rows = new ArrayList<>(summaries.size());
        for (SymbolTradeSummary summary : summaries.values()) {
            rows.add(row(summary.symbol(), summary.tradeEvents(), summary.shares()));
        }
        return rows;
    }


    /** One materialized symbol-day's standing trades. */
    public static DataValue row(SymbolDay day) {
        long[] standing = day.graph().standingTradeEventsAndShares();
        return row(day.symbol(), standing[0], standing[1]);
    }
}
