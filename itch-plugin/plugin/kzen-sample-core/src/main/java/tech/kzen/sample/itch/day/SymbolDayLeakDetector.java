package tech.kzen.sample.itch.day;

import java.lang.foreign.Arena;
import java.lang.ref.Cleaner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Diagnostic backstop for a {@link SymbolDay} abandoned without {@link SymbolDay#close}: a JDK {@link Cleaner}
 * action registered with detached state (arena, lease, symbol, byte count, optional allocation provenance —
 * never the day or its graph) that, when the day becomes unreachable unclosed, reports a named leak and makes
 * one safe attempt to release the native storage and the permit. An explicit close marks the state first, so
 * the action then does nothing. This catches unreachable unclosed days only; a day still held by a bad lease
 * cannot become unreachable, which is what {@link NativeAccounting} and the run's own accounting are for.
 */
public final class SymbolDayLeakDetector {
    private SymbolDayLeakDetector() {}

    private static final Cleaner cleaner = Cleaner.create();
    private static final System.Logger logger = System.getLogger(SymbolDayLeakDetector.class.getName());
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean captureProvenance = false;


    /** What the detector observed for one abandoned day. */
    public record LeakDiagnostic(
            String symbol,
            long nativeBytes,
            boolean nativeReleased,
            boolean permitReleased,
            Throwable releaseFailure,
            List<StackTraceElement> provenance
    ) {}


    public interface Listener {
        void onLeak(LeakDiagnostic diagnostic);
    }


    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Whether each day records its allocation stack for the diagnostic (costly; off by default). */
    public static void captureProvenance(boolean enabled) {
        captureProvenance = enabled;
    }


    static CleanupState register(SymbolDay day, String symbol, long nativeBytes, Arena arena,
            MaterializationBudget.Lease lease) {
        List<StackTraceElement> provenance = captureProvenance
                ? List.of(Thread.currentThread().getStackTrace())
                : List.of();
        CleanupState state = new CleanupState(symbol, nativeBytes, arena, lease, provenance);
        state.cleanable = cleaner.register(day, state);
        return state;
    }


    /**
     * The detached cleanup state. {@link #run} is invoked by the Cleaner when the day is unreachable, or by the
     * day's own close (after {@link #markClosed}) so the Cleaner never runs it a second time.
     */
    static final class CleanupState implements Runnable {
        private final String symbol;
        private final long nativeBytes;
        private final Arena arena;
        private final MaterializationBudget.Lease lease;
        private final List<StackTraceElement> provenance;
        private final AtomicBoolean explicitlyClosed = new AtomicBoolean(false);
        private final AtomicBoolean ran = new AtomicBoolean(false);
        private Cleaner.Cleanable cleanable;

        private CleanupState(String symbol, long nativeBytes, Arena arena, MaterializationBudget.Lease lease,
                List<StackTraceElement> provenance) {
            this.symbol = symbol;
            this.nativeBytes = nativeBytes;
            this.arena = arena;
            this.lease = lease;
            this.provenance = provenance;
        }

        void markClosed() {
            explicitlyClosed.set(true);
        }

        /** Deregisters and runs the action now (a no-op after {@link #markClosed}). */
        void clean() {
            cleanable.clean();
        }

        @Override
        public void run() {
            if (!ran.compareAndSet(false, true) || explicitlyClosed.get()) {
                return;
            }
            NativeAccounting.leakDetected();
            boolean nativeReleased = false;
            boolean permitReleased = false;
            Throwable failure = null;
            try {
                arena.close();
                nativeReleased = true;
            }
            catch (Throwable t) {
                failure = t;
            }
            try {
                lease.close();
                permitReleased = true;
            }
            catch (Throwable t) {
                if (failure == null) failure = t; else failure.addSuppressed(t);
            }
            if (nativeReleased) {
                NativeAccounting.leakReclaimed(nativeBytes);
            }
            else {
                NativeAccounting.releaseFailed();
            }
            LeakDiagnostic diagnostic = new LeakDiagnostic(symbol, nativeBytes, nativeReleased, permitReleased,
                    failure, provenance);
            logger.log(System.Logger.Level.WARNING, "SymbolDay {0} was abandoned without close: {1} native bytes"
                    + " (native released: {2}, permit released: {3})", symbol, nativeBytes, nativeReleased,
                    permitReleased);
            for (Listener listener : listeners) {
                listener.onLeak(diagnostic);
            }
        }
    }
}
