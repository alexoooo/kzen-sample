package tech.kzen.sample.itch.day;

import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.model.BookSnapshot;
import tech.kzen.sample.itch.model.OrderLifecycle;
import tech.kzen.sample.itch.model.SymbolDayGraph;
import tech.kzen.sample.itch.model.TradeEvent;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.PartitionStats;
import tech.kzen.sample.itch.store.StoreFormat;
import tech.kzen.sample.itch.wire.ItchCursor;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.wire.ItchEncoder;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


/**
 * One fully materialized symbol-day: the symbol's messages (with the shared market-wide ones merged in) held
 * off-heap in a shared arena, and the persistent analytical graph ({@link SymbolDayGraph}) on the heap. It is
 * the closeable analytical unit: {@link #materialize} acquires the budget lease for its weight before allocating,
 * and {@link #close} drops the graph roots, releases the native storage and only then returns the permit.
 *
 * Lifetime contract: any thread may read while open; a read after close is a named failure; the heap graph a
 * caller still references stays usable after close (close promises native release, not heap reclamation);
 * views that read the arena ({@link #message}) hold this owner. A native release failure keeps the permit —
 * the budget then still reflects the storage that was not freed — and is reported by the exception and in
 * {@link NativeAccounting}. Abandoning a day without close is diagnosed by {@link SymbolDayLeakDetector}.
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
    private volatile SymbolDayGraph graph;


    //-----------------------------------------------------------------------------------------------------------------
    /** Materializes [locate] of [store] under the unlimited budget. */
    public static SymbolDay materialize(ItchStore store, int locate) {
        try {
            return materialize(store, locate, MaterializationBudget.unlimited(), MaterializationWeight.Coefficients.measured);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted under the unlimited budget", e);
        }
    }


    /**
     * Acquires a lease for the day's weight (blocking on [budget]), then loads and builds. Any failure or
     * interruption after acquisition releases the native storage and the lease before propagating; an
     * oversized day fails before waiting.
     */
    public static SymbolDay materialize(
            ItchStore store, int locate, MaterializationBudget budget, MaterializationWeight.Coefficients coefficients
    ) throws InterruptedException {
        PartitionStats own = store.stats(locate);
        PartitionStats shared = store.partitions().get(ItchHeader.marketWideLocate);
        MaterializationWeight weight = MaterializationWeight.of(own, shared, coefficients);
        if (!budget.canEverAdmit(weight)) {
            throw new IllegalArgumentException("Symbol-day " + own.symbol() + " (locate " + locate + ") weighs "
                    + weight.total() + " bytes, more than the budget can ever admit");
        }
        MaterializationBudget.Lease lease = budget.acquire(weight);
        Arena arena = Arena.ofShared();
        try {
            return load(store, locate, own, shared, weight, lease, arena);
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
            MaterializationBudget.Lease lease, Arena arena
    ) throws InterruptedException {
        long frameBytes = own.bytes() + (shared == null ? 0 : shared.bytes());
        long messages = own.messages() + (shared == null ? 0 : shared.messages());
        MemorySegment frames = arena.allocate(frameBytes, MaterializationWeight.nativeAlignment);
        MemorySegment offsets = arena.allocate(messages * MaterializationWeight.offsetIndexBytesPerMessage,
                MaterializationWeight.nativeAlignment);
        SymbolDayGraph.Builder builder = SymbolDayGraph.builder();

        long position = 0;
        int index = 0;
        try (ItchCursor cursor = store.replay(locate)) {
            while (cursor.hasNext()) {
                ItchMessage message = cursor.next();
                if (index % interruptCheckInterval == 0 && Thread.interrupted()) {
                    throw new InterruptedException("Materialization of " + own.symbol() + " interrupted at message " + index);
                }
                if (index >= messages) {
                    throw new IllegalStateException("Store " + store.root() + " replayed more messages for locate "
                            + locate + " than its catalog counts (" + messages + ")");
                }
                byte[] bytes = ItchEncoder.encode(message);
                long frameLength = StoreFormat.frameHeaderBytes + bytes.length;
                if (position + frameLength > frameBytes) {
                    throw new IllegalStateException("Store " + store.root() + " partition " + locate
                            + " exceeds its catalogued byte size " + frameBytes);
                }
                offsets.setAtIndex(ValueLayout.JAVA_LONG, index, position);
                frames.set(ValueLayout.JAVA_LONG_UNALIGNED, position, message.header().ordinal());
                frames.set(ValueLayout.JAVA_SHORT_UNALIGNED, position + StoreFormat.ordinalBytes, (short) bytes.length);
                MemorySegment.copy(bytes, 0, frames, ValueLayout.JAVA_BYTE, position + StoreFormat.frameHeaderBytes,
                        bytes.length);
                position += frameLength;
                index++;
                builder.observe(message);
            }
        }
        if (index != messages) {
            throw new IllegalStateException("Store " + store.root() + " replayed " + index + " messages for locate "
                    + locate + ", catalog counts " + messages);
        }
        return new SymbolDay(own.symbol(), locate, arena, frames, offsets, index, weight, lease, builder.build());
    }


    private SymbolDay(
            String symbol, int locate, Arena arena, MemorySegment frames, MemorySegment offsets, int messageCount,
            MaterializationWeight weight, MaterializationBudget.Lease lease, SymbolDayGraph graph
    ) {
        this.symbol = symbol;
        this.locate = locate;
        this.arena = arena;
        this.frames = frames;
        this.offsets = offsets;
        this.messageCount = messageCount;
        this.nativeBytes = frames.byteSize() + offsets.byteSize();
        this.weight = weight;
        this.lease = lease;
        this.graph = graph;
        NativeAccounting.opened(nativeBytes);
        this.cleanup = SymbolDayLeakDetector.register(this, symbol, nativeBytes, arena, lease);
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


    /** Decodes message [index] (feed order, market-wide merged) from the arena; a named failure after close. */
    public ItchMessage message(int index) {
        requireOpen();
        if (index < 0 || index >= messageCount) {
            throw new IndexOutOfBoundsException("Message " + index + " of " + messageCount);
        }
        long position = offsets.getAtIndex(ValueLayout.JAVA_LONG, index);
        long ordinal = frames.get(ValueLayout.JAVA_LONG_UNALIGNED, position);
        int length = Short.toUnsignedInt(frames.get(ValueLayout.JAVA_SHORT_UNALIGNED, position + StoreFormat.ordinalBytes));
        byte[] bytes = new byte[length];
        MemorySegment.copy(frames, ValueLayout.JAVA_BYTE, position + StoreFormat.frameHeaderBytes, bytes, 0, length);
        return ItchDecoder.decode(bytes, 0, length, ordinal);
    }


    /** The persistent analytical graph; a named failure after close (a reference obtained earlier stays valid). */
    public SymbolDayGraph graph() {
        requireOpen();
        return graph;
    }

    public List<BookSnapshot> bookHistory() {
        return graph().bookHistory();
    }

    public List<OrderLifecycle> orders() {
        return graph().orders();
    }

    public List<TradeEvent> trades() {
        return graph().trades();
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * Drops the graph roots, releases the native storage, then returns the budget permit; idempotent. If the
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
        graph = null;
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


    /** Test seam: the detached cleanup state, so the abandoned path can be driven deterministically. */
    SymbolDayLeakDetector.CleanupState cleanupState() {
        return cleanup;
    }
}
