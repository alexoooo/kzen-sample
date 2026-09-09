package tech.kzen.sample.itch.store;

import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.ItchRecord;
import java.lang.foreign.MemorySegment;
import tech.kzen.sample.itch.wire.ItchCursor;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.wire.ItchFormatException;
import tech.kzen.sample.itch.store.block.PartitionBlocks;
import tech.kzen.sample.itch.day.MaterializationProgress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;


/**
 * A complete derived store: its manifest, catalog and partitions. A symbol replay merges the symbol's partition
 * with the shared locate-0 partition by feed ordinal, so market-wide messages appear in every symbol's history
 * exactly where they occurred (stored once, duplicated logically on load).
 */
public final class ItchStore {
    private final Path root;
    private final Path directory;
    private final StoreManifest manifest;
    private final SortedMap<Integer, PartitionStats> partitionsByLocate;
    private final SortedMap<String, Integer> locatesBySymbol;


    /**
     * Opens the version the store's {@code current} pointer names; a missing, incomplete or other-version store
     * fails by name. The version directory stays valid for this instance even if a later build supersedes it.
     */
    public static ItchStore open(Path root) {
        String versionName = currentVersionNameOrNull(root);
        if (versionName == null) {
            throw new ItchStoreException("Not a store (no " + StoreFormat.currentPointerFileName + "): " + root);
        }
        Path directory = root.resolve(versionName);
        StoreManifest manifest = StoreManifest.load(directory);
        manifest.requireCurrentAndComplete(directory);
        return new ItchStore(root, directory, manifest, readCatalog(directory));
    }


    /** The version name the pointer file holds, or null when the root is not (yet) a store. */
    static String currentVersionNameOrNull(Path root) {
        Path pointer = root.resolve(StoreFormat.currentPointerFileName);
        if (!Files.isRegularFile(pointer)) {
            return null;
        }
        try {
            String name = Files.readString(pointer).strip();
            return name.startsWith(StoreFormat.versionDirectoryPrefix) ? name : null;
        }
        catch (IOException e) {
            throw new ItchStoreException("Unable to read " + pointer, e);
        }
    }


    private ItchStore(
            Path root, Path directory, StoreManifest manifest, SortedMap<Integer, PartitionStats> partitionsByLocate
    ) {
        this.root = root;
        this.directory = directory;
        this.manifest = manifest;
        this.partitionsByLocate = partitionsByLocate;
        SortedMap<String, Integer> symbols = new TreeMap<>();
        for (PartitionStats stats : partitionsByLocate.values()) {
            if (!stats.symbol().equals(PartitionStats.noSymbol)) {
                symbols.put(stats.symbol(), stats.locate());
            }
        }
        this.locatesBySymbol = Collections.unmodifiableSortedMap(symbols);
    }


    //-----------------------------------------------------------------------------------------------------------------
    /** The store root a caller names (holds the pointer and every version). */
    public Path root() {
        return root;
    }

    /** The published version directory this instance reads. */
    public Path directory() {
        return directory;
    }

    public StoreManifest manifest() {
        return manifest;
    }

    public boolean isCurrent() {
        return directory.getFileName().toString().equals(currentVersionNameOrNull(root));
    }

    /** Every partition including locate 0, keyed by locate. */
    public SortedMap<Integer, PartitionStats> partitions() {
        return Collections.unmodifiableSortedMap(partitionsByLocate);
    }

    /** Catalogued symbols in lexical order, with their locates. */
    public SortedMap<String, Integer> symbols() {
        return locatesBySymbol;
    }

    public PartitionStats stats(int locate) {
        PartitionStats stats = partitionsByLocate.get(locate);
        if (stats == null) {
            throw new ItchStoreException("Store " + directory + " has no partition for locate " + locate);
        }
        return stats;
    }

    public int locate(String symbol) {
        Integer locate = locatesBySymbol.get(symbol);
        if (locate == null) {
            throw new ItchStoreException("Store " + directory + " has no symbol '" + symbol + "'");
        }
        return locate;
    }


