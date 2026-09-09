package tech.kzen.sample.itch.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Shared OS lock protects every partition a session may open, including files not yet prefetched. */
public final class StoreVersionLease implements AutoCloseable {
    static final String fileName = "readers.lock";
    private static final Map<Path, Held> held = new HashMap<>();
    private final Path path;
    private boolean closed;

    public StoreVersionLease(ItchStore store) {
        path = store.directory().toAbsolutePath().normalize();
        synchronized (held) {
            Held existing = held.get(path);
            if (existing != null) { existing.references++; return; }
            try {
                FileChannel channel = FileChannel.open(path.resolve(fileName), StandardOpenOption.READ);
                try {
                    FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true);
                    if (lock == null) throw new ItchStoreException("Store version is being removed: " + path);
                    held.put(path, new Held(channel, lock));
                }
                catch (Throwable failure) { channel.close(); throw failure; }
            }
            catch (IOException e) { throw new ItchStoreException("Cannot pin store version " + path, e); }
        }
    }

    static void prune(Path directory, Consumer<Path> delete) throws IOException {
        Path path = directory.toAbsolutePath().normalize();
        synchronized (held) {
            if (held.containsKey(path)) return;
            Path marker = path.resolve(fileName);
            if (java.nio.file.Files.exists(marker)) {
                try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    try (FileLock lock = channel.tryLock()) {
                        if (lock == null) return;
                        delete.accept(path);
                    }
                    catch (OverlappingFileLockException e) { return; }
                }
            }
            delete.accept(path);
        }
    }

    @Override public void close() {
        synchronized (held) {
            if (closed) return;
            closed = true;
            Held entry = held.get(path);
            if (--entry.references != 0) return;
            try { entry.channel.close(); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            finally { held.remove(path); }
        }
    }

    private static final class Held {
        final FileChannel channel;
        final FileLock lock;
        int references = 1;
        Held(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    }
}
