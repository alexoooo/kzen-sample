package tech.kzen.sample.plugin.analysis;

import tech.kzen.auto.common.paradigm.job.api.ChannelInput;
import tech.kzen.auto.common.paradigm.job.api.ChannelOutput;
import tech.kzen.auto.common.paradigm.job.control.JobControl;
import tech.kzen.auto.server.objects.job.worker.JavaTransformWorker;
import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.model.location.ObjectLocation;
import tech.kzen.lib.common.reflect.Reflect;
import tech.kzen.sample.itch.analysis.TradeVolumeFold;
import tech.kzen.sample.itch.message.ItchMessage;

import java.util.Iterator;


/**
 * The raw-ingestion route's fold: consumes decoded {@link ItchMessage}s (an expression source over the core's
 * {@code ItchReader}) through the core's {@link TradeVolumeFold} and emits one {@link TradeVolumeRows} row per
 * symbol once the stream ends. Stateful across the day, thin over the core, no coroutine in sight.
 */
@Reflect
public final class ItchTradeVolumeWorker extends JavaTransformWorker {
    private final TradeVolumeFold fold = new TradeVolumeFold();


    public ItchTradeVolumeWorker(ChannelInput<?> input, ChannelOutput<DataValue> output, ObjectLocation selfLocation) {
        super(input, output, selfLocation);
    }


    @Override
    protected Iterator<?> onElementBlocking(Object element, JobControl control) {
        if (!(element instanceof ItchMessage message)) {
            throw new IllegalArgumentException("ITCH trade volume expects decoded ItchMessage elements, not "
                    + (element == null ? "null" : element.getClass().getName()));
        }
        fold.observe(message);
        return null;
    }


    @Override
    protected Iterator<?> onCompleteBlocking(JobControl control) {
        return TradeVolumeRows.rows(fold.summaries()).iterator();
    }


    @Override
    protected DataContract outputContract() {
        return TradeVolumeRows.contract;
    }
}