    /** Throws by name if [source] no longer matches the fingerprint the store was built from. */
    public void requireFresh(Path source) {
        if (!manifest.sourceFingerprint().matches(source)) {
            throw new ItchStoreException("Store " + directory + " is stale: " + source
                    + " no longer matches fingerprint " + manifest.sourceFingerprint().encode());
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * The symbol-day replay for [locate]: its partition merged with locate 0 by ordinal. The cursor holds both
     * partition files open until closed.
     */
    public ItchCursor replay(int locate) {
        stats(locate);
        if (locate == ItchHeader.marketWideLocate) {
            return ItchCursor.adopt(new PartitionCursor(partitionFile(locate)));
        }
        PartitionCursor own = new PartitionCursor(partitionFile(locate));
        try {
            PartitionCursor shared = partitionsByLocate.containsKey(ItchHeader.marketWideLocate)
                    ? new PartitionCursor(partitionFile(ItchHeader.marketWideLocate)) : null;
            return ItchCursor.adopt(shared == null ? own : new MergedCursor(own, shared));
        }
        catch (Throwable failure) { own.close(); throw failure; }
    }


    /** The record is borrowed for the duration of the callback; only the batch loader retains its bytes. */
    public interface RecordConsumer {
        void accept(ItchRecord record) throws InterruptedException;
    }

    public void readRecords(int locate, RecordConsumer consumer) throws InterruptedException {
        stats(locate);
        try (PartitionCursor own = new PartitionCursor(partitionFile(locate));
             PartitionCursor shared = locate != ItchHeader.marketWideLocate && partitionsByLocate.containsKey(ItchHeader.marketWideLocate)
                     ? new PartitionCursor(partitionFile(ItchHeader.marketWideLocate)) : null) {
            while (true) {
                boolean hasOwn = own.hasNext();
                boolean hasShared = shared != null && shared.hasNext();
                if (!hasOwn && !hasShared) return;
                PartitionCursor next = !hasShared || (hasOwn && own.pending.ordinal() < shared.pending.ordinal()) ? own : shared;
                consumer.accept(next.nextRecord());
            }
        }
    }

    public ItchCursor replay(String symbol) {
        return replay(locate(symbol));
    }


    /** Only the shared market-wide messages. */
    public ItchCursor marketWide() {
        return replay(ItchHeader.marketWideLocate);
    }


    /** A lazily decoded replay of [locate]; closing the stream closes the partition files. */
    public Stream<ItchMessage> stream(int locate) {
        ItchCursor cursor = replay(locate);
        return java.util.stream.StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(cursor, java.util.Spliterator.ORDERED), false)
                .onClose(cursor::close);
    }


    public Path partitionFile(int locate) {
        return ItchStoreBuilder.partitionFile(directory.resolve(StoreFormat.partitionsDirectoryName), locate);
    }

    public void loadPartition(int locate, MemorySegment destination, PartitionBlocks.Decoder decoder,
            byte[] prefix, MaterializationProgress progress) throws InterruptedException {
        PartitionStats expected = stats(locate);
        long position = 0, messages = 0, previousOrdinal = -1;
        try (var reader = new PartitionBlocks.Reader(partitionFile(locate), prefix, decoder)) {
            while (reader.next()) {
                if (Thread.interrupted()) throw new InterruptedException("Partition load interrupted");
                int size = reader.rawBytes();
                if (size > destination.byteSize() - position) throw new ItchStoreException("Partition exceeds catalog bytes");
                reader.decompress(destination.asSlice(position, size));
                long end = position + size;
                int count = 0;
                while (position < end) {
                    if (end - position < StoreFormat.frameHeaderBytes) throw new ItchStoreException("Truncated record header");
                    long ordinal = destination.get(StoreFormat.ordinalLayout, position);
                    int length = Short.toUnsignedInt(destination.get(StoreFormat.lengthLayout, position + StoreFormat.ordinalBytes));
                    if (ordinal <= previousOrdinal || length < 11 || length > end - position - StoreFormat.frameHeaderBytes)
                        throw new ItchStoreException("Invalid partition record at " + position);
                    previousOrdinal = ordinal;
                    position += StoreFormat.frameHeaderBytes + length;
                    count++;
                }
                if (count != reader.records()) throw new ItchStoreException("Block record count differs");
                messages += count;
                progress.update(messages, position);
            }
        }
        if (position != expected.bytes() || messages != expected.messages())
            throw new ItchStoreException("Partition differs from catalog counts for " + expected.symbol());
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static SortedMap<Integer, PartitionStats> readCatalog(Path directory) {
        Path file = directory.resolve(StoreFormat.catalogFileName);
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        }
        catch (IOException e) {
            throw new ItchStoreException("Unable to read " + file, e);
        }
        if (lines.isEmpty() || !lines.getFirst().equals(PartitionStats.tsvHeader)) {
            throw new ItchStoreException("Malformed catalog header in " + file);
        }
        SortedMap<Integer, PartitionStats> partitions = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            PartitionStats stats = PartitionStats.fromTsv(line);
            partitions.put(stats.locate(), stats);
        }
        return partitions;
    }


