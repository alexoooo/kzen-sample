package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


/**
 * A raw or gzip ITCH 5.0 day file as an iterable of decoded messages. Each {@link #iterator()} opens the file
 * afresh and returns an {@link ItchCursor} the caller closes; {@link #forEach} and {@link #stream} own that
 * close themselves.
 */
public final class ItchReader implements Iterable<ItchMessage> {
    private final Path path;


    public ItchReader(Path path) {
        this.path = path;
    }


    public Path path() {
        return path;
    }


    /** Opens the feed; the cursor holds the file handle until closed. */
    public ItchCursor open() {
        try {
            return ItchCursor.open(Files.newInputStream(path));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    @Override
    public ItchCursor iterator() {
        return open();
    }


    @Override
    public void forEach(Consumer<? super ItchMessage> action) {
        try (ItchCursor cursor = open()) {
            while (cursor.hasNext()) {
                action.accept(cursor.next());
            }
        }
    }


    /** A lazily decoded stream; closing it closes the feed. */
    public Stream<ItchMessage> stream() {
        ItchCursor cursor = open();
        return StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(cursor, java.util.Spliterator.ORDERED), false)
                .onClose(cursor::close);
    }
}
