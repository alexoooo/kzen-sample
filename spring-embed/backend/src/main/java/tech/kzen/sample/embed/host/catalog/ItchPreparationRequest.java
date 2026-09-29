package tech.kzen.sample.embed.host.catalog;

import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.StoreVersionLease;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A run's interest in shared preparation and the prepared versions it will read. */
public final class ItchPreparationRequest implements AutoCloseable {
    private static final long progressIntervalMillis = 200;
    private final ItchCatalog catalog;
    private final List<ItchCatalogFile> files;
    private final Map<String, ItchPreparation> pending;
    private final Map<String, ItchStore> stores = new LinkedHashMap<>();
    private final List<StoreVersionLease> versions = new ArrayList<>();
    private volatile boolean closed;

    public record Progress(String file, String date, String phase, long bytes, long totalBytes,
                           String detail, int fileIndex, int totalFiles) {}

    ItchPreparationRequest(ItchCatalog catalog, List<ItchCatalogFile> files,
                           Map<String, ItchPreparation> pending, Map<String, ItchStore> ready) {
        this.catalog = catalog;
        this.files = files;
        this.pending = pending;
        try { ready.forEach(this::pin); }
        catch (RuntimeException | Error failure) {
            for (var version : versions) {
                try { version.close(); } catch (RuntimeException e) { failure.addSuppressed(e); }
            }
            throw failure;
        }
    }

    public ItchCatalog.Selection await(Consumer<Progress> progress) throws InterruptedException {
        for (int index = 0; index < files.size(); index++) {
            checkOpen();
            var file = files.get(index);
            var task = pending.get(file.id());
            if (task == null) continue;
            do {
                checkOpen();
                progress.accept(new Progress(file.id(), file.date().toString(), task.state, task.bytes,
                        file.size(), task.detail, index + 1, files.size()));
            } while (!task.completion.await(progressIntervalMillis, TimeUnit.MILLISECONDS));
            checkOpen();
            if (!task.state.equals("ready")) {
                throw new IllegalStateException("Could not prepare " + file.date() + ": " + task.detail, task.failure);
            }
            synchronized (this) {
                checkOpen();
                if (!stores.containsKey(file.id())) pin(file.id(), task.store);
            }
        }
        synchronized (this) {
            checkOpen();
            return new ItchCatalog.Selection(files, Map.copyOf(stores));
        }
    }

    private void checkOpen() throws InterruptedException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("Preparation wait cancelled");
    }

    private void pin(String id, ItchStore store) {
        versions.add(new StoreVersionLease(store));
        stores.put(id, store);
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try { catalog.releasePreparation(pending); }
        finally {
            RuntimeException failure = null;
            synchronized (this) {
                for (var version : versions) {
                    try { version.close(); }
                    catch (RuntimeException e) {
                        if (failure == null) failure = e; else failure.addSuppressed(e);
                    }
                }
                versions.clear();
            }
            if (failure != null) throw failure;
        }
    }
}
