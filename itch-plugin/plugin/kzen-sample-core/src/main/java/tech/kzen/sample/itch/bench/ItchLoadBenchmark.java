package tech.kzen.sample.itch.bench;

import tech.kzen.sample.itch.day.*;
import tech.kzen.sample.itch.store.ItchStore;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Prepared-store throughput without graph construction, forced GC, or per-symbol source validation. */
public final class ItchLoadBenchmark {
    private ItchLoadBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException("Usage: ItchLoadBenchmark <store> [runs=4] [prefetch=true]");
        ItchStore store = ItchStore.open(Path.of(args[0]));
        int runs = args.length >= 2 ? Integer.parseInt(args[1]) : 4;
        boolean prefetch = args.length < 3 || Boolean.parseBoolean(args[2]);
        long start = System.nanoTime(), disk = 0;
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20);
        for (int locate : store.partitions().keySet()) try (FileChannel channel = FileChannel.open(store.partitionFile(locate))) {
            int count;
            while ((count = channel.read(buffer.clear())) != -1) disk += count;
        }
        System.out.printf(Locale.ROOT, "Bulk read: %.3f s, %d compressed bytes%n", (System.nanoTime() - start) / 1e9, disk);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var gc = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("run\tseconds\tmessages\tlogicalBytes\tpullThreadAllocatedBytes\tgcMillis");
        for (int run = 1; run <= runs; run++) {
            long allocated = threads.getCurrentThreadAllocatedBytes();
            long gcBefore = gc.stream().mapToLong(p -> p.getCollectionTime()).sum();
            long messages = 0, bytes = 0;
            start = System.nanoTime();
            try (var session = new SymbolDaySession(store, List.copyOf(store.symbols().values()), MaterializationBudget.unlimited(), prefetch)) {
                while (session.hasNext()) try (var day = session.next(MaterializationProgress.none)) {
                    messages += day.messageCount();
                    bytes += day.nativeBytes() - (long) day.messageCount() * Long.BYTES;
                }
            }
            System.out.printf(Locale.ROOT, "%d\t%.3f\t%d\t%d\t%d\t%d%n", run, (System.nanoTime() - start) / 1e9,
                    messages, bytes, threads.getCurrentThreadAllocatedBytes() - allocated,
                    gc.stream().mapToLong(p -> p.getCollectionTime()).sum() - gcBefore);
        }
        System.out.println(NativeAccounting.snapshot());
    }
}
