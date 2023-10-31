package tech.kzen.sample.plugin.model;


import org.jetbrains.annotations.Nullable;


public class WcpPassthroughEvent {
    public boolean endOfData;

    @Nullable
    public WcpRow rowOrNull;
}
