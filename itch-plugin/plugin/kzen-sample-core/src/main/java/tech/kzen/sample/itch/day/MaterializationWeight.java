package tech.kzen.sample.itch.day;

import tech.kzen.sample.itch.store.PartitionStats;
import tech.kzen.sample.itch.store.StoreFormat;


/**
 * What materializing one symbol-day costs, in two parts the host's budget may weigh differently: the native
 * bytes the arena will hold (exact: the partition frames, the shared market-wide frames and the offset index,
 * rounded up to {@link #nativeAlignment}), and an estimate of the peak heap the persistent graph, indexes and
 * reconstruction temporaries reach (linear in the partition's message-family counts). The default coefficients
 * are the ones HS06 measured on a real day ({@link Coefficients#measured}); the placeholders that preceded them
 * stay as {@link Coefficients#initial} for the estimate-versus-observed record.
 */
public record MaterializationWeight(
        long nativeBytes,
        long estimatedHeapBytes
) {
    public static final long nativeAlignment = 4096;
    public static final int offsetIndexBytesPerMessage = Long.BYTES;

    public long total() {
        return nativeBytes + estimatedHeapBytes;
    }


    public static MaterializationWeight of(PartitionStats own, PartitionStats shared, Coefficients coefficients) {
        long messages = own.messages() + (shared == null ? 0 : shared.messages());
        long frameBytes = own.bytes() + (shared == null ? 0 : shared.bytes());
        long nativeBytes = alignUp(frameBytes) + alignUp(messages * offsetIndexBytesPerMessage);
        long heap = coefficients.perMessage() * messages
                + coefficients.perOrder() * own.adds()
                + coefficients.perOrderEvent() * (own.executions() + own.cancels() + own.deletes() + own.replaces())
                + coefficients.perBookState() * (own.adds() + own.executions() + own.cancels() + own.deletes()
                        + own.replaces())
                + coefficients.perTrade() * (own.executions() + own.trades() + own.crosses())
                + coefficients.fixed();
        return new MaterializationWeight(nativeBytes, heap);
    }


    private static long alignUp(long bytes) {
        return (bytes + nativeAlignment - 1) / nativeAlignment * nativeAlignment;
    }


    /**
     * Heap cost model, bytes per unit. [perBookState] covers the persistent-map path copied per book update
     * (O(log n) nodes) plus the snapshot record; [perOrder] the lifecycle record and index entries;
     * [perOrderEvent] the event record plus the copied event list; [perTrade] the trade record.
     */
    public record Coefficients(
            long perMessage,
            long perOrder,
            long perOrderEvent,
            long perBookState,
            long perTrade,
            long fixed
    ) {
        /** The pre-measurement placeholders (HS05), kept for the estimate-versus-observed record. */
        public static final Coefficients initial = new Coefficients(16, 200, 120, 400, 96, 1 << 16);

        /**
         * HS06 (2026-09-05, Nasdaq 2019-12-30, JDK 25, G1): retained heap after materialization was 0.89–1.09×
         * {@link #initial} on the three largest symbol-days (QQQ, SPY, IWM) and 1.45× on the median one. The
         * dominant term is the persistent book path copy, whose depth grows with the peak live order count
         * (8 847 for QQQ, 434 for IWM); [perBookState] is raised so the estimate covers the deepest observed
         * book (QQQ: estimate 1 443 MiB against 1 461 MiB retained) and errs high for shallower ones.
         * Reconstruction temporaries peaked at 1.3–1.4× the retained graph on top of this; a host that must
         * never exceed a fixed heap should hold that {@link #transientHeadroom} beyond the sum it admits.
         */
        public static final Coefficients measured = new Coefficients(16, 200, 120, 460, 96, 1 << 16);

        /** Peak-to-retained heap ratio observed while materializing the largest days (HS06). */
        public static final double transientHeadroom = 1.4;
    }
}
