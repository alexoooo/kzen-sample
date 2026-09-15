package tech.kzen.sample.embed.glue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.auto.common.paradigm.job.control.JobControl;
import tech.kzen.lib.common.model.location.ObjectLocation;
import tech.kzen.sample.embed.host.WeightedBudget;
import tech.kzen.sample.embed.host.catalog.ItchCatalog;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ItchSourceCursorTest {
    @TempDir Path temp;
    private static final String first = "12302019.NASDAQ_ITCH50.gz";
    private static final String second = "01302020.NASDAQ_ITCH50.gz";

    @Test void selectedBatchesHaveExactTotalsAndDoNotRetainDays() throws Exception {
        Path sources = Files.createDirectories(temp.resolve("sources"));
        SyntheticItchDay.generate(31, 20).writeTo(sources.resolve(first), true);
        SyntheticItchDay.generate(32, 20).writeTo(sources.resolve(second), true);
        WeightedBudget budget = new WeightedBudget(4L << 20);
        try (var catalog = new ItchCatalog(temp, null, budget, URI.create("http://127.0.0.1:1/"))) {
            try (var cursor = new ItchSourceCursor(catalog, List.of(second, first), List.of("AAPL", "ABSENT"))) {
                assertTrue(cursor.hasNext());
                assertEquals(2, number(cursor, "totalSymbols"));
                assertEquals(0, number(cursor, "symbols"));
                interruptWaitingRead(cursor, budget);
                assertEquals(0, number(cursor, "symbols"));
                assertEquals(0, number(cursor, "messages"));
                long messages = 0;
                while (cursor.hasNext()) {
                    try (var day = cursor.next()) { messages += day.day().messageCount(); }
                }
                assertEquals(2, number(cursor, "symbols"));
                assertEquals(messages, number(cursor, "messages"));
                assertEquals(messages, number(cursor, "totalMessages"));
                assertEquals(number(cursor, "totalBytes"), number(cursor, "bytes"));
                assertEquals("complete", ((Map<?, ?>)cursor.progress().get("itch")).get("phase"));
                assertEquals(0, budget.stats().outstandingItems());
            }
            try (var cursor = new ItchSourceCursor(catalog, List.of(first, second), List.of("AAPL"))) {
                var failPublication = new java.util.concurrent.atomic.AtomicBoolean(true);
                JobControl control = (JobControl) java.lang.reflect.Proxy.newProxyInstance(
                        JobControl.class.getClassLoader(), new Class<?>[]{JobControl.class}, (proxy, method, args) -> {
                            if (!method.getName().equals("publishProgress")) throw new UnsupportedOperationException(method.getName());
                            Map<?, ?> progress = (Map<?, ?>) ((Map<?, ?>) args[1]).get("itch");
                            if ("1".equals(progress.get("symbols")) && failPublication.getAndSet(false)) {
                                assertEquals("downstream", progress.get("phase"));
                                throw new IllegalStateException("publication interrupted");
                            }
                            return null;
                        });
                cursor.bind(control, ObjectLocation.Companion.parse("main/Progress.yaml#main.workers/Itch"));
                assertThrows(IllegalStateException.class, cursor::next);
                assertEquals(0, budget.stats().outstandingItems(), "batch was not adopted yet and must close on publication failure");
                assertEquals(0, number(cursor, "symbols"));
                try (var day = cursor.next()) { assertEquals("2019-12-30", day.date(), "failed publication must not skip the batch"); }
                assertEquals(1, number(cursor, "symbols"));
            }
        }
    }

    private static void interruptWaitingRead(ItchSourceCursor cursor, WeightedBudget budget) throws Exception {
        var finished = new java.util.concurrent.CountDownLatch(1);
        try (var hold = budget.acquire(new tech.kzen.sample.itch.day.MaterializationWeight(budget.capacityBytes(), 0));
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> {
                try (var day = cursor.next()) { fail("Admission should remain blocked"); }
                finally { finished.countDown(); }
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            try {
                while (budget.stats().waiting() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(1, budget.stats().waiting());
            }
            finally { task.cancel(true); }
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, budget.stats().interruptedWaits());
        }
    }

    @Test void runningSelectionUsesItsValidatedStoreAndANewRunRejectsChangedSource() throws Exception {
        Path source = Files.createDirectories(temp.resolve("sources")).resolve(first);
        SyntheticItchDay.generate(31, 20).writeTo(source, true);
        WeightedBudget budget = new WeightedBudget(4L << 20);
        try (var catalog = new ItchCatalog(temp, null, budget, URI.create("http://127.0.0.1:1/"))) {
            catalog.prepare(List.of(first));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!catalog.catalog(false).getEntries().getFirst().getState().equals("ready")) {
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(10);
            }
            try (var cursor = new ItchSourceCursor(catalog, List.of(first), List.of())) {
                assertTrue(cursor.hasNext());
                Files.write(source, new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);
                int symbols = 0;
                while (cursor.hasNext()) try (var day = cursor.next()) { symbols++; }
                assertEquals(4, symbols);
            }
            try (var cursor = new ItchSourceCursor(catalog, List.of(first), List.of())) {
                assertThrows(IllegalStateException.class, cursor::hasNext);
            }
            assertEquals(0, budget.stats().currentBytes());
        }
    }

    @Test void cursorDownloadsOnFirstPullAndCancellationReleasesItsRequest() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        Path fixture = temp.resolve("fixture.gz");
        SyntheticItchDay.generate(34, 20).writeTo(fixture, true);
        byte[] bytes = Files.readAllBytes(fixture);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean listing = exchange.getRequestURI().getPath().equals("/");
            byte[] body = listing ? (bytes.length + " <a href=\"" + first + "\">day</a>").getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes;
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                if (!listing) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    entered.countDown();
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                }
                else exchange.getResponseBody().write(body);
            }
        });
        server.start();
        WeightedBudget budget = new WeightedBudget(4L << 20);
        try (var catalog = new ItchCatalog(temp.resolve("data"), null, budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            catalog.catalog(true);
            try (var cursor = new ItchSourceCursor(catalog, List.of(first), List.of("AAPL"))) {
                var waiting = executor.submit(cursor::hasNext);
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                cursor.close();
                var error = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> waiting.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(InterruptedException.class, error.getCause());
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!catalog.catalog(false).getEntries().getFirst().getState().equals("cancelled") && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals("cancelled", catalog.catalog(false).getEntries().getFirst().getState());
            }
            try (var cursor = new ItchSourceCursor(catalog, List.of(first), List.of("AAPL"))) {
                assertTrue(cursor.hasNext());
                try (var day = cursor.next()) { assertEquals("AAPL", day.symbol()); }
                assertFalse(cursor.hasNext());
            }
            assertEquals(0, budget.stats().outstandingItems());
        }
        finally { server.stop(0); }
    }

    private static long number(ItchSourceCursor cursor, String key) {
        return Long.parseLong(((Map<?, ?>)cursor.progress().get("itch")).get(key).toString());
    }
}
