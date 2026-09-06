package tech.kzen.sample.plugin.analysis;

import tech.kzen.auto.common.paradigm.job.api.ChannelInput;
import tech.kzen.auto.common.paradigm.job.api.ChannelOutput;
import tech.kzen.auto.common.paradigm.job.control.JobControl;
import tech.kzen.auto.server.objects.job.worker.JavaTransformWorker;
import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.model.location.ObjectLocation;
import tech.kzen.lib.common.reflect.Reflect;

import java.util.Iterator;


/**
 * Materialized symbol-day → {@link BookSnapshotRows} at a cadence: `intervalMillis` between samples, `levels`
 * of depth per side. Both are ordinary notation attributes bound by constructor parameter name.
 */
@Reflect
public final class SymbolDayBookSnapshotWorker extends JavaTransformWorker {
    private final long intervalNanos;
    private final int levels;


    public SymbolDayBookSnapshotWorker(
            ChannelInput<?> input,
            ChannelOutput<DataValue> output,
            int intervalMillis,
            int levels,
            ObjectLocation selfLocation
    ) {
        super(input, output, selfLocation);
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("Book sampling interval must be positive: " + intervalMillis + " ms");
        }
        if (levels <= 0) {
            throw new IllegalArgumentException("Book depth levels must be positive: " + levels);
        }
        this.intervalNanos = intervalMillis * 1_000_000L;
        this.levels = levels;
    }


    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        return BookSnapshotRows.rows(
                SymbolDayElements.require(element, "Symbol-day book snapshot"), intervalNanos, levels).iterator();
    }


    @Override
    protected DataContract outputContract() {
        return BookSnapshotRows.contract;
    }


    /** Rows of scalars read off the day: copies, so a Sort downstream keeps rows, never the symbol-day's lease. */
    @Override
    protected boolean independentOutputs() {
        return true;
    }
}
