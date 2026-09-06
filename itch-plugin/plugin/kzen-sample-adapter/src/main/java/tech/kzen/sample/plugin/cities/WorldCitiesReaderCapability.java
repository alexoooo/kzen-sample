package tech.kzen.sample.plugin.cities;

import tech.kzen.auto.common.data.api.DataCursor;
import tech.kzen.auto.common.data.read.ContentCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.auto.plugin.api.data.BlockingReaderCapability;
import tech.kzen.auto.plugin.api.data.BlockingReaderProbe;
import tech.kzen.auto.plugin.api.data.ReaderByteInput;
import tech.kzen.auto.plugin.api.data.ReaderInspectionRequest;
import tech.kzen.auto.plugin.api.data.ReaderOpenRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeResult;
import tech.kzen.auto.plugin.api.data.ReaderProbeStrength;
import tech.kzen.lib.common.exec.ExecutionValue;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.exec.data.shape.ShapeProvenance;
import tech.kzen.lib.common.exec.data.shape.ShapeStability;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.sample.plugin.io.ReaderByteInputStream;
import tech.kzen.sample.plugin.model.WcpRow;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;


/**
 * The world-cities population file as a plain-Java reader — the simple sample beside ITCH, Job-only (the
 * former Report definer is retired): no configuration, ISO-8859-1 lines, the header row skipped, each data
 * line a typed {@link WcpRow} record. Detection is by content: the sample must begin with the file's exact
 * header row. Compression is the host's content coding.
 */
public final class WorldCitiesReaderCapability extends BlockingReaderCapability implements BlockingReaderProbe {
    public static final ReaderCapabilityIdentity identity =
            new ReaderCapabilityIdentity("tech.kzen.sample", "world-cities", "1");

    private static final ReaderConfig noConfig = new ReaderConfig() {};


    @Override
    public ReaderCapabilityIdentity getIdentity() {
        return identity;
    }


    @Override
    public String getReaderCompatibility() {
        return identity.getCompatibility();
    }


    @Override
    public ReaderConfig decode(ExecutionValue config) {
        return noConfig;
    }


    @Override
    public void validate(ReaderConfig config) {}


    @Override
    public ReaderConfig canonicalize(ReaderConfig config) {
        return noConfig;
    }


    @Override
    public ExecutionValue encode(ReaderConfig config) {
        return new MapExecutionValue(Map.of());
    }


    @Override
    public ContentCapabilityIdentity requiredContent(ReaderConfig config) {
        return ContentCapabilityIdentity.Companion.getSequentialBytes();
    }


    public static DataShape shape() {
        return new DataShape(WcpRow.contract(), ShapeProvenance.Declared, ShapeStability.Stable.INSTANCE, List.of());
    }


    @Override
    public DataCursor openBlocking(ReaderOpenRequest request) {
        return new Cursor(request.getBytes(), Long.MAX_VALUE);
    }


    @Override
    public DataShape inspectBlocking(ReaderInspectionRequest request) {
        try (Cursor cursor = new Cursor(request.getOpen().getBytes(), request.getMaximumRecords())) {
            while (cursor.hasNext()) {
                cursor.next();
            }
            return cursor.getShape();
        }
    }


    /**
     * The exact header row is the file's signature (it outranks the built-in delimited reader's structural
     * match on the same comma-separated text); a mere `.txt` name never matches.
     */
    @Override
    public ReaderProbeResult probeBlocking(ReaderProbeRequest request) {
        String head = new String(request.getSample().toByteArray(), StandardCharsets.ISO_8859_1);
        int lineEnd = head.indexOf('\n');
        String firstLine = (lineEnd < 0 ? head : head.substring(0, lineEnd)).stripTrailing();
        if (WcpRow.isHeaderRow(firstLine)) {
            request.getObserver().completeLogicalRecordsConsidered(1);
            return new ReaderProbeResult.Matched(
                    ReaderProbeStrength.ContentSignature, noConfig, "world-cities header row present");
        }
        return ReaderProbeResult.NoMatch.INSTANCE;
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static final class Cursor implements DataCursor {
        private final BufferedReader lines;
        private final long recordLimit;
        private String pending;
        private long delivered;
        private boolean headerSeen;
        private boolean closed;

        Cursor(ReaderByteInput bytes, long recordLimit) {
            this.lines = new BufferedReader(new InputStreamReader(new ReaderByteInputStream(bytes), StandardCharsets.ISO_8859_1));
            this.recordLimit = recordLimit;
        }

        @Override
        public DataShape getShape() {
            return shape();
        }

        @Override
        public boolean hasNext() {
            if (closed || delivered >= recordLimit) {
                return false;
            }
            try {
                while (pending == null) {
                    String line = lines.readLine();
                    if (line == null) {
                        return false;
                    }
                    if (!headerSeen && WcpRow.isHeaderRow(line)) {
                        headerSeen = true;
                        continue;
                    }
                    if (!line.isEmpty()) {
                        pending = line;
                    }
                }
                return true;
            }
            catch (IOException e) {
                throw new UncheckedIOException("Unable to read world-cities input", e);
            }
        }

        @Override
        public DataValue next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String line = pending;
            pending = null;
            delivered++;
            return LiteralRecords.row(WcpRow.contract(), WcpRow.parse(line).asColumns());
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                lines.close();
            }
            catch (IOException e) {
                throw new UncheckedIOException("Unable to close world-cities input", e);
            }
        }
    }
}
