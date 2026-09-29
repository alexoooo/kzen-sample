package tech.kzen.sample.plugin.support;

import tech.kzen.auto.common.data.format.detection.DetectionPolicy;
import tech.kzen.auto.common.data.format.detection.NormalizedFormatHints;
import tech.kzen.auto.common.data.read.ReadOperationalPolicy;
import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.auto.plugin.api.data.ReaderByteInput;
import tech.kzen.auto.plugin.api.data.ReaderOpenRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeObserver;
import tech.kzen.auto.plugin.api.data.ReaderProbeRequest;
import tech.kzen.lib.common.exec.ExecutionValue;
import tech.kzen.lib.common.exec.LongExecutionValue;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.exec.NullExecutionValue;
import tech.kzen.lib.common.exec.NumberExecutionValue;
import tech.kzen.lib.common.exec.TextExecutionValue;
import tech.kzen.lib.common.exec.data.type.DataField;
import tech.kzen.lib.common.exec.data.type.DataType;
import tech.kzen.lib.common.exec.data.type.ScalarKind;
import tech.kzen.lib.common.exec.data.value.DataSnapshot;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.exec.data.value.SensitiveSnapshotPolicy;
import tech.kzen.lib.common.exec.data.value.SnapshotPolicy;
import tech.kzen.lib.common.exec.data.value.SnapshotResult;
import tech.kzen.lib.common.util.ImmutableByteArray;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;


/**
 * What the host would hand a reader, built by hand for the reader tests: a sequential byte input over a
 * byte array (gzip sources inflated first, as the host's content coding does), the open and probe requests
 * with neutral policies, and a flat read of a scalar-only record row.
 */
public final class ReaderTestSupport {
    private ReaderTestSupport() {}


    public static ReaderOpenRequest open(ReaderConfig config, ReaderByteInput input) {
        return new ReaderOpenRequest("test", null, config, input, new ReadOperationalPolicy(null, null, null, null, null));
    }


    public static ReaderProbeRequest probe(
            ReaderConfig candidate, byte[] sample, boolean endOfInput, boolean structuredHint
    ) {
        return new ReaderProbeRequest(
                candidate,
                NormalizedFormatHints.Companion.getEmpty(),
                ImmutableByteArray.Companion.copyOf(sample),
                List.of(),
                endOfInput,
                new DetectionPolicy(
                        DetectionPolicy.defaultMaximumDecodedBytes,
                        DetectionPolicy.defaultMaximumLogicalRecords,
                        DetectionPolicy.defaultTimeoutMillis,
                        List.of(),
                        List.of("UTF-8")),
                structuredHint,
                false,
                false,
                ReaderProbeObserver.Companion.getNone());
    }


    /** Raw bytes, or — for a `.gz` path — the inflated stream, as the host's content coding delivers it. */
    public static ReaderByteInput input(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (path.toString().endsWith(".gz")) {
            try (InputStream inflated = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return input(inflated.readAllBytes());
            }
        }
        return input(bytes);
    }


    public static ReaderByteInput input(byte[] bytes) {
        return new ReaderByteInput() {
            private int position = 0;

            @Override
            public long getExpandedBytesRead() {
                return position;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (length == 0) return 0;
                if (position >= bytes.length) return -1;
                int count = Math.min(length, bytes.length - position);
                System.arraycopy(bytes, position, buffer, offset, count);
                position += count;
                return count;
            }
        };
    }


    /**
     * A flat record row as Java values through a detached snapshot (the live node API is Kotlin value classes,
     * which Java cannot call; the snapshot writes integers as canonical text, so the declared field kind
     * decides): integers as Long, floating as Double, text as String, absent as null.
     */
    public static Map<String, Object> columns(DataValue value) {
        SnapshotResult captured = DataSnapshot.Companion.capture(value, new SnapshotPolicy(
                64, 100_000, 1_000_000, 10_000_000, 5_000, SensitiveSnapshotPolicy.Redact), false);
        if (! (captured instanceof SnapshotResult.Complete complete)) {
            throw new IllegalStateException("Row snapshot failed: " + captured);
        }
        DataType.Record record = (DataType.Record) value.getType();
        Map<String, ExecutionValue> written = ((MapExecutionValue) complete.getSnapshot().getValue()).getValues();
        Map<String, Object> row = new LinkedHashMap<>();
        for (DataField field : record.getFields()) {
            String name = field.getId().getName();
            ExecutionValue cell = written.get(name);
            ScalarKind kind = ((DataType.Scalar) field.getType()).getKind();
            row.put(name, switch (cell) {
                case null -> null;
                case NullExecutionValue ignored -> null;
                case TextExecutionValue text when kind instanceof ScalarKind.Integer -> Long.parseLong(text.getValue());
                case TextExecutionValue text -> text.getValue();
                case LongExecutionValue longValue -> longValue.getValue();
                case NumberExecutionValue number -> number.getValue();
                default -> throw new IllegalStateException("Unexpected column value: " + cell);
            });
        }
        return row;
    }
}
