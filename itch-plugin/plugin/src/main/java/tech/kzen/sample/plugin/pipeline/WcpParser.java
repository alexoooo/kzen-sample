package tech.kzen.sample.plugin.pipeline;


import com.google.common.base.Splitter;
import tech.kzen.auto.plugin.api.ReportIntermediateStep;
import tech.kzen.auto.plugin.model.data.DataRecordBuffer;
import tech.kzen.sample.plugin.model.WcpPipelineEvent;
import tech.kzen.sample.plugin.model.WcpRow;

import java.util.Iterator;


public class WcpParser
        implements ReportIntermediateStep<WcpPipelineEvent>
{
    //-----------------------------------------------------------------------------------------------------------------
    private static final Splitter commaSplitter = Splitter.on(',');


    //-----------------------------------------------------------------------------------------------------------------
    private final StringBuilder buffer = new StringBuilder();


    //-----------------------------------------------------------------------------------------------------------------
    @Override
    public void process(WcpPipelineEvent model, long index) {
        if (model.getEndOfData()) {
            return;
        }

        DataRecordBuffer data = model.getData();

        char[] chars = data.chars;
        int length = data.charsLength;

        if (WcpRow.isHeaderRow(chars, length)) {
            model.rowOrNull = null;
            return;
        }

        buffer.setLength(0);
        buffer.append(chars, 0, length);

        Iterator<String> values = commaSplitter.split(buffer).iterator();

        String country = values.next();
        String city = values.next();
        String accentCity = values.next();
        String region = values.next();

        String populationText = values.next();
        Integer populationOrNull = populationText.isEmpty() ? null : Integer.parseInt(populationText);

        String latitudeText = values.next();
        Double latitudeOrNull = latitudeText.isEmpty() ? null : Double.parseDouble(latitudeText);

        String longitudeText = values.next();
        Double longitudeOrNull = longitudeText.isEmpty() ? null : Double.parseDouble(longitudeText);

        model.rowOrNull = new WcpRow(
                country, city, accentCity, region, populationOrNull, latitudeOrNull, longitudeOrNull);
    }
}
