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


/** Materialized symbol-day → one {@link OrderLifecycleRows} row per reconstructed order. */
@Reflect
public final class SymbolDayOrderLifecycleWorker extends JavaTransformWorker {
    public SymbolDayOrderLifecycleWorker(ChannelInput<?> input, ChannelOutput<DataValue> output, ObjectLocation selfLocation) {
        super(input, output, selfLocation);
    }


    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        return OrderLifecycleRows.rows(SymbolDayElements.require(element, "Symbol-day order lifecycle")).iterator();
    }


    @Override
    protected DataContract outputContract() {
        return OrderLifecycleRows.contract;
    }


    /** Rows of scalars read off the day: copies, so a Sort downstream keeps rows, never the symbol-day's lease. */
    @Override
    protected boolean independentOutputs() {
        return true;
    }
}
