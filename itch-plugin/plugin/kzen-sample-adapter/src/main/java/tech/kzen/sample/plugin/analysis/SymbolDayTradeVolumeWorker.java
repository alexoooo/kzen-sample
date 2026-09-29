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
import java.util.List;


/**
 * The store-backed route's trade volume: one {@link TradeVolumeRows} row per materialized symbol-day, read off
 * its graph (standing printed trades) — the same named metric the raw fold computes, from a different
 * representation that shares nothing with it.
 */
@Reflect
public final class SymbolDayTradeVolumeWorker extends JavaTransformWorker {
    public SymbolDayTradeVolumeWorker(ChannelInput<?> input, ChannelOutput<DataValue> output, ObjectLocation selfLocation) {
        super(input, output, selfLocation);
    }


    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        return List.of(TradeVolumeRows.row(SymbolDayElements.require(element, "Symbol-day trade volume"))).iterator();
    }


    @Override
    protected DataContract outputContract() {
        return TradeVolumeRows.contract;
    }


    /** Rows of scalars read off the day: copies, so a Sort downstream keeps rows, never the symbol-day's lease. */
    @Override
    protected boolean independentOutputs() {
        return true;
    }
}
