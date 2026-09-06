package tech.kzen.sample.plugin.analysis;

import tech.kzen.auto.common.paradigm.job.api.ChannelInput;
import tech.kzen.auto.common.paradigm.job.api.ChannelOutput;
import tech.kzen.auto.common.paradigm.job.control.JobControl;
import tech.kzen.auto.server.objects.job.worker.JavaTransformWorker;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.model.location.ObjectLocation;
import tech.kzen.lib.common.reflect.Reflect;
import tech.kzen.sample.itch.model.OrderLifecycle;

import java.util.Iterator;


/**
 * Materialized symbol-day → its reconstructed {@link tech.kzen.sample.itch.model.OrderLifecycle} records as
 * they are: an object graph (side and state enums, the event list with its executed / cancelled / deleted /
 * replaced variants) for the host's path projection and export, no flattening here. Each record keeps the
 * symbol-day it came from alive until the run is done with it (E9 inheritance).
 */
@Reflect
public final class SymbolDayOrdersWorker extends JavaTransformWorker {
    public SymbolDayOrdersWorker(ChannelInput<?> input, ChannelOutput<DataValue> output, ObjectLocation selfLocation) {
        super(input, output, selfLocation);
    }


    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        return SymbolDayElements.require(element, "Symbol-day orders").orders().iterator();
    }


    /** The record's own shape, so the path picker offers its leaves before any run. */
    @Override
    protected Class<?> outputClass() {
        return OrderLifecycle.class;
    }
}
