package tech.kzen.sample.itch.day;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.store.*;
import tech.kzen.sample.itch.store.block.PartitionBlocks;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SymbolDaySessionTest {
    @TempDir Path temp;

    private ItchStore store() throws Exception {
        Path source = temp.resolve("day.itch");
        SyntheticItchDay.generate(42, 50_000).writeTo(source, false);
        new ItchStoreBuilder().build(source, temp.resolve("store"));
        return ItchStore.open(temp.resolve("store"));
    }

    @Test void orderedBatchesMatchReplayAndReleaseEveryReservation() throws Exception {
        ItchStore store = store();
        TestBudget budget = new TestBudget(16L << 20);
        var locates = List.copyOf(store.symbols().values());
        try (var session = new SymbolDaySession(store, locates, budget)) {
            for (int locate : locates) {
                try (var day = session.next(MaterializationProgress.none); var cursor = store.replay(locate)) {
                    int index = 0;
                    while (cursor.hasNext()) assertEquals(cursor.next(), day.message(index++));
                    assertEquals(day.messageCount(), index);
                }
            }
            assertFalse(session.hasNext());
        }
        assertEquals(0, budget.used);
        assertTrue(budget.peak <= budget.capacity);
    }

    @Test void smallBudgetDiscardsPrefetchAndNeverWaitsWithItsOwnScratch() throws Exception {
        ItchStore store = store();
        int locate = store.locate("AAPL");
        long capacity = SymbolDay.batchWeight(store, locate).total() + PartitionBlocks.decoderScratchBytes;
        TestBudget budget = new TestBudget(capacity);
        try (var session = new SymbolDaySession(store, List.of(locate, locate), budget)) {
            for (int i = 0; i < 2; i++) try (var day = session.next(MaterializationProgress.none)) {
                assertEquals("AAPL", day.symbol());
            }
        }
        assertEquals(0, budget.used);
    }

    @Test void cancellationWhileAdmittingStopsAndReleasesSession() throws Exception {
        ItchStore store = store();
        TestBudget budget = new TestBudget(16L << 20);
        try (var hold = budget.acquire(new MaterializationWeight(0, budget.capacity));
             var session = new SymbolDaySession(store, List.of(store.locate("AAPL")), budget);
             var executor = Executors.newSingleThreadExecutor()) {
            var waiting = new CountDownLatch(1);
            var future = executor.submit(() -> {
                assertThrows(InterruptedException.class, () -> session.next(new MaterializationProgress() {
                    public void update(long messages, long bytes) {}
                    public void waiting() { waiting.countDown(); }
                }));
            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            session.close();
            future.get(5, TimeUnit.SECONDS);
            assertEquals(budget.capacity, budget.used);
        }
        assertEquals(0, budget.used);
    }

    @Test void closingPrefetchLeavesTheReturnedDayOwnedByItsCaller() throws Exception {
        ItchStore store = store();
        TestBudget budget = new TestBudget(16L << 20);
        var session = new SymbolDaySession(store, List.copyOf(store.symbols().values()), budget);
        try (session; var day = session.next(MaterializationProgress.none)) {
            session.close();
            assertTrue(day.isOpen());
            assertEquals(day.weight().total(), budget.used);
            assertNotNull(day.message(0));
        }
        assertEquals(0, budget.used);
    }

    @Test void unopenedPartitionsSurviveRebuildAndCorruptionFailsWithoutLeaking() throws Exception {
        ItchStore store = store();
        TestBudget budget = new TestBudget(16L << 20);
        try (var session = new SymbolDaySession(store, List.of(store.locate("AAPL"), store.locate("MSFT")), budget)) {
            new ItchStoreBuilder().build(temp.resolve("day.itch"), store.root());
            try (var day = session.next(MaterializationProgress.none)) { assertEquals("AAPL", day.symbol()); }
            try (var day = session.next(MaterializationProgress.none)) { assertEquals("MSFT", day.symbol()); }
        }
        assertEquals(0, budget.used);
        ItchStore current = ItchStore.open(store.root());
        Path file = current.partitionFile(current.locate("AAPL"));
        byte[] contents = Files.readAllBytes(file);
        contents[PartitionBlocks.headerBytes + 20] ^= 0x40;
        Files.write(file, contents);
        try (var session = new SymbolDaySession(current, List.of(current.locate("AAPL")), budget)) {
            assertThrows(ItchStoreException.class, () -> session.next(MaterializationProgress.none));
        }
        assertEquals(0, budget.used);
    }

    private static final class TestBudget implements MaterializationBudget {
        final long capacity;
        long used, peak;
        TestBudget(long capacity) { this.capacity = capacity; }
        public boolean canEverAdmit(MaterializationWeight weight) { return weight.total() <= capacity; }
        public synchronized Lease acquire(MaterializationWeight weight) throws InterruptedException {
            if (!canEverAdmit(weight)) throw new IllegalArgumentException("Oversized admission");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (used + weight.total() > capacity) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Admission deadlocked");
                wait(50);
            }
            return tryAcquire(weight);
        }
        public synchronized Lease tryAcquire(MaterializationWeight weight) {
            if (used + weight.total() > capacity) return null;
            used += weight.total(); peak = Math.max(peak, used);
            return new Lease() {
                boolean closed;
                public MaterializationWeight weight() { return weight; }
                public void close() { synchronized (TestBudget.this) {
                    if (!closed) { closed = true; used -= weight.total(); TestBudget.this.notifyAll(); }
                } }
            };
        }
    }
}
