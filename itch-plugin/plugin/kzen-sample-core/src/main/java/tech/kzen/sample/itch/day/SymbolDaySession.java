package tech.kzen.sample.itch.day;

import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.StoreVersionLease;
import tech.kzen.sample.itch.store.block.PartitionBlocks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

/** Ordered, single-consumer loading session. Only compressed bytes are read speculatively. */
public final class SymbolDaySession implements AutoCloseable {
    public static final int maximumReadAheadBytes = 8 << 20;
    private static final int shutdownSeconds = 30;
    private final ItchStore store;
    private final MaterializationBudget budget;
    private final List<Integer> locates;
    private final boolean readAhead;
    private final StoreVersionLease version;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("itch-prefetch").factory());
    private PartitionBlocks.Decoder decoder;
    private MaterializationBudget.Lease scratch;
    private Pending pending;
    private int index;
    private volatile boolean closed;
    private volatile Thread pulling;
    private volatile Thread reading;

    public SymbolDaySession(ItchStore store, List<Integer> locates, MaterializationBudget budget) {
        this(store, locates, budget, true);
    }

    public SymbolDaySession(ItchStore store, List<Integer> locates, MaterializationBudget budget, boolean readAhead) {
        this.store = store;
        this.locates = List.copyOf(locates);
        this.budget = budget;
        this.readAhead = readAhead;
        version = new StoreVersionLease(store);
    }

    public boolean hasNext() { return !closed && index < locates.size(); }

    public SymbolDay next(MaterializationProgress progress) throws InterruptedException {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Symbol-day session is closed");
            if (!hasNext()) throw new NoSuchElementException();
            if (pulling != null) throw new IllegalStateException("Concurrent symbol-day pulls are unsupported");
            pulling = Thread.currentThread();
        }
        try {
            int locate = locates.get(index);
            MaterializationWeight weight = SymbolDay.batchWeight(store, locate);
            if (!budget.canEverAdmit(new MaterializationWeight(weight.nativeBytes(), PartitionBlocks.decoderScratchBytes)))
                throw new IllegalArgumentException("Symbol-day " + store.stats(locate).symbol()
                        + " and decoding workspace exceed what the budget can ever admit");
            if (decoder == null) {
                MaterializationWeight workspace = new MaterializationWeight(0, PartitionBlocks.decoderScratchBytes);
                scratch = budget.tryAcquire(workspace);
                if (scratch == null) { discardPending(); progress.waiting(); scratch = budget.acquire(workspace); }
                try { decoder = new PartitionBlocks.Decoder(); }
                catch (Throwable failure) { scratch.close(); scratch = null; throw failure; }
            }
            MaterializationBudget.Lease lease = budget.tryAcquire(weight);
            SymbolDay day;
            if (lease == null) {
                discardPending();
                releaseDecoder();
                progress.waiting();
                day = SymbolDay.materialize(store, locate, budget, progress);
            }
            else {
                byte[] prefix;
                try { prefix = pending == null ? null : pending.result(); }
                catch (Throwable failure) { lease.close(); discardPending(); throw failure; }
                try { day = SymbolDay.materialize(store, locate, budget, progress, decoder, prefix, lease); }
                finally { discardPending(); }
            }
            index++;
            try {
                if (closed) throw new InterruptedException("Symbol-day session closed while loading");
                if (readAhead && index < locates.size()) schedule(locates.get(index));
                if (index == locates.size()) releaseDecoder();
                return day;
            }
            catch (Throwable failure) { day.close(); throw failure; }
        }
        finally { synchronized (this) { pulling = null; notifyAll(); } }
    }

    private synchronized void schedule(int locate) {
        if (closed) return;
        Path path = store.partitionFile(locate);
        int size = (int) Math.min(maximumReadAheadBytes, store.stats(locate).storedBytes());
        MaterializationBudget.Lease reservation = budget.tryAcquire(new MaterializationWeight(0, size));
        if (reservation != null) {
            try { pending = new Pending(path, size, reservation); }
            catch (Throwable failure) { reservation.close(); throw failure; }
        }
    }

    private void discardPending() {
        Pending current;
        synchronized (this) { current = pending; pending = null; }
        if (current != null) current.close();
    }

    private void releaseDecoder() {
        if (decoder != null) {
            decoder.close(); decoder = null;
            scratch.close(); scratch = null;
        }
    }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        Thread active = pulling;
        if (active != null && active != Thread.currentThread()) active.interrupt();
        reader.shutdown();
        Thread io = reading;
        if (io != null) io.interrupt();
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownSeconds);
            while (true) {
                try {
                    if (!reader.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                        throw new IllegalStateException("ITCH prefetch did not stop");
                    break;
                }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (active != null && active != Thread.currentThread()) {
                synchronized (this) {
                    while (pulling != null && System.nanoTime() < deadline) {
                        try { TimeUnit.NANOSECONDS.timedWait(this, deadline - System.nanoTime()); }
                        catch (InterruptedException e) { interrupted = true; }
                    }
                    if (pulling != null) throw new IllegalStateException("ITCH loading did not stop");
                }
            }
            discardPending();
            try { releaseDecoder(); }
            finally { version.close(); }
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private final class Pending implements AutoCloseable {
        private final MaterializationBudget.Lease lease;
        private final Future<byte[]> future;
        private final CountDownLatch finished = new CountDownLatch(1);

        Pending(Path path, int size, MaterializationBudget.Lease lease) {
            this.lease = lease;
            future = reader.submit(() -> {
                reading = Thread.currentThread();
                try {
                    if (closed) throw new InterruptedException("ITCH prefetch closed");
                    try (FileChannel channel = FileChannel.open(path)) {
                        byte[] bytes = new byte[size];
                        ByteBuffer target = ByteBuffer.wrap(bytes);
                        while (target.hasRemaining()) {
                            if (channel.read(target) < 0) throw new IOException("Truncated prefetched partition " + path);
                        }
                        return bytes;
                    }
                }
                finally { reading = null; finished.countDown(); }
            });
        }

        byte[] result() throws InterruptedException {
            try { return future.get(); }
            catch (ExecutionException e) { throw new IllegalStateException("ITCH prefetch failed", e.getCause()); }
        }

        @Override public void close() {
            // Do not cancel a queued Future: its callable must run to signal that its buffer is no longer in use.
            boolean interrupted = Thread.interrupted();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownSeconds);
                while (true) {
                    try {
                        if (!finished.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                            throw new IllegalStateException("ITCH prefetch did not finish");
                        break;
                    }
                    catch (InterruptedException e) { interrupted = true; }
                }
                lease.close();
            }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
