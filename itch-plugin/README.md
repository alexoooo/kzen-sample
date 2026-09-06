# kzen-sample-plugin

Example plugin for kzen, in two tiers:

- **`kzen-sample-core`** — a plain-Java analytical core with **no kzen and no Kotlin dependency** (enforced by
  the Maven enforcer): a NASDAQ TotalView-ITCH 5.0 decoder/encoder, a locate-partitioned derived store, an
  off-heap `SymbolDay` with a persistent (structurally shared) order-book history, budgets, native accounting
  and a leak detector, plus the `ItchDayBenchmark` measurement entry point. It is what a real domain team would
  own regardless of kzen.
- **`kzen-sample-adapter`** — the thin kzen-facing layer compiled against `kzen-auto-plugin` (provided scope;
  `kzen-auto-jvm` likewise, for the Java Worker bases only): two Job readers, five analytical Workers, their
  bundled notation and ready-made Job templates over the core. `ItchReaderCapability` reads a TotalView-ITCH 5.0
  feed (raw or `.gz` through the host's gzip content coding) into flat typed rows with symbol / message-type /
  time-window filters, bounded shape inspection, content-based detection (the framing must decode — an
  extension alone never claims binary) and an authoring capability so a File source can materialize a
  configured `ItchFormat` object; `WorldCitiesReaderCapability` is the minimal `BlockingReaderCapability`
  sample (no configuration, detected by its header row). Both are registered in
  `META-INF/services/tech.kzen.auto.plugin.api.data.ReaderCapability`; the formats are graph objects in
  `notation/auto-jvm/kzen-sample-plugin/sample-formats.yaml`. The former `WorldCitiesPop` `ReportDefiner` and
  its `META-INF/kzen/plugins.yaml` are gone: Reports take the host's built-in definers only.

  The **analytical Workers** (`tech.kzen.sample.plugin.analysis`, archetypes in `sample-workers.yaml`) are thin
  `JavaTransformWorker` subclasses over the core — `@Reflect`, one constructor, no coroutine:
  `ItchTradeVolumeWorker` folds decoded `ItchMessage`s (the core's `TradeVolumeFold`) into one row per symbol;
  `SymbolDayTradeVolumeWorker`, `SymbolDayOrderLifecycleWorker` and `SymbolDayBookSnapshotWorker`
  (`intervalMillis`, `levels`) read a materialized `SymbolDay` (standing trades, one row per reconstructed
  order, the book sampled at a cadence); `SymbolDayOrdersWorker` hands the core's `OrderLifecycle` records
  through unchanged for the host's path projection. Each declares its static output (a literal contract or the
  record class), so the Worker cards show the columns before a run; the three row Workers also declare their rows
  independent copies (`independentOutputs()`), so a Sort or other accumulator after them keeps rows, not the
  symbol-day's lease — `SymbolDayOrdersWorker` hands the core's records through and keeps the default.

## Installing a plugin

A plugin is a **directory of jars** under kzen-auto's plugin root, pinned once when the server starts:

```
plugins/<name>/*.jar                          # the adapter jar, the core jar, and their dependencies
plugins/<name>/... META-INF/kzen/plugin.yaml  # optional metadata: id, version, spi
```

The root is `--plugin.root=<dir>` or, by default, `plugins/` beside the module. There is no upload and no
reload: put the jars in place and restart. The Plugin document in the UI then lists the scope, what it
contributed (readers, documents, generated modules), which classes this workspace can instantiate, and every
named failure; `plugin.yaml` may declare `spi: 1` so an incompatible build is refused by name at boot.

`mvn package` here produces `kzen-sample-adapter/target/kzen-sample-adapter-*.jar` with its runtime dependencies
copied to `kzen-sample-adapter/target/lib/` — for this sample just `kzen-sample-core-*.jar`, since the SPI is
`provided` scope (kzen's own artifacts come from the host and must not be copied along, or the plugin's copy
would shadow the host's classes). The adapter jar carries `META-INF/kzen/plugin.yaml` (`id: kzen-sample`,
`spi: 1`). To install:

```
mkdir <plugin root>/kzen-sample
cp kzen-sample-adapter/target/kzen-sample-adapter-*.jar kzen-sample-adapter/target/lib/*.jar <plugin root>/kzen-sample/
java -jar kzen-auto-jvm-*.jar --plugin.root=<plugin root>
```

Then a File source over a `.itch` / `.nasdaq_itch50` file (or a `.gz` of one) detects "NASDAQ ITCH 5.0" and a
file starting with the world-cities header row detects "World cities population"; both read as Job records.

## Ready-made Jobs

The adapter jar carries six Job templates under `kzen-sample/jobs/` (not notation — copy the ones you want into
the project's `src/main/resources/notation/main/`). They expect the day file at `data/kzen-sample/synthetic-day.itch`
relative to the server's working directory and write under `data/kzen-sample/out/`; edit the paths to taste. A
synthetic day comes from the core's test jar (`SyntheticItchDay.generate(seed, ordersPerSymbol).writeTo(path, gzip)`);
no real feed data ships here.

| Template | Route | What it shows |
|---|---|---|
| `ItchDayAnalysis.yaml` | File → reader → generic Workers | typed rows from the reader, print rows filtered, an hour column, shares pivoted by symbol and hour into a CSV — plugin code only in the reader |
| `ItchRawIngestion.yaml` | expression `ItchReader(path)` → `ItchTradeVolumeWorker` | the raw plain-library route: decoded messages folded to per-symbol trade volume, no store |
| `SymbolDayTradeVolume.yaml` | expression `SymbolDays.of(ItchDataArea(root).ensureStore(...))` → `SymbolDayTradeVolumeWorker` | the canonical store-backed route: the store built on first run, each symbol-day one owned element the run closes |
| `SymbolDayOrderLifecycle.yaml` | store → `SymbolDayOrderLifecycleWorker` → CSV | one row per reconstructed order |
| `SymbolDayBook.yaml` | store → `SymbolDayBookSnapshotWorker` | the displayed book once a second, one level deep |
| `SymbolDayOrdersExport.yaml` | store → `SymbolDayOrdersWorker` → Paths → Export | the object graph projected by the path picker and exported |

The raw and store routes both reproduce the generator's per-symbol tally (`TradeVolumeRowsTest`); the file
route counts print rows with generic Workers and is not the oracle-grade tally (breaks and non-printable
executions need the message graph).

## Building

For a local snapshot build, publish `kzen-lib` first and then publish `:kzen-auto-plugin` from the `kzen-auto`
build before running Maven here (`kzen-lib-common-jvm` resolves transitively from Maven Local). Build with a
JDK 25 and Maven 3.9: `mvn -B package`. `kzen-sample-core` needs neither and can be built alone with
`mvn -pl kzen-sample-core package`. Both tiers emit Java 25 class files, the same baseline as kzen itself: the
host that loads them (`kzen-auto-jvm`, a kzen-project home, or an embedding JVM) must run on Java 25 or newer.

## Real market data

The ready-made Jobs run on a synthetic day. To run the same routes on a real day, fetch a Nasdaq TotalView-ITCH
5.0 sample day (gzip, 3.5–18 GB) from Nasdaq's public sample area, `https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/`.
That directory publishes the files without terms-of-use text (checked 2026-09-04): you are fetching Nasdaq
sample data under whatever terms Nasdaq attaches to it — read their site before you download, and keep the
feed file and every derived store **outside any git working tree** (a durable data area such as
`C:/Users/<you>/kzen-data/itch/{sources,stores}`; nothing here ever commits feed data).

Three routes read such a day, and they differ in who governs memory:

| Route | Where | Memory |
|---|---|---|
| plain library — `ItchReader(path)` expression → `ItchTradeVolumeWorker` | any kzen workspace | a streaming fold; no materialization |
| store-backed — `SymbolDays.of(ItchDataArea(root).ensureStore(...))` expression → `SymbolDay*Worker` | any kzen workspace | each symbol-day materialized as one owned element under the core's **unlimited** budget: an expression has no host to receive a budget from |
| governed — the host's `SymbolDayLoader` handed in as a `@Service` (see `../kzen-sample-embed-spring`) | an embedding host | the same symbol-days admitted by the **host's** weighted budget, shared with the host's own reports |

The core's measurement entry point reproduces the sizing record (decode throughput, store build, the largest
symbol-days' native and heap, replay, close, leak accounting) without kzen or a host — from this directory,
after `mvn -pl kzen-sample-core compile`, on a JDK 25:

```
java -Xmx16g -XX:+UseG1GC -cp kzen-sample-core/target/classes tech.kzen.sample.itch.bench.ItchDayBenchmark \
     <data>/sources/<day>.NASDAQ_ITCH50.gz <data> --repeat=3 --largest=3 [--symbols=AAPL,MSFT] [--skip-decode] [--sha256]
```

It writes a Markdown report under `<data>/reports/` and builds (or reuses, when the fingerprint is fresh) the
derived store under `<data>/stores/`; the first pass over a full day is decode-bound (~10 minutes for 2019-12-30,
268.7 M messages). The 2019-12-30 record itself is in the umbrella's HS06 and HS25 as-builts
(`../kzen/docs/plans/in-process-hosting/`).

## Checking compatibility

kzen-auto ships a compatibility kit that checks a plugin directory the way the server would see it:

```
java -cp <kzen-auto-jvm runtime classpath> tech.kzen.auto.server.context.runtime.kit.PluginCompatibilityKit <plugin root> [--verify]
```

`inspect` (default) discovers scopes, contributions, notation, service needs, duplicates and shadowing without
pinning anything; `--verify` pins the runtime, creates a standalone context and proves availability and
expression identity, so it runs in its own process. Expectations go on the command line as repeatable
`--expect-scope=`, `--expect-reader=<namespace.name@compatibility>`, `--expect-document=`, `--expect-class=`,
`--expect-expression=` (and failed-scope / boot-error / unavailable / ambiguous / shadowed variants); exit code
1 names every unmet one. From Kotlin, `PluginCompatibilityKit.inspect(root, KitExpectations(...))` returns the
same report.

`mvn verify` runs this against the freshly packaged jar set (`PluginDirectoryIT`): once as a folder plugin with
only the host on the class path, once with the same jars on the application class path (plugin zero), each in
its own child JVM, expecting both readers, both bundled documents, the seven `@Reflect` classes and expression
identity for the core's `SymbolDays` and `ItchReader`. The host jars come from `-Dkzen.auto.libs=<kzen-auto-jvm
build/libs>` (default: the umbrella sibling's, after `./gradlew :kzen-auto-jvm:jar :kzen-auto-jvm:copyDependencies`);
the test skips itself when that directory is absent.
