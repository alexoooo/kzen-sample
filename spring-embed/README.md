# kzen-sample-embed-spring

A Spring Boot 4 host (plain jars, JDK 25) that embeds kzen-auto workspaces in one JVM — the *embedded* way into
the market-data sample, beside the folder-plugin way:

- one process-global `KzenAutoRuntime`, pinned at startup (plugin root and class path are the host's choice);
- one `KzenAutoContext` and one loopback Ktor (CIO) server per configured workspace, under Spring's lifecycle —
  started in order, rolled back in reverse if a later one fails, stopped server-then-context on shutdown;
- a synchronous streaming reverse proxy at `/kzen/{workspace}/**` (kzen's UI, assets and its run-status SSE
  stream all arrive through it), and a workspace list at `/`;
- the sample plugin (`../kzen-sample-plugin`: the ITCH core, its readers and analytical Workers) on the
  application class path as ordinary Maven dependencies — plugin zero — so every workspace has them;
- the host's own market-data services over the plain core — `TradeRepository`, `OrderBookService`,
  `SymbolDayLoader` — served on the host's endpoints (`/host/trades`, `/host/book/{symbol}`) and handed to every
  workspace as `@Service` objects, all admitted by one weighted memory budget (`/kzen-host/budget`); a Kotlin
  glue Worker (`HostSymbolDaySourceWorker`) streams the loader's fresh symbol-days into a Job.

The host owns logging and configuration; kzen's process-exit and headless behaviour are not installed.

## Run

```
mvn -o -B verify
java -jar target/kzen-sample-embed-spring-0.0.1-SNAPSHOT.jar
```

Then open http://127.0.0.1:18280/ and follow a workspace link (`/kzen/trading/index.html`,
`/kzen/risk/index.html`). Configuration (`application.yaml`, every key overridable as `--key=value`):

```yaml
kzen:
  home: kzen-home            # <home>/<workspace>/src/main/resources/notation/main holds each workspace's documents
  plugin-root:               # optional folder plugins beside the application class path
  workspaces:
    - name: trading
      port: 18281            # loopback ports, one per workspace
    - name: risk
      port: 18282
  host:
    day-file:              # the ITCH day the host's services load (blank: services report "no day")
    data-root:             # derived store; default <home>/data
    budget-bytes: 268435456   # the shared arena every materialization acquires from
```

Prerequisites: kzen-lib and kzen-auto published to Maven Local from their own directories, and the sample plugin
installed (`cd ../kzen-sample-plugin && mvn -o -B install`). See [`AGENTS.md`](AGENTS.md) for the layout, the
verification the integration test performs, and the gotchas (streaming, logging, the runtime's one-per-process rule).
