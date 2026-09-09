package tech.kzen.sample.itch.store;

import tech.kzen.sample.itch.analysis.SymbolCatalog;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.wire.ItchFrameInput;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.stream.Stream;


/**
 * Builds a derived store version from one sequential decode of a day file: every frame is appended, with its
 * feed ordinal, to the partition of its Stock Locate; the Stock Directory becomes the catalog; per-partition
 * counts become the sizing inputs. The build writes a uniquely named version directory under the store root and
 * publishes it by atomically replacing the {@code current} pointer only after the manifest is written, so a
 * reader either finds a complete version or none. Frames are staged in one in-memory buffer per partition and
 * flushed to the partition file (opened in append mode for the duration of the flush) when that buffer reaches
 * the per-partition flush size or when the total staged bytes exceed the build's buffer budget; a real day
 * interleaves thousands of locates message by message, so any scheme that keeps a bounded set of files open
 * thrashes on open/close instead of streaming.
 *
 * Single writer, concurrent readers: a reader keeps the version it opened; superseded versions are deleted
 * best-effort after publishing and again at the start of the next build, so one still held open by a reader
 * (Windows) is removed later rather than failing anything. Two concurrent builds of one store are not
 * supported (the last pointer write wins).
 */
public final class ItchStoreBuilder {
    public static final long defaultBufferBudgetBytes = 512L << 20;
    static final int partitionFlushBytes = 1 << 20;
    private static final int initialPartitionBufferBytes = 1 << 12;
    private static final String pointerTemporarySuffix = ".tmp";

    private final long bufferBudgetBytes;


    public ItchStoreBuilder() {
        this(defaultBufferBudgetBytes);
    }

    /** [bufferBudgetBytes] bounds the frames staged in memory across all partitions before a bulk flush. */
    public ItchStoreBuilder(long bufferBudgetBytes) {
        if (bufferBudgetBytes < 1) {
            throw new IllegalArgumentException("A positive buffer budget is required");
        }
        this.bufferBudgetBytes = bufferBudgetBytes;
    }


    /** Builds a new version of [store] from [source] and publishes it; an existing version is superseded. */
    public StoreManifest build(Path source, Path store) {
        return build(source, store, ordinal -> true);
    }


