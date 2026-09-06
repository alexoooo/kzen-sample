package tech.kzen.sample.plugin.analysis;

import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.sample.itch.analysis.BookHistorySampler;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.model.BookLevel;
import tech.kzen.sample.itch.model.BookSnapshot;
import tech.kzen.sample.plugin.itch.ItchPrices;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static tech.kzen.sample.plugin.value.LiteralRecords.field;
import static tech.kzen.sample.plugin.value.LiteralRecords.floatingOrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.integer;
import static tech.kzen.sample.plugin.value.LiteralRecords.integerOrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.text;


/**
 * The displayed book of a symbol-day sampled at a cadence (the core's {@link BookHistorySampler}): per interval
 * the best bid and ask (price and shares; null on an empty side), the spread, and the shares and level count
 * within the top N levels of each side. Persistent snapshots share structure, so no history is copied.
 */
public final class BookSnapshotRows {
    public static final String symbol = "symbol";
    public static final String intervalEndNanos = "intervalEndNanos";
    public static final String bidPrice = "bidPrice";
    public static final String bidShares = "bidShares";
    public static final String askPrice = "askPrice";
    public static final String askShares = "askShares";
    public static final String spread = "spread";
    public static final String bidDepthShares = "bidDepthShares";
    public static final String askDepthShares = "askDepthShares";
    public static final String bidLevels = "bidLevels";
    public static final String askLevels = "askLevels";

    public static final DataContract contract = LiteralRecords.contract(List.of(
            field(symbol, text),
            field(intervalEndNanos, integer),
            field(bidPrice, floatingOrNull),
            field(bidShares, integerOrNull),
            field(askPrice, floatingOrNull),
            field(askShares, integerOrNull),
            field(spread, floatingOrNull),
            field(bidDepthShares, integer),
            field(askDepthShares, integer),
            field(bidLevels, integer),
            field(askLevels, integer)));


    private BookSnapshotRows() {}


    public static List<DataValue> rows(SymbolDay day, long intervalNanos, int levels) {
        List<BookHistorySampler.Sample> samples = BookHistorySampler.sample(day.bookHistory(), intervalNanos);
        List<DataValue> rows = new ArrayList<>(samples.size());
        for (BookHistorySampler.Sample sample : samples) {
            rows.add(row(day.symbol(), sample, levels));
        }
        return rows;
    }


    public static DataValue row(String symbolName, BookHistorySampler.Sample sample, int levels) {
        BookSnapshot book = sample.book();
        BookLevel bid = book.bestBid();
        BookLevel ask = book.bestAsk();
        List<BookLevel> bids = book.bidDepth(levels);
        List<BookLevel> asks = book.askDepth(levels);
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put(symbol, symbolName);
        columns.put(intervalEndNanos, sample.intervalEndNanos());
        columns.put(bidPrice, bid == null ? null : ItchPrices.toDecimal(bid.price()));
        columns.put(bidShares, bid == null ? null : bid.shares());
        columns.put(askPrice, ask == null ? null : ItchPrices.toDecimal(ask.price()));
        columns.put(askShares, ask == null ? null : ask.shares());
        columns.put(spread, book.spread() < 0 ? null : ItchPrices.toDecimal(book.spread()));
        columns.put(bidDepthShares, bids.stream().mapToLong(BookLevel::shares).sum());
        columns.put(askDepthShares, asks.stream().mapToLong(BookLevel::shares).sum());
        columns.put(bidLevels, (long) bids.size());
        columns.put(askLevels, (long) asks.size());
        return LiteralRecords.row(contract, columns);
    }
}
