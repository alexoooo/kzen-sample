package tech.kzen.sample.itch.bench;

import tech.kzen.sample.itch.day.MaterializationWeight;
import tech.kzen.sample.itch.day.NativeAccounting;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.day.SymbolDayLeakDetector;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.model.SymbolDayGraph;
import tech.kzen.sample.itch.model.TradeEvent;
import tech.kzen.sample.itch.store.ItchDataArea;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.ItchStoreBuilder;
import tech.kzen.sample.itch.store.PartitionStats;
import tech.kzen.sample.itch.store.SourceFingerprint;
import tech.kzen.sample.itch.wire.ItchCursor;
import tech.kzen.sample.itch.wire.ItchReader;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;


/**
 * The P1 measurement entry point over one real day, core only (no kzen, no host): sequential decode
 * throughput, store build time and disk size, then for selected symbol-days (largest, median, named)
 * repeated materialize / replay / historical-query / close cycles reporting exact native bytes, peak heap,
 * retained heap, allocation volume and the estimate-versus-observed ratio, plus the process-wide accounting
 * at the end so leakage across repeats is visible. Prints one Markdown table per section to stdout.
 *
 * <pre>
 * java -Xmx6g -cp kzen-sample-core.jar tech.kzen.sample.itch.bench.ItchDayBenchmark \
 *      &lt;source .gz&gt; &lt;data root&gt; [--repeat=3] [--largest=3] [--symbols=AAPL,MSFT] [--skip-decode] [--sha256]
 * </pre>
 */
public final class ItchDayBenchmark {
    private static final int defaultRepeat = 3;
    private static final int defaultLargest = 3;
    private static final double bytesPerMebibyte = 1024.0 * 1024.0;
    private static final double nanosPerSecond = 1_000_000_000.0;

