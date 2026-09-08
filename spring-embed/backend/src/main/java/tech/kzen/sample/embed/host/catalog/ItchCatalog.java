package tech.kzen.sample.embed.host.catalog;

import tech.kzen.auto.common.data.catalog.CatalogEntry;
import tech.kzen.auto.common.data.catalog.CatalogSnapshot;
import tech.kzen.sample.embed.host.WeightedBudget;
import tech.kzen.sample.itch.day.DatedSymbolDay;
import tech.kzen.sample.itch.day.MaterializationWeight;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.store.ItchDataArea;
import tech.kzen.sample.itch.store.ItchStore;
import tech.kzen.sample.itch.store.ItchStoreBuilder;
import tech.kzen.sample.itch.store.ItchStoreException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ItchCatalog implements AutoCloseable {
    private static final int transferBufferBytes = 128 * 1024;
    private static final long progressMessageInterval = 100_000;
    private static final Duration connectionTimeout = Duration.ofSeconds(30);
    private static final Duration downloadTimeout = Duration.ofHours(6);
    private final ItchDataArea data;
    private final WeightedBudget budget;
    private final URI directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(connectionTimeout).build();
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "itch-preparation"); thread.setDaemon(true); return thread;
    });
    private final Map<String, ItchCatalogFile> files = new LinkedHashMap<>();
    private final Map<String, ItchPreparation> tasks = new LinkedHashMap<>();
    private final Map<String, ItchStore> stores = new LinkedHashMap<>();
    private final Map<String, Path> localPaths = new LinkedHashMap<>();
    private String catalogError;

    public ItchCatalog(Path root, Path configuredDay, WeightedBudget budget) {
        this(root, configuredDay, budget, NasdaqCatalog.source);
    }

    public ItchCatalog(Path root, Path configuredDay, WeightedBudget budget, URI directory) {
        this.data = new ItchDataArea(root);
        this.budget = budget;
        this.directory = directory;
        data.createDirectories();
        loadCatalog();
        if (configuredDay != null && NasdaqCatalog.date(configuredDay.getFileName().toString()) != null) {
            localPaths.put(configuredDay.getFileName().toString(), configuredDay.toAbsolutePath().normalize());
            discover(configuredDay);
        }
        scanLocal();
    }

    public CatalogSnapshot catalog(boolean refresh) {
        if (refresh) {
            try {
                var remote = NasdaqCatalog.fetch(client, directory);
                synchronized (this) {
                    remote.forEach(file -> files.put(file.id(), file));
                    catalogError = null;
                    saveCatalog();
                }
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) {
                synchronized (this) { catalogError = "Could not refresh download sources: " + e.getMessage(); }
            }
        }
        synchronized (this) {
            scanLocal();
            List<CatalogEntry> rows = files.values().stream()
                    .sorted(Comparator.comparing(ItchCatalogFile::date).thenComparing(ItchCatalogFile::id))
                    .map(this::row).toList();
            return new CatalogSnapshot(rows, catalogError);
        }
    }

    private void scanLocal() {
        try (var paths = Files.list(data.sources())) { paths.filter(Files::isRegularFile).forEach(this::discover); }
        catch (IOException e) { throw new IllegalStateException("Cannot list downloaded files", e); }
    }

    private void discover(Path path) {
        String name = path.getFileName().toString();
        LocalDate date = NasdaqCatalog.date(name);
        if (date == null || files.containsKey(name)) return;
        try { files.put(name, new ItchCatalogFile(name, date, directory.resolve(name), Files.size(path), null)); }
        catch (IOException e) { throw new IllegalStateException("Cannot inspect " + name, e); }
    }

    private Path source(ItchCatalogFile file) { return localPaths.getOrDefault(file.id(), data.sources().resolve(file.id())); }

    private boolean downloaded(ItchCatalogFile file) {
        try { return Files.isRegularFile(source(file)) && (file.size() < 0 || Files.size(source(file)) == file.size()); }
        catch (IOException e) { return false; }
    }

    private CatalogEntry row(ItchCatalogFile file) {
        boolean local = downloaded(file);
        ItchStore store = local ? freshStore(file) : null;
        ItchPreparation task = tasks.get(file.id());
        if (task != null && task.finished && (store != null || task.state.equals("ready"))) task = null;
        String state = task != null ? task.state : store != null ? "ready" : local ? "downloaded" : "missing";
        String detail = task != null ? task.detail : store != null ? "Ready for analysis" : local ? "Prepare for analysis" : "Not downloaded";
        return new CatalogEntry(file.id(), file.date().toString(), file.id(), file.url().toString(), file.size(), local,
                state, task == null ? 0 : task.bytes, detail,
                store == null ? List.of() : store.symbols().keySet().stream().sorted().toList());
    }

    private ItchStore freshStore(ItchCatalogFile file) {
        try {
            ItchStore store = stores.get(file.id());
            if (store == null) store = ItchStore.open(data.storeFor(source(file)));
            store.requireFresh(source(file));
            stores.put(file.id(), store);
            return store;
        }
        catch (ItchStoreException e) { stores.remove(file.id()); return null; }
    }

    public synchronized void prepare(List<String> ids) {
        if (ids.isEmpty()) throw new IllegalArgumentException("Select at least one date");
        List<ItchCatalogFile> selected = ids.stream().distinct().map(this::requireFile).toList();
        for (ItchCatalogFile file : selected) {
            ItchPreparation previous = tasks.get(file.id());
            if (previous != null && !previous.finished) continue;
            if (downloaded(file) && freshStore(file) != null) { tasks.remove(file.id()); continue; }
            ItchPreparation task = new ItchPreparation();
            tasks.put(file.id(), task);
            queue.submit(() -> prepare(file, task));
        }
    }

    public synchronized void cancel(List<String> ids) {
        ids.stream().map(tasks::get).filter(java.util.Objects::nonNull).forEach(ItchPreparation::cancel);
    }

    private void prepare(ItchCatalogFile file, ItchPreparation task) {
        task.started();
        try {
            checkCancelled(task);
            if (!downloaded(file)) download(file, task);
            checkCancelled(task);
            task.state = "preparing";
            task.detail = "Preparing analysis store";
            ItchStore existing;
            synchronized (this) { existing = freshStore(file); }
            if (existing == null) {
                new ItchStoreBuilder().build(source(file), data.storeFor(source(file)), count -> {
                    if (count % progressMessageInterval == 0) task.detail = "Prepared " + count + " messages";
                    return task.proceed();
                });
            }
            checkCancelled(task);
            synchronized (this) {
                if (freshStore(file) == null) throw new IllegalStateException("Prepared store is not fresh");
            }
            task.state = "ready";
            task.detail = "Ready for analysis";
        }
        catch (Exception e) {
            task.state = task.cancelled || Thread.currentThread().isInterrupted() ? "cancelled" : "failed";
            task.detail = task.state.equals("cancelled") ? "Cancelled; retry when ready" : e.getMessage();
        }
        finally { task.finished(); Thread.interrupted(); }
    }

    private void download(ItchCatalogFile file, ItchPreparation task) throws Exception {
        Path target = source(file);
        if (Files.exists(target)) throw new IllegalStateException("Existing file has an unexpected size: " + target + ". Move it aside before retrying.");
        Path partial = Files.createTempFile(data.sources(), "itch-download-", ".part");
        try {
            task.state = "downloading";
            task.detail = "Downloading " + file.id();
            var response = client.send(HttpRequest.newBuilder(file.url()).timeout(downloadTimeout).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body(); var output = Files.newOutputStream(partial)) {
                task.input(input);
                if (response.statusCode() != 200) throw new IOException("Download returned HTTP " + response.statusCode());
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[transferBufferBytes];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(task); output.write(buffer, 0, count); md5.update(buffer, 0, count); task.bytes += count;
                }
                long advertised = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if ((file.size() >= 0 && task.bytes != file.size()) || (advertised >= 0 && task.bytes != advertised))
                    throw new IOException("Download length does not match the source");
                task.state = "verifying";
                task.detail = "Verifying download";
                if (file.checksum() != null) {
                    var checksum = client.send(HttpRequest.newBuilder(file.checksum()).timeout(connectionTimeout).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (checksum.statusCode() != 200) throw new IOException("Could not fetch published checksum");
                    String actual = HexFormat.of().formatHex(md5.digest());
                    if (!checksum.body().toLowerCase(java.util.Locale.ROOT).contains(actual))
                        throw new IOException("Download checksum does not match the published checksum");
                }
            }
            task.input(null);
            checkCancelled(task);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            synchronized (this) { saveCatalog(); }
        }
        finally { Files.deleteIfExists(partial); }
    }

    private static void checkCancelled(ItchPreparation task) throws InterruptedIOException {
        if (!task.proceed()) throw new InterruptedIOException("Preparation cancelled");
    }

    private ItchCatalogFile requireFile(String id) {
        ItchCatalogFile file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Catalog file is unavailable: " + id);
        return file;
    }

    public synchronized List<ItchCatalogFile> selected(List<String> ids) {
        if (ids.isEmpty()) throw new IllegalArgumentException("Select at least one date");
        List<ItchCatalogFile> selected = ids.stream().distinct().map(this::requireFile)
                .sorted(Comparator.comparing(ItchCatalogFile::date).thenComparing(ItchCatalogFile::id)).toList();
        for (ItchCatalogFile file : selected) readyStore(file.id());
        return selected;
    }

    public synchronized ItchStore readyStore(String id) {
        ItchCatalogFile file = requireFile(id);
        ItchStore store = downloaded(file) ? freshStore(file) : null;
        if (store == null) throw new IllegalStateException("Download and prepare " + file.date() + " before running");
        return store;
    }

    public synchronized String entryForDate(String date) {
        List<String> matches = files.values().stream().filter(f -> f.date().toString().equals(date)).map(ItchCatalogFile::id).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Select a catalog entry for date " + date + ": " + matches);
        return matches.getFirst();
    }

    public DatedSymbolDay materialize(String id, String symbol) throws InterruptedException {
        ItchCatalogFile file;
        ItchStore store;
        synchronized (this) { file = requireFile(id); store = readyStore(id); }
        SymbolDay day = SymbolDay.materialize(store, store.locate(symbol), budget, MaterializationWeight.Coefficients.measured);
        return new DatedSymbolDay(file.date().toString(), symbol, file.url().toString(), day);
    }

    private void loadCatalog() {
        Path path = data.root().resolve("catalog.properties");
        if (!Files.exists(path)) return;
        Properties props = new Properties();
        try (var input = Files.newInputStream(path)) {
            props.load(input);
            for (String name : props.stringPropertyNames()) {
                if (!name.endsWith(".url")) continue;
                String id = name.substring(0, name.length() - 4);
                LocalDate date = NasdaqCatalog.date(id);
                if (date == null) continue;
                String checksum = props.getProperty(id + ".checksum");
                files.put(id, new ItchCatalogFile(id, date, URI.create(props.getProperty(name)),
                        Long.parseLong(props.getProperty(id + ".size")), checksum == null ? null : URI.create(checksum)));
            }
        }
        catch (IOException e) { throw new IllegalStateException("Cannot read saved catalog", e); }
    }

    private void saveCatalog() {
        Properties props = new Properties();
        for (ItchCatalogFile file : files.values()) {
            props.setProperty(file.id() + ".url", file.url().toString());
            props.setProperty(file.id() + ".size", Long.toString(file.size()));
            if (file.checksum() != null) props.setProperty(file.id() + ".checksum", file.checksum().toString());
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(data.root(), "catalog-", ".tmp");
            try (var output = Files.newOutputStream(temporary)) { props.store(output, "ITCH download sources"); }
            Files.move(temporary, data.root().resolve("catalog.properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) { throw new IllegalStateException("Cannot save download sources", e); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { throw new IllegalStateException("Cannot remove catalog temporary file", e); }
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        synchronized (this) { tasks.values().forEach(ItchPreparation::cancel); }
        queue.shutdown();
        if (!queue.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("ITCH preparation did not stop");
        client.close();
    }
}
