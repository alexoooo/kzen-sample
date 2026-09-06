package tech.kzen.sample.itch.synth;

import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.Side;
import tech.kzen.sample.itch.wire.ItchEncoder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;


/**
 * A seeded synthetic ITCH day in the real binary format, with expected results tallied by the generator's own
 * bookkeeping as it emits events (independent of the decoder and the folds under test). The scripted prefix
 * covers every scenario the fixture must exercise; the seeded tail adds volume with random lifecycles.
 */
public final class SyntheticItchDay {
    public static final String aapl = "AAPL";
    public static final String msft = "MSFT";
    public static final String goog = "GOOG";
    /** Announced in the directory but never traded. */
    public static final String quiet = "QUIET";

    public static final int aaplLocate = 1;
    public static final int msftLocate = 2;
    public static final int googLocate = 3;
    public static final int quietLocate = 4;

    private static final long nanosPerSecond = 1_000_000_000L;
    private static final long marketOpenNanos = 9 * 3600 * nanosPerSecond + 30 * 60 * nanosPerSecond;
    private static final long scriptedStepNanos = nanosPerSecond;
    private static final long randomStepMaxNanos = 50_000_000L;
    private static final long price4 = ItchMessage.priceScale4;
    private static final int defaultRandomOrdersPerSymbol = 400;

    private final List<ItchMessage> messages = new ArrayList<>();
    private final Map<String, SymbolTradeSummary> expectedTrades = new HashMap<>();
    private final Map<Long, PrintedEvent> printedByMatch = new HashMap<>();
    private final Map<Integer, String> symbolsByLocate = new TreeMap<>();

    private long nextOrdinal = 0;
    private int nextTracking = 1;
    private long clock = marketOpenNanos;
    private long nextOrderReference = 1;
    private long nextMatchNumber = 1000;

    private record PrintedEvent(String symbol, long shares) {}


    //-----------------------------------------------------------------------------------------------------------------
    public static SyntheticItchDay generate(long seed) {
        return generate(seed, defaultRandomOrdersPerSymbol);
    }


    public static SyntheticItchDay generate(long seed, int randomOrdersPerSymbol) {
        SyntheticItchDay day = new SyntheticItchDay();
        day.scripted();
        day.random(new Random(seed), randomOrdersPerSymbol);
        day.closeOfDay();
        return day;
    }


    private SyntheticItchDay() {}


    //-----------------------------------------------------------------------------------------------------------------
    public List<ItchMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public SortedMap<String, SymbolTradeSummary> expectedTrades() {
        return new TreeMap<>(expectedTrades);
    }

    public Map<Integer, String> symbolsByLocate() {
        return Collections.unmodifiableMap(symbolsByLocate);
    }


    public void writeTo(Path path, boolean gzip) throws IOException {
        try (OutputStream raw = Files.newOutputStream(path);
             OutputStream out = gzip ? new GZIPOutputStream(raw) : new BufferedOutputStream(raw)) {
            for (ItchMessage message : messages) {
                ItchEncoder.writeFrame(out, message);
            }
        }
    }


    public long encodedLength() {
        long total = 0;
        for (ItchMessage message : messages) {
            total += ItchEncoder.frameLength(message);
        }
        return total;
    }


