package tech.kzen.sample.embed;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tech.kzen.sample.embed.host.HostDay;
import tech.kzen.sample.embed.host.WeightedBudget;
import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.day.SymbolDay;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The governed host route on the packaged host over a synthetic day: the host's own report, the plugin's raw
 * route and the {@code @Service}-fed host route reach one tally; every symbol-day materializes under the shared
 * budget and returns its lease; a host hold makes a workspace's Job and a host query wait and both progress
 * when it is released; a run cancelled while waiting acquires nothing; an over-capacity budget refuses before
 * waiting; an accumulator that retains owned symbol-days under a one-day budget stalls with a warning naming it
 * (never a verdict) and the same accumulator over scalar rows completes. Counters are read from
 * {@code /kzen-host/budget}; no leak is ever reported.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HostGovernedIT {
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final long seed = 20260905L;
    private static final long budgetBytes = 4L << 20;

    @TempDir
    static Path temp;

    private static Path hostJar;
    private static Path home;
    private static Path dayFile;
    private static SyntheticItchDay synthetic;
    private static Process host;
    private static Path hostLog;
    private static int hostPort;
    private static Path hostRouteCsv;
    private static Path rawRouteCsv;
    private static long oneDayBudget;
    private static long largestBatchWeight;


    @BeforeAll
    static void bootHost() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        try (Stream<Path> files = Files.list(target)) {
            hostJar = files.filter(p -> p.getFileName().toString().matches("kzen-sample-embed-spring-.*(?<!-tests)\\.jar"))
                    .findFirst().orElseThrow();
        }
        home = temp.resolve("home");
        dayFile = temp.resolve("synthetic-day.itch");
        synthetic = SyntheticItchDay.generate(seed, 20);
        synthetic.writeTo(dayFile, false);
        oneDayBudget = largestSymbolDayWeight() * 3 / 2;
        hostRouteCsv = temp.resolve("host-route.csv");
        rawRouteCsv = temp.resolve("raw-route.csv");

        Path notation = home.resolve("trading/src/main/resources/notation/main");
        Files.createDirectories(notation);
        Files.writeString(notation.resolve("HostRoute.yaml"), """
                main:
                  is: Job

                main.workers/Host:
                  is: HostSymbolDaySourceWorker

                main.workers/TradeVolume:
                  is: SymbolDayTradeVolumeWorker

                main.workers/Csv:
                  is: CsvWriterWorker
                  path: '%s'
                """.formatted(hostRouteCsv.toString().replace('\\', '/')));
        Files.writeString(notation.resolve("RawRoute.yaml"), """
                main:
                  is: Job

                main.workers/Messages:
                  is: FormulaSourceWorker
                  code: 'tech.kzen.sample.itch.wire.ItchReader(java.nio.file.Path.of("%s"))'

                main.workers/TradeVolume:
                  is: ItchTradeVolumeWorker

                main.workers/Csv:
                  is: CsvWriterWorker
                  path: '%s'
                """.formatted(dayFile.toString().replace('\\', '/'), rawRouteCsv.toString().replace('\\', '/')));

        hostPort = freePort();
        hostLog = temp.resolve("host.log");
        host = launch(hostLog, home, hostPort, freePort(), freePort(), budgetBytes);
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
    void hostReportAndBookQueryRunUnderTheBudget() throws Exception {
        HttpResponse<String> trades = get("/host/trades");
        assertEquals(200, trades.statusCode(), trades.body());
        assertEquals(expectedTally(), tallyOfJson(trades.body()));
        HttpResponse<String> book = get("/host/book/" + SyntheticItchDay.aapl + "?levels=2");
        assertEquals(200, book.statusCode(), book.body());
        assertTrue(book.body().contains("\"symbol\":\"" + SyntheticItchDay.aapl + "\""), book.body());
        assertEquals(2, stat("acquisitions"), "the book query reserves one batch and one graph");
        assertEquals(0, stat("outstandingItems"));
        assertEquals(0, stat("leaks"));
    }


    @Test
    @Order(2)
    void hostRouteRawRouteAndHostReportAgreeOnTheTally() throws Exception {
        runToCompletion("HostRoute", hostRouteCsv, Duration.ofSeconds(60));
        SortedMap<String, List<Long>> viaHost = tallyOfCsv(hostRouteCsv);
        viaHost.remove(SyntheticItchDay.quiet);
        assertEquals(expectedTally(), viaHost, "host route (live @Service loader, fresh symbol-days)");

        clearTrace();
        runToCompletion("RawRoute", rawRouteCsv, Duration.ofSeconds(60));
        assertEquals(expectedTally(), tallyOfCsv(rawRouteCsv), "raw route (plain library expression)");

        assertTrue(stat("acquisitions") >= 1 + synthetic.symbolsByLocate().size(), "every symbol-day was admitted");
        assertEquals(stat("acquisitions"), stat("releases"), "every lease returned");
        assertEquals(0, stat("outstandingItems"));
        assertEquals(0, stat("currentNativeBytes"));
        assertEquals(0, stat("leaks"));
    }


    @Test
    @Order(3)
    void heldBudgetMakesWorkspaceAndHostWaitAndBothProgressWhenReleased() throws Exception {
        clearTrace();
        Files.deleteIfExists(hostRouteCsv);
        long hold = hold(budgetBytes - 1024);
        long waitsBefore = stat("waits");
        start("HostRoute");
        CompletableFuture<HttpResponse<String>> hostQuery = CompletableFuture.supplyAsync(() -> {
            try {
                return get("/host/book/" + SyntheticItchDay.msft + "?levels=1");
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitStat("waiting", 2, Duration.ofSeconds(30));
        assertTrue(stat("waits") >= waitsBefore + 2);
        assertTrue(get("/kzen/trading/logic/status").body().contains("\"state\":\"Running\""),
                "the Job is blocked in its first pull while the budget is held");
        assertTrue(!hostQuery.isDone(), "the host query waits too");

        release(hold);
        awaitLogicIdle(Duration.ofSeconds(60));
        SortedMap<String, List<Long>> viaHost = tallyOfCsv(hostRouteCsv);
        viaHost.remove(SyntheticItchDay.quiet);
        assertEquals(expectedTally(), viaHost, "the Job progressed to completion once the lease returned");
        assertEquals(200, hostQuery.get(60, TimeUnit.SECONDS).statusCode());
        awaitStat("outstandingItems", 0, Duration.ofSeconds(30));
        assertEquals(0, stat("waiting"));
        assertEquals(0, stat("leaks"));
    }


    @Test
    @Order(4)
    void runCancelledWhileWaitingAcquiresNothing() throws Exception {
        clearTrace();
        Files.deleteIfExists(hostRouteCsv);
        long hold = hold(budgetBytes - 1024);
        long acquisitionsBefore = stat("acquisitions");
        String runId = start("HostRoute");
        awaitStat("waiting", 1, Duration.ofSeconds(30));

        HttpResponse<String> cancelled = get("/kzen/trading/logic/cancel?run=" + runId);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        awaitStat("waiting", 0, Duration.ofSeconds(30));
        awaitLogicIdle(Duration.ofSeconds(30));
        assertTrue(stat("interruptedWaits") >= 1, "the blocked acquire was interrupted by the cancel");
        assertEquals(acquisitionsBefore, stat("acquisitions"), "nothing was admitted");
        assertEquals(1, stat("outstandingItems"), "only the host's hold");
        // The writer may have opened its file at start; no symbol-day row was ever produced.
        assertTrue(!Files.exists(hostRouteCsv) || Files.readAllLines(hostRouteCsv).size() <= 1, "no rows written");
        release(hold);
        assertEquals(0, stat("outstandingItems"));
        assertEquals(0, stat("leaks"));
    }


    @Test
    @Order(5)
    void overCapacityBudgetRefusesBeforeWaiting() throws Exception {
        Path log = temp.resolve("tiny.log");
        int port = freePort();
        Process tiny = launch(log, temp.resolve("tiny-home"), port, freePort(), freePort(), 4096);
        try {
            awaitLog(log, "Tomcat started on port " + port, Duration.ofSeconds(90));
            HttpResponse<String> refused = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/host/book/" + SyntheticItchDay.aapl)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("more than the budget can ever admit"), refused.body());
            HttpResponse<String> budget = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/kzen-host/budget")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(budget.body().contains("\"waits\":0"), budget.body());
            assertTrue(budget.body().contains("\"acquisitions\":0"), budget.body());
        }
        finally {
            http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/kzen-host/shutdown"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(tiny.waitFor(60, TimeUnit.SECONDS));
        }
    }


    @Test
    @Order(6)
    void accumulatorRetainingOwnedSymbolDaysStallsWithAWarningAndTheSameAccumulatorOverScalarRowsCompletes() throws Exception {
        Path log = temp.resolve("stall.log");
        Path stallHome = temp.resolve("stall-home");
        Path notation = stallHome.resolve("trading/src/main/resources/notation/main");
        Files.createDirectories(notation);
        // Sort keeps every element it receives until end of stream: over owned symbol-days it retains their
        // leases, and under a budget that admits one large day the source's next pull can never be served.
        Files.writeString(notation.resolve("SortDays.yaml"), """
                main:
                  is: Job

                main.workers/Host:
                  is: HostSymbolDaySourceWorker

                main.workers/Sort:
                  is: SortWorker
                  sort:
                    "0|symbol": true

                main.workers/Preview:
                  is: PreviewWorker
                """);
        Path sortedCsv = temp.resolve("sorted-route.csv");
        Files.writeString(notation.resolve("SortedVolume.yaml"), """
                main:
                  is: Job

                main.workers/Host:
                  is: HostSymbolDaySourceWorker

                main.workers/TradeVolume:
                  is: SymbolDayTradeVolumeWorker

                main.workers/Sort:
                  is: SortWorker
                  sort:
                    "0|symbol": true

                main.workers/Csv:
                  is: CsvWriterWorker
                  path: '%s'
                """.formatted(sortedCsv.toString().replace('\\', '/')));
        int port = freePort();
        Process stall = launch(log, stallHome, port, freePort(), freePort(), oneDayBudget);
        try {
            awaitLog(log, "Tomcat started on port " + port, Duration.ofSeconds(90));
            String base = "http://127.0.0.1:" + port;
            long held = hold(base, oneDayBudget - largestBatchWeight * 3 / 2);

            HttpResponse<String> started = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/kzen/trading/logic/startRun?path=main%2FSortDays.yaml&object=main")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, started.statusCode(), started.body());
            String runId = started.body().trim();
            awaitLog(log, "stalled: No progress while owned natives are held", Duration.ofSeconds(60));
            String text = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(text.contains("workers/Sort: "), "the Sort is named as the holder:\n" + text);
            assertEquals(1, stat(base, "waiting"), "the source waits for a permit only the Sort could return");
            assertTrue(stat(base, "outstandingItems") >= 1, "the retained day holds its lease");
            assertEquals(0, stat(base, "leaks"));
            String status = http.send(HttpRequest.newBuilder(URI.create(base + "/kzen/trading/logic/status")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(status.contains("\"state\":\"Running\""), "a warning, not a verdict: " + status);

            HttpResponse<String> cancelled = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/kzen/trading/logic/cancel?run=" + runId)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cancelled.statusCode(), cancelled.body());
            awaitIdle(base, Duration.ofSeconds(60));
            release(base, held);
            awaitStat(base, "outstandingItems", 0, Duration.ofSeconds(30));
            assertEquals(stat(base, "acquisitions"), stat(base, "releases"), "cancel returned every retained lease");
            assertEquals(0, stat(base, "waiting"));
            assertEquals(0, stat(base, "leaks"));
            int stallWarnings = countOf(Files.readString(log, StandardCharsets.UTF_8), "stalled: No progress");

            // Recovery: the same accumulator over the scalar projection of each day — the Worker's rows are not
            // owned, so the Sort retains nothing the budget cares about and the run completes under one day.
            http.send(HttpRequest.newBuilder(URI.create(base
                    + "/kzen/trading/action/detached?path=auto-jvm%2Flogic%2Flogic-trace.yaml&object=LogicTraceEndpoint&action=reset-all")).build(),
                    HttpResponse.BodyHandlers.ofString());
            long acquisitionsBefore = stat(base, "acquisitions");
            HttpResponse<String> recovery = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/kzen/trading/logic/startRun?path=main%2FSortedVolume.yaml&object=main")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, recovery.statusCode(), recovery.body());
            awaitFile(sortedCsv, Duration.ofSeconds(120));
            awaitIdle(base, Duration.ofSeconds(120));
            SortedMap<String, List<Long>> sorted = tallyOfCsv(sortedCsv);
            sorted.remove(SyntheticItchDay.quiet);
            assertEquals(expectedTally(), sorted, "the sorted scalar route reaches the tally under a one-day budget");
            List<String> symbols = Files.readAllLines(sortedCsv).stream().skip(1).filter(l -> !l.isBlank())
                    .map(l -> l.substring(0, l.indexOf(','))).toList();
            assertEquals(symbols.stream().sorted().toList(), symbols, "rows left the Sort in symbol order");
            assertTrue(stat(base, "acquisitions") - acquisitionsBefore >= synthetic.symbolsByLocate().size(),
                    "every symbol-day was admitted, one after another");
            awaitStat(base, "outstandingItems", 0, Duration.ofSeconds(30));
            assertEquals(stat(base, "acquisitions"), stat(base, "releases"));
            assertEquals(0, stat(base, "currentNativeBytes"));
            assertEquals(0, stat(base, "leaks"));
            assertEquals(stallWarnings, countOf(Files.readString(log, StandardCharsets.UTF_8), "stalled: No progress"),
                    "no stall on the scalar route");
        }
        finally {
            http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/kzen-host/shutdown"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(stall.waitFor(60, TimeUnit.SECONDS));
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * The largest symbol-day's weight, measured through the core in this JVM (a throwaway store beside the test's
     * data): a budget of one and a half of it admits any single day and never two large ones at once.
     */
    private static long largestSymbolDayWeight() throws InterruptedException {
        WeightedBudget unlimited = new WeightedBudget(Long.MAX_VALUE / 4);
        HostDay probe = new HostDay(dayFile, temp.resolve("probe-data"), unlimited);
        try {
            long largest = 0;
            for (String symbol : probe.symbols()) {
                try (SymbolDay day = probe.materialize(symbol)) {
                    largestBatchWeight = Math.max(largestBatchWeight, day.weight().total());
                    largest = Math.max(largest, day.weight().total() +
                            tech.kzen.sample.itch.day.MaterializationWeight.graph(day.partitionStats(),
                                    tech.kzen.sample.itch.day.MaterializationWeight.Coefficients.measured).total());
                }
            }
            assertTrue(largest > 0);
            return largest;
        }
        finally {
            probe.close();
        }
    }


    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }


    private static long stat(String base, String name) throws Exception {
        String json = http.send(HttpRequest.newBuilder(URI.create(base + "/kzen-host/budget")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), name + " in " + json);
        return Long.parseLong(matcher.group(1));
    }


    private static void awaitStat(String base, String name, long expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long last = -1;
        while (System.nanoTime() < deadline) {
            last = stat(base, name);
            if (last == expected) return;
            Thread.sleep(200);
        }
        assertEquals(expected, last, "budget stat " + name);
    }


    private static void awaitIdle(String base, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String status = http.send(HttpRequest.newBuilder(URI.create(base + "/kzen/trading/logic/status")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            if (status.contains("\"active\":null")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run still active on " + base);
    }


    private static Process launch(Path log, Path home, int port, int trading, int risk, long budget) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElseThrow(),
                "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                "-jar", hostJar.toString(),
                "--server.port=" + port,
                "--kzen.home=" + home,
                "--kzen.host.day-file=" + dayFile,
                "--kzen.host.data-root=" + home.resolve("data"),
                "--kzen.host.budget-bytes=" + budget,
                "--kzen.workspaces[0].name=trading", "--kzen.workspaces[0].port=" + trading,
                "--kzen.workspaces[1].name=risk", "--kzen.workspaces[1].port=" + risk));
        return new ProcessBuilder(command)
                .directory(temp.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }


    private static SortedMap<String, List<Long>> expectedTally() {
        SortedMap<String, List<Long>> tally = new TreeMap<>();
        for (SymbolTradeSummary summary : synthetic.expectedTrades().values()) {
            tally.put(summary.symbol(), List.of(summary.tradeEvents(), summary.shares()));
        }
        return tally;
    }


    private static SortedMap<String, List<Long>> tallyOfJson(String json) {
        SortedMap<String, List<Long>> tally = new TreeMap<>();
        Matcher matcher = Pattern.compile("\"symbol\":\"([A-Z]+)\",\"tradeEvents\":(\\d+),\"shares\":(\\d+)").matcher(json);
        while (matcher.find()) {
            tally.put(matcher.group(1), List.of(Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(3))));
        }
        return tally;
    }


    private static SortedMap<String, List<Long>> tallyOfCsv(Path csv) throws IOException {
        SortedMap<String, List<Long>> tally = new TreeMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals("symbol,tradeEvents,shares", lines.getFirst(), csv.toString());
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",");
            tally.put(cells[0], List.of(Long.parseLong(cells[1]), Long.parseLong(cells[2])));
        }
        return tally;
    }


    private static String start(String document) throws Exception {
        HttpResponse<String> started = get("/kzen/trading/logic/startRun?path=main%2F" + document + ".yaml&object=main");
        assertEquals(200, started.statusCode(), started.body());
        return started.body().trim();
    }


    private static void runToCompletion(String document, Path csv, Duration timeout) throws Exception {
        Files.deleteIfExists(csv);
        start(document);
        awaitFile(csv, timeout);
        awaitLogicIdle(timeout);
    }


    private static void clearTrace() throws Exception {
        // A finished run stays the document's trace until cleared; a new start needs a clean slate.
        awaitLogicIdle(Duration.ofSeconds(30));
        http.send(HttpRequest.newBuilder(uri("/kzen/trading/action/detached?path=auto-jvm%2Flogic%2Flogic-trace.yaml&object=LogicTraceEndpoint&action=reset-all")).build(),
                HttpResponse.BodyHandlers.ofString());
    }


    private static void awaitLogicIdle(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (get("/kzen/trading/logic/status").body().contains("\"active\":null")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run still active: " + get("/kzen/trading/logic/status").body());
    }


    private static long hold(long bytes) throws Exception {
        return hold("http://127.0.0.1:" + hostPort, bytes);
    }

    private static long hold(String base, long bytes) throws Exception {
        HttpResponse<String> held = http.send(HttpRequest.newBuilder(URI.create(base + "/kzen-host/budget/hold?bytes=" + bytes))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, held.statusCode(), held.body());
        Matcher matcher = Pattern.compile("\"hold\":(\\d+)").matcher(held.body());
        assertTrue(matcher.find(), held.body());
        return Long.parseLong(matcher.group(1));
    }


    private static void release(long hold) throws Exception {
        release("http://127.0.0.1:" + hostPort, hold);
    }

    private static void release(String base, long hold) throws Exception {
        HttpResponse<String> released = http.send(HttpRequest.newBuilder(URI.create(base + "/kzen-host/budget/hold/" + hold)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, released.statusCode(), released.body());
    }


    private static long stat(String name) throws Exception {
        String json = get("/kzen-host/budget").body();
        Matcher matcher = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), name + " in " + json);
        return Long.parseLong(matcher.group(1));
    }


    private static void awaitStat(String name, long expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long last = -1;
        while (System.nanoTime() < deadline) {
            last = stat(name);
            if (last == expected) return;
            Thread.sleep(200);
        }
        assertEquals(expected, last, "budget stat " + name);
    }


    private static void awaitFile(Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.size(file) > 0) {
                Thread.sleep(300);
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Not written in time: " + file + "\n" + Files.readString(hostLog, StandardCharsets.UTF_8));
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
