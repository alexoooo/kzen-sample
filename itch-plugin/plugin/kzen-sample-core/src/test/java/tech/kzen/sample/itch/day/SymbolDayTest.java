package tech.kzen.sample.itch.day;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.model.BookLevel;
import tech.kzen.sample.itch.model.BookSnapshot;
import tech.kzen.sample.itch.model.OrderEvent;
import tech.kzen.sample.itch.model.OrderLifecycle;
import tech.kzen.sample.itch.model.SymbolDayGraph;
import tech.kzen.sample.itch.model.TradeEvent;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.ItchStoreBuilder;
import tech.kzen.sample.itch.store.StoreFormat;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SymbolDayTest {
    private static final long seed = 11L;
    private static final long price4 = ItchMessage.priceScale4;
    private static final long threadTimeoutSeconds = 10;

    @TempDir
    Path temp;

    private ItchStore scripted;
    private final List<SymbolDayLeakDetector.LeakDiagnostic> leaks = new ArrayList<>();
    private final SymbolDayLeakDetector.Listener listener = leaks::add;


    @BeforeEach
    void buildScriptedStore() throws IOException {
        scripted = store(SyntheticItchDay.generate(seed, 0), "scripted");
        SymbolDayLeakDetector.addListener(listener);
    }

    @AfterEach
    void removeListener() {
        SymbolDayLeakDetector.removeListener(listener);
    }


    //-----------------------------------------------------------------------------------------------------------------
    @Test
    void loadingProgressIncludesSharedFramesAndUpdatesInsideLargeBatches() throws Exception {
        ItchStore large = store(SyntheticItchDay.generate(seed, 10_000), "progress");
        List<long[]> updates = new ArrayList<>();
        try (SymbolDay batch = SymbolDay.materialize(large, SyntheticItchDay.aaplLocate,
                MaterializationBudget.unlimited(), (messages, bytes) -> updates.add(new long[]{messages, bytes}))) {
            assertTrue(updates.size() > 2);
            assertEquals(0, updates.getFirst()[0]);
            assertEquals(batch.messageCount(), updates.getLast()[0]);
            assertEquals(large.stats(SyntheticItchDay.aaplLocate).bytes() + large.stats(0).bytes(), updates.getLast()[1]);
            for (int i = 1; i < updates.size(); i++) {
                assertTrue(updates.get(i)[0] >= updates.get(i - 1)[0]);
                assertTrue(updates.get(i)[1] >= updates.get(i - 1)[1]);
            }
        }
    }

    @Test
    void progressFailureReleasesAdmission() {
        RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> SymbolDay.materialize(scripted,
                SyntheticItchDay.aaplLocate, budget, (messages, bytes) -> { throw new IllegalStateException("observer failed"); }));
        assertEquals(1, budget.acquired.get());
        assertEquals(1, budget.released.get());
    }

    @Test
    void batchLoadsWithoutGraphAdmissionAndAllRecordViewsShareItsLifetime() throws Exception {
        RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
        ItchMessage retained;
        try (SymbolDay batch = SymbolDay.materialize(scripted, SyntheticItchDay.aaplLocate, budget)) {
            assertEquals(1, budget.acquired.get());
            assertEquals(0, batch.weight().estimatedHeapBytes());
            retained = batch.message(0);
            assertEquals('S', retained.type());
            assertThrows(IllegalStateException.class, () -> SymbolDayGraph.build(batch));
            assertEquals('S', retained.type(), "rejected graph construction leaves the batch readable");
        }
        assertThrows(IllegalStateException.class, retained::type);
        assertEquals(1, budget.released.get());
    }

    @Test
    void bookStatesAndOrderLifecyclesMatchTheHandAuthoredScenario() {
        try (SymbolDay aapl = SymbolDay.materialize(scripted, SyntheticItchDay.aaplLocate)) {
            SymbolDayGraph graph = SymbolDayGraph.build(aapl);
            List<BookSnapshot> history = graph.bookHistory();
            assertEquals(1 + 8, history.size(), "empty book plus one state per book-affecting AAPL message");

            BookSnapshot afterAdds = history.get(2);
            assertEquals(new BookLevel(150 * price4, 100, 1), afterAdds.bestBid());
            assertEquals(new BookLevel(150 * price4 + 5000, 100, 1), afterAdds.bestAsk());
            assertEquals(5000, afterAdds.spread());
            assertEquals(new BookLevel(150 * price4, 60, 1), history.get(3).bestBid());
            assertNull(history.get(4).bestBid(), "full fill empties the bid");
            assertEquals(new BookLevel(150 * price4 + 4000, 80, 1), history.get(5).bestAsk(), "replace moved the ask");
            assertEquals(50, history.get(6).bestAsk().shares());
            assertEquals(30, history.get(7).bestAsk().shares());
            assertNull(history.get(8).bestAsk(), "delete empties the ask");

            OrderLifecycle buy = graph.order(1);
            assertEquals(OrderLifecycle.State.FILLED, buy.state());
            assertEquals(100, buy.executedShares());
            assertEquals(2, buy.executionCount());
            assertEquals(1.0, buy.fillRatio());
            OrderLifecycle sell = graph.order(2);
            assertEquals(OrderLifecycle.State.REPLACED, sell.state());
            assertEquals(3, ((OrderEvent.Replaced) sell.events().getLast()).newReference());
            OrderLifecycle replacement = graph.order(3);
            assertEquals(2, replacement.replacedFrom());
            assertEquals(OrderLifecycle.State.DELETED, replacement.state());
            assertEquals(30, replacement.executedShares());
            assertEquals(List.of(OrderEvent.Executed.class, OrderEvent.Cancelled.class, OrderEvent.Deleted.class),
                    replacement.events().stream().map(Object::getClass).toList());
            assertEquals(3, graph.orders().size());
            assertEquals(2, graph.peakActiveOrders());

            BookSnapshot beforeFirstFill = graph.bookBefore(graph.trades().getFirst().ordinal());
            assertEquals(afterAdds, beforeFirstFill, "depth before the first execution is the two-order book");
            assertEquals(new long[] {3, 130}[1], graph.standingTradeEventsAndShares()[1]);
        }

        try (SymbolDay msft = SymbolDay.materialize(scripted, SyntheticItchDay.msftLocate)) {
            SymbolDayGraph graph = SymbolDayGraph.build(msft);
            OrderLifecycle attributed = graph.orders().getFirst();
            assertEquals("NSDQ", attributed.attribution());
            assertEquals(OrderLifecycle.State.DELETED, attributed.state());
            assertEquals(300, ((OrderEvent.Deleted) attributed.events().getLast()).remainingShares());
            List<TradeEvent.Kind> kinds = graph.trades().stream().map(TradeEvent::kind).toList();
            assertEquals(List.of(TradeEvent.Kind.EXECUTED, TradeEvent.Kind.EXECUTED, TradeEvent.Kind.NON_DISPLAYED,
                    TradeEvent.Kind.CROSS, TradeEvent.Kind.CROSS), kinds);
            assertFalse(graph.trades().get(1).printable(), "non-printable C");
            assertFalse(graph.trades().get(4).printable(), "zero-share cross");
            assertTrue(graph.brokenMatches().contains(graph.trades().getFirst().matchNumber()));
            assertArrayEqualsLong(new long[] {2, 1250}, graph.standingTradeEventsAndShares());
        }

        try (SymbolDay goog = SymbolDay.materialize(scripted, SyntheticItchDay.googLocate)) {
            SymbolDayGraph graph = SymbolDayGraph.build(goog);
            TradeEvent nonDisplayed = graph.trades().getFirst();
            assertEquals(TradeEvent.Kind.NON_DISPLAYED, nonDisplayed.kind());
            assertEquals(new BookLevel(2800 * price4, 10, 1), graph.bookBefore(nonDisplayed.ordinal()).bestBid());
            assertEquals(OrderLifecycle.State.FILLED, graph.orders().getFirst().state());
            assertTrue(goog.messageCount() > graph.bookHistory().size(), "market-wide messages are merged in");
            assertEquals(ItchMessage.SystemEvent.class, goog.message(0).getClass());
        }
    }


    @Test
    void historicalStatesAreImmutableAndStoreRouteAgreesWithTheRawFold() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed);
        ItchStore store = store(day, "seeded");
        for (Map.Entry<String, SymbolTradeSummary> expected : day.expectedTrades().entrySet()) {
            try (SymbolDay symbolDay = SymbolDay.materialize(store, store.locate(expected.getKey()))) {
                SymbolDayGraph graph = SymbolDayGraph.build(symbolDay);
                BookSnapshot early = graph.bookHistory().get(3);
                List<BookLevel> earlyBids = early.bidDepth(5);
                List<BookLevel> earlyAsks = early.askDepth(5);
                long[] standing = graph.standingTradeEventsAndShares();
                assertEquals(expected.getValue().tradeEvents(), standing[0], expected.getKey());
                assertEquals(expected.getValue().shares(), standing[1], expected.getKey());
                assertEquals(earlyBids, graph.bookHistory().get(3).bidDepth(5));
                assertEquals(earlyAsks, graph.bookHistory().get(3).askDepth(5));
                for (OrderLifecycle order : graph.orders()) {
                    assertTrue(order.remainingShares() >= 0);
                    if (order.state() == OrderLifecycle.State.ACTIVE) {
                        throw new AssertionError("the generator deletes every live order at close of day");
                    }
                }
            }
        }
    }


    @Test
    void crossThreadReadThenCloseAndPostCloseAccess() throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            SymbolDay day = SymbolDay.materialize(scripted, SyntheticItchDay.aaplLocate);
            BookSnapshot borrowed = SymbolDayGraph.build(day).bookHistory().get(2);
            Future<ItchMessage> read = other.submit(() -> day.message(day.messageCount() - 1));
            assertEquals(ItchMessage.SystemEvent.class, read.get(threadTimeoutSeconds, TimeUnit.SECONDS).getClass());

            NativeAccounting.Snapshot before = NativeAccounting.snapshot();
            other.submit(day::close).get(threadTimeoutSeconds, TimeUnit.SECONDS);
            assertEquals(SymbolDay.State.CLOSED, day.state());
            assertEquals(before.nativeBytesLive() - day.nativeBytes(), NativeAccounting.snapshot().nativeBytesLive());

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> day.message(0));
            assertTrue(failure.getMessage().contains("AAPL is CLOSED"), failure.getMessage());
            assertThrows(IllegalStateException.class, () -> SymbolDayGraph.build(day));
            assertEquals(new BookLevel(150 * price4, 100, 1), borrowed.bestBid(),
                    "a heap child obtained before close stays readable");
            day.close();
            assertEquals(SymbolDay.State.CLOSED, day.state());
        }
        finally {
            other.shutdownNow();
        }
    }


    @Test
    void failedMaterializationReleasesNativeStorageAndLease() throws Exception {
        RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
        Path partition = scripted.directory().resolve(StoreFormat.partitionsDirectoryName)
                .resolve(SyntheticItchDay.googLocate + StoreFormat.partitionFileSuffix);
        Files.write(partition, new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        NativeAccounting.Snapshot before = NativeAccounting.snapshot();

        assertThrows(RuntimeException.class, () -> SymbolDay.materialize(scripted, SyntheticItchDay.googLocate, budget));
        assertEquals(1, budget.acquired.get());
        assertEquals(1, budget.released.get(), "the lease came back");
        assertEquals(before.daysLive(), NativeAccounting.snapshot().daysLive());
        assertEquals(before.nativeBytesLive(), NativeAccounting.snapshot().nativeBytesLive());
    }


    @Test
    void oversizedDayFailsBeforeAcquiring() {
        RecordingBudget budget = new RecordingBudget(1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> SymbolDay.materialize(
                scripted, SyntheticItchDay.aaplLocate, budget));
        assertTrue(failure.getMessage().contains("more than the budget can ever admit"), failure.getMessage());
        assertEquals(0, budget.acquired.get());
    }


    @Test
    void nativeReleasePrecedesPermitReturnAndCloseIsIdempotent() throws Exception {
        RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
        SymbolDay day = SymbolDay.materialize(scripted, SyntheticItchDay.aaplLocate, budget);
        long liveBeforeClose = NativeAccounting.snapshot().nativeBytesLive();
        budget.onRelease = () -> assertEquals(liveBeforeClose - day.nativeBytes(),
                NativeAccounting.snapshot().nativeBytesLive(), "native bytes are released before the permit");
        day.close();
        day.close();
        assertEquals(1, budget.released.get());
        assertTrue(leaks.isEmpty());
        day.cleanupState().run();
        assertTrue(leaks.isEmpty(), "an explicitly closed day never reports a leak");
    }


    @Test
    void abandonedDayIsDiagnosedAndReclaimedExactlyOnce() throws Exception {
        RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
        SymbolDay day = SymbolDay.materialize(scripted, SyntheticItchDay.msftLocate, budget);
        NativeAccounting.Snapshot before = NativeAccounting.snapshot();

        day.cleanupState().clean();
        day.cleanupState().run();
        assertEquals(1, leaks.size());
        SymbolDayLeakDetector.LeakDiagnostic diagnostic = leaks.getFirst();
        assertEquals(SyntheticItchDay.msft, diagnostic.symbol());
        assertEquals(day.nativeBytes(), diagnostic.nativeBytes());
        assertTrue(diagnostic.nativeReleased());
        assertTrue(diagnostic.permitReleased());
        assertNull(diagnostic.releaseFailure());
        assertEquals(1, budget.released.get());
        NativeAccounting.Snapshot after = NativeAccounting.snapshot();
        assertEquals(before.leaksDetected() + 1, after.leaksDetected());
        assertEquals(before.leaksReclaimed() + 1, after.leaksReclaimed());
        assertEquals(before.nativeBytesLive() - day.nativeBytes(), after.nativeBytesLive());
    }


    @Test
    void cleanupFailureIsReportedNotHidden() throws Exception {
        RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
        budget.onRelease = () -> { throw new IllegalStateException("permit accounting broken"); };
        SymbolDay day = SymbolDay.materialize(scripted, SyntheticItchDay.googLocate, budget);
        day.cleanupState().run();
        assertEquals(1, leaks.size());
        SymbolDayLeakDetector.LeakDiagnostic diagnostic = leaks.getFirst();
        assertTrue(diagnostic.nativeReleased());
        assertFalse(diagnostic.permitReleased());
        assertNotNull(diagnostic.releaseFailure());
        assertEquals("permit accounting broken", diagnostic.releaseFailure().getMessage());
    }


    @Test
    void budgetBlocksUntilAPermitReturns() throws Exception {
        SemaphoreBudget budget = new SemaphoreBudget(1);
        SymbolDay first = SymbolDay.materialize(scripted, SyntheticItchDay.aaplLocate, budget);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<SymbolDay> second = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                waiting.countDown();
                second.set(SymbolDay.materialize(scripted, SyntheticItchDay.msftLocate, budget));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        waiting.await();
        thread.join(300);
        assertTrue(thread.isAlive(), "the second day waits for the permit");
        assertEquals(1, budget.waits.get());

        first.close();
        thread.join(TimeUnit.SECONDS.toMillis(threadTimeoutSeconds));
        assertFalse(thread.isAlive());
        assertNotNull(second.get());
        second.get().close();
        assertEquals(1, budget.semaphore.availablePermits());
    }


    @Test
    void symbolDaysStreamIsSingleUseAndHandsOutOwnedDays() {
        List<String> symbols = new ArrayList<>();
        try (SymbolDays days = SymbolDays.of(scripted)) {
            for (SymbolDay day : days) {
                try (day) {
                    symbols.add(day.symbol());
                }
            }
            assertThrows(IllegalStateException.class, days::iterator);
        }
        assertEquals(List.of(SyntheticItchDay.aapl, SyntheticItchDay.goog, SyntheticItchDay.msft,
                SyntheticItchDay.quiet), symbols);
        SymbolDays closed = SymbolDays.of(scripted);
        closed.close();
        assertThrows(IllegalStateException.class, closed::iterator);
    }


    //-----------------------------------------------------------------------------------------------------------------
    private ItchStore store(SyntheticItchDay day, String name) throws IOException {
        Path source = temp.resolve(name + ".itch");
        day.writeTo(source, false);
        Path store = temp.resolve(name + ".store");
        new ItchStoreBuilder().build(source, store);
        return ItchStore.open(store);
    }

    private static void assertArrayEqualsLong(long[] expected, long[] actual) {
        assertEquals(List.of(expected[0], expected[1]), List.of(actual[0], actual[1]));
    }


    /** Counts acquisitions and releases; [onRelease] runs inside the lease's close. */
    private static final class RecordingBudget implements MaterializationBudget {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        final long capacity;
        Runnable onRelease = () -> {};

        RecordingBudget(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean canEverAdmit(MaterializationWeight weight) {
            return weight.total() <= capacity;
        }

        @Override
        public Lease acquire(MaterializationWeight weight) {
            acquired.incrementAndGet();
            return new Lease() {
                private boolean closed;
                @Override public MaterializationWeight weight() { return weight; }
                @Override public void close() {
                    if (closed) return;
                    closed = true;
                    onRelease.run();
                    released.incrementAndGet();
                }
            };
        }
    }


    /** One permit per day, blocking: the shape a host's weighted semaphore takes. */
    private static final class SemaphoreBudget implements MaterializationBudget {
        final Semaphore semaphore;
        final AtomicInteger waits = new AtomicInteger();

        SemaphoreBudget(int permits) {
            semaphore = new Semaphore(permits);
        }

        @Override
        public Lease acquire(MaterializationWeight weight) throws InterruptedException {
            if (!semaphore.tryAcquire()) {
                waits.incrementAndGet();
                semaphore.acquire();
            }
            return new Lease() {
                private boolean closed;
                @Override public MaterializationWeight weight() { return weight; }
                @Override public void close() {
                    if (closed) return;
                    closed = true;
                    semaphore.release();
                }
            };
        }
    }
}
