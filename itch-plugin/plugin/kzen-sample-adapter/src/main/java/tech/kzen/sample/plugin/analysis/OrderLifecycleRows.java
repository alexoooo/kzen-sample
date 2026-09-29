package tech.kzen.sample.plugin.analysis;

import tech.kzen.sample.itch.model.SymbolDayGraph;

import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.model.OrderEvent;
import tech.kzen.sample.itch.model.OrderLifecycle;
import tech.kzen.sample.plugin.itch.ItchPrices;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static tech.kzen.sample.plugin.value.LiteralRecords.field;
import static tech.kzen.sample.plugin.value.LiteralRecords.floating;
import static tech.kzen.sample.plugin.value.LiteralRecords.integer;
import static tech.kzen.sample.plugin.value.LiteralRecords.integerOrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.text;


/**
 * One flat row per reconstructed order of a symbol-day: what was placed, what happened to it (state, executed
 * shares and executions, fill ratio), how long it rested (from add to its last event, nanoseconds) and the
 * reference it replaced, if any. The reconstruction is the core's; this is only the projection.
 */
public final class OrderLifecycleRows {
    public static final String symbol = "symbol";
    public static final String reference = "reference";
    public static final String side = "side";
    public static final String originalShares = "originalShares";
    public static final String price = "price";
    public static final String addTimestampNanos = "addTimestampNanos";
    public static final String state = "state";
    public static final String executedShares = "executedShares";
    public static final String executionCount = "executionCount";
    public static final String fillRatio = "fillRatio";
    public static final String restingNanos = "restingNanos";
    public static final String replacedFrom = "replacedFrom";

    public static final DataContract contract = LiteralRecords.contract(List.of(
            field(symbol, text),
            field(reference, integer),
            field(side, text),
            field(originalShares, integer),
            field(price, floating),
            field(addTimestampNanos, integer),
            field(state, text),
            field(executedShares, integer),
            field(executionCount, integer),
            field(fillRatio, floating),
            field(restingNanos, integer),
            field(replacedFrom, integerOrNull)));


    private OrderLifecycleRows() {}


    public static List<DataValue> rows(SymbolDay day) {
        List<OrderLifecycle> orders = SymbolDayGraph.build(day).orders();
        List<DataValue> rows = new ArrayList<>(orders.size());
        for (OrderLifecycle order : orders) {
            rows.add(row(day.symbol(), order));
        }
        return rows;
    }


    public static DataValue row(String symbolName, OrderLifecycle order) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put(symbol, symbolName);
        columns.put(reference, order.reference());
        columns.put(side, order.side().name());
        columns.put(originalShares, order.originalShares());
        columns.put(price, ItchPrices.toDecimal(order.price()));
        columns.put(addTimestampNanos, order.addTimestampNanos());
        columns.put(state, order.state().name());
        columns.put(executedShares, order.executedShares());
        columns.put(executionCount, (long) order.executionCount());
        columns.put(fillRatio, order.fillRatio());
        columns.put(restingNanos, restingNanos(order));
        columns.put(replacedFrom, order.replacedFrom() == OrderLifecycle.noPredecessor ? null : order.replacedFrom());
        return LiteralRecords.row(contract, columns);
    }


    /** Add to the last event; zero for an order still resting with no event. */
    static long restingNanos(OrderLifecycle order) {
        List<OrderEvent> events = order.events();
        if (events.isEmpty()) {
            return 0;
        }
        return events.getLast().timestampNanos() - order.addTimestampNanos();
    }
}
