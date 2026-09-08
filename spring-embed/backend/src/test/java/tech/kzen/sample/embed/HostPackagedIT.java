package tech.kzen.sample.embed;

import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import tech.kzen.auto.common.objects.document.job.preview.PreviewNode;
import org.junit.jupiter.api.AfterAll;
import tech.kzen.sample.itch.synth.SyntheticItchDay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The packaged host on JDK 25 — {@code java -jar target/kzen-sample-embed-spring-*.jar} with {@code lib/}
 * beside it — driven over HTTP: two prefixed workspace UIs, their assets (gzip relayed), a fixture Job run
 * through the proxy, the run-status SSE stream arriving incrementally and its upstream closing when the
 * client leaves, one workspace stopped while the other keeps serving, and clean shutdown. Two failing boots
 * (a work root shared by both workspaces; both workspaces on one port) must roll back what was started.
 * Runs in the {@code integration-test} phase; ports are picked free at test time.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HostPackagedIT {
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static Path hostJar;

    @TempDir
    static Path temp;

    private static Process host;
    private static Path hostLog;
    private static int hostPort;
    private static int tradingPort;
    private static int riskPort;
    private static Path catalogCsv;


    @BeforeAll
    static void bootHost() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        try (Stream<Path> files = Files.list(target)) {
            hostJar = files.filter(p -> p.getFileName().toString().matches("kzen-sample-embed-spring-.*(?<!-tests)\\.jar"))
                    .findFirst().orElseThrow();
        }
        Path home = temp.resolve("home");
        Files.createDirectories(home.resolve("trading/src/main/resources/notation/main"));
        Files.writeString(home.resolve("trading/src/main/resources/notation/main/Fixture.yaml"), """
                main:
                  is: Job

                main.workers/Source:
                  is: FormulaSourceWorker
                  code: (1..3)

                main.workers/Preview:
                  is: PreviewWorker
                """);
        Path sources = Files.createDirectories(home.resolve("data/sources"));
        SyntheticItchDay.generate(41, 20).writeTo(sources.resolve("12302019.NASDAQ_ITCH50.gz"), true);
        SyntheticItchDay.generate(42, 20).writeTo(sources.resolve("01302020.NASDAQ_ITCH50.gz"), true);
        catalogCsv = temp.resolve("dated-volume.csv");
        Path riskNotation = Files.createDirectories(home.resolve("risk/src/main/resources/notation/main"));
        Files.writeString(riskNotation.resolve("Catalog.yaml"), """
                main:
                  is: Job
                main.workers/Itch:
                  is: ItchSourceWorker
                  selection:
                    - 12302019.NASDAQ_ITCH50.gz
                    - 01302020.NASDAQ_ITCH50.gz
                  symbols:
                    - AAPL
                main.workers/Volume:
                  is: DatedTradeVolumeWorker
                main.workers/Csv:
                  is: CsvWriterWorker
                  path: '%s'
                """.formatted(catalogCsv.toString().replace('\\', '/')));
        Files.writeString(riskNotation.resolve("CatalogPreview.yaml"), """
                main:
                  is: Job
                main.workers/Itch:
                  is: ItchSourceWorker
                  selection:
                    - 12302019.NASDAQ_ITCH50.gz
                    - 01302020.NASDAQ_ITCH50.gz
                  symbols:
                    - AAPL
                main.workers/Formula:
                  is: FormulaWorker
                  formula:
                    test: symbol.length
                main.workers/Next:
                  is: FormulaWorker
                  formula:
                    checked: test == symbol.length && day.isOpen()
                main.workers/Preview:
                  is: PreviewWorker
                """);
        hostPort = freePort();
        tradingPort = freePort();
        riskPort = freePort();
        hostLog = temp.resolve("host.log");
        host = launch(hostLog, home, hostPort, tradingPort, riskPort);
        awaitLog(hostLog, "2 workspace(s) started", Duration.ofSeconds(90));
        awaitLog(hostLog, "Tomcat started on port " + hostPort, Duration.ofSeconds(30));
    }


    /** Graceful shutdown through the host's own endpoint (a Windows child has no SIGTERM): workspaces, then Tomcat. */
    @AfterAll
    static void stopHost() throws Exception {
        if (host == null) {
            return;
        }
        HttpResponse<String> shutdown = http.send(HttpRequest.newBuilder(uri("/kzen-host/shutdown"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, shutdown.statusCode(), shutdown.body());
        assertTrue(host.waitFor(60, TimeUnit.SECONDS), "host did not exit after shutdown");
        assertEquals(0, host.exitValue());
        String text = Files.readString(hostLog, StandardCharsets.UTF_8);
        assertTrue(text.contains("Workspace 'trading' stopped; work root released"), text);
    }


    @Test
    @Order(1)
    void portletPageListsBothWorkspacesAndBothUisServeThroughThePrefix() throws Exception {
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("/kzen/trading/index.html"), index.body());
        assertTrue(index.body().contains("/kzen/risk/index.html"), index.body());

        for (String workspace : List.of("trading", "risk")) {
            HttpResponse<String> root = get("/kzen/" + workspace + "/");
            assertEquals(302, root.statusCode(), "kzen's / redirects to index.html, relayed not followed");
            assertEquals("index.html", root.headers().firstValue("location").orElse(""), "relative Location relayed as is");

            HttpResponse<String> ui = get("/kzen/" + workspace + "/index.html");
            assertEquals(200, ui.statusCode());
            assertTrue(ui.body().contains("kzen-auto-js.js"), ui.body().substring(0, Math.min(300, ui.body().length())));

            HttpResponse<byte[]> bundle = http.send(HttpRequest.newBuilder(uri("/kzen/" + workspace + "/static/kzen-auto-js.js"))
                    .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, bundle.statusCode());
            assertEquals("gzip", bundle.headers().firstValue("content-encoding").orElse(""), "gzip relayed, not re-encoded");
            try (InputStream inflated = new GZIPInputStream(new java.io.ByteArrayInputStream(bundle.body()))) {
                assertTrue(inflated.readAllBytes().length > 500_000, "the JS bundle inflates to megabytes");
            }
        }
        HttpResponse<String> missing = get("/kzen/trading/no-such-path");
        assertEquals(404, missing.statusCode(), "upstream 404 relayed");
        HttpResponse<String> unknownWorkspace = get("/kzen/nowhere/index.html");
        assertEquals(503, unknownWorkspace.statusCode());
    }


    @Test
    @Order(2)
    void fixtureJobRunsThroughTheProxyAndStatusStreamsIncrementally() throws Exception {
        HttpResponse<String> scan = get("/kzen/trading/scan");
        assertEquals(200, scan.statusCode());
        assertTrue(scan.body().contains("main/Fixture.yaml"), "the workspace's own notation is served: " + scan.body());

        // A PUT form body must reach the upstream as bytes (Boot's FormContentFilter would have consumed it).
        HttpResponse<String> batch = http.send(HttpRequest.newBuilder(uri("/kzen/trading/notation-batch"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString("path=main%2FFixture.yaml")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, batch.statusCode());
        assertTrue(batch.body().contains("FormulaSourceWorker"), "the PUT body was relayed: " + batch.body());

        HttpResponse<String> started = get("/kzen/trading/logic/startRun?path=main%2FFixture.yaml&object=main");
        assertEquals(200, started.statusCode(), started.body());
        assertFalse(started.body().isBlank(), "the run id");

        // The status stream: the first event (the current status) arrives right away, well before kzen's
        // 15 s heartbeat — which is the incremental delivery the synchronous relay exists for.
        long before = System.nanoTime();
        HttpResponse<InputStream> events = http.send(HttpRequest.newBuilder(uri("/kzen/trading/logic/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, events.statusCode());
        String firstLine;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(events.body(), StandardCharsets.UTF_8))) {
            firstLine = reader.readLine();
            while (firstLine != null && firstLine.isBlank()) {
                firstLine = reader.readLine();
            }
        }
        long millis = (System.nanoTime() - before) / 1_000_000;
        assertTrue(firstLine != null && (firstLine.startsWith("data:") || firstLine.startsWith("event:")), "first SSE line: " + firstLine);
        assertTrue(millis < 5_000, "first status event took " + millis + " ms");

        // The client is gone (the reader closed the stream); the proxy's copy loop ends at its next write —
        // kzen's heartbeat at the latest — and the upstream stream closes with it.
        awaitStat("activeStreams", 0, Duration.ofSeconds(40));
        assertTrue(readStat("clientDisconnects") >= 1);

        HttpResponse<String> status = get("/kzen/trading/logic/status");
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"epoch\""), "the run advanced the status epoch: " + status.body());
    }


    @Test
    @Order(3)
    void catalogPreparesAndRunsDatedTypedAnalysisThroughTheProxy() throws Exception {
        String action = "/kzen/risk/action/detached?path=auto-jvm%2Fdatasource%2Fcatalog-source.yaml"
                + "&object=CatalogActions&source=main%2FCatalog.yaml%23main.workers%2FItch";
        HttpResponse<String> listed = get(action + "&action=list");
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains("2019-12-30"), listed.body());
        String selection = java.net.URLEncoder.encode(
                "[\"12302019.NASDAQ_ITCH50.gz\",\"01302020.NASDAQ_ITCH50.gz\"]", StandardCharsets.UTF_8);
        String validator = "/kzen/risk/action/detached?path=auto-jvm%2Fjob%2Fjob-jvm.yaml&object=JobValidator&host=main%2FCatalog.yaml";
        String beforePreparation = get(validator).body();
        assertTrue(beforePreparation.contains("DatedSymbolDay"), beforePreparation);
        assertFalse(beforePreparation.contains("before running"), beforePreparation);
        HttpResponse<String> preparing = get(action + "&action=prepare&selection=" + selection);
        assertEquals(200, preparing.statusCode(), preparing.body());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String ready;
        do {
            ready = get(action + "&action=list").body();
            if (ready.contains("Ready for analysis") && !ready.contains("preparing") && !ready.contains("queued")) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertTrue(ready.contains("Ready for analysis"), ready);
        HttpResponse<String> validation = get("/kzen/risk/action/detached?path=auto-jvm%2Fjob%2Fjob-jvm.yaml&object=JobValidator&host=main%2FCatalog.yaml");
        assertTrue(validation.body().contains("DatedSymbolDay"), validation.body());
        HttpResponse<String> run = get("/kzen/risk/logic/startRun?path=main%2FCatalog.yaml&object=main");
        assertEquals(200, run.statusCode(), run.body());
        deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while ((!Files.exists(catalogCsv) || Files.readAllLines(catalogCsv).size() < 3) && System.nanoTime() < deadline) Thread.sleep(50);
        List<String> rows = Files.readAllLines(catalogCsv);
        assertEquals(3, rows.size(), String.join("\n", rows));
        assertEquals("date,symbol,tradeEvents,shares", rows.getFirst());
        var first = SyntheticItchDay.generate(41, 20).expectedTrades().get("AAPL");
        var second = SyntheticItchDay.generate(42, 20).expectedTrades().get("AAPL");
        assertEquals("2019-12-30,AAPL," + first.tradeEvents() + "," + first.shares(), rows.get(1));
        assertEquals("2020-01-30,AAPL," + second.tradeEvents() + "," + second.shares(), rows.get(2));
        assertEquals(200, get("/host/book/AAPL?date=2019-12-30&levels=1").statusCode());
        awaitStat("activeStreams", 0, Duration.ofSeconds(5));
        String budget = get("/kzen-host/budget").body();
        assertTrue(budget.contains("\"outstandingItems\":0"), budget);
        assertTrue(budget.contains("\"leaks\":0"), budget);
        deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!get("/kzen/risk/logic/status").body().contains("\"active\":null") && System.nanoTime() < deadline) Thread.sleep(50);
        get("/kzen/risk/action/detached?path=auto-jvm%2Flogic%2Flogic-trace.yaml&object=LogicTraceEndpoint&action=reset-all");
        HttpResponse<String> previewRun = get("/kzen/risk/logic/startRun?path=main%2FCatalogPreview.yaml&object=main");
        assertEquals(200, previewRun.statusCode(), previewRun.body());
        deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String status;
        do {
            status = get("/kzen/risk/logic/status").body();
            if (status.contains("\"active\":null")) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertTrue(status.contains("\"active\":null"), status);
        String trace = get("/kzen/risk/action/detached?path=auto-jvm%2Flogic%2Flogic-trace.yaml"
                + "&object=LogicTraceEndpoint&action=lookup-run&query=%2F&run="
                + java.net.URLEncoder.encode(previewRun.body().trim().replace("\"", ""), StandardCharsets.UTF_8)).body();
        assertTrue(trace.contains("previewItems"), trace);
        List<PreviewNode> previewItems = new ArrayList<>();
        collectPreviewItems(Json.Default.parseToJsonElement(trace), previewItems);
        assertEquals(2, previewItems.size(), trace);
        for (PreviewNode item : previewItems) {
            assertEquals("4", previewField(item, "test").getText());
            assertEquals("true", previewField(item, "checked").getText());
            assertEquals("AAPL", previewField(item, "symbol").getText());
            assertEquals("true", previewField(previewField(item, "day"), "open").getText());
        }
        assertFalse(trace.contains("Native metadata path does not exist"), trace);
        assertTrue(trace.contains("2019-12-30") && trace.contains("2020-01-30"), trace);
        assertTrue(trace.contains("open") && trace.contains("true"), trace);
        assertFalse(trace.contains("scalar is valid only") || trace.contains("Could not read"), trace);
        budget = get("/kzen-host/budget").body();
        assertTrue(budget.contains("\"outstandingItems\":0"), budget);
        assertTrue(budget.contains("\"leaks\":0"), budget);

    }


    private static void collectPreviewItems(JsonElement element, List<PreviewNode> items) {
        if (element instanceof JsonObject object) {
            for (var entry : object.entrySet()) {
                if (entry.getKey().equals("previewItems")) {
                    JsonArray array = (JsonArray) ((JsonObject) entry.getValue()).get("value");
                    for (JsonElement item : array) {
                        items.add(PreviewNode.Companion.decode(((JsonPrimitive) item).getContent()));
                    }
                } else {
                    collectPreviewItems(entry.getValue(), items);
                }
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement child : array) collectPreviewItems(child, items);
        }
    }

    private static PreviewNode previewField(PreviewNode item, String name) {
        return item.getChildren().stream().filter(child -> child.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + name + " in " + item));
    }


    @Test
    @Order(4)
    void stoppingOneWorkspaceLeavesTheOtherServing() throws Exception {
        HttpResponse<String> stopped = http.send(HttpRequest.newBuilder(uri("/kzen-host/workspaces/risk")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stopped.statusCode(), stopped.body());
        awaitLog(hostLog, "Workspace 'risk' stopped; work root released", Duration.ofSeconds(30));

        assertEquals(503, get("/kzen/risk/index.html").statusCode());
        assertEquals(200, get("/kzen/trading/index.html").statusCode());
        assertEquals(404, http.send(HttpRequest.newBuilder(uri("/kzen-host/workspaces/risk")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "a second stop finds nothing to stop");
        try (ServerSocket reclaimed = new ServerSocket(riskPort)) {
            assertTrue(reclaimed.isBound(), "the stopped workspace's port is free again");
        }
    }


    @Test
    @Order(5)
    void secondWorkspaceSharingTheFirstsWorkRootFailsByNameAndRollsBack() throws Exception {
        Path home = temp.resolve("same-root");
        Path log = temp.resolve("same-root.log");
        Process failing = launch(log, home, freePort(), freePort(), freePort(),
                "--kzen.workspaces[1].work-root=" + home.resolve("trading/work"));
        assertTrue(failing.waitFor(120, TimeUnit.SECONDS), "a failing boot must exit");
        assertNotEquals(0, failing.exitValue());
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("Rolled back workspace 'trading'"), text);
        assertTrue(text.contains("Workspace 'trading' stopped; work root released"), text);
        assertTrue(text.toLowerCase().contains("work root"), "the failure names the root: " + text);
        assertFalse(text.contains("2 workspace(s) started"), text);
    }


    @Test
    @Order(6)
    void secondWorkspaceOnTheFirstsPortFailsToBindAndRollsBack() throws Exception {
        Path home = temp.resolve("same-port");
        Path log = temp.resolve("same-port.log");
        int shared = freePort();
        Process failing = launch(log, home, freePort(), shared, shared);
        assertTrue(failing.waitFor(120, TimeUnit.SECONDS), "a failing boot must exit");
        assertNotEquals(0, failing.exitValue());
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("Workspace 'risk': server did not start on 127.0.0.1:" + shared), text);
        assertTrue(text.contains("Rolled back workspace 'trading'"), text);
        assertTrue(text.contains("Workspace 'trading' stopped; work root released"), text);
        try (ServerSocket reclaimed = new ServerSocket(shared)) {
            assertTrue(reclaimed.isBound(), "nothing keeps the port after the rollback");
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static Process launch(Path log, Path home, int port, int trading, int risk, String... extra) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElseThrow(),
                "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                "-jar", hostJar.toString(),
                "--server.port=" + port,
                "--kzen.home=" + home,
                "--kzen.host.data-root=" + home.resolve("data"),
                "--kzen.workspaces[0].name=trading", "--kzen.workspaces[0].port=" + trading,
                "--kzen.workspaces[1].name=risk", "--kzen.workspaces[1].port=" + risk));
        command.addAll(List.of(extra));
        return new ProcessBuilder(command)
                .directory(temp.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }


    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }


    private static void awaitLog(Path log, String needle, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log, StandardCharsets.UTF_8).contains(needle)) {
                return;
            }
            if (host != null && !host.isAlive()) {
                break;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Did not see '" + needle + "' in " + log + ":\n" + Files.readString(log, StandardCharsets.UTF_8));
    }


    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + hostPort + path);
    }


    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }


    private static long readStat(String name) throws Exception {
        String json = get("/kzen-host/stats").body();
        int i = json.indexOf("\"" + name + "\":");
        assertTrue(i >= 0, json);
        int start = i + name.length() + 3;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }


    private static void awaitStat(String name, long expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long last = -1;
        while (System.nanoTime() < deadline) {
            last = readStat(name);
            if (last == expected) return;
            Thread.sleep(500);
        }
        assertEquals(expected, last, "stat " + name);
    }
}