    private ItchDayBenchmark() {}


    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: <source> <data root> [--repeat=N] [--largest=K] [--symbols=A,B] [--skip-decode] [--sha256]");
            System.exit(2);
        }
        Path source = Path.of(args[0]).toAbsolutePath();
        ItchDataArea area = new ItchDataArea(Path.of(args[1]));
        int repeat = defaultRepeat;
        int largest = defaultLargest;
        List<String> symbols = new ArrayList<>();
        boolean skipDecode = false;
        boolean fullSha = false;
        for (String arg : args) {
            if (arg.startsWith("--repeat=")) repeat = Integer.parseInt(arg.substring("--repeat=".length()));
            else if (arg.startsWith("--largest=")) largest = Integer.parseInt(arg.substring("--largest=".length()));
            else if (arg.startsWith("--symbols=")) symbols.addAll(List.of(arg.substring("--symbols=".length()).split(",")));
            else if (arg.equals("--skip-decode")) skipDecode = true;
            else if (arg.equals("--sha256")) fullSha = true;
        }

        environment(source, fullSha);
        if (!skipDecode) {
            decodePass(source);
        }
        ItchStore store = buildPass(source, area);
        List<Integer> selected = select(store, largest, symbols);
        materializePass(store, selected, repeat);
        accounting();
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static void environment(Path source, boolean fullSha) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        System.out.println("## Environment");
        System.out.println();
        System.out.println("| Item | Value |");
        System.out.println("|---|---|");
        System.out.println("| source | `" + source + "` |");
        System.out.println("| source size | " + Files.size(source) + " bytes |");
        System.out.println("| fingerprint | `" + SourceFingerprint.of(source).encode() + "` |");
        if (fullSha) {
            System.out.println("| sha256 | `" + SourceFingerprint.fullSha256(source) + "` |");
        }
        System.out.println("| java | " + Runtime.version() + " " + System.getProperty("java.vendor") + " |");
        System.out.println("| os | " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + " |");
        System.out.println("| processors | " + runtime.availableProcessors() + " |");
        System.out.println("| max heap | " + mib(runtime.maxMemory()) + " MiB |");
        System.out.println("| gc | " + ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(gc -> gc.getName()).toList() + " |");
        System.out.println();
    }


    private static void decodePass(Path source) throws IOException {
        Map<Character, Long> byType = new LinkedHashMap<>();
        long messages = 0;
        long maxLocate = 0;
        long start = System.nanoTime();
        try (ItchCursor cursor = new ItchReader(source).open()) {
            while (cursor.hasNext()) {
                ItchMessage message = cursor.next();
                byType.merge(message.type(), 1L, Long::sum);
                maxLocate = Math.max(maxLocate, message.header().stockLocate());
                messages++;
            }
        }
        double seconds = (System.nanoTime() - start) / nanosPerSecond;
        long bytes = Files.size(source);
        System.out.println("## Sequential decode (raw feed, gzip inflated in-process)");
        System.out.println();
        System.out.println("| Metric | Value |");
        System.out.println("|---|---|");
        System.out.println("| messages | " + messages + " |");
        System.out.println("| highest locate | " + maxLocate + " |");
        System.out.println("| elapsed | " + String.format(Locale.ROOT, "%.1f s", seconds) + " |");
        System.out.println("| throughput | " + String.format(Locale.ROOT, "%.2f M msg/s, %.1f MiB/s compressed",
                messages / seconds / 1e6, bytes / bytesPerMebibyte / seconds) + " |");
        System.out.println("| by type | " + byType + " |");
        System.out.println();
    }


    private static ItchStore buildPass(Path source, ItchDataArea area) throws IOException {
        Path storeRoot = area.storeFor(source);
        boolean existed = Files.exists(storeRoot.resolve("current"));
        long start = System.nanoTime();
        ItchStore store = area.ensureStore(source, new ItchStoreBuilder());
        double seconds = (System.nanoTime() - start) / nanosPerSecond;
        long diskBytes;
        try (Stream<Path> walk = Files.walk(store.directory())) {
            diskBytes = walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { throw new RuntimeException(e); }
            }).sum();
        }
        long messages = store.manifest().messages();
        System.out.println("## Derived store");
        System.out.println();
        System.out.println("| Metric | Value |");
        System.out.println("|---|---|");
        System.out.println("| store | `" + store.directory() + "` |");
        System.out.println("| built now | " + !existed + (existed ? " (reused a fresh store)" : "") + " |");
        System.out.println("| build elapsed | " + String.format(Locale.ROOT, "%.1f s", seconds) + " |");
        if (!existed) {
            System.out.println("| build throughput | " + String.format(Locale.ROOT, "%.2f M msg/s", messages / seconds / 1e6) + " |");
        }
        System.out.println("| messages | " + messages + " |");
        System.out.println("| partitions | " + store.partitions().size() + " (symbols " + store.symbols().size() + ") |");
        System.out.println("| disk | " + mib(diskBytes) + " MiB |");
        System.out.println("| buffer budget | " + mib(ItchStoreBuilder.defaultBufferBudgetBytes) + " MiB |");
        PartitionStats shared = store.partitions().get(ItchHeader.marketWideLocate);
        System.out.println("| locate-0 messages / bytes | " + (shared == null ? "none" : shared.messages() + " / " + shared.bytes()) + " |");
        System.out.println();
        return store;
    }


    private static List<Integer> select(ItchStore store, int largest, List<String> symbols) {
        List<PartitionStats> symbolPartitions = store.partitions().values().stream()
                .filter(p -> p.locate() != ItchHeader.marketWideLocate)
                .sorted(Comparator.comparingLong(PartitionStats::messages).reversed())
                .toList();
        List<Integer> selected = new ArrayList<>();
        System.out.println("## Selected partitions");
        System.out.println();
        System.out.println("| Role | Symbol | Locate | Messages | Adds | Executions | Trades | Bytes |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        for (int i = 0; i < Math.min(largest, symbolPartitions.size()); i++) {
            PartitionStats p = symbolPartitions.get(i);
            selected.add(p.locate());
            row("largest " + (i + 1), p);
        }
        if (!symbolPartitions.isEmpty()) {
            PartitionStats median = symbolPartitions.get(symbolPartitions.size() / 2);
            if (!selected.contains(median.locate())) {
                selected.add(median.locate());
                row("median", median);
            }
        }
        for (String symbol : symbols) {
            int locate = store.locate(symbol);
            if (!selected.contains(locate)) {
                selected.add(locate);
                row("named", store.stats(locate));
            }
        }
        System.out.println();
        return selected;
    }

    private static void row(String role, PartitionStats p) {
        System.out.println("| " + role + " | " + p.symbol() + " | " + p.locate() + " | " + p.messages() + " | " + p.adds()
                + " | " + p.executions() + " | " + p.trades() + " | " + p.bytes() + " |");
    }


    private static void materializePass(ItchStore store, List<Integer> selected, int repeat) throws Exception {
        List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP).toList();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        SymbolDayLeakDetector.Listener listener = d -> System.out.println("LEAK " + d);
        SymbolDayLeakDetector.addListener(listener);

        System.out.println("## Materialization (one symbol-day at a time, unlimited budget)");
        System.out.println();
        System.out.println("| Symbol | Run | Messages | Materialize s | Native MiB (exact) | Est. heap MiB | Retained heap MiB "
                + "| Est/retained | Peak heap MiB | Allocated MiB | Book states | Orders | Peak live | Trades "
                + "| Replay s | Depth-before-trade queries/s | Close ms | Heap after close MiB |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");

        for (int locate : selected) {
            for (int run = 1; run <= repeat; run++) {
                settle();
                long heapBefore = used();
                heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
                long allocatedBefore = threads.getCurrentThreadAllocatedBytes();

                long t0 = System.nanoTime();
                SymbolDay day = SymbolDay.materialize(store, locate);
                long t1 = System.nanoTime();
                long allocated = threads.getCurrentThreadAllocatedBytes() - allocatedBefore;
                long peak = heapPools.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
                settle();
                long retained = used() - heapBefore;

                SymbolDayGraph graph = day.graph();
                long r0 = System.nanoTime();
                long replayed = 0;
                for (int i = 0; i < day.messageCount(); i++) {
                    day.message(i);
                    replayed++;
                }
                long r1 = System.nanoTime();
                long queries = 0;
                long q0 = System.nanoTime();
                for (TradeEvent trade : graph.trades()) {
                    graph.bookBefore(trade.ordinal()).bestBid();
                    queries++;
                }
                long q1 = System.nanoTime();
                MaterializationWeight weight = day.weight();
                long nativeBytes = day.nativeBytes();
                int bookStates = graph.bookHistory().size();
                int orders = graph.orders().size();
                int peakLive = graph.peakActiveOrders();
                int trades = graph.trades().size();

                long c0 = System.nanoTime();
                day.close();
                long c1 = System.nanoTime();
                graph = null;
                settle();
                long heapAfterClose = used() - heapBefore;

                System.out.println(String.format(Locale.ROOT,
                        "| %s | %d | %d | %.2f | %.1f | %.1f | %.1f | %.2f | %.1f | %.1f | %d | %d | %d | %d | %.3f | %.0f | %.1f | %.1f |",
                        day.symbol(), run, replayed, (t1 - t0) / nanosPerSecond, mibD(nativeBytes),
                        mibD(weight.estimatedHeapBytes()), mibD(retained),
                        retained <= 0 ? 0.0 : (double) weight.estimatedHeapBytes() / retained,
                        mibD(peak), mibD(allocated), bookStates, orders, peakLive, trades,
                        (r1 - r0) / nanosPerSecond, queries / ((q1 - q0) / nanosPerSecond), (c1 - c0) / 1e6,
                        mibD(heapAfterClose)));
            }
        }
        SymbolDayLeakDetector.removeListener(listener);
        System.out.println();
    }


    private static void accounting() {
        NativeAccounting.Snapshot s = NativeAccounting.snapshot();
        System.out.println("## Process accounting after all runs");
        System.out.println();
        System.out.println("| Metric | Value |");
        System.out.println("|---|---|");
        System.out.println("| days opened / closed / live | " + s.daysOpened() + " / " + s.daysClosed() + " / " + s.daysLive() + " |");
        System.out.println("| native allocated / released / live | " + s.nativeBytesAllocated() + " / " + s.nativeBytesReleased()
                + " / " + s.nativeBytesLive() + " |");
        System.out.println("| release failures | " + s.releaseFailures() + " |");
        System.out.println("| leaks detected / reclaimed | " + s.leaksDetected() + " / " + s.leaksReclaimed() + " |");
        System.out.println();
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static void settle() throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        System.gc();
    }

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long mib(long bytes) {
        return Math.round(bytes / bytesPerMebibyte);
    }

    private static double mibD(long bytes) {
        return bytes / bytesPerMebibyte;
    }
}
