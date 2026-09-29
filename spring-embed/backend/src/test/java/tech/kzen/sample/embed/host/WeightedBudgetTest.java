package tech.kzen.sample.embed.host;

import org.junit.jupiter.api.Test;
import tech.kzen.sample.itch.day.MaterializationBudget;
import tech.kzen.sample.itch.day.MaterializationWeight;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class WeightedBudgetTest {
    private static MaterializationWeight weight(long nativeBytes, long heapBytes) {
        return new MaterializationWeight(nativeBytes, heapBytes);
    }


    @Test
    void derivedAdmissionDoesNotWaitWhileTheBatchHoldsCapacity() throws Exception {
        WeightedBudget budget = new WeightedBudget(1000);
        try (var batch = budget.acquire(weight(800, 0))) {
            assertEquals(null, budget.tryAcquire(weight(0, 201)));
            assertEquals(0, budget.stats().waits());
            try (var graph = budget.tryAcquire(weight(0, 200))) {
                assertEquals(1000, budget.stats().currentBytes());
                assertEquals(800, budget.stats().currentNativeBytes());
            }
            assertEquals(800, budget.stats().currentBytes());
        }
        assertEquals(0, budget.stats().currentBytes());
        assertEquals(budget.stats().acquisitions(), budget.stats().releases());
    }

    @Test
    void admitsUpToCapacityCountsAndReleasesExactlyOnce() throws Exception {
        WeightedBudget budget = new WeightedBudget(1000);
        MaterializationBudget.Lease a = budget.acquire(weight(300, 300));
        MaterializationBudget.Lease b = budget.acquire(weight(100, 200));
        BudgetStats held = budget.stats();
        assertEquals(900, held.currentBytes());
        assertEquals(400, held.currentNativeBytes());
        assertEquals(900, held.peakBytes());
        assertEquals(2, held.outstandingItems());
        assertEquals(2, held.acquisitions());
        assertEquals(0, held.waits());

        a.close();
        a.close();
        BudgetStats after = budget.stats();
        assertEquals(300, after.currentBytes());
        assertEquals(1, after.outstandingItems());
        assertEquals(1, after.releases(), "a second close is a no-op");
        assertEquals(900, after.peakBytes(), "the peak is kept");
        b.close();
        assertEquals(0, budget.stats().currentBytes());
    }


    @Test
    void oversizedWeightFailsBeforeWaitingAndIsCounted() {
        WeightedBudget budget = new WeightedBudget(1000);
        assertFalse(budget.canEverAdmit(weight(600, 600)));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> budget.acquire(weight(600, 600)));
        assertTrue(failure.getMessage().contains("can never be admitted"), failure.getMessage());
        assertEquals(1, budget.stats().oversizedRejections());
        assertEquals(0, budget.stats().outstandingItems());
    }


    @Test
    void waitsUntilALeaseReturnsAndProgressResumes() throws Exception {
        WeightedBudget budget = new WeightedBudget(1000);
        MaterializationBudget.Lease held = budget.acquire(weight(700, 0));
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<MaterializationBudget.Lease> second = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                second.set(budget.acquire(weight(500, 0)));
                acquired.countDown();
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        awaitWaiting(budget, 1);
        assertFalse(acquired.await(200, TimeUnit.MILLISECONDS), "blocked while the capacity is spoken for");
        assertEquals(1, budget.stats().waits());

        held.close();
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "admitted once the lease returned");
        assertEquals(0, budget.stats().waiting());
        assertEquals(500, budget.stats().currentBytes());
        second.get().close();
        waiter.join(5_000);
    }


    @Test
    void interruptedWaitAcquiresNothing() throws Exception {
        WeightedBudget budget = new WeightedBudget(1000);
        MaterializationBudget.Lease held = budget.acquire(weight(900, 0));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                budget.acquire(weight(200, 0)).close();
            }
            catch (Throwable t) {
                outcome.set(t);
            }
        });
        waiter.start();
        awaitWaiting(budget, 1);
        waiter.interrupt();
        waiter.join(5_000);
        assertInstanceOf(InterruptedException.class, outcome.get());
        BudgetStats stats = budget.stats();
        assertEquals(1, stats.interruptedWaits());
        assertEquals(0, stats.waiting());
        assertEquals(1, stats.outstandingItems(), "only the original lease is held");
        assertEquals(900, stats.currentBytes());
        held.close();
        assertEquals(0, budget.stats().currentBytes());
    }


    private static void awaitWaiting(WeightedBudget budget, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (budget.stats().waiting() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, budget.stats().waiting());
    }
}
