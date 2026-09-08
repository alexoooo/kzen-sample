# kzen-sample-embed-spring — AI agent guide

A plain-jar Spring Boot host that embeds kzen-auto workspaces in one JVM. It is the *second way in* for the
market-data sample: the plugin's core and adapter arrive as ordinary Maven dependencies (plugin zero), the host
owns the runtime, the workspaces, the logging backend and the reverse proxy. Read the umbrella's
[`../kzen/AGENTS.md`](../kzen/AGENTS.md) first; the design authority is the hosting analysis
(`../kzen/docs/analysis/2026-09-03_in-process-hosting.md` §5.3) and the HS02 spike as-built
(`../kzen/docs/plans/in-process-hosting/02-spring-compatibility-spike.md`).

## Layout

| Path | What |
|---|---|
| `pom.xml` | `spring-boot-starter-parent` 4.1.1 as the **parent** (a BOM import ignores the Kotlin / coroutines / serialization / Selenium overrides), plain jars (`copy-dependencies` → `target/lib/`, manifest `Class-Path`), no `spring-boot-maven-plugin`, the convergence pins HS02 found |
| `src/main/java/tech/kzen/sample/embed/EmbedApplication.java` | `@SpringBootApplication` entry point |
| `config/KzenHostProperties.java` | `kzen.home`, `kzen.plugin-root`, `kzen.workspaces[]{name, port, work-root}` |
| `workspace/KzenWorkspace.java` | one `KzenAutoContext` (module root `<home>/<name>`, work root `<home>/<name>/work`, `manageLogs=false`) + one loopback CIO server; start = context then server, stop = server then context |
| `workspace/KzenWorkspaces.java` | `SmartLifecycle` (phase 0): initializes the one `KzenAutoRuntime`, starts the workspaces in order, rolls back the started ones in reverse on a failure, stops them on shutdown; `stopWorkspace(name)` |
| `proxy/KzenProxyController.java`, `ProxyHeaders.java` | `/kzen/{workspace}/**` synchronous streaming relay (JDK `HttpClient`, HTTP/1.1, no redirects, 8 KiB chunks, flush per chunk); kzen-shell's header rules |
| `web/HostController.java` | `/` workspace list, `/kzen-host/workspaces`, `/kzen-host/stats` (live proxy streams, disconnects), `DELETE /kzen-host/workspaces/{name}`, `POST /kzen-host/shutdown` (graceful exit; a Windows child gets no SIGTERM) |
| `host/` | the host's own market-data objects (HS24): `WeightedBudget` (the arena as a weighted semaphore over the core's `MaterializationBudget`; over-capacity fails before waiting, an interrupted wait acquires nothing, counters in `BudgetStats`), `HostDay` (day file, derived store under the data area, the budget, the leak count from the core's Cleaner-backed detector), `FileTradeRepository` (`TradeRepository`: the day folded through the plain core, no materialization), `GovernedOrderBookService` (`OrderBookService`: a fresh symbol-day per query, closed in a `finally`), `GovernedSymbolDayLoader` (`SymbolDayLoader`: a fresh `SymbolDays` pass per open, every day admitted by the budget as it is pulled), `HostServicesConfig` (the beans, and the same instances registered on `KzenAutoHost` under their interfaces) |
| `web/HostReportController.java` | the host's reports over those services: `/host/trades`, `/host/book/{symbol}?levels=`, the budget's counters at `/kzen-host/budget`, and `POST /kzen-host/budget/hold?bytes=` / `DELETE /kzen-host/budget/hold/{id}` (a host report occupying the arena — how a test makes kzen wait) |
| `src/main/kotlin/.../glue/HostSymbolDaySourceWorker.kt` | the Kotlin glue: a `@Reflect` `CursorSourceWorker` taking `@Service SymbolDayLoader`, opening the host's governed cursor (archetype `HostSymbolDaySourceWorker` in `notation/auto-jvm/kzen-sample-embed/host-workers.yaml`) |
| `src/main/resources/application.yaml`, `logback.xml` | defaults (host 18280, workspaces `trading` 18281 / `risk` 18282, home `kzen-home/`); the host's own console logging |
| `src/test/java/.../HostPackagedIT.java` | the packaged jar driven over HTTP in child JVMs (see Verification) |

## Build & run

JDK 25 and Maven 3.9 (`~/.m2/wrapper/dists/apache-maven-3.9.9/.../bin/mvn`, `JAVA_HOME` = a JDK 25). Everything the
host depends on must be in Maven Local first — from their own directories:

```
cd ../kzen-lib && ./gradlew publishToMavenLocal
cd ../kzen-auto && ./gradlew publishToMavenLocal          # all of kzen-auto, not a subset
cd ../kzen-sample-plugin && mvn -o -B install              # the plugin's core + adapter (plugin zero here)
cd ../kzen-sample-embed-spring && mvn -o -B verify         # package + the integration tests
java -jar target/kzen-sample-embed-spring-0.0.1-SNAPSHOT.jar   # http://127.0.0.1:18280/
```

Any property is overridable on the command line: `--server.port=`, `--kzen.home=`, `--kzen.plugin-root=`,
`--kzen.host.day-file=` (the ITCH day the host's services load), `--kzen.host.data-root=` (derived store; default
`<home>/data`), `--kzen.host.budget-bytes=` (the shared arena; default 256 MiB), `--kzen.workspaces[0].port=` …
(an indexed workspace override on the command line replaces the whole yaml list — give every field). A
workspace's notation lives in `<home>/<name>/src/main/resources/notation/main/` (what kzen's locator expects);
drop the sample's Job templates there (`../kzen-sample-plugin/README.md`), and a `HostSymbolDaySourceWorker →
SymbolDayTradeVolumeWorker → …` Job is the host route over the live loader.

## Verification

`mvn -o -B verify` runs `HostPackagedIT` in the `integration-test` phase: it launches `target/*.jar` as a child
process on free ports and checks the portlet page, both prefixed UIs (302 → `index.html` relayed, `index.html`
200, the JS bundle relayed gzip and inflating to megabytes, an upstream 404 relayed, an unknown workspace 503), a
fixture Job started through the proxy (`GET /kzen/trading/logic/startRun?path=…&object=main`; a `PUT` form body relayed intact), the run-status SSE stream's first
event arriving in well under a second and the upstream stream closing after the client leaves
(`/kzen-host/stats.activeStreams` back to 0, `clientDisconnects` ≥ 1), one workspace stopped while the other
serves and its port freed, a clean exit (code 0, work roots released) through `POST /kzen-host/shutdown`; and two failing boots in their own child JVMs — the second
workspace configured on the first's work root (`--kzen.workspaces[1].work-root=`) and on the first's port —
each rolling back the first workspace (`Rolled back workspace 'trading'`, `work root released`) and exiting
non-zero. `HostGovernedIT` boots the host over a synthetic day with a small budget: the host report, the plugin's
raw route and the `@Service`-fed host route reach the generator's tally; every symbol-day returns its lease
(`acquisitions == releases`, `outstandingItems` 0, `leaks` 0); a hold makes a workspace's Job and a host query
wait and both progress when it is released; a run cancelled while waiting acquires nothing
(`interruptedWaits`); a 4 KiB budget refuses a symbol-day before waiting (422, `more than the budget can ever
admit`). `WeightedBudgetTest` / `GovernedServicesTest` are the unit level (surefire `test`). Boot a manual
instance on spare ports, never on the user's 8080 / 18081.

## Measuring a real day (P1-final, HS25)

The governed route over a real Nasdaq day, sharing the arena with host queries — the source and its derived
store live outside git under a durable data area (HS06 built `C:/Users/ostro/kzen-data/itch/{sources,stores}`
for 2019-12-30; the store is reused when its fingerprint is fresh, otherwise rebuilt from the `.gz` in ~10 min):

```
java -Xmx16g -XX:+UseG1GC -jar target/kzen-sample-embed-spring-0.0.1-SNAPSHOT.jar --server.port=18290 ^
     --kzen.home=<home> --kzen.host.day-file=<data>/sources/12302019.NASDAQ_ITCH50.gz ^
     --kzen.host.data-root=<data> --kzen.host.budget-bytes=4294967296
curl "http://127.0.0.1:18290/host/book/QQQ?levels=2"            # one governed symbol-day, ~1.5 GiB weight
curl "http://127.0.0.1:18290/kzen/trading/logic/startRun?path=main%2FHostRouteRealDay.yaml&object=main"
curl http://127.0.0.1:18290/kzen-host/budget                     # peak / native / outstanding / waits / leaks
curl -X POST "http://127.0.0.1:18290/kzen-host/budget/hold?bytes=3221225472"   # a host report squatting 3 GiB
```

with `<home>/trading/src/main/resources/notation/main/HostRouteRealDay.yaml` = `HostSymbolDaySourceWorker →
SymbolDayTradeVolumeWorker → CsvWriterWorker`. A hold that leaves less than the next symbol-day's weight makes
the Job (and any governed host query) wait until it is released — do not wait on such a query from the same
shell that holds the budget. Results are recorded in the HS25 as-built (`../kzen/docs/plans/in-process-hosting/25-*.md`).

## Gotchas

- **The runtime is process-global and pinned once.** `KzenAutoRuntime.initialize` runs in `KzenWorkspaces.start`;
  a second host in the same JVM with a different plugin root would fail fast, by design. The host never closes it.
- **Do not return `StreamingResponseBody` from the proxy.** Tomcat buffers it until completion (HS02 G7 measured
  0 bytes in 18 s for SSE); the void handler writing `HttpServletResponse` with `flushBuffer()` per chunk is what
  streams. `Content-Length`/hop-by-hop headers are dropped both ways; `Content-Encoding` and `Location` relay as is.
- **Ship your own `logback.xml`** (this module does) or Boot's `LogbackLoggingSystem` picks up
  `kzen-auto-jvm.jar!/logback.xml` and the host inherits kzen's file appender under its CWD. `manageLogs=false`
  keeps kzen's `logs/` managed area off.
- **`kzen-auto-common-jvm` must be declared explicitly**: kzen-auto-jvm publishes it at runtime scope and the SPI
  types live there.
- **The budget governs the host's services, not arbitrary expressions.** A workspace expression such as
  `SymbolDays.of(ItchStore.open(...))` (the plugin's canonical store-backed route) runs under the core's unlimited
  budget — an expression has no host to receive one from; only what goes through `SymbolDayLoader` /
  `OrderBookService` is admitted by the arena. Cached models are not exposed; every governed instance is fresh
  and owned by whoever pulled it (E9) — a shared one would have to be `Borrowed`.
- **Ports are loopback and per workspace**; two workspaces on one port is a bind failure the lifecycle rolls back,
  two on one work root a named claim failure — neither is a sharing mode.
