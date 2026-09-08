package tech.kzen.sample.plugin.analysis.dated;

import tech.kzen.auto.common.paradigm.job.api.ChannelInput;
import tech.kzen.auto.common.paradigm.job.api.ChannelOutput;
import tech.kzen.auto.common.paradigm.job.control.JobControl;
import tech.kzen.auto.server.objects.job.worker.JavaTransformWorker;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.model.location.ObjectLocation;
import tech.kzen.lib.common.reflect.Reflect;
import java.util.Iterator;

@Reflect
public final class DatedTradeVolumeWorker extends JavaTransformWorker {
    public DatedTradeVolumeWorker(ChannelInput<?> input, ChannelOutput<DataValue> output, ObjectLocation selfLocation) {
        super(input, output, selfLocation);
    }
    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        var dated = DatedElements.require(element);
        long[] tally = dated.day().graph().standingTradeEventsAndShares();
        return java.util.List.of(new DatedTradeVolume(dated.date(), dated.symbol(), tally[0], tally[1])).iterator();
    }
    @Override
    protected Class<?> outputClass() { return DatedTradeVolume.class; }
    @Override
    protected boolean independentOutputs() { return true; }
}
