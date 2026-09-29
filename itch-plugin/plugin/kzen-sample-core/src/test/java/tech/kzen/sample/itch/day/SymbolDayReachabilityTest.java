package tech.kzen.sample.itch.day;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.ItchStoreBuilder;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The one GC-dependent check, kept apart from the deterministic suite: an unreachable unclosed day is reported
 * and reclaimed by the Cleaner within a bounded number of collections. Ordinary tests never rely on this.
 */
class SymbolDayReachabilityTest {
    private static final int gcAttempts = 40;
    private static final long gcPauseMillis = 250;

    @TempDir
    Path temp;


    @Test
    void unreachableUnclosedDayIsReclaimedByTheCleaner() throws Exception {
        SyntheticItchDay day = SyntheticItchDay.generate(5L, 0);
        Path source = temp.resolve("day.itch");
        day.writeTo(source, false);
        Path store = temp.resolve("day.store");
        new ItchStoreBuilder().build(source, store);
        ItchStore opened = ItchStore.open(store);

        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<SymbolDayLeakDetector.LeakDiagnostic> diagnostic = new AtomicReference<>();
        SymbolDayLeakDetector.Listener listener = d -> {
            if (d.symbol().equals(SyntheticItchDay.aapl)) {
                diagnostic.set(d);
                reported.countDown();
            }
        };
        SymbolDayLeakDetector.addListener(listener);
        try {
            long nativeBytes = abandon(opened);
            boolean seen = false;
            for (int i = 0; i < gcAttempts && !seen; i++) {
                System.gc();
                seen = reported.await(gcPauseMillis, TimeUnit.MILLISECONDS);
            }
            assertTrue(seen, "the abandoned day was not reported within " + gcAttempts + " collections");
            assertEquals(nativeBytes, diagnostic.get().nativeBytes());
            assertTrue(diagnostic.get().nativeReleased());
            assertTrue(diagnostic.get().permitReleased());
        }
        finally {
            SymbolDayLeakDetector.removeListener(listener);
        }
    }


    /** Materializes in a separate frame so no local keeps the day reachable. */
    private static long abandon(ItchStore store) {
        SymbolDay day = SymbolDay.materialize(store, SyntheticItchDay.aaplLocate);
        return day.nativeBytes();
    }
}
