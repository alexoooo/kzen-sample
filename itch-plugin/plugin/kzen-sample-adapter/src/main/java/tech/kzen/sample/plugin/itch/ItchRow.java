package tech.kzen.sample.plugin.itch;

import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;

import tech.kzen.sample.plugin.value.LiteralRecords;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static tech.kzen.sample.plugin.value.LiteralRecords.field;
import static tech.kzen.sample.plugin.value.LiteralRecords.floatingOrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.integer;
import static tech.kzen.sample.plugin.value.LiteralRecords.integerOrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.text;
import static tech.kzen.sample.plugin.value.LiteralRecords.textOrNull;


/**
 * The flat, typed projection of a decoded ITCH message for generic Jobs: the header columns every message
 * carries, the symbol (resolved from the day's stock directory by locate), and the order / trade columns of
 * the event kinds, null where a kind does not carry them. Prices are the ITCH four-decimal fixed point as a
 * double. The contract is static ({@link #contract}), so a Job knows its columns before any byte is read.
 */
public final class ItchRow {
    public static final String ordinal = "ordinal";
    public static final String type = "type";
    public static final String timestampNanos = "timestampNanos";
    public static final String stockLocate = "stockLocate";
    public static final String stock = "stock";
    public static final String orderReference = "orderReference";
    public static final String side = "side";
    public static final String shares = "shares";
    public static final String price = "price";
    public static final String matchNumber = "matchNumber";
    public static final String executedShares = "executedShares";
    public static final String newOrderReference = "newOrderReference";
    public static final String eventCode = "eventCode";
    public static final String tradingState = "tradingState";

    public static final List<String> columns = List.of(
            ordinal, type, timestampNanos, stockLocate, stock, orderReference, side, shares, price,
            matchNumber, executedShares, newOrderReference, eventCode, tradingState);

    private static final DataContract contract = LiteralRecords.contract(List.of(
            field(ordinal, integer),
            field(type, text),
            field(timestampNanos, integer),
            field(stockLocate, integer),
            field(stock, textOrNull),
            field(orderReference, integerOrNull),
            field(side, textOrNull),
            field(shares, integerOrNull),
            field(price, floatingOrNull),
            field(matchNumber, integerOrNull),
            field(executedShares, integerOrNull),
            field(newOrderReference, integerOrNull),
            field(eventCode, textOrNull),
            field(tradingState, textOrNull)));


    private ItchRow() {}


    public static DataContract contract() {
        return contract;
    }


    /** The row as a column → value map (null for a column the kind does not carry); [symbolByLocate] names locates. */
    public static Map<String, Object> project(ItchMessage message, IntFunction<String> symbolByLocate) {
        ItchHeader header = message.header();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ordinal, header.ordinal());
        row.put(type, String.valueOf(message.type()));
        row.put(timestampNanos, header.timestampNanos());
        row.put(stockLocate, (long) header.stockLocate());
        row.put(stock, symbolOf(message, symbolByLocate));
        row.put(orderReference, null);
        row.put(side, null);
        row.put(shares, null);
        row.put(price, null);
        row.put(matchNumber, null);
        row.put(executedShares, null);
        row.put(newOrderReference, null);
        row.put(eventCode, null);
        row.put(tradingState, null);
        switch (message) {
            case ItchMessage.AddOrder m -> {
                row.put(orderReference, m.orderReference());
                row.put(side, m.side().name());
                row.put(shares, m.shares());
                row.put(price, ItchPrices.toDecimal(m.price()));
            }
            case ItchMessage.OrderExecuted m -> {
                row.put(orderReference, m.orderReference());
                row.put(executedShares, m.executedShares());
                row.put(matchNumber, m.matchNumber());
            }
            case ItchMessage.OrderExecutedWithPrice m -> {
                row.put(orderReference, m.orderReference());
                row.put(executedShares, m.executedShares());
                row.put(matchNumber, m.matchNumber());
                row.put(price, ItchPrices.toDecimal(m.executionPrice()));
            }
            case ItchMessage.OrderCancel m -> {
                row.put(orderReference, m.orderReference());
                row.put(shares, m.cancelledShares());
            }
            case ItchMessage.OrderDelete m -> row.put(orderReference, m.orderReference());
            case ItchMessage.OrderReplace m -> {
                row.put(orderReference, m.originalOrderReference());
                row.put(newOrderReference, m.newOrderReference());
                row.put(shares, m.shares());
                row.put(price, ItchPrices.toDecimal(m.price()));
            }
            case ItchMessage.Trade m -> {
                row.put(orderReference, m.orderReference());
                row.put(side, m.side().name());
                row.put(shares, m.shares());
                row.put(price, ItchPrices.toDecimal(m.price()));
                row.put(matchNumber, m.matchNumber());
            }
            case ItchMessage.CrossTrade m -> {
                row.put(shares, m.shares());
                row.put(price, ItchPrices.toDecimal(m.crossPrice()));
                row.put(matchNumber, m.matchNumber());
                row.put(eventCode, m.crossType());
            }
            case ItchMessage.BrokenTrade m -> row.put(matchNumber, m.matchNumber());
            case ItchMessage.SystemEvent m -> row.put(eventCode, m.eventCode());
            case ItchMessage.StockTradingAction m -> row.put(tradingState, m.tradingState());
            default -> {}
        }
        return row;
    }


    private static String symbolOf(ItchMessage message, IntFunction<String> symbolByLocate) {
        return switch (message) {
            case ItchMessage.StockDirectory m -> m.stock();
            case ItchMessage.AddOrder m -> m.stock();
            case ItchMessage.Trade m -> m.stock();
            case ItchMessage.CrossTrade m -> m.stock();
            case ItchMessage.StockTradingAction m -> m.stock();
            default -> symbolByLocate.apply(message.header().stockLocate());
        };
    }
}
