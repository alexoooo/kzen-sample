package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.day.MaterializationBudget;
import tech.kzen.sample.itch.day.MaterializationWeight;

import java.util.concurrent.atomic.AtomicLong;


/**
 * Host-wide admission for native batches and derived heap data. Initial batch acquisition can wait;
 * derived admission is nonblocking because its caller already holds a batch. Counters count reservations.
 */
public final class WeightedBudget implements MaterializationBudget {
    private final long capacityBytes;
    private final Object lock = new Object();

    private long currentBytes;
    private long currentNativeBytes;
    private long peakBytes;
    private int outstanding;
    private int waiting;

    private final AtomicLong acquisitions = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong oversizedRejections = new AtomicLong();
    private final AtomicLong interruptedWaits = new AtomicLong();


    public WeightedBudget(long capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("Budget capacity must be positive: " + capacityBytes);
        }
        this.capacityBytes = capacityBytes;
    }


    public long capacityBytes() {
        return capacityBytes;
    }


    @Override
    public boolean canEverAdmit(MaterializationWeight weight) {
        return weight.total() <= capacityBytes;
    }


    @Override
    public Lease acquire(MaterializationWeight weight) throws InterruptedException {
        long total = weight.total();
        if (total < 0) {
            throw new IllegalArgumentException("Weight must not be negative: " + weight);
        }
        if (total > capacityBytes) {
            oversizedRejections.incrementAndGet();
            throw new IllegalArgumentException("Weight " + total + " bytes exceeds the budget's capacity of "
                    + capacityBytes + " bytes; it can never be admitted");
        }
        synchronized (lock) {
            boolean waited = false;
            while (currentBytes + total > capacityBytes) {
                if (!waited) {
                    waited = true;
                    waits.incrementAndGet();
                    waiting++;
                }
                try {
                    lock.wait();
                }
                catch (InterruptedException e) {
                    waiting--;
                    interruptedWaits.incrementAndGet();
                    throw e;
                }
            }
            if (waited) {
                waiting--;
            }
            currentBytes += total;
            currentNativeBytes += weight.nativeBytes();
            peakBytes = Math.max(peakBytes, currentBytes);
            outstanding++;
            acquisitions.incrementAndGet();
        }
        return new HeldLease(weight);
    }


    @Override
    public Lease tryAcquire(MaterializationWeight weight) {
        long total = weight.total();
        if (weight.nativeBytes() < 0 || weight.estimatedHeapBytes() < 0 || total < 0)
            throw new IllegalArgumentException("Weight must not be negative: " + weight);
        synchronized (lock) {
            if (total > capacityBytes - currentBytes) return null;
            currentBytes += total;
            currentNativeBytes += weight.nativeBytes();
            peakBytes = Math.max(peakBytes, currentBytes);
            outstanding++;
            acquisitions.incrementAndGet();
            return new HeldLease(weight);
        }
    }

    public BudgetStats stats() {
        synchronized (lock) {
            return new BudgetStats(
                    capacityBytes, currentBytes, currentNativeBytes, peakBytes, outstanding, waiting,
                    acquisitions.get(), releases.get(), waits.get(), interruptedWaits.get(), oversizedRejections.get());
        }
    }


    private final class HeldLease implements Lease {
        private final MaterializationWeight weight;
        private boolean closed;

        HeldLease(MaterializationWeight weight) {
            this.weight = weight;
        }

        @Override
        public MaterializationWeight weight() {
            return weight;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                currentBytes -= weight.total();
                currentNativeBytes -= weight.nativeBytes();
                outstanding--;
                releases.incrementAndGet();
                lock.notifyAll();
            }
        }
    }
}
