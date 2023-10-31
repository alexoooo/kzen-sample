package tech.kzen.sample.plugin.model;

import org.jetbrains.annotations.Nullable;
import tech.kzen.auto.plugin.model.DataInputEvent;


public class WcpPipelineEvent
        extends DataInputEvent
{
    @Nullable
    public WcpRow rowOrNull;


    @Override
    public String toString() {
        return "[row: " + rowOrNull + "]";
    }
}
