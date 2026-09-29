package tech.kzen.sample.embed.host.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.embed.catalog.CatalogEntry;
import tech.kzen.sample.embed.host.WeightedBudget;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ItchCatalogTest {
    private static final String first = "12302019.NASDAQ_ITCH50.gz";
    private static final String second = "01302020.NASDAQ_ITCH50.gz";
    @TempDir Path temp;

    @Test void preparesExistingDaysAndSharesBudgetWithoutRedownloading() throws Exception {
        Path sources = Files.createDirectories(temp.resolve("sources"));
        SyntheticItchDay.generate(31, 20).writeTo(sources.resolve(first), true);
        SyntheticItchDay.generate(32, 20).writeTo(sources.resolve(second), true);
        WeightedBudget budget = new WeightedBudget(4L << 20);
        try (var catalog = new ItchCatalog(temp, null, budget, URI.create("http://127.0.0.1:1/"))) {
            assertEquals(2, catalog.catalog(false).getEntries().size());
            assertEquals("downloaded", entry(catalog, first).getState());
            assertThrows(IllegalStateException.class, () -> catalog.selected(List.of(first)));
            catalog.prepare(List.of(second, first));
            catalog.prepare(List.of(first));
            await(catalog, first, "ready"); await(catalog, second, "ready");
            assertEquals(first, catalog.selected(List.of(second, first)).files().getFirst().id());
            Path pointer = temp.resolve("stores").resolve(first + ".store/current");
            var modified = Files.getLastModifiedTime(pointer);
            catalog.prepare(List.of(first));
            assertEquals(modified, Files.getLastModifiedTime(pointer));
            try (var day = catalog.materialize(first, "AAPL")) {
                assertEquals("2019-12-30", day.date());
                assertTrue(day.day().isOpen());
                assertEquals(1, budget.stats().outstandingItems());
            }
            assertEquals(0, budget.stats().outstandingItems());
            assertEquals(budget.stats().acquisitions(), budget.stats().releases());
            assertNotNull(catalog.catalog(true).getError());
            assertTrue(entry(catalog, first).getDownloaded());
            assertEquals("ready", entry(catalog, first).getState());
        }
    }

    @Test void downloadsOnceVerifiesAndPersistsCatalog() throws Exception {
        Path fixture = temp.resolve("fixture.gz");
        SyntheticItchDay.generate(34, 20).writeTo(fixture, true);
        byte[] bytes = Files.readAllBytes(fixture);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        AtomicInteger downloads = new AtomicInteger();
        HttpServer server = server(bytes, checksum, downloads, null);
        Path data = temp.resolve("data");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            try (var catalog = new ItchCatalog(data, null, new WeightedBudget(4L << 20), base)) {
                catalog.catalog(true);
                catalog.prepare(List.of(first)); catalog.prepare(List.of(first));
                await(catalog, first, "ready");
                assertEquals(1, downloads.get());
                assertArrayEquals(bytes, Files.readAllBytes(data.resolve("sources").resolve(first)));
            }
            try (var restored = new ItchCatalog(data, null, new WeightedBudget(4L << 20), base)) {
                assertEquals("ready", entry(restored, first).getState());
                assertEquals(base.resolve(first).toString(), entry(restored, first).getSourceUrl());
            }
        }
        finally { server.stop(0); }
    }

    @Test void corruptDownloadNeverBecomesDownloadedAndCanBeRetried() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        HttpServer server = server(bytes, "00000000000000000000000000000000", new AtomicInteger(), null);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true); catalog.prepare(List.of(first)); await(catalog, first, "failed");
            assertFalse(entry(catalog, first).getDownloaded());
            assertTrue(entry(catalog, first).getDetail().contains("checksum"));
            assertFalse(Files.exists(temp.resolve("data/sources").resolve(first)));
            catalog.prepare(List.of(first)); await(catalog, first, "failed");
        }
        finally { server.stop(0); }
    }

    @Test void cancellationLeavesNoCompletedOrPartialDownload() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        HttpServer server = server(new byte[1024 * 1024], "", new AtomicInteger(), entered);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true); catalog.prepare(List.of(first));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            catalog.cancel(List.of(first)); await(catalog, first, "cancelled");
            assertFalse(entry(catalog, first).getDownloaded());
            try (var files = Files.list(temp.resolve("data/sources"))) { assertEquals(0, files.count()); }
        }
        finally { server.stop(0); }
    }

    @Test void runRequestsShareDownloadsAndReleaseOnlyTheirOwnInterest() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        HttpServer server = validServer(downloads, entered);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true);
            try (var firstRun = catalog.requestPreparation(List.of(first));
                 var secondRun = catalog.requestPreparation(List.of(first))) {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                firstRun.close();
                var phases = new java.util.ArrayList<String>();
                assertEquals(1, secondRun.await(p -> phases.add(p.phase())).stores().size());
                assertTrue(phases.contains("downloading"), phases.toString());
                assertEquals(1, downloads.get());
            }
            try (var warm = catalog.requestPreparation(List.of(first))) {
                assertEquals(1, warm.await(p -> fail("Fresh data needs no preparation")).stores().size());
                assertEquals(1, downloads.get());
            }
        }
        finally { server.stop(0); }
    }

    @Test void lastRunCancellationCanBeRetriedImmediately() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger downloads = new AtomicInteger();
        HttpServer server = validServer(downloads, entered);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true);
            try (var cancelled = catalog.requestPreparation(List.of(first))) {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                cancelled.close();
                assertThrows(InterruptedException.class, () -> cancelled.await(p -> {}));
                try (var retry = catalog.requestPreparation(List.of(first))) {
                    assertEquals(1, retry.await(p -> {}).stores().size());
                }
            }
            assertEquals(2, downloads.get());
            try (var files = Files.list(temp.resolve("data/sources"))) { assertEquals(1, files.count()); }
        }
        finally { server.stop(0); }
    }

    @Test void manualPreparationSurvivesRunCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        HttpServer server = validServer(new AtomicInteger(), entered);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true);
            try (var run = catalog.requestPreparation(List.of(first))) {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                catalog.prepare(List.of(first));
                run.close();
                await(catalog, first, "ready");
            }
        }
        finally { server.stop(0); }
    }

    @Test void automaticPreparationReportsDownloadFailureWithDateAndCause() throws Exception {
        HttpServer server = server(new byte[]{1, 2, 3}, "bad-checksum", new AtomicInteger(), null);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true);
            try (var run = catalog.requestPreparation(List.of(first))) {
                var error = assertThrows(IllegalStateException.class, () -> run.await(p -> {}));
                assertTrue(error.getMessage().contains("2019-12-30"));
                assertTrue(error.getMessage().contains("checksum"));
                assertNotNull(error.getCause());
            }
            try (var files = Files.list(temp.resolve("data/sources"))) { assertEquals(0, files.count()); }
        }
        finally { server.stop(0); }
    }

    @Test void automaticPreparationRebuildsObsoleteStoreWithoutNetwork() throws Exception {
        Path source = Files.createDirectories(temp.resolve("sources")).resolve(first);
        SyntheticItchDay.generate(34, 20).writeTo(source, true);
        Path store = temp.resolve("stores").resolve(first + ".store");
        new tech.kzen.sample.itch.store.ItchStoreBuilder().build(source, store);
        String previous = Files.readString(store.resolve("current")).trim();
        Path manifest = store.resolve(previous).resolve("manifest.properties");
        Files.writeString(manifest, Files.readString(manifest).replace("formatVersion=2", "formatVersion=1"));
        try (var catalog = new ItchCatalog(temp, null, new WeightedBudget(4L << 20), URI.create("http://127.0.0.1:1/"));
             var request = catalog.requestPreparation(List.of(first))) {
            assertEquals(1, request.await(p -> {}).stores().size());
            assertNotEquals(previous, Files.readString(store.resolve("current")).trim());
        }
    }

    @Test void runRequestPinsCompletedStoreBeforeItsWaiterConsumesIt() throws Exception {
        Path source = Files.createDirectories(temp.resolve("sources")).resolve(first);
        SyntheticItchDay.generate(34, 20).writeTo(source, true);
        Path store = temp.resolve("stores").resolve(first + ".store");
        try (var catalog = new ItchCatalog(temp, null, new WeightedBudget(4L << 20), URI.create("http://127.0.0.1:1/"));
             var request = catalog.requestPreparation(List.of(first))) {
            await(catalog, first, "ready");
            String version = Files.readString(store.resolve("current")).trim();
            new tech.kzen.sample.itch.store.ItchStoreBuilder().build(source, store);
            assertNotEquals(version, Files.readString(store.resolve("current")).trim());
            assertTrue(Files.isDirectory(store.resolve(version)));
            assertFalse(request.await(p -> {}).stores().get(first).isCurrent());
        }
    }

    private HttpServer validServer(AtomicInteger downloads, CountDownLatch entered) throws Exception {
        Path fixture = temp.resolve("fixture.gz");
        SyntheticItchDay.generate(34, 20).writeTo(fixture, true);
        byte[] bytes = Files.readAllBytes(fixture);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        return server(bytes, checksum, downloads, entered);
    }

    @Test void cancellationStopsQueuedWorkAndExplicitCancellationFailsActiveWaiter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        HttpServer server = validServer(new AtomicInteger(), entered);
        Path data = temp.resolve("data");
        SyntheticItchDay.generate(35, 20).writeTo(Files.createDirectories(data.resolve("sources")).resolve(second), true);
        try (var catalog = new ItchCatalog(data, null, new WeightedBudget(4L << 20),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            catalog.catalog(true);
            try (var active = catalog.requestPreparation(List.of(first));
                 var queued = catalog.requestPreparation(List.of(second))) {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                queued.close();
                catalog.cancel(List.of(first));
                var error = assertThrows(IllegalStateException.class, () -> active.await(p -> {}));
                assertTrue(error.getMessage().contains("2019-12-30"));
                await(catalog, second, "cancelled");
                assertFalse(Files.exists(data.resolve("stores").resolve(second + ".store/current")));
            }
        }
        finally { server.stop(0); }
    }

    private HttpServer server(byte[] bytes, String checksum, AtomicInteger downloads, CountDownLatch entered) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.equals("/")) body = (bytes.length + " <a href=\"" + first + "\">day</a> <a href=\"" + first + ".md5sum\">sum</a>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else if (path.endsWith("md5sum")) body = checksum.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else { downloads.incrementAndGet(); body = bytes; }
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                if (entered != null && path.equals("/" + first)) {
                    exchange.getResponseBody().write(body, 0, 1); exchange.getResponseBody().flush(); entered.countDown();
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                }
                else exchange.getResponseBody().write(body);
            }
        });
        server.start(); return server;
    }

    private static CatalogEntry entry(ItchCatalog catalog, String id) {
        return catalog.catalog(false).getEntries().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }
    private static void await(ItchCatalog catalog, String id, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!entry(catalog, id).getState().equals(state) && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(state, entry(catalog, id).getState(), entry(catalog, id).getDetail());
    }
}
