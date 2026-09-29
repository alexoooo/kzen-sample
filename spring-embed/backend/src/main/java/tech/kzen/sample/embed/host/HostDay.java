package tech.kzen.sample.embed.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kzen.sample.itch.day.MaterializationWeight;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.day.SymbolDayLeakDetector;
import tech.kzen.sample.itch.store.ItchDataArea;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.ItchStoreBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The host's loaded day: the source file, the derived locate-partitioned store under the durable data area
 * (built when absent or stale, through the plain core), and the one {@link WeightedBudget} every
 * materialization draws on. Also the leak record: the core's Cleaner-backed detector reports a symbol-day
 * abandoned without close, and the host counts those — a governed route must show zero.
 */
public final class HostDay {
    private static final Logger logger = LoggerFactory.getLogger(HostDay.class);

    private final Path dayFile;
    private final ItchDataArea dataArea;
    private final WeightedBudget budget;
    private final AtomicLong leaks = new AtomicLong();
    private final SymbolDayLeakDetector.Listener leakListener = diagnostic -> {
        leaks.incrementAndGet();
        logger.error("Symbol-day leak: {} ({} native bytes; native released {}, permit released {})",
                diagnostic.symbol(), diagnostic.nativeBytes(), diagnostic.nativeReleased(), diagnostic.permitReleased());
    };

    private volatile ItchStore store;


    public HostDay(Path dayFile, Path dataRoot, WeightedBudget budget) {
        this.dayFile = dayFile;
        this.dataArea = new ItchDataArea(dataRoot);
        this.budget = budget;
        SymbolDayLeakDetector.addListener(leakListener);
    }


    public boolean available() {
        return dayFile != null && Files.isRegularFile(dayFile);
    }

    public Optional<Path> dayFile() {
        return Optional.ofNullable(dayFile);
    }

    public WeightedBudget budget() {
        return budget;
    }

    public long leaks() {
        return leaks.get();
    }


    /** The derived store, built on first use; fails by name when no day file is configured. */
    public ItchStore store() {
        ItchStore open = store;
        if (open != null) {
            return open;
        }
        synchronized (this) {
            if (store == null) {
                if (!available()) {
                    throw new IllegalStateException("No ITCH day file is configured (kzen.host.day-file)");
                }
                store = dataArea.ensureStore(dayFile, new ItchStoreBuilder());
                logger.info("Host day store ready under {} for {}", dataArea.stores(), dayFile);
            }
            return store;
        }
    }


    public List<String> symbols() {
        return List.copyOf(store().symbols().keySet());
    }


    /** One fresh symbol-day under the budget; the caller closes it. */
    public SymbolDay materialize(String symbol) throws InterruptedException {
        ItchStore open = store();
        return SymbolDay.materialize(open, open.locate(symbol), budget);
    }


    public void close() {
        SymbolDayLeakDetector.removeListener(leakListener);
    }
}
