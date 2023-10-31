package tech.kzen.sample.plugin.pipeline;


import org.jetbrains.annotations.NotNull;
import tech.kzen.auto.plugin.api.ReportTerminalStep;
import tech.kzen.auto.plugin.api.managed.PipelineOutput;
import tech.kzen.auto.plugin.model.ModelOutputEvent;
import tech.kzen.auto.plugin.model.record.FlatFileRecord;
import tech.kzen.sample.plugin.model.WcpPassthroughEvent;
import tech.kzen.sample.plugin.model.WcpRow;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;


public class WcpFlatten
        implements ReportTerminalStep<WcpPassthroughEvent, ModelOutputEvent<WcpRow>>
{
    //-----------------------------------------------------------------------------------------------------------------
    private final CountDownLatch endOfData = new CountDownLatch(1);


    //-----------------------------------------------------------------------------------------------------------------
    @Override
    public void process(
            WcpPassthroughEvent model,
            @NotNull PipelineOutput<ModelOutputEvent<WcpRow>> output
    ) {
        if (model.endOfData) {
            endOfData.countDown();
            return;
        }

        WcpRow rowOrNull = model.rowOrNull;
        Objects.requireNonNull(rowOrNull);

        ModelOutputEvent<WcpRow> nextEvent = output.next();

        nextEvent.setModel(rowOrNull);

        FlatFileRecord flatBuilder = nextEvent.getRow();
        rowOrNull.flatten(flatBuilder);

        output.commit();
        model.rowOrNull = null;
    }


    @Override
    public void awaitEndOfData() {
        try {
            endOfData.await();
        }
        catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
