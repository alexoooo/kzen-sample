package tech.kzen.sample.embed.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The leak detector exercised on purpose, at the host's level: a symbol-day materialized under the budget and
 * abandoned without close is reported by the core's Cleaner once unreachable, the host counts it, and the
 * fallback returns its lease exactly once. Bounded and separate from the correctness tests (GC timing is not
 * something a normal run may depend on); a normal run must report zero of these.
 */
class LeakDiagnosticTest {
    @TempDir
    Path temp;


    @Test
    void abandonedSymbolDayIsReportedAndItsLeaseReturnedByTheFallback() throws Exception {
        SyntheticItchDay synthetic = SyntheticItchDay.generate(7L, 5);
        Path file = temp.resolve("day.itch");
        synthetic.writeTo(file, false);
        WeightedBudget budget = new WeightedBudget(64L << 20);
        HostDay day = new HostDay(file, temp.resolve("data"), budget);
        try {
            abandon(day);
            assertEquals(1, budget.stats().outstandingItems(), "the abandoned day still holds its lease");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (day.leaks() == 0 && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(100);
            }
            assertEquals(1, day.leaks(), "the Cleaner reported the abandoned day");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (budget.stats().outstandingItems() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0, budget.stats().outstandingItems(), "the fallback returned the lease");
            assertEquals(1, budget.stats().releases());

            // A day closed normally is never a leak, however many collections follow.
            try (SymbolDay closed = day.materialize(SyntheticItchDay.msft)) {
                assertTrue(closed.isOpen());
            }
            System.gc();
            Thread.sleep(200);
            assertEquals(1, day.leaks());
            assertEquals(0, budget.stats().outstandingItems());
        }
        finally {
            day.close();
        }
    }


    /** Materializes and drops the reference in a frame that returns — no local keeps the day reachable. */
    private static void abandon(HostDay day) throws InterruptedException {
        SymbolDay leaked = day.materialize(SyntheticItchDay.aapl);
        assertTrue(leaked.isOpen());
    }
}