    //-----------------------------------------------------------------------------------------------------------------
    private void scripted() {
        emit(systemEvent("O"));
        directory(aaplLocate, aapl);
        directory(msftLocate, msft);
        directory(googLocate, goog);
        directory(quietLocate, quiet);
        emit(new ItchMessage.MwcbDeclineLevel(header(ItchHeader.marketWideLocate),
                3000_00000000L, 2800_00000000L, 2500_00000000L));
        emit(systemEvent("S"));
        for (int locate : List.of(aaplLocate, msftLocate, googLocate, quietLocate)) {
            emit(new ItchMessage.StockTradingAction(header(locate), symbolsByLocate.get(locate), "T", " ", ""));
            emit(new ItchMessage.RegShoRestriction(header(locate), symbolsByLocate.get(locate), "0"));
        }
        emit(systemEvent("Q"));
        emit(new ItchMessage.MwcbStatus(header(ItchHeader.marketWideLocate), "1"));

        // AAPL: partial then full fill, a replace chain, partial cancel, delete; two adds at one timestamp
        long aaplBuy = add(aaplLocate, aapl, Side.BUY, 100, 150 * price4, null);
        holdClock();
        long aaplSell = add(aaplLocate, aapl, Side.SELL, 100, 150 * price4 + 5000, null);
        execute(aaplLocate, aapl, aaplBuy, 40);
        execute(aaplLocate, aapl, aaplBuy, 60);
        long aaplSell2 = replace(aaplLocate, aaplSell, 80, 150 * price4 + 4000);
        execute(aaplLocate, aapl, aaplSell2, 30);
        emit(new ItchMessage.OrderCancel(header(aaplLocate), aaplSell2, 20));
        emit(new ItchMessage.OrderDelete(header(aaplLocate), aaplSell2));

        // MSFT: attributed add, printable and non-printable execution with price, a break, a non-displayed
        // trade, a real cross and an empty one; GOOG interleaved at identical timestamps
        long msftSell = add(msftLocate, msft, Side.SELL, 500, 300 * price4, "NSDQ");
        holdClock();
        long googBuy = add(googLocate, goog, Side.BUY, 10, 2800 * price4, null);
        long printedMatch = executeWithPrice(msftLocate, msft, msftSell, 100, true, 300 * price4 - 100);
        holdClock();
        long googTradeMatch = trade(googLocate, goog, Side.BUY, 5, 2800 * price4 + 500);
        executeWithPrice(msftLocate, msft, msftSell, 100, false, 300 * price4);
        trade(msftLocate, msft, Side.BUY, 250, 300 * price4 + 1000);
        breakTrade(msftLocate, printedMatch);
        breakTrade(googLocate, googTradeMatch);
        cross(msftLocate, msft, 1000, 300 * price4, "O");
        cross(msftLocate, msft, 0, 300 * price4, "H");
        execute(googLocate, goog, googBuy, 10);
        emit(new ItchMessage.OrderDelete(header(msftLocate), msftSell));
    }


    private void random(Random random, int ordersPerSymbol) {
        record Live(int locate, String symbol, long reference, long remaining, Side side, long price) {}
        List<Live> live = new ArrayList<>();
        int[] locates = {aaplLocate, msftLocate, googLocate};
        long[] basePrices = {150 * price4, 300 * price4, 2800 * price4};

        for (int i = 0; i < ordersPerSymbol * locates.length; i++) {
            int pick = random.nextInt(locates.length);
            int locate = locates[pick];
            String symbol = symbolsByLocate.get(locate);
            int action = random.nextInt(10);

            if (live.isEmpty() || action < 4) {
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                long shares = (1 + random.nextInt(20)) * 100L;
                long price = basePrices[pick] + (random.nextInt(201) - 100) * 100L;
                String attribution = random.nextInt(5) == 0 ? "MPID" : null;
                long reference = add(locate, symbol, side, shares, price, attribution);
                live.add(new Live(locate, symbol, reference, shares, side, price));
                continue;
            }

            Live order = live.remove(random.nextInt(live.size()));
            switch (action) {
                case 4, 5 -> {
                    long executed = Math.min(order.remaining(), (1 + random.nextInt(5)) * 100L);
                    execute(order.locate(), order.symbol(), order.reference(), executed);
                    if (executed < order.remaining()) {
                        live.add(new Live(order.locate(), order.symbol(), order.reference(),
                                order.remaining() - executed, order.side(), order.price()));
                    }
                }
                case 6 -> {
                    boolean printable = random.nextBoolean();
                    long executed = Math.min(order.remaining(), 100L);
                    executeWithPrice(order.locate(), order.symbol(), order.reference(), executed, printable,
                            order.price() - 100);
                    if (executed < order.remaining()) {
                        live.add(new Live(order.locate(), order.symbol(), order.reference(),
                                order.remaining() - executed, order.side(), order.price()));
                    }
                }
                case 7 -> {
                    long newShares = order.remaining() + 100;
                    long newPrice = order.price() + 100;
                    long newReference = replace(order.locate(), order.reference(), newShares, newPrice);
                    live.add(new Live(order.locate(), order.symbol(), newReference, newShares, order.side(), newPrice));
                }
                case 8 -> {
                    if (order.remaining() > 100) {
                        emit(new ItchMessage.OrderCancel(header(order.locate()), order.reference(), 100));
                        live.add(new Live(order.locate(), order.symbol(), order.reference(),
                                order.remaining() - 100, order.side(), order.price()));
                    }
                    else {
                        emit(new ItchMessage.OrderDelete(header(order.locate()), order.reference()));
                    }
                }
                default -> {
                    long match = trade(order.locate(), order.symbol(), Side.BUY, (1 + random.nextInt(3)) * 100L,
                            order.price());
                    if (random.nextInt(4) == 0) {
                        breakTrade(order.locate(), match);
                    }
                    live.add(order);
                }
            }
            if (random.nextInt(7) == 0) {
                holdClock();
            }
        }
        for (Live order : live) {
            emit(new ItchMessage.OrderDelete(header(order.locate()), order.reference()));
        }
    }