    //-----------------------------------------------------------------------------------------------------------------
    /** Decodes one partition file's frames in stored (feed) order. */
    static final class PartitionCursor implements Iterator<ItchMessage>, AutoCloseable {
        private final Path file;
        private final PartitionBlocks.Reader input;
        private final byte[] frame = new byte[PartitionBlocks.maximumRawBytes];
        private final MemorySegment segment = MemorySegment.ofArray(frame);
        private int position;
        private int limit;
        private int blockRecords;
        private int consumedRecords;
        private long previousOrdinal = -1;
        private ItchRecord pending;
        private boolean finished;

        PartitionCursor(Path file) {
            this.file = file;
            this.input = new PartitionBlocks.Reader(file);
        }

        @Override
        public boolean hasNext() {
            if (pending != null) {
                return true;
            }
            if (finished) {
                return false;
            }
            if (position == limit) {
                if (consumedRecords != blockRecords) throw new ItchStoreException("Block record count differs in " + file);
                if (!input.next()) {
                    finished = true;
                    return false;
                }
                input.decompress(frame);
                position = 0;
                limit = input.rawBytes();
                blockRecords = input.records();
                consumedRecords = 0;
            }
            if (limit - position < StoreFormat.frameHeaderBytes) throw new ItchFormatException("Truncated frame header in " + file);
            long ordinal = segment.get(StoreFormat.ordinalLayout, position);
            int length = Short.toUnsignedInt(segment.get(StoreFormat.lengthLayout, position + StoreFormat.ordinalBytes));
            position += StoreFormat.frameHeaderBytes;
            if (length > limit - position || ordinal <= previousOrdinal) throw new ItchFormatException("Invalid frame in " + file);
            pending = new ItchRecord(segment, position, length, ordinal, null);
            ItchDecoder.validate(pending);
            previousOrdinal = ordinal;
            position += length;
            consumedRecords++;
            return true;
        }

        @Override
        public ItchMessage next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ItchRecord record = nextRecord();
            return ItchDecoder.view(new ItchRecord(MemorySegment.ofArray(record.copyBytes()), 0, record.length(), record.ordinal(), null));
        }

        ItchRecord nextRecord() {
            if (!hasNext()) throw new NoSuchElementException();
            ItchRecord record = pending;
            pending = null;
            return record;
        }

        @Override
        public void close() {
            input.close();
        }
    }


    /** Two ordinal-sorted cursors merged into one ordinal-sorted sequence. */
    private static final class MergedCursor implements Iterator<ItchMessage>, AutoCloseable {
        private final PartitionCursor left;
        private final PartitionCursor right;
        private ItchMessage leftHead;
        private ItchMessage rightHead;

        MergedCursor(PartitionCursor left, PartitionCursor right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean hasNext() {
            if (leftHead == null && left.hasNext()) leftHead = left.next();
            if (rightHead == null && right.hasNext()) rightHead = right.next();
            return leftHead != null || rightHead != null;
        }

        @Override
        public ItchMessage next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ItchMessage result;
            if (rightHead == null || (leftHead != null && leftHead.header().ordinal() < rightHead.header().ordinal())) {
                result = leftHead;
                leftHead = null;
            }
            else {
                result = rightHead;
                rightHead = null;
            }
            return result;
        }

        @Override
        public void close() {
            try {
                left.close();
            }
            finally {
                right.close();
            }
        }
    }
}
