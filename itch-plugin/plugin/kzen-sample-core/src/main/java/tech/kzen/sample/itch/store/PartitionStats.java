package tech.kzen.sample.itch.store;

import tech.kzen.sample.itch.message.ItchMessage;


/**
 * Per-partition counts persisted in the catalog: the native sizing input ({@link #bytes}, the exact size of the
 * partition's frames) and the heap-estimate inputs (message counts by family, which a materialization weight is a
 * linear function of). Locate 0's row describes the shared market-wide partition every symbol replay also loads.
 */
public record PartitionStats(
        int locate,
        String symbol,
        long messages,
        long bytes,
        long adds,
        long executions,
        long cancels,
        long deletes,
        long replaces,
        long trades,
        long crosses,
        long breaks,
        long other
) {
    public static final String noSymbol = "";

    public static PartitionStats empty(int locate, String symbol) {
        return new PartitionStats(locate, symbol, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }


    public PartitionStats withSymbol(String newSymbol) {
        return new PartitionStats(locate, newSymbol, messages, bytes, adds, executions, cancels, deletes, replaces,
                trades, crosses, breaks, other);
    }


    public PartitionStats plus(ItchMessage message, int frameBytes) {
        long a = adds, e = executions, x = cancels, d = deletes, u = replaces, p = trades, q = crosses, b = breaks,
                o = other;
        switch (message) {
            case ItchMessage.AddOrder m -> a++;
            case ItchMessage.OrderExecuted m -> e++;
            case ItchMessage.OrderExecutedWithPrice m -> e++;
            case ItchMessage.OrderCancel m -> x++;
            case ItchMessage.OrderDelete m -> d++;
            case ItchMessage.OrderReplace m -> u++;
            case ItchMessage.Trade m -> p++;
            case ItchMessage.CrossTrade m -> q++;
            case ItchMessage.BrokenTrade m -> b++;
            default -> o++;
        }
        return new PartitionStats(locate, symbol, messages + 1, bytes + frameBytes, a, e, x, d, u, p, q, b, o);
    }


    static final String tsvHeader = "locate\tsymbol\tmessages\tbytes\tadds\texecutions\tcancels\tdeletes\treplaces"
            + "\ttrades\tcrosses\tbreaks\tother";

    String toTsv() {
        return locate + "\t" + symbol + "\t" + messages + "\t" + bytes + "\t" + adds + "\t" + executions + "\t"
                + cancels + "\t" + deletes + "\t" + replaces + "\t" + trades + "\t" + crosses + "\t" + breaks + "\t"
                + other;
    }

    static PartitionStats fromTsv(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 13) {
            throw new ItchStoreException("Malformed catalog line: " + line);
        }
        return new PartitionStats(Integer.parseInt(f[0]), f[1], Long.parseLong(f[2]), Long.parseLong(f[3]),
                Long.parseLong(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]), Long.parseLong(f[7]),
                Long.parseLong(f[8]), Long.parseLong(f[9]), Long.parseLong(f[10]), Long.parseLong(f[11]),
                Long.parseLong(f[12]));
    }
}