    private void closeOfDay() {
        emit(systemEvent("M"));
        emit(systemEvent("E"));
        emit(systemEvent("C"));
    }


    //-----------------------------------------------------------------------------------------------------------------
    private void directory(int locate, String symbol) {
        symbolsByLocate.put(locate, symbol);
        emit(new ItchMessage.StockDirectory(header(locate), symbol, "Q", "N", 100, "N", "C", "Z", "P", "N",
                "N", "1", "N", 0, "N"));
    }

    private ItchMessage.SystemEvent systemEvent(String code) {
        return new ItchMessage.SystemEvent(header(ItchHeader.marketWideLocate), code);
    }

    private long add(int locate, String symbol, Side side, long shares, long price, String attribution) {
        long reference = nextOrderReference++;
        emit(new ItchMessage.AddOrder(header(locate), reference, side, shares, symbol, price, attribution));
        return reference;
    }

    private void execute(int locate, String symbol, long reference, long shares) {
        long match = nextMatchNumber++;
        emit(new ItchMessage.OrderExecuted(header(locate), reference, shares, match));
        tally(symbol, match, shares);
    }

    private long executeWithPrice(int locate, String symbol, long reference, long shares, boolean printable, long price) {
        long match = nextMatchNumber++;
        emit(new ItchMessage.OrderExecutedWithPrice(header(locate), reference, shares, match, printable, price));
        if (printable) {
            tally(symbol, match, shares);
        }
        return match;
    }

    private long replace(int locate, long reference, long shares, long price) {
        long newReference = nextOrderReference++;
        emit(new ItchMessage.OrderReplace(header(locate), reference, newReference, shares, price));
        return newReference;
    }

    private long trade(int locate, String symbol, Side side, long shares, long price) {
        long match = nextMatchNumber++;
        emit(new ItchMessage.Trade(header(locate), 0, side, shares, symbol, price, match));
        tally(symbol, match, shares);
        return match;
    }

    private void cross(int locate, String symbol, long shares, long price, String crossType) {
        long match = nextMatchNumber++;
        emit(new ItchMessage.CrossTrade(header(locate), shares, symbol, price, match, crossType));
        if (shares > 0) {
            tally(symbol, match, shares);
        }
    }

    private void breakTrade(int locate, long match) {
        emit(new ItchMessage.BrokenTrade(header(locate), match));
        PrintedEvent printed = printedByMatch.remove(match);
        if (printed != null) {
            expectedTrades.compute(printed.symbol(), (s, summary) -> summary.minus(printed.shares()));
        }
    }

    private void tally(String symbol, long match, long shares) {
        printedByMatch.put(match, new PrintedEvent(symbol, shares));
        expectedTrades.compute(symbol, (s, summary) ->
                (summary == null ? SymbolTradeSummary.empty(s) : summary).plus(shares));
    }


    //-----------------------------------------------------------------------------------------------------------------
    private boolean holdNextClock;

    /** The next message keeps the previous timestamp, so equal timestamps occur across locates on purpose. */
    private void holdClock() {
        holdNextClock = true;
    }

    private ItchHeader header(int locate) {
        if (holdNextClock) {
            holdNextClock = false;
        }
        else {
            clock += messages.size() < 64 ? scriptedStepNanos : 1 + (messages.size() * 7919L) % randomStepMaxNanos;
        }
        return new ItchHeader(nextOrdinal, locate, nextTracking++, clock);
    }

    private void emit(ItchMessage message) {
        if (message.header().ordinal() != nextOrdinal) {
            throw new IllegalStateException("Header built out of order");
        }
        messages.add(message);
        nextOrdinal++;
    }
}
