package tech.kzen.sample.plugin.itch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.auto.common.data.api.DataCursor;
import tech.kzen.auto.common.data.read.ContentCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.auto.plugin.api.data.ReaderByteInput;
import tech.kzen.auto.plugin.api.data.ReaderInspectionRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeResult;
import tech.kzen.auto.plugin.api.data.ReaderProbeStrength;
import tech.kzen.lib.common.exec.ExecutionValue;
import tech.kzen.lib.common.exec.ListExecutionValue;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.exec.TextExecutionValue;
import tech.kzen.lib.common.exec.data.type.DataType;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import tech.kzen.sample.itch.wire.ItchFormatException;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * The ITCH reader against the synthetic day: config round trip and validation, the typed projection of every
 * message (raw and, as the host inflates it, gzip), the symbol / type / time filters, bounded inspection,
 * malformed content failing by name, and detection by content only.
 */
class ItchReaderCapabilityTest {
    private static final long seed = 20260905L;
    private static final int ordersPerSymbol = 20;

    private final ItchReaderCapability reader = new ItchReaderCapability();

    @TempDir
    Path temp;


    @Test
    void configRoundTripsCanonicallyAndValidatesByName() {
        Map<String, ExecutionValue> raw = Map.of(
                ItchReadConfig.symbolsKey, texts("msft", " aapl"),
                ItchReadConfig.messageTypesKey, texts("P", "A"),
                ItchReadConfig.fromKey, new TextExecutionValue("100"),
                ItchReadConfig.toKey, new TextExecutionValue(""));
        ReaderConfig decoded = reader.decode(new MapExecutionValue(raw));
        ItchReadConfig canonical = (ItchReadConfig) reader.canonicalize(decoded);
        assertEquals(List.of("AAPL", "MSFT"), new ArrayList<>(canonical.symbols()));
        assertEquals(List.of('A', 'P'), new ArrayList<>(canonical.messageTypes()));
        assertEquals(100L, canonical.fromNanos());
        assertEquals(ItchReadConfig.noBound, canonical.toNanos());
        assertEquals(canonical, reader.decode(reader.encode(canonical)), "encode → decode is identity");
        assertEquals(ContentCapabilityIdentity.Companion.getSequentialBytes(), reader.requiredContent(canonical));

        IllegalArgumentException inverted = assertThrows(IllegalArgumentException.class, () ->
                reader.validate(new ItchReadConfig(Set.of(), Set.of(), 200L, 100L)));
        assertTrue(inverted.getMessage().contains("precedes"), inverted.getMessage());
        assertThrows(IllegalArgumentException.class, () ->
                reader.decode(new MapExecutionValue(Map.of(ItchReadConfig.messageTypesKey, texts("PX")))));
        assertThrows(IllegalArgumentException.class, () ->
                reader.decode(new MapExecutionValue(Map.of(ItchReadConfig.fromKey, new TextExecutionValue("noon")))));
    }


