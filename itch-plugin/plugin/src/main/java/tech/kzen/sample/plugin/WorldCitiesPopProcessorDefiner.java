package tech.kzen.sample.plugin;

import org.jetbrains.annotations.NotNull;
import tech.kzen.auto.plugin.api.HeaderExtractor;
import tech.kzen.auto.plugin.definition.*;
import tech.kzen.auto.plugin.model.ModelOutputEvent;
import tech.kzen.auto.plugin.model.PluginCoordinate;
import tech.kzen.auto.plugin.spec.DataEncodingSpec;
import tech.kzen.auto.plugin.spec.TextEncodingSpec;
import tech.kzen.sample.plugin.model.WcpPassthroughEvent;
import tech.kzen.sample.plugin.model.WcpPipelineEvent;
import tech.kzen.sample.plugin.model.WcpRow;
import tech.kzen.sample.plugin.pipeline.WcpFlatten;
import tech.kzen.sample.plugin.pipeline.WcpFramer;
import tech.kzen.sample.plugin.pipeline.WcpParser;
import tech.kzen.sample.plugin.pipeline.WcpPassthrough;

import java.nio.charset.StandardCharsets;
import java.util.List;


// https://github.com/CODAIT/redrock/blob/master/twitter-decahose/src/main/resources/Location/worldcitiespop.txt.gz
@SuppressWarnings("unused")
public class WorldCitiesPopProcessorDefiner
        implements ReportDefiner<WcpRow>
{
    //-----------------------------------------------------------------------------------------------------------------
    private static final DataEncodingSpec dataEncoding = new DataEncodingSpec(
            new TextEncodingSpec(StandardCharsets.ISO_8859_1));

    private static final ReportDefinitionInfo info = new ReportDefinitionInfo(
            new PluginCoordinate("World Cities Population (worldcitiespop)"),
            List.of("csv"),
            dataEncoding,
            ReportDefinitionInfo.priorityAvoid);

    private static final HeaderExtractor<WcpRow> headerExtractor = HeaderExtractor.Companion.ofLiteral(
            WcpRow.header.toArray(String[]::new));


    private static final int inputRingBufferSize = 32 * 1024;
    private static final int handoffRingBufferSize = 16 * 1024;


    //-----------------------------------------------------------------------------------------------------------------
    @NotNull
    @Override
    public ReportDefinitionInfo info() {
        return info;
    }


    //-----------------------------------------------------------------------------------------------------------------
    @NotNull
    @Override
    public ReportDefinition<WcpRow> define() {
        return new ReportDefinition<>(
                defineData(),
                () -> headerExtractor);
    }


    private ReportDataDefinition<WcpRow> defineData() {
        return new ReportDataDefinition<>(
                WcpFramer::new,
                WcpRow.class,
                List.of(
                        defineInputSegment(),
                        defineHandoffSegment()));
    }


    private ReportSegmentDefinition<WcpPipelineEvent, WcpPassthroughEvent> defineInputSegment() {
        return new ReportSegmentDefinition<>(
                WcpPipelineEvent::new,
                WcpPassthroughEvent.class,
                List.of(
                        new ReportSegmentStepDefinition<>(List.of(
                                WcpParser::new))
                ),
                WcpPassthrough::new,
                inputRingBufferSize);
    }


    private ReportSegmentDefinition<WcpPassthroughEvent, ModelOutputEvent<WcpRow>> defineHandoffSegment() {
        return new ReportSegmentDefinition<>(
                WcpPassthroughEvent::new,
                WcpRow.class,
                List.of(),
                WcpFlatten::new,
                inputRingBufferSize);
    }
}
