package tech.kzen.sample.plugin.analysis.dated;

import tech.kzen.sample.itch.model.SymbolDayGraph;

import tech.kzen.auto.common.paradigm.job.api.ChannelInput;
import tech.kzen.auto.common.paradigm.job.api.ChannelOutput;
import tech.kzen.auto.common.paradigm.job.control.JobControl;
import tech.kzen.auto.server.objects.job.worker.JavaTransformWorker;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.model.location.ObjectLocation;
import tech.kzen.lib.common.reflect.Reflect;
import java.util.Iterator;

@Reflect
public final class DatedOrdersWorker extends JavaTransformWorker {
    public DatedOrdersWorker(ChannelInput<?> input, ChannelOutput<DataValue> output, ObjectLocation selfLocation) {
        super(input, output, selfLocation);
    }
    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        var dated = DatedElements.require(element);
        return SymbolDayGraph.build(dated.day()).orders().stream().map(order -> new DatedOrder(dated.date(), dated.symbol(), order)).iterator();
    }
    @Override
    protected Class<?> outputClass() { return DatedOrder.class; }
}
