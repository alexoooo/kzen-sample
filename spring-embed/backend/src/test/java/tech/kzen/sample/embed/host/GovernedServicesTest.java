package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.model.SymbolDayGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The host's services over a synthetic day: the repository's tally equals the generator's, the governed loader
 * and book service materialize under the budget and return every lease (no leak, no native bytes outstanding),
 * a throwing processing callback still returns its lease, an oversized budget fails before waiting, and an
 * interruption while waiting rolls back — the three fixture paths' host leg, checked without kzen.
 */
class GovernedServicesTest {
    private static final long seed = 20260905L;

    @TempDir
    Path temp;

    private SyntheticItchDay synthetic;
    private WeightedBudget budget;
    private HostDay day;


    @BeforeEach
    void loadDay() throws IOException {
        synthetic = SyntheticItchDay.generate(seed, 20);
        Path file = temp.resolve("day.itch");
        synthetic.writeTo(file, false);
        budget = new WeightedBudget(64L << 20);
        day = new HostDay(file, temp.resolve("data"), budget);
    }

    @AfterEach
    void closeDay() {
        day.close();
    }


    @Test
    void repositoryTallyEqualsTheGeneratorsAndNeedsNoBudget() {
        FileTradeRepository trades = new FileTradeRepository(day);
        assertTrue(trades.available());
        assertEquals(synthetic.expectedTrades(), trades.tradeVolume());
        assertEquals(0, budget.stats().acquisitions(), "the fold materializes nothing");
    }


    @Test
    void loaderMaterializesEverySymbolFreshUnderTheBudgetAndClosingReturnsEveryLease() {
        GovernedSymbolDayLoader loader = new GovernedSymbolDayLoader(day);
        SortedMap<String, List<Long>> viaHost = new TreeMap<>();
        try (SymbolDayLoader.SymbolDayCursor cursor = loader.open()) {
            while (cursor.hasNext()) {
                try (SymbolDay symbolDay = cursor.next()) {
                    assertEquals(1, budget.stats().outstandingItems(), "one owned day at a time");
                    assertTrue(budget.stats().currentNativeBytes() > 0, "native bytes are counted while held");
                    long[] standing = SymbolDayGraph.build(symbolDay).standingTradeEventsAndShares();
                    if (standing[0] > 0) {
                        viaHost.put(symbolDay.symbol(), List.of(standing[0], standing[1]));
                    }
                }
                assertEquals(0, budget.stats().outstandingItems(), "closed day, lease returned");
            }
        }
        SortedMap<String, List<Long>> expected = new TreeMap<>();
        synthetic.expectedTrades().forEach((symbol, summary) ->
                expected.put(symbol, List.of(summary.tradeEvents(), summary.shares())));
        assertEquals(expected, viaHost, "the host route reaches the generator's tally");
        assertEquals(2 * loader.symbols().size(), budget.stats().acquisitions(), "one batch and one graph reservation per symbol");
        assertEquals(budget.stats().acquisitions(), budget.stats().releases());
        assertEquals(0, budget.stats().currentNativeBytes());
        assertEquals(0, day.leaks());
    }


    @Test
    void bookServiceReturnsTheLeaseWhetherProcessingReturnsOrThrows() {
        GovernedOrderBookService books = new GovernedOrderBookService(day);
        OrderBookService.BookTop top = books.bookTop(SyntheticItchDay.aapl, 2);
        assertEquals(SyntheticItchDay.aapl, top.symbol());
        assertEquals(0, budget.stats().outstandingItems());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> books.withSymbolDay(SyntheticItchDay.msft, symbolDay -> {
                    throw new IllegalStateException("processing failed on purpose");
                }));
        assertEquals("processing failed on purpose", failure.getMessage());
        assertEquals(0, budget.stats().outstandingItems(), "the day closed in the finally");
        assertEquals(budget.stats().acquisitions(), budget.stats().releases());
        assertEquals(0, day.leaks());
    }


    @Test
    void oversizedBudgetFailsBeforeWaitingAndAWaitingAcquireRollsBackOnInterrupt() throws Exception {
        WeightedBudget tiny = new WeightedBudget(1024);
        HostDay starved = new HostDay(temp.resolve("day.itch"), temp.resolve("data"), tiny);
        try {
            IllegalArgumentException oversized = assertThrows(IllegalArgumentException.class,
                    () -> new GovernedOrderBookService(starved).bookTop(SyntheticItchDay.aapl, 1));
            // The core consults canEverAdmit and refuses before acquiring: no wait, no permit, no allocation.
            assertTrue(oversized.getMessage().contains("more than the budget can ever admit"), oversized.getMessage());
            assertEquals(0, tiny.stats().acquisitions());
            assertEquals(0, tiny.stats().waits(), "rejected before waiting");
        }
        finally {
            starved.close();
        }

        // Fill the shared budget with a host hold, then a materialization must wait; interrupting it acquires nothing.
        long remaining = budget.capacityBytes() - 1;
        var hold = budget.acquire(new tech.kzen.sample.itch.day.MaterializationWeight(0, remaining));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                new GovernedOrderBookService(day).bookTop(SyntheticItchDay.goog, 1);
            }
            catch (Throwable t) {
                outcome.set(t);
            }
            finally {
                done.countDown();
            }
        });
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (budget.stats().waiting() != 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, budget.stats().waiting());
        waiter.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(outcome.get() instanceof IllegalStateException, String.valueOf(outcome.get()));
        assertTrue(outcome.get().getMessage().contains("Interrupted"), outcome.get().getMessage());
        assertEquals(1, budget.stats().interruptedWaits());
        assertEquals(1, budget.stats().outstandingItems(), "only the hold");
        hold.close();
        assertEquals(0, budget.stats().outstandingItems());
        assertEquals(0, day.leaks());
    }


    @Test
    void repositoryAndLoaderAgreeOnTheNamedMetric() {
        SortedMap<String, SymbolTradeSummary> repository = new FileTradeRepository(day).tradeVolume();
        List<String> loaderSymbols = new ArrayList<>(new GovernedSymbolDayLoader(day).symbols());
        assertTrue(loaderSymbols.containsAll(repository.keySet()));
        assertFalse(repository.containsKey(SyntheticItchDay.quiet));
    }
}
