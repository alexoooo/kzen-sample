package tech.kzen.sample.plugin.pipeline;


import org.jetbrains.annotations.NotNull;
import tech.kzen.auto.plugin.api.ReportTerminalStep;
import tech.kzen.auto.plugin.api.managed.PipelineOutput;
import tech.kzen.sample.plugin.model.WcpPassthroughEvent;
import tech.kzen.sample.plugin.model.WcpPipelineEvent;
import tech.kzen.sample.plugin.model.WcpRow;

import java.util.concurrent.CountDownLatch;


public class WcpPassthrough
        implements ReportTerminalStep<WcpPipelineEvent, WcpPassthroughEvent>
{
    //-----------------------------------------------------------------------------------------------------------------
    private final CountDownLatch endOfData = new CountDownLatch(1);


    //-----------------------------------------------------------------------------------------------------------------
    @Override
    public void process(
            WcpPipelineEvent model,
            @NotNull PipelineOutput<WcpPassthroughEvent> output
    ) {
        if (model.getEndOfData()) {
            WcpPassthroughEvent nextEvent = output.next();
            nextEvent.endOfData = true;
            nextEvent.rowOrNull = null;
            output.commit();

            endOfData.countDown();
            return;
        }

        WcpRow rowOrNull = model.rowOrNull;
        if (rowOrNull == null) {
            return;
        }

        WcpPassthroughEvent nextEvent = output.next();
        nextEvent.endOfData = false;
        nextEvent.rowOrNull = rowOrNull;
        output.commit();
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
