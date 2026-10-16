package tech.kzen.sample.plugin.cities;

import tech.kzen.auto.common.data.api.DataCursor;
import tech.kzen.auto.common.data.read.ContentCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.auto.plugin.api.data.BlockingReaderCapability;
import tech.kzen.auto.plugin.api.data.ReaderByteInput;
import tech.kzen.auto.plugin.api.data.ReaderOpenRequest;
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
 * line a typed {@link WcpRow} record. Its format declares no extension, so it is only ever selected
 * explicitly. Compression is the host's content coding, chosen by the file name.
 */
public final class WorldCitiesReaderCapability extends BlockingReaderCapability {
    public static final ReaderCapabilityIdentity identity =
            new ReaderCapabilityIdentity("tech.kzen.sample", "world-cities", "1");

    private static final ReaderConfig noConfig = new ReaderConfig() {};


    @Override
    public ReaderCapabilityIdentity getIdentity() {
        return identity;
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


    /** The columns are fixed, so inspection reads no content. */
    @Override
    public DataShape fixedShape(ReaderConfig config) {
        return shape();
    }


    @Override
    public DataCursor openBlocking(ReaderOpenRequest request) {
        return new Cursor(request.getBytes());
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static final class Cursor implements DataCursor {
        private final BufferedReader lines;
        private String pending;
        private boolean headerSeen;
        private boolean closed;

        Cursor(ReaderByteInput bytes) {
            this.lines = new BufferedReader(new InputStreamReader(new ReaderByteInputStream(bytes), StandardCharsets.ISO_8859_1));
        }

        @Override
        public DataShape getShape() {
            return shape();
        }

        @Override
        public boolean hasNext() {
            if (closed) {
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
