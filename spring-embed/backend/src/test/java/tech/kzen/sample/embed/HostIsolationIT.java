package tech.kzen.sample.embed;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Two workspaces in one host, adversarially: one's boot sweep, output, notation, run cancellation and shutdown
 * with a run active never touch the other's work root, documents or state; an aliased work root is refused by
 * name and rolled back; a workspace whose construction fails rolls the other back and leaves its state valid for
 * the next boot. The other workspace must remain valid after every step.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HostIsolationIT {
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    static Path temp;

    private static Path hostJar;
    private static Path home;
    private static Process host;
    private static Path hostLog;
    private static int hostPort;
    private static Path riskMarker;
    private static Path tradingCsv;


    @BeforeAll
    static void bootHost() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        try (Stream<Path> files = Files.list(target)) {
            hostJar = files.filter(p -> p.getFileName().toString().matches("kzen-sample-embed-spring-.*(?<!-tests)\\.jar"))
                    .findFirst().orElseThrow();
        }
        home = temp.resolve("home");
        tradingCsv = temp.resolve("trading-out.csv");
        Path trading = home.resolve("trading/src/main/resources/notation/main");
        Path risk = home.resolve("risk/src/main/resources/notation/main");
        Files.createDirectories(trading);
        Files.createDirectories(risk);
        Files.writeString(trading.resolve("Quick.yaml"), """
                main:
                  is: Job

                main.workers/Source:
                  is: FormulaSourceWorker
                  code: (1..5)

                main.workers/Csv:
                  is: CsvWriterWorker
                  path: '%s'
                """.formatted(tradingCsv.toString().replace('\\', '/')));
        // A run long enough to be cancelled and to be alive at shutdown: fifty million scalars through a channel.
        Files.writeString(trading.resolve("Long.yaml"), """
                main:
                  is: Job

                main.workers/Source:
                  is: FormulaSourceWorker
                  code: (1..50_000_000)

                main.workers/Preview:
                  is: PreviewWorker
                """);
        Files.writeString(risk.resolve("RiskOnly.yaml"), """
                main:
                  is: Job

                main.workers/Source:
                  is: FormulaSourceWorker
                  code: listOf("risk")

                main.workers/Preview:
                  is: PreviewWorker
                """);
        // Something in risk's work root that trading's boot sweep and runs must leave alone.
        Files.createDirectories(home.resolve("risk/work"));
        riskMarker = home.resolve("risk/work/marker.txt");
        Files.writeString(riskMarker, "risk's own");

        hostPort = freePort();
        hostLog = temp.resolve("host.log");
        host = launch(hostLog, home, hostPort, freePort(), freePort());
        awaitLog(hostLog, "2 workspace(s) started", Duration.ofSeconds(90));
        awaitLog(hostLog, "Tomcat started on port " + hostPort, Duration.ofSeconds(30));
    }


    @AfterAll
    static void stopHost() throws Exception {
        if (host == null) {
            return;
        }
        http.send(HttpRequest.newBuilder(uri("/kzen-host/shutdown")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(host.waitFor(60, TimeUnit.SECONDS), "host did not exit after shutdown");
    }


    @Test
    @Order(1)
    void bootSweepAndRunsStayInsideTheirOwnWorkRoot() throws Exception {
        assertTrue(Files.exists(riskMarker), "trading's boot sweep left risk's root alone");
        start("trading", "Quick");
        awaitIdle("trading", Duration.ofSeconds(60));
        assertTrue(Files.exists(tradingCsv));
        assertEquals("risk's own", Files.readString(riskMarker), "trading's run and its cleanup left risk's root alone");
        assertFalse(Files.exists(home.resolve("risk/work/job")), "no scratch of trading's under risk");
    }


    @Test
    @Order(2)
    void notationOfOneWorkspaceIsInvisibleToTheOther() throws Exception {
        assertTrue(get("/kzen/risk/scan?fresh=true").body().contains("main/RiskOnly.yaml"));
        assertFalse(get("/kzen/trading/scan?fresh=true").body().contains("main/RiskOnly.yaml"));
        assertFalse(get("/kzen/risk/scan?fresh=true").body().contains("main/Quick.yaml"));

        // A document written into risk's notation appears in risk's scan only.
        Files.writeString(home.resolve("risk/src/main/resources/notation/main/Later.yaml"), "main:\n  is: Job\n");
        assertTrue(get("/kzen/risk/scan?fresh=true").body().contains("main/Later.yaml"));
        assertFalse(get("/kzen/trading/scan?fresh=true").body().contains("main/Later.yaml"));
    }


    @Test
    @Order(3)
    void cancellingOneWorkspacesRunLeavesTheOtherUsable() throws Exception {
        clearTrace("trading");
        String runId = start("trading", "Long");
        Thread.sleep(500);
        assertTrue(get("/kzen/trading/logic/status").body().contains("\"state\":\"Running\""));
        start("risk", "RiskOnly");
        awaitIdle("risk", Duration.ofSeconds(60));
        HttpResponse<String> cancelled = get("/kzen/trading/logic/cancel?run=" + runId);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        awaitIdle("trading", Duration.ofSeconds(60));
        assertTrue(get("/kzen/risk/scan?fresh=true").body().contains("main/RiskOnly.yaml"), "risk still serves");
    }


    @Test
    @Order(4)
    void stoppingAWorkspaceWithARunActiveCancelsJoinsAndReleasesWhileTheOtherServes() throws Exception {
        clearTrace("trading");
        start("trading", "Long");
        Thread.sleep(500);
        assertTrue(get("/kzen/trading/logic/status").body().contains("\"state\":\"Running\""));

        HttpResponse<String> stopped = http.send(HttpRequest.newBuilder(uri("/kzen-host/workspaces/trading")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stopped.statusCode(), stopped.body());
        awaitLog(hostLog, "Workspace 'trading' stopped; work root released", Duration.ofSeconds(60));
        assertFalse(Files.readString(hostLog, StandardCharsets.UTF_8).contains("stays claimed"));
        assertEquals(503, get("/kzen/trading/index.html").statusCode());
        assertEquals(200, get("/kzen/risk/index.html").statusCode());
        assertEquals("risk's own", Files.readString(riskMarker));
        clearTrace("risk");
        start("risk", "RiskOnly");
        awaitIdle("risk", Duration.ofSeconds(60));
    }


    @Test
    @Order(5)
    void aliasedWorkRootIsRefusedByNameAndRolledBack() throws Exception {
        Path aliasHome = temp.resolve("alias-home");
        Path log = temp.resolve("alias.log");
        Path alias = aliasHome.resolve("trading").resolve("..").resolve("trading").resolve("work");
        Process failing = launch(log, aliasHome, freePort(), freePort(), freePort(),
                "--kzen.workspaces[1].work-root=" + alias);
        assertTrue(failing.waitFor(120, TimeUnit.SECONDS), "a failing boot must exit");
        assertNotEquals(0, failing.exitValue());
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("Rolled back workspace 'trading'"), text);
        assertTrue(text.toLowerCase().contains("work root"), text);
    }


    @Test
    @Order(6)
    void failedConstructionOfOneWorkspaceRollsBackTheOtherAndLeavesItsStateValid() throws Exception {
        Path failHome = temp.resolve("fail-home");
        Path tradingNotation = failHome.resolve("trading/src/main/resources/notation/main");
        Files.createDirectories(tradingNotation);
        String kept = "main:\n  is: Job\n";
        Files.writeString(tradingNotation.resolve("Kept.yaml"), kept);
        // risk's work root is an existing regular file: its context cannot even be constructed.
        Path file = temp.resolve("not-a-directory.txt");
        String fileText = "a file where a work root was expected";
        Files.writeString(file, fileText);
        Path log = temp.resolve("fail.log");
        Process failing = launch(log, failHome, freePort(), freePort(), freePort(), "--kzen.workspaces[1].work-root=" + file);
        assertTrue(failing.waitFor(120, TimeUnit.SECONDS), "a failing boot must exit");
        assertNotEquals(0, failing.exitValue());
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("Workspace 'risk': cannot create"), text);
        assertTrue(text.contains("Rolled back workspace 'trading'"), text);
        assertFalse(text.contains("stays claimed"), text);
        assertEquals(kept, Files.readString(tradingNotation.resolve("Kept.yaml")), "trading's notation is untouched");
        assertEquals(fileText, Files.readString(file), "the file that stood in the way is untouched");

        // The same home boots cleanly once risk is configured properly: nothing of the failed boot lingers.
        Path retryLog = temp.resolve("fail-retry.log");
        int port = freePort();
        Process retry = launch(retryLog, failHome, port, freePort(), freePort());
        try {
            awaitLog(retryLog, "2 workspace(s) started", Duration.ofSeconds(90));
            awaitLog(retryLog, "Tomcat started on port " + port, Duration.ofSeconds(30));
            HttpResponse<String> scan = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/kzen/trading/scan?fresh=true")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(scan.body().contains("main/Kept.yaml"), scan.body());
        }
        finally {
            http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/kzen-host/shutdown"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(retry.waitFor(60, TimeUnit.SECONDS));
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


    private static String start(String workspace, String document) throws Exception {
        HttpResponse<String> started = get("/kzen/" + workspace + "/logic/startRun?path=main%2F" + document + ".yaml&object=main");
        assertEquals(200, started.statusCode(), started.body());
        return started.body().trim();
    }


    private static void clearTrace(String workspace) throws Exception {
        awaitIdle(workspace, Duration.ofSeconds(30));
        http.send(HttpRequest.newBuilder(uri("/kzen/" + workspace
                + "/action/detached?path=auto-jvm%2Flogic%2Flogic-trace.yaml&object=LogicTraceEndpoint&action=reset-all")).build(),
                HttpResponse.BodyHandlers.ofString());
    }


    private static void awaitIdle(String workspace, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (get("/kzen/" + workspace + "/logic/status").body().contains("\"active\":null")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(workspace + " still active: " + get("/kzen/" + workspace + "/logic/status").body());
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
}
