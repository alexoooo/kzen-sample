package tech.kzen.sample.itch.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import tech.kzen.sample.itch.wire.ItchCursor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ItchStoreTest {
    private static final long seed = 7L;
    private static final int abortOrdinal = 100;

    @TempDir
    Path temp;


    @Test
    void replayPerLocateIsTheFeedFilteredToThatLocatePlusMarketWide() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed);
        Path source = temp.resolve("day.itch.gz");
        day.writeTo(source, true);
        Path store = temp.resolve("day.store");
        StoreManifest manifest = new ItchStoreBuilder().build(source, store);

        assertTrue(manifest.complete());
        assertEquals(day.messages().size(), manifest.messages());
        ItchStore opened = ItchStore.open(store);
        assertEquals(store.toAbsolutePath(), opened.root());
        assertEquals(day.symbolsByLocate().values().stream().sorted().toList(), List.copyOf(opened.symbols().keySet()));

        long partitionTotal = 0;
        for (int locate : opened.partitions().keySet()) {
            List<ItchMessage> expected = day.messages().stream()
                    .filter(m -> m.header().stockLocate() == locate
                            || (locate != ItchHeader.marketWideLocate
                                    && m.header().stockLocate() == ItchHeader.marketWideLocate))
                    .toList();
            List<ItchMessage> replayed = new ArrayList<>();
            try (ItchCursor cursor = opened.replay(locate)) {
                cursor.forEachRemaining(replayed::add);
            }
            assertEquals(expected, replayed, "locate " + locate);
            for (int i = 1; i < replayed.size(); i++) {
                assertTrue(replayed.get(i - 1).header().ordinal() < replayed.get(i).header().ordinal(),
                        "ordinal order at locate " + locate);
            }
            partitionTotal += opened.stats(locate).messages();
        }
        assertEquals(day.messages().size(), partitionTotal, "market-wide messages are stored once");

        PartitionStats aapl = opened.stats(SyntheticItchDay.aaplLocate);
        assertEquals(SyntheticItchDay.aapl, aapl.symbol());
        assertEquals(day.messages().stream().filter(m -> m instanceof ItchMessage.AddOrder
                && m.header().stockLocate() == SyntheticItchDay.aaplLocate).count(), aapl.adds());
        assertEquals(PartitionStats.noSymbol, opened.stats(ItchHeader.marketWideLocate).symbol());
        try (Stream<ItchMessage> stream = opened.stream(SyntheticItchDay.quietLocate)) {
            assertTrue(stream.allMatch(m -> m.header().stockLocate() == SyntheticItchDay.quietLocate
                    || m.header().stockLocate() == ItchHeader.marketWideLocate));
        }
    }


    @Test
    void staleSourceAndForeignVersionsAreNamedFailures() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 10);
        Path source = temp.resolve("day.itch");
        day.writeTo(source, false);
        Path store = temp.resolve("day.store");
        new ItchStoreBuilder().build(source, store);
        ItchStore opened = ItchStore.open(store);
        opened.requireFresh(source);

        Files.write(source, new byte[] {0}, StandardOpenOption.APPEND);
        ItchStoreException stale = assertThrows(ItchStoreException.class, () -> opened.requireFresh(source));
        assertTrue(stale.getMessage().contains("is stale"), stale.getMessage());

        Path manifest = opened.directory().resolve(StoreFormat.manifestFileName);
        String edited = Files.readString(manifest).replace("formatVersion=" + StoreFormat.formatVersion,
                "formatVersion=" + (StoreFormat.formatVersion + 1));
        Files.writeString(manifest, edited);
        ItchStoreException version = assertThrows(ItchStoreException.class, () -> ItchStore.open(store));
        assertTrue(version.getMessage().contains("format version " + (StoreFormat.formatVersion + 1)), version.getMessage());

        Files.writeString(manifest, Files.readString(manifest).replace("complete=true", "complete=false"));
        ItchStoreException incomplete = assertThrows(ItchStoreException.class, () -> ItchStore.open(store));
        assertTrue(incomplete.getMessage().contains("incomplete"), incomplete.getMessage());

        Files.delete(store.resolve(StoreFormat.currentPointerFileName));
        ItchStoreException notAStore = assertThrows(ItchStoreException.class, () -> ItchStore.open(store));
        assertTrue(notAStore.getMessage().contains("Not a store"), notAStore.getMessage());
    }


    @Test
    void budgetedBuffersProduceIdenticalPartitions() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 50);
        Path source = temp.resolve("day.itch");
        day.writeTo(source, false);
        ItchStore unbounded = build(source, temp.resolve("unbounded.store"), new ItchStoreBuilder());
        ItchStore bounded = build(source, temp.resolve("bounded.store"), new ItchStoreBuilder(64));

        assertEquals(5, bounded.partitions().size(), "four symbols plus locate 0, flushed many times under a 64-byte budget");
        for (int locate : unbounded.partitions().keySet()) {
            try (var a = unbounded.stream(locate); var b = bounded.stream(locate)) {
                assertEquals(a.toList(), b.toList(), "locate " + locate);
            }
        }
    }


    @Test
    void midBuildFailurePublishesNothingAndKeepsThePreviousVersion() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 30);
        Path source = temp.resolve("day.itch");
        day.writeTo(source, false);
        Path store = temp.resolve("day.store");
        StoreManifest first = new ItchStoreBuilder().build(source, store);

        ItchStoreException aborted = assertThrows(ItchStoreException.class,
                () -> new ItchStoreBuilder().build(source, store, ordinal -> ordinal < abortOrdinal));
        assertTrue(aborted.getMessage().contains("aborted at ordinal " + abortOrdinal), aborted.getMessage());

        assertEquals(first, ItchStore.open(store).manifest());
        assertEquals(List.of(StoreFormat.currentPointerFileName, ItchStore.currentVersionNameOrNull(store)),
                entries(store), "only the pointer and the published version remain");

        Path fresh = temp.resolve("fresh.store");
        assertThrows(ItchStoreException.class,
                () -> new ItchStoreBuilder().build(source, fresh, ordinal -> ordinal < abortOrdinal));
        assertThrows(ItchStoreException.class, () -> ItchStore.open(fresh), "an aborted first build publishes nothing");
    }


    @Test
    void readerHoldingTheOldVersionCompletesAcrossARebuild() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 30);
        Path source = temp.resolve("day.itch");
        day.writeTo(source, false);
        Path store = temp.resolve("day.store");
        new ItchStoreBuilder().build(source, store);
        ItchStore old = ItchStore.open(store);

        long replayed = 0;
        try (ItchCursor cursor = old.replay(SyntheticItchDay.msftLocate)) {
            cursor.next();
            replayed++;
            StoreManifest rebuilt = new ItchStoreBuilder().build(source, store);
            assertTrue(rebuilt.complete());
            ItchStore current = ItchStore.open(store);
            assertEquals(rebuilt, current.manifest());
            assertNotEquals(old.directory(), current.directory());
            while (cursor.hasNext()) {
                cursor.next();
                replayed++;
            }
        }
        assertEquals(old.stats(SyntheticItchDay.msftLocate).messages() + old.stats(ItchHeader.marketWideLocate).messages(),
                replayed, "the old reader saw its complete old version");

        new ItchStoreBuilder().build(source, store);
        assertEquals(2, entries(store).size(), "superseded versions are removed once no reader holds them");
    }


    @Test
    void dataAreaIsASiblingOfRunScratchAndSurvivesItsSweep() throws IOException {
        Path workRoot = temp.resolve("work");
        ItchDataArea area = new ItchDataArea(workRoot.resolve("data"));
        area.createDirectories();
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 10);
        Path source = area.sources().resolve("day.itch.gz");
        day.writeTo(source, true);

        ItchStore built = area.ensureStore(source, new ItchStoreBuilder());
        ItchStore reused = area.ensureStore(source, new ItchStoreBuilder());
        assertEquals(built.manifest(), reused.manifest(), "a fresh store is reused, not rebuilt");
        assertEquals(workRoot.resolve("data").resolve(ItchDataArea.storesDirectoryName)
                .resolve("day.itch.gz" + ItchDataArea.storeSuffix).toAbsolutePath(), built.root());

        Path job = workRoot.resolve("job");
        Files.createDirectories(job.resolve("run-1"));
        Files.writeString(job.resolve("run-1").resolve("scratch.bin"), "x");
        try (Stream<Path> walk = Files.walk(job)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
        assertFalse(Files.exists(job));
        assertTrue(Files.exists(source), "sweeping job/ leaves the source");
        ItchStore.open(built.root()).requireFresh(source);
        assertEquals(4, built.symbols().size());

        Files.write(source, new byte[] {0}, StandardOpenOption.APPEND);
        ItchStore rebuilt = area.ensureStore(source, new ItchStoreBuilder());
        assertNotEquals(built.manifest().sourceFingerprint(), rebuilt.manifest().sourceFingerprint(),
                "a changed source triggers a rebuild");
    }


    private static ItchStore build(Path source, Path store, ItchStoreBuilder builder) {
        builder.build(source, store);
        return ItchStore.open(store);
    }

    @Test
    void rejectsMalformedCompressedBlocksAndTracksPhysicalSizes() throws Exception {
        Path source = temp.resolve("day.itch");
        SyntheticItchDay.generate(seed, 20).writeTo(source, false);
        ItchStore store = build(source, temp.resolve("compressed.store"), new ItchStoreBuilder());
        int locate = SyntheticItchDay.aaplLocate;
        Path file = partitionFile(store, locate);
        byte[] valid = Files.readAllBytes(file);
        assertEquals(valid.length, store.stats(locate).storedBytes());
        for (int field = 0; field < 4; field++) {
            byte[] broken = valid.clone();
            java.nio.ByteBuffer.wrap(broken).putInt(field * Integer.BYTES, Integer.MAX_VALUE);
            Files.write(file, broken);
            assertThrows(ItchStoreException.class, () -> {
                try (var day = tech.kzen.sample.itch.day.SymbolDay.materialize(store, locate)) {}
            });
        }
        Files.write(file, java.util.Arrays.copyOf(valid, valid.length - 1));
        assertThrows(ItchStoreException.class, () -> {
            try (var day = tech.kzen.sample.itch.day.SymbolDay.materialize(store, locate)) {}
        });
    }

    private static Path partitionFile(ItchStore store, int locate) {
        return ItchStoreBuilder.partitionFile(store.directory().resolve(StoreFormat.partitionsDirectoryName), locate);
    }

    private static List<String> entries(Path store) throws IOException {
        try (Stream<Path> list = Files.list(store)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
