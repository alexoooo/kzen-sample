package tech.kzen.sample.itch.day;

import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.ItchRecord;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.PartitionStats;
import tech.kzen.sample.itch.store.StoreFormat;
import tech.kzen.sample.itch.store.StoreVersionLease;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.store.block.PartitionBlocks;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


/**
 * One complete symbol-day batch, including shared market-wide records in feed order. The Arena and admission
 * leases belong to this unit. Messages are views into its packed storage and become invalid when it closes.
 */
public final class SymbolDay implements AutoCloseable {
    public enum State { OPEN, CLOSED, CLOSE_FAILED }

    private static final int interruptCheckInterval = 4096;

    private final String symbol;
    private final int locate;
    private final Arena arena;
    private final MemorySegment frames;
    private final MemorySegment offsets;
    private final int messageCount;
    private final long nativeBytes;
    private final MaterializationWeight weight;
    private final MaterializationBudget.Lease lease;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final SymbolDayLeakDetector.CleanupState cleanup;
    private final MaterializationBudget budget;
    private final PartitionStats partitionStats;
    private final List<MaterializationBudget.Lease> heapLeases;



    //-----------------------------------------------------------------------------------------------------------------
    /** Materializes [locate] of [store] under the unlimited budget. */
    public static SymbolDay materialize(ItchStore store, int locate) {
        try {
            return materialize(store, locate, MaterializationBudget.unlimited());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted under the unlimited budget", e);
        }
    }


    /**
     * Acquires a lease for the day's weight (blocking on [budget]), then loads the batch. Any failure or
     * interruption after acquisition releases the native storage and the lease before propagating; an
     * oversized day fails before waiting.
     */
    public static SymbolDay materialize(
            ItchStore store, int locate, MaterializationBudget budget
    ) throws InterruptedException {
        return materialize(store, locate, budget, MaterializationProgress.none);
    }


    public static SymbolDay materialize(
            ItchStore store, int locate, MaterializationBudget budget, MaterializationProgress progress
    ) throws InterruptedException {
        try (var version = new StoreVersionLease(store)) {
            MaterializationWeight batch = batchWeight(store, locate);
            MaterializationWeight admission = new MaterializationWeight(batch.nativeBytes(), PartitionBlocks.decoderScratchBytes);
            if (!budget.canEverAdmit(admission)) throw new IllegalArgumentException("Symbol-day " + store.stats(locate).symbol()
                    + " and decoding workspace weigh " + admission.total() + " bytes, more than the budget can ever admit");
            MaterializationBudget.Lease lease = budget.acquire(admission);
            PartitionBlocks.Decoder decoder;
            try { decoder = new PartitionBlocks.Decoder(); }
            catch (Throwable failure) { lease.close(); throw failure; }
            try (decoder) { return materialize(store, locate, budget, progress, decoder, null, lease); }
        }
    }

    static MaterializationWeight batchWeight(ItchStore store, int locate) {
        return MaterializationWeight.batch(store.stats(locate), locate == ItchHeader.marketWideLocate
                ? null : store.partitions().get(ItchHeader.marketWideLocate));
    }

