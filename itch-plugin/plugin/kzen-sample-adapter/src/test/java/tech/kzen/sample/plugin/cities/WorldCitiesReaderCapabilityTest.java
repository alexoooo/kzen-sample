package tech.kzen.sample.plugin.cities;

import org.junit.jupiter.api.Test;
import tech.kzen.auto.common.data.api.DataCursor;
import tech.kzen.auto.common.data.read.ContentCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.auto.plugin.api.data.ReaderByteInput;
import tech.kzen.auto.plugin.api.data.ReaderInspectionRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeResult;
import tech.kzen.auto.plugin.api.data.ReaderProbeStrength;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.exec.data.type.DataType;
import tech.kzen.sample.plugin.model.WcpRow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.columns;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.input;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.open;
import static tech.kzen.sample.plugin.support.ReaderTestSupport.probe;


/**
 * The simple sample: a hand-written excerpt in the world-cities layout (no real data enters the repository)
 * read through the reader contract — header skipped, empty numbers null, ISO-8859-1 accents kept, bounded
 * inspection, content-based probe.
 */
class WorldCitiesReaderCapabilityTest {
    private static final String excerpt = String.join("\n",
            WcpRow.headerRow,
            "ad,andorra la vella,Andorra la Vella,07,20430,42.5,1.5166667",
            "ad,canillo,Canillo,02,,42.5666667,1.6",
            "ca,montréal,Montréal,10,3268513,45.5,-73.5833333",
            "",
            "zw,harare,Harare,04,2213701,-17.8177778,31.0447222") + "\n";

    private final WorldCitiesReaderCapability reader = new WorldCitiesReaderCapability();


    @Test
    void configIsEmptyAndContentSequential() {
        ReaderConfig config = reader.decode(new MapExecutionValue(Map.of()));
        reader.validate(config);
        assertEquals(config, reader.canonicalize(config));
        assertEquals(new MapExecutionValue(Map.of()), reader.encode(config));
        assertEquals(ContentCapabilityIdentity.Companion.getSequentialBytes(), reader.requiredContent(config));
        assertEquals("tech.kzen.sample", reader.getIdentity().getNamespace());
        assertEquals(reader.getIdentity().getCompatibility(), reader.getReaderCompatibility());
    }


    @Test
    void readsTypedRowsSkippingHeaderAndBlankLines() {
        List<Map<String, Object>> rows = readAll(excerpt);
        assertEquals(4, rows.size());
        assertEquals(WcpRow.header, new ArrayList<>(rows.getFirst().keySet()));

        Map<String, Object> first = rows.getFirst();
        assertEquals("ad", first.get("Country"));
        assertEquals("Andorra la Vella", first.get("AccentCity"));
        assertEquals(20430L, first.get("Population"));
        assertEquals(42.5, first.get("Latitude"));
        assertEquals(1.5166667, first.get("Longitude"));

        assertNull(rows.get(1).get("Population"), "empty population is null, not zero");
        assertEquals("Montréal", rows.get(2).get("AccentCity"), "ISO-8859-1 accents survive");
        assertEquals(-17.8177778, rows.get(3).get("Latitude"));
    }


    @Test
    void malformedRowFailsByName() {
        String shortRow = WcpRow.headerRow + "\nad,andorra la vella,Andorra la Vella\n";
        IllegalArgumentException few = assertThrows(IllegalArgumentException.class, () -> readAll(shortRow));
        assertTrue(few.getMessage().contains("fewer than 7 columns"), few.getMessage());

        String badNumber = WcpRow.headerRow + "\nad,canillo,Canillo,02,many,42.5,1.6\n";
        IllegalArgumentException number = assertThrows(IllegalArgumentException.class, () -> readAll(badNumber));
        assertTrue(number.getMessage().contains("malformed number"), number.getMessage());
    }


    @Test
    void inspectIsBoundedAndDeclaresStaticColumns() {
        var shape = reader.inspectBlocking(new ReaderInspectionRequest(open(config(), bytes(excerpt)), 2));
        assertEquals(WcpRow.contract(), shape.getItemType());
        assertEquals(WcpRow.header, ((DataType.Record) shape.getItemType().getStructural()).getFields()
                .stream().map(field -> field.getId().getName()).toList());
        assertEquals(shape, new WorldCitiesFormat("t", true).declaredShape());
    }


    @Test
    void probeMatchesOnHeaderRowOnly() {
        ReaderProbeResult matched = reader.probeBlocking(probe(config(), excerptBytes(), false, false));
        assertInstanceOf(ReaderProbeResult.Matched.class, matched);
        assertEquals(ReaderProbeStrength.ContentSignature, ((ReaderProbeResult.Matched) matched).getStrength(),
                "the header row is the file's signature, above a generic delimited guess");

        byte[] csvAlike = "Country,City\nad,andorra\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(ReaderProbeResult.NoMatch.INSTANCE, reader.probeBlocking(probe(config(), csvAlike, true, false)),
                "a different header is some other format, whatever the extension");

        byte[] truncatedHeader = Arrays.copyOf(excerptBytes(), 10);
        assertEquals(ReaderProbeResult.NoMatch.INSTANCE, reader.probeBlocking(probe(config(), truncatedHeader, false, false)));
        assertFalse(new WorldCitiesFormat("t", true).getExtensions().contains("txt"),
                "no extension hint: plain .txt must stay text for everything else");
    }


    //-----------------------------------------------------------------------------------------------------------------
    private List<Map<String, Object>> readAll(String text) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (DataCursor cursor = reader.openBlocking(open(config(), bytes(text)))) {
            while (cursor.hasNext()) {
                rows.add(columns(cursor.next()));
            }
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }


    private ReaderConfig config() {
        return reader.decode(new MapExecutionValue(Map.of()));
    }


    private static ReaderByteInput bytes(String text) {
        return input(text.getBytes(StandardCharsets.ISO_8859_1));
    }


    private static byte[] excerptBytes() {
        return excerpt.getBytes(StandardCharsets.ISO_8859_1);
    }
}