    @Test
    void projectsEveryMessageTypedRawAndInflated() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, ordersPerSymbol);
        Path raw = temp.resolve("day.itch");
        Path gzip = temp.resolve("day.itch.gz");
        day.writeTo(raw, false);
        day.writeTo(gzip, true);

        for (Path path : List.of(raw, gzip)) {
            List<Map<String, Object>> rows = readAll(ItchReadConfig.all, input(path));
            assertEquals(day.messages().size(), rows.size(), path.toString());
            for (int i = 0; i < rows.size(); i++) {
                ItchMessage message = day.messages().get(i);
                Map<String, Object> row = rows.get(i);
                assertEquals((long) i, row.get(ItchRow.ordinal));
                assertEquals(String.valueOf(message.type()), row.get(ItchRow.type));
                assertEquals(message.header().timestampNanos(), row.get(ItchRow.timestampNanos));
            }
            // A trade carries its symbol, price and match number; the symbol of a locate-only event resolves
            // through the directory
            Map<String, Object> trade = rows.stream()
                    .filter(row -> "P".equals(row.get(ItchRow.type))).findFirst().orElseThrow();
            assertTrue(trade.get(ItchRow.price) instanceof Double);
            assertTrue(trade.get(ItchRow.matchNumber) instanceof Long);
            Map<String, Object> executed = rows.stream()
                    .filter(row -> "E".equals(row.get(ItchRow.type))).findFirst().orElseThrow();
            assertTrue(SyntheticItchDay.aapl.equals(executed.get(ItchRow.stock)) ||
                    SyntheticItchDay.msft.equals(executed.get(ItchRow.stock)) ||
                    SyntheticItchDay.goog.equals(executed.get(ItchRow.stock)) ||
                    SyntheticItchDay.quiet.equals(executed.get(ItchRow.stock)), executed.toString());
            assertNull(executed.get(ItchRow.side), "an execution carries no side");
        }
    }


    @Test
    void filtersBySymbolTypeAndTimeWindow() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, ordersPerSymbol);
        Path raw = temp.resolve("filtered.itch");
        day.writeTo(raw, false);

        List<Map<String, Object>> trades = readAll(
                new ItchReadConfig(Set.of(), Set.of('P', 'E', 'C', 'Q'), ItchReadConfig.noBound, ItchReadConfig.noBound),
                input(raw));
        assertFalse(trades.isEmpty());
        assertTrue(trades.stream().allMatch(row -> Set.of("P", "E", "C", "Q").contains((String) row.get(ItchRow.type))));

        List<Map<String, Object>> aapl = readAll(
                new ItchReadConfig(Set.of(SyntheticItchDay.aapl), Set.of('A', 'F'), ItchReadConfig.noBound, ItchReadConfig.noBound),
                input(raw));
        assertFalse(aapl.isEmpty());
        assertTrue(aapl.stream().allMatch(row -> SyntheticItchDay.aapl.equals(row.get(ItchRow.stock))));
        long expectedAdds = day.messages().stream()
                .filter(m -> m instanceof ItchMessage.AddOrder add && SyntheticItchDay.aapl.equals(add.stock()))
                .count();
        assertEquals(expectedAdds, aapl.size());

        long midpoint = day.messages().get(day.messages().size() / 2).header().timestampNanos();
        List<Map<String, Object>> late = readAll(
                new ItchReadConfig(Set.of(), Set.of(), midpoint, ItchReadConfig.noBound), input(raw));
        assertTrue(late.stream().allMatch(row -> (Long) row.get(ItchRow.timestampNanos) >= midpoint));
        long expectedLate = day.messages().stream().filter(m -> m.header().timestampNanos() >= midpoint).count();
        assertEquals(expectedLate, late.size());
    }


    @Test
    void inspectionIsBoundedAndMalformedContentFailsByName() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 5);
        Path raw = temp.resolve("inspect.itch");
        day.writeTo(raw, false);
        var shape = reader.inspectBlocking(new ReaderInspectionRequest(open(ItchReadConfig.all, input(raw)), 3));
        assertEquals(ItchRow.contract(), shape.getItemType());
        assertEquals(ItchRow.columns, ((DataType.Record) shape.getItemType().getStructural()).getFields()
                .stream().map(f -> f.getId().getName()).toList());

        byte[] bytes = Files.readAllBytes(raw);
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 3);
        ItchFormatException failure = assertThrows(ItchFormatException.class, () ->
                readAll(ItchReadConfig.all, input(truncated)));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());

        byte[] garbage = "Country,City,AccentCity\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(ItchFormatException.class, () -> readAll(ItchReadConfig.all, input(garbage)));
    }


    @Test
    void probeMatchesByFramingOnlyAndNamesAFalseExtension() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 5);
        Path raw = temp.resolve("probe.itch");
        day.writeTo(raw, false);
        byte[] bytes = Files.readAllBytes(raw);
        byte[] sample = Arrays.copyOf(bytes, Math.min(bytes.length, 4096));

        ReaderProbeResult matched = reader.probeBlocking(probe(ItchReadConfig.all, sample, false, false));
        ReaderProbeResult.Matched match = assertInstanceOf(ReaderProbeResult.Matched.class, matched);
        assertEquals(ReaderProbeStrength.ContentStrong, match.getStrength());
        assertTrue(match.getEvidence().contains("frames decoded"), match.getEvidence());

        byte[] text = "Country,City,AccentCity,Region,Population,Latitude,Longitude\nad,aixas,Aixàs,06,,42.48,1.46\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(ReaderProbeResult.NoMatch.INSTANCE, reader.probeBlocking(probe(ItchReadConfig.all, text, true, false)),
                "text content never matches, whatever the name");
        ReaderProbeResult rejected = reader.probeBlocking(probe(ItchReadConfig.all, text, true, true));
        assertInstanceOf(ReaderProbeResult.Rejected.class, rejected);
        assertTrue(((ReaderProbeResult.Rejected) rejected).getReason().contains("does not frame"));
    }


    //-----------------------------------------------------------------------------------------------------------------
    private List<Map<String, Object>> readAll(ItchReadConfig config, ReaderByteInput input) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (DataCursor cursor = reader.openBlocking(open(config, input))) {
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


    private static ListExecutionValue texts(String... values) {
        List<ExecutionValue> items = new ArrayList<>();
        for (String value : values) {
            items.add(new TextExecutionValue(value));
        }
        return new ListExecutionValue(items);
    }
}