    static SymbolDay materialize(ItchStore store, int locate, MaterializationBudget budget,
            MaterializationProgress progress, PartitionBlocks.Decoder decoder, byte[] prefix,
            MaterializationBudget.Lease lease) throws InterruptedException {
        java.util.Objects.requireNonNull(progress);
        PartitionStats own = store.stats(locate);
        PartitionStats shared = locate == ItchHeader.marketWideLocate ? null : store.partitions().get(ItchHeader.marketWideLocate);
        MaterializationWeight weight = MaterializationWeight.batch(own, shared);
        Arena arena;
        try { arena = Arena.ofShared(); }
        catch (Throwable failure) { lease.close(); throw failure; }
        try {
            return load(store, locate, own, shared, weight, lease, arena, budget, progress, decoder, prefix);
        }
        catch (Throwable failure) {
            try {
                arena.close();
            }
            catch (Throwable releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            try {
                lease.close();
            }
            catch (Throwable releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }


    private static SymbolDay load(
            ItchStore store, int locate, PartitionStats own, PartitionStats shared, MaterializationWeight weight,
            MaterializationBudget.Lease lease, Arena arena, MaterializationBudget budget, MaterializationProgress progress,
            PartitionBlocks.Decoder decoder, byte[] prefix
    ) throws InterruptedException {
        progress.update(0, 0);
        long frameBytes = own.bytes() + (shared == null ? 0 : shared.bytes());
        long messages = own.messages() + (shared == null ? 0 : shared.messages());
        MemorySegment frames = arena.allocate(frameBytes, MaterializationWeight.nativeAlignment);
        MemorySegment offsets = arena.allocate(messages * MaterializationWeight.offsetIndexBytesPerMessage,
                MaterializationWeight.nativeAlignment);
        store.loadPartition(locate, frames.asSlice(0, own.bytes()), decoder, prefix, progress);
        if (shared != null) store.loadPartition(ItchHeader.marketWideLocate,
                frames.asSlice(own.bytes(), shared.bytes()), decoder, null,
                (count, bytes) -> progress.update(own.messages() + count, own.bytes() + bytes));
        long ownPosition = 0, sharedPosition = own.bytes(), previousOrdinal = -1;
        int count = Math.toIntExact(messages);
        for (int index = 0; index < count; index++) {
            if (index % interruptCheckInterval == 0 && Thread.interrupted())
                throw new InterruptedException("Materialization of " + own.symbol() + " interrupted at message " + index);
            boolean takeOwn = ownPosition < own.bytes() && (sharedPosition == frameBytes
                    || frames.get(StoreFormat.ordinalLayout, ownPosition) < frames.get(StoreFormat.ordinalLayout, sharedPosition));
            long position = takeOwn ? ownPosition : sharedPosition;
            if (position >= frameBytes) throw new IllegalStateException("Store replay exceeds catalog message count");
            long ordinal = frames.get(StoreFormat.ordinalLayout, position);
            if (ordinal <= previousOrdinal) throw new IllegalStateException("Store replay is not in feed order");
            previousOrdinal = ordinal;
            offsets.setAtIndex(ValueLayout.JAVA_LONG, index, position);
            int length = Short.toUnsignedInt(frames.get(StoreFormat.lengthLayout, position + StoreFormat.ordinalBytes));
            if (takeOwn) ownPosition += StoreFormat.frameHeaderBytes + length;
            else sharedPosition += StoreFormat.frameHeaderBytes + length;
        }
        if (ownPosition != own.bytes() || sharedPosition != frameBytes)
            throw new IllegalStateException("Store replay differs from catalog counts for " + own.symbol());
        progress.update(count, frameBytes);
        return new SymbolDay(own.symbol(), locate, arena, frames, offsets, count, weight, lease, budget, own);
    }


    private SymbolDay(
            String symbol, int locate, Arena arena, MemorySegment frames, MemorySegment offsets, int messageCount,
            MaterializationWeight weight, MaterializationBudget.Lease lease, MaterializationBudget budget, PartitionStats partitionStats
    ) {
        this.symbol = symbol;
        this.locate = locate;
        this.arena = arena;
        this.frames = frames;
        this.offsets = offsets;
        this.messageCount = messageCount;
        this.nativeBytes = frames.byteSize() + offsets.byteSize();
        this.weight = weight;
        this.budget = budget;
        this.partitionStats = partitionStats;
        this.heapLeases = new ArrayList<>();
        this.lease = new BatchLease(weight, lease, heapLeases);
        NativeAccounting.opened(nativeBytes);
        this.cleanup = SymbolDayLeakDetector.register(this, symbol, nativeBytes, arena, this.lease);
    }


    //-----------------------------------------------------------------------------------------------------------------
    public String symbol() {
        return symbol;
    }

    public int locate() {
        return locate;
    }

    public State state() {
        return state.get();
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    /** Native bytes the arena holds while open: frames plus the offset index. */
    public long nativeBytes() {
        return nativeBytes;
    }

    public MaterializationWeight weight() {
        return weight;
    }

    public int messageCount() {
        return messageCount;
    }


    public ItchMessage message(int index) {
        return ItchDecoder.view(record(index));
    }

    public ItchRecord record(int index) {
        requireOpen();
        if (index < 0 || index >= messageCount) throw new IndexOutOfBoundsException("Message " + index + " of " + messageCount);
        long position = offsets.getAtIndex(ValueLayout.JAVA_LONG, index);
        long ordinal = frames.get(StoreFormat.ordinalLayout, position);
        int length = Short.toUnsignedInt(frames.get(StoreFormat.lengthLayout, position + StoreFormat.ordinalBytes));
        return new ItchRecord(frames, position + StoreFormat.frameHeaderBytes, length, ordinal, this);
    }

    public PartitionStats partitionStats() { return partitionStats; }

    /** Derived heap data is charged separately and conservatively reserved until the batch closes. */
    public void reserveHeap(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative heap reservation");
        synchronized (heapLeases) {
            requireOpen();
            MaterializationBudget.Lease reservation = budget.tryAcquire(new MaterializationWeight(0, bytes));
            if (reservation == null) throw new IllegalStateException("Insufficient budget for derived data of " + symbol);
            heapLeases.add(reservation);
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * Releases the native storage, then returns the budget permits; idempotent. If the
     * native release fails the permit is kept and the failure thrown, so the budget never reports capacity that
     * was not actually freed.
     */
    @Override
    public void close() {
        if (!state.compareAndSet(State.OPEN, State.CLOSED)) {
            if (state.get() == State.CLOSE_FAILED) {
                retryClose();
            }
            return;
        }
        try {
            arena.close();
        }
        catch (Throwable releaseFailure) {
            state.set(State.CLOSE_FAILED);
            NativeAccounting.releaseFailed();
            throw new IllegalStateException("Native release of SymbolDay " + symbol + " failed; its permit of "
                    + weight.total() + " bytes is retained", releaseFailure);
        }
        NativeAccounting.closed(nativeBytes);
        cleanup.markClosed();
        lease.close();
        cleanup.clean();
    }


    private void retryClose() {
        try {
            arena.close();
        }
        catch (Throwable releaseFailure) {
            throw new IllegalStateException("Native release of SymbolDay " + symbol + " failed again", releaseFailure);
        }
        if (state.compareAndSet(State.CLOSE_FAILED, State.CLOSED)) {
            NativeAccounting.closed(nativeBytes);
            cleanup.markClosed();
            lease.close();
            cleanup.clean();
        }
    }


    private void requireOpen() {
        if (state.get() != State.OPEN) {
            throw new IllegalStateException("SymbolDay " + symbol + " is " + state.get());
        }
    }


    private static final class BatchLease implements MaterializationBudget.Lease {
        private final MaterializationWeight weight;
        private final MaterializationBudget.Lease nativeLease;
        private final List<MaterializationBudget.Lease> heapLeases;

        BatchLease(MaterializationWeight weight, MaterializationBudget.Lease nativeLease,
                List<MaterializationBudget.Lease> heapLeases) {
            this.weight = weight;
            this.nativeLease = nativeLease;
            this.heapLeases = heapLeases;
        }

        @Override public MaterializationWeight weight() { return weight; }
        @Override public void close() {
            try {
                synchronized (heapLeases) {
                    for (MaterializationBudget.Lease reservation : heapLeases) reservation.close();
                    heapLeases.clear();
                }
            }
            finally { nativeLease.close(); }
        }
    }

    /** Test seam: the detached cleanup state, so the abandoned path can be driven deterministically. */
    SymbolDayLeakDetector.CleanupState cleanupState() {
        return cleanup;
    }
}
