package tech.kzen.sample.itch.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * The durable data area that holds downloaded source days and their derived stores: {@code <root>/sources/} and
 * {@code <root>/stores/<source file name>.store}. It is configured explicitly by whoever hosts the core (a kzen
 * workspace, a Spring host, a benchmark main) and is never a run-scratch directory: nothing here is swept when a
 * run settles or a process boots, and a rebuild replaces only the store, never a user's source file.
 */
public final class ItchDataArea {
    public static final String sourcesDirectoryName = "sources";
    public static final String storesDirectoryName = "stores";
    public static final String storeSuffix = ".store";

    private final Path root;


    public ItchDataArea(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }


    public Path root() {
        return root;
    }

    public Path sources() {
        return root.resolve(sourcesDirectoryName);
    }

    public Path stores() {
        return root.resolve(storesDirectoryName);
    }

    /** Where the store derived from [source] lives, keyed by the source file name. */
    public Path storeFor(Path source) {
        return stores().resolve(source.getFileName() + storeSuffix);
    }


    public void createDirectories() {
        try {
            Files.createDirectories(sources());
            Files.createDirectories(stores());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    /**
     * The complete, fresh store for [source], building it when absent, stale, incomplete or of another version.
     * The source is never modified.
     */
    public ItchStore ensureStore(Path source, ItchStoreBuilder builder) {
        Path store = storeFor(source);
        try {
            ItchStore existing = ItchStore.open(store);
            existing.requireFresh(source);
            return existing;
        }
        catch (ItchStoreException rebuildReason) {
            createDirectories();
            builder.build(source, store);
            return ItchStore.open(store);
        }
    }
}
