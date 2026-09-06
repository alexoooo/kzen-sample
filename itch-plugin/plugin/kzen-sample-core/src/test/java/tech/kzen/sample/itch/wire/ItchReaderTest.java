package tech.kzen.sample.itch.wire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ItchReaderTest {
    private static final long seed = 20260904L;
    private static final int truncationOffsetInsideMessage = 5;

    @TempDir
    Path temp;


    @Test
    void rawAndGzipRoundTripPreserveEveryMessageAndOrdinal() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed);
        Path raw = temp.resolve("day.itch");
        Path gzip = temp.resolve("day.itch.gz");
        day.writeTo(raw, false);
        day.writeTo(gzip, true);
        assertEquals(day.encodedLength(), Files.size(raw));

        for (Path path : List.of(raw, gzip)) {
            List<ItchMessage> read = new ArrayList<>();
            new ItchReader(path).forEach(read::add);
            assertEquals(day.messages(), read, path.toString());
            for (int i = 0; i < read.size(); i++) {
                assertEquals(i, read.get(i).header().ordinal());
            }
        }
        try (var stream = new ItchReader(gzip).stream()) {
            assertEquals(day.messages().size(), stream.count());
        }
    }


    @Test
    void truncatedFeedFailsByNameAndReleasesTheFile() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 5);
        Path path = temp.resolve("truncated.itch");
        day.writeTo(path, false);
        byte[] bytes = Files.readAllBytes(path);
        int cut = bytes.length - ItchEncoder.frameLength(day.messages().getLast()) + truncationOffsetInsideMessage;
        Files.write(path, java.util.Arrays.copyOf(bytes, cut));

        ItchFormatException failure = assertThrows(ItchFormatException.class,
                () -> new ItchReader(path).forEach(message -> {}));
        assertTrue(failure.getMessage().contains("truncated inside message " + (day.messages().size() - 1)),
                failure.getMessage());

        // A leaked handle would make this delete fail on Windows
        Files.delete(path);
        assertFalse(Files.exists(path));
    }


    @Test
    void partialLengthPrefixZeroLengthAndUnknownTypeAreNamedFailures() throws IOException {
        byte[] oneMessage = ItchEncoder.encode(SyntheticItchDay.generate(seed, 0).messages().getFirst());
        byte[] framed = new byte[oneMessage.length + 2];
        framed[0] = 0;
        framed[1] = (byte) oneMessage.length;
        System.arraycopy(oneMessage, 0, framed, 2, oneMessage.length);

        byte[] partialPrefix = java.util.Arrays.copyOf(framed, framed.length + 1);
        ItchFormatException prefixFailure = assertThrows(ItchFormatException.class,
                () -> drain(partialPrefix));
        assertTrue(prefixFailure.getMessage().contains("length prefix of message 1"), prefixFailure.getMessage());

        byte[] zeroLength = java.util.Arrays.copyOf(framed, framed.length + 2);
        ItchFormatException zeroFailure = assertThrows(ItchFormatException.class, () -> drain(zeroLength));
        assertTrue(zeroFailure.getMessage().contains("zero length"), zeroFailure.getMessage());

        byte[] unknownType = framed.clone();
        unknownType[2] = 'Z';
        ItchFormatException typeFailure = assertThrows(ItchFormatException.class, () -> drain(unknownType));
        assertTrue(typeFailure.getMessage().contains("unknown type 'Z'"), typeFailure.getMessage());
    }


    @Test
    void missingFileFailsAtOpen() {
        Path missing = temp.resolve("missing.itch");
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> new ItchReader(missing).open());
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
    }


    @Test
    void decodingIsStreamedNotPreloaded() throws IOException {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 3000);
        Path path = temp.resolve("large.itch");
        day.writeTo(path, false);
        long size = Files.size(path);
        long[] consumed = {0};
        InputStream counting = new FilterInputStream(Files.newInputStream(path)) {
            @Override public int read() throws IOException {
                int b = super.read();
                if (b != -1) consumed[0]++;
                return b;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) consumed[0] += n;
                return n;
            }
        };
        try (ItchCursor cursor = ItchCursor.open(counting)) {
            for (int i = 0; i < 3; i++) {
                cursor.next();
            }
            assertTrue(consumed[0] < size / 2, "consumed " + consumed[0] + " of " + size + " bytes after 3 messages");
            long count = 3;
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
            assertEquals(day.messages().size(), count);
        }
    }


    private static void drain(byte[] feed) throws IOException {
        try (ItchCursor cursor = ItchCursor.open(new ByteArrayInputStream(feed))) {
            while (cursor.hasNext()) {
                cursor.next();
            }
        }
    }
}
