package tech.kzen.sample.plugin.support;

import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.ItchStoreBuilder;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.io.IOException;
import java.nio.file.Path;


/** A synthetic day written to disk and built into a persistent store, the way the store-backed route sees it. */
public final class SyntheticStores {
    private SyntheticStores() {}


    public static Path writeDay(SyntheticItchDay day, Path directory, String name) throws IOException {
        Path source = directory.resolve(name + ".itch");
        day.writeTo(source, false);
        return source;
    }


    public static ItchStore build(SyntheticItchDay day, Path directory, String name) throws IOException {
        Path source = writeDay(day, directory, name);
        Path store = directory.resolve(name + ".store");
        new ItchStoreBuilder().build(source, store);
        return ItchStore.open(store);
    }
}