    /**
     * Test seam: [proceed] is consulted with each message's ordinal before it is appended, so a mid-build failure
     * can be injected deterministically; returning false aborts the build.
     */
    public StoreManifest build(Path source, Path store, LongPredicate proceed) {
        Path root = store.toAbsolutePath().normalize();
        String token = ProcessHandle.current().pid() + "-" + UUID.randomUUID();
        Path version = root.resolve(StoreFormat.versionDirectoryPrefix + token);
        try {
            Files.createDirectories(root);
            removeUnpublishedVersions(root);
            Files.createDirectories(version.resolve(StoreFormat.partitionsDirectoryName));
            StoreManifest manifest = writeVersion(source, version, proceed);
            publish(root, version, token);
            removeUnpublishedVersions(root);
            return manifest;
        }
        catch (IOException e) {
            deleteQuietly(version);
            throw new UncheckedIOException(e);
        }
        catch (RuntimeException e) {
            deleteQuietly(version);
            throw e;
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    private StoreManifest writeVersion(Path source, Path version, LongPredicate proceed) throws IOException {
        SourceFingerprint fingerprint = SourceFingerprint.of(source);
        Path partitions = version.resolve(StoreFormat.partitionsDirectoryName);
        SymbolCatalog catalog = new SymbolCatalog();
        Map<Integer, PartitionStats> stats = new TreeMap<>();
        long messages = 0;

        try (PartitionWriters writers = new PartitionWriters(partitions, bufferBudgetBytes);
             ItchFrameInput frames = ItchFrameInput.open(Files.newInputStream(source))) {
            while (frames.next()) {
                long ordinal = frames.ordinal();
                if (!proceed.test(ordinal)) {
                    throw new ItchStoreException("Build of " + version + " aborted at ordinal " + ordinal);
                }
                int length = frames.frameLength();
                ItchMessage message = ItchDecoder.view(new tech.kzen.sample.itch.message.ItchRecord(
                        java.lang.foreign.MemorySegment.ofArray(frames.frame()), 0, length, ordinal, null));
                int locate = message.header().stockLocate();
                catalog.observe(message);
                writers.append(locate, ordinal, frames.frame(), length);
                int frameBytes = StoreFormat.frameHeaderBytes + length;
                stats.merge(locate, PartitionStats.empty(locate, PartitionStats.noSymbol).plus(message, frameBytes),
                        (previous, ignored) -> previous.plus(message, frameBytes));
                messages++;
            }
        }

        try (BufferedWriter out = Files.newBufferedWriter(version.resolve(StoreFormat.catalogFileName))) {
            out.write(PartitionStats.tsvHeader);
            out.newLine();
            for (PartitionStats partition : stats.values()) {
                String symbol = partition.locate() == ItchHeader.marketWideLocate || !catalog.contains(partition.locate())
                        ? PartitionStats.noSymbol
                        : catalog.symbol(partition.locate());
                out.write(partition.withSymbol(symbol).toTsv());
                out.newLine();
            }
        }

        StoreManifest manifest = new StoreManifest(StoreFormat.formatVersion, StoreFormat.parserVersion,
                source.getFileName().toString(), fingerprint, messages, stats.size(), Instant.now(), true);
        manifest.save(version);
        return manifest;
    }


    /** Atomically points {@code current} at [version]: a temporary pointer file is moved over the live one. */
    private static void publish(Path root, Path version, String token) throws IOException {
        Path pointer = root.resolve(StoreFormat.currentPointerFileName);
        Path temporary = root.resolve(StoreFormat.currentPointerFileName + pointerTemporarySuffix + token);
        Files.writeString(temporary, version.getFileName().toString(), StandardCharsets.UTF_8);
        Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }


    /** Deletes every version directory the pointer does not name; a held one (Windows) is left for next time. */
    private static void removeUnpublishedVersions(Path root) throws IOException {
        String current = ItchStore.currentVersionNameOrNull(root);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean staleVersion = Files.isDirectory(entry)
                        && name.startsWith(StoreFormat.versionDirectoryPrefix) && !name.equals(current);
                boolean stalePointer = Files.isRegularFile(entry)
                        && name.startsWith(StoreFormat.currentPointerFileName + pointerTemporarySuffix);
                if (staleVersion || stalePointer) {
                    deleteQuietly(entry);
                }
            }
        }
    }


    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                }
                catch (IOException ignored) {
                    // a file a reader still holds open; removed by a later build
                }
            });
        }
        catch (IOException ignored) {
            // same
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * Per-partition staging buffers under one budget: a partition flushes alone when it reaches
     * {@link #partitionFlushBytes}; when the total staged exceeds the budget every non-empty partition flushes.
     * Each flush opens the partition file in append mode, writes once, and closes it.
     */
    private static final class PartitionWriters implements AutoCloseable {
        private final Path directory;
        private final long budgetBytes;
        private final Map<Integer, PartitionBuffer> buffers = new HashMap<>();
        private final byte[] frameHeader = new byte[StoreFormat.frameHeaderBytes];
        private long staged;

        PartitionWriters(Path directory, long budgetBytes) {
            this.directory = directory;
            this.budgetBytes = budgetBytes;
        }

        void append(int locate, long ordinal, byte[] frame, int length) throws IOException {
            PartitionBuffer buffer = buffers.computeIfAbsent(locate, ignored -> new PartitionBuffer());
            ByteBuffer.wrap(frameHeader).putLong(ordinal).putShort((short) length);
            buffer.write(frameHeader, 0, StoreFormat.frameHeaderBytes);
            buffer.write(frame, 0, length);
            staged += StoreFormat.frameHeaderBytes + length;
            if (buffer.size() >= partitionFlushBytes) {
                flush(locate, buffer);
            }
            else if (staged > budgetBytes) {
                flushAll();
            }
        }

        private void flush(int locate, PartitionBuffer buffer) throws IOException {
            if (buffer.size() == 0) {
                return;
            }
            try (OutputStream out = Files.newOutputStream(partitionFile(directory, locate),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                buffer.writeTo(out);
            }
            staged -= buffer.size();
            buffer.reset();
        }

        private void flushAll() throws IOException {
            for (Map.Entry<Integer, PartitionBuffer> entry : buffers.entrySet()) {
                flush(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void close() throws IOException {
            flushAll();
            buffers.clear();
        }
    }


    /** A growable byte sink whose backing array is reused across flushes (ByteArrayOutputStream without locking). */
    private static final class PartitionBuffer {
        private byte[] bytes = new byte[initialPartitionBufferBytes];
        private int size;

        void write(byte[] source, int offset, int length) {
            if (size + length > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + length));
            }
            System.arraycopy(source, offset, bytes, size, length);
            size += length;
        }

        int size() {
            return size;
        }

        void writeTo(OutputStream out) throws IOException {
            out.write(bytes, 0, size);
        }

        void reset() {
            size = 0;
        }
    }


    static Path partitionFile(Path partitionsDirectory, int locate) {
        return partitionsDirectory.resolve(locate + StoreFormat.partitionFileSuffix);
    }
}
