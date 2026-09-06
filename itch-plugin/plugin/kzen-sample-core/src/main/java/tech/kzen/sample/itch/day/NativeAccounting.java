package tech.kzen.sample.itch.day;

import java.util.concurrent.atomic.AtomicLong;


/**
 * Process-wide open/close accounting for symbol-days: native bytes allocated and released, days open and
 * closed, and leaks the detector reported. Explicit and deterministic: a retained leak shows here as
 * {@code open > closed} at the end of a run, independent of GC timing. Read-only snapshots for hosts and tests.
 */
public final class NativeAccounting {
    private NativeAccounting() {}

    private static final AtomicLong nativeBytesAllocated = new AtomicLong();
    private static final AtomicLong nativeBytesReleased = new AtomicLong();
    private static final AtomicLong daysOpened = new AtomicLong();
    private static final AtomicLong daysClosed = new AtomicLong();
    private static final AtomicLong releaseFailures = new AtomicLong();
    private static final AtomicLong leaksDetected = new AtomicLong();
    private static final AtomicLong leaksReclaimed = new AtomicLong();


    public record Snapshot(
            long nativeBytesAllocated,
            long nativeBytesReleased,
            long daysOpened,
            long daysClosed,
            long releaseFailures,
            long leaksDetected,
            long leaksReclaimed
    ) {
        public long nativeBytesLive() {
            return nativeBytesAllocated - nativeBytesReleased;
        }

        public long daysLive() {
            return daysOpened - daysClosed;
        }
    }


    public static Snapshot snapshot() {
        return new Snapshot(nativeBytesAllocated.get(), nativeBytesReleased.get(), daysOpened.get(),
                daysClosed.get(), releaseFailures.get(), leaksDetected.get(), leaksReclaimed.get());
    }


    static void opened(long nativeBytes) {
        nativeBytesAllocated.addAndGet(nativeBytes);
        daysOpened.incrementAndGet();
    }

    static void closed(long nativeBytes) {
        nativeBytesReleased.addAndGet(nativeBytes);
        daysClosed.incrementAndGet();
    }

    static void releaseFailed() {
        releaseFailures.incrementAndGet();
    }

    static void leakDetected() {
        leaksDetected.incrementAndGet();
    }

    static void leakReclaimed(long nativeBytes) {
        leaksReclaimed.incrementAndGet();
        nativeBytesReleased.addAndGet(nativeBytes);
        daysClosed.incrementAndGet();
    }
}
