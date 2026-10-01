# Embedded Kzen with ITCH market data

Two parts: `frontend/` (Gradle KMP) and `backend/` (Maven Spring Boot host). They see each other only through
Maven Local. Build with Java 25 after publishing kzen-auto and installing [`../itch-plugin`](../itch-plugin/README.md)
locally (see [AGENTS.md](AGENTS.md)). The sample owns its catalog UI and composes it with Kzen's published client:

```powershell
cd frontend
./gradlew publishToMavenLocal
cd ../backend
./mvnw -o -B verify
```

The frontend JVM artifact carries the shared catalog models and the browser bundle. Launch from `backend/`:

```powershell
& "$env:JAVA_HOME/bin/java" -Xmx16g -XX:+UseG1GC -jar target/kzen-sample-embed-spring-0.0.1-SNAPSHOT.jar
```

Open http://127.0.0.1:18280/ and choose **trading** or **risk**.

## Analyze a day

1. Open or create a Job. Choose **ITCH** in its **Sources** palette and insert the source.
2. Expand **Dates** and select one or more dates. Filter to **Selected**, **Downloaded**, or **All**, or search by date. Expand a date’s **Source** for its filename and URL. **Refresh dates** reloads the Nasdaq public sample catalog.
3. Leave **All symbols**, or expand **Symbols** to search and select symbols such as AAPL or QQQ. Choices appear after preparation. Missing symbol/date combinations are shown and skipped.
4. Add **ITCH trade volume** or **ITCH orders** from the transforms palette, followed by a Preview or CSV writer.
5. Click **Run**. Missing downloads and missing or outdated analysis stores are prepared automatically before reading begins. Existing downloads and fresh stores are reused. Download, verification, and preparation progress appear on the ITCH card. **Download and prepare** remains available to prepare dates ahead of time. Cancelling a run stops preparation only when no other run or manual request needs it; completed files remain reusable. A new run retries failed or cancelled preparation.

The source emits a typed dated symbol-day with `date`, `symbol`, `sourceUrl`, and `day`. Trade volume produces `date`, `symbol`, `tradeEvents`, and `shares`. Orders produce `date`, `symbol`, and a typed `order` with its lifecycle fields. The type display and field picker are available before running. A native symbol-day stays alive while downstream processing uses it; scalar summaries release its memory.

A configured source starts compact, showing selected dates, readiness, and symbols. Expand **Dates** or **Symbols** when editing. Preparation progress and failures remain visible when collapsed.

Expand a type summary to inspect its field tree. The **Stream item** is one emitted value; secondary JVM badges describe how those same fields are represented in the host. **Technical details** contains full type names and schema diagnostics.

You can also connect ITCH directly to **Preview**. Each row shows `date`, `symbol`, `sourceUrl`, and expandable `day.open`, the fields exposed by the existing contract. Preview captures values while the symbol-day is alive, so cells remain readable after the native memory is released. Trades and orders are exposed through their transforms.

To add a column, place **Formula** between ITCH and Preview, add `test` with expression `symbol.length`, and leave **Payload** blank. The output retains `date`, `symbol`, `sourceUrl`, and the live `day` object alongside the typed calculated column. Later Formula steps can read both the original fields and `test`. **Carry** is only needed when a Payload expression replaces the object; choose None, All, or Selected fields with optional renames.

Preview retains a rolling sample bounded by both the configured item count and 8 MiB of encoded content. Each item is limited to 256 KiB, 16 levels, 200 children per container, 2,000 visited nodes, 4,096 characters per text value, and a 64-byte binary excerpt. Capture checks a 50 ms deadline between reads; it cannot interrupt a blocking getter. Unavailable or omitted branches are marked without hiding readable siblings. Expanding a cell reads only captured content.

Dates and symbols are saved in the Job. They are fixed during a run; stop and start a new run to change them. Download and preparation work is shared by both workspaces. Manual preparation can run without a Job.

## Storage and memory

The default durable data area is **`<user.home>/kzen-data/itch`**, outside the project:

- `sources/`: downloaded Nasdaq files.
- `stores/`: Zstd-compressed analysis stores, reused while fresh.
- `catalog.properties`: saved source URLs and catalog metadata for offline use.

Override it with `--kzen.host.data-root=<directory>`. The existing `12302019.NASDAQ_ITCH50.gz` download under that default directory is discovered automatically. Recognized filenames carry their trading date; catalog upload timestamps are never treated as trading dates. Public samples cover selected dates, not every trading day.

The default shared analysis budget is **4 GiB** (`--kzen.host.budget-bytes=`). The launch command allows a **16 GiB heap**; these are limits, not eager allocations. Store preparation also uses bounded working buffers. A full day can take several minutes to prepare. A symbol too large for the budget fails with a capacity message.

Store format v2 requires a one-time rebuild for dates prepared with v1, performed automatically on Run. The existing source
download is reused. Each run validates its selected sources once and keeps those store versions for its entire
selection. New runs validate again. The loader decompresses blocks directly into native batch storage and
prefetches at most 8 MiB of the next symbol's compressed file. Read buffers count toward the host budget;
prefetch falls back to synchronous reading when memory is tight. See [`../itch-plugin/README.md`](../itch-plugin/README.md) for the format,
lifetime rules and standalone throughput benchmark. Generic Kzen scheduling and channels are unchanged.

Each `SymbolDay` is one Arena-backed batch of packed ITCH records, including market-wide messages in feed order. `ItchMessage` getters read their `ItchRecord` on demand; loading a batch creates neither decoded field objects nor a state graph. `SymbolDayGraph.build(batch)` computes the graph separately. Batch admission accounts for its native storage; graph construction makes an additional heap reservation, held conservatively until that batch closes. If graph capacity is unavailable, construction fails immediately rather than waiting while holding native capacity. Formula/Preview over the source do not build a graph.

The catalog models, actions, notation, and date/symbol display belong to this sample. The stock Kzen artifacts contain none of them. The frontend registers its own display before invoking Kzen's client entry point; workspaces serve the sample bundle through the existing configurable module name.

A mismatched existing file is left intact and reported for operator inspection. Failed downloads never replace it. Partial transfers created by the downloader are removed on failure/cancellation; retries restart the transfer. A host stopped abruptly can leave `.part` files, which are ignored by the catalog.

## Host reports

The Spring host uses the same catalog and budget:

- `/host/trades?date=2019-12-30`
- `/host/book/QQQ?date=2019-12-30&levels=2`
- `/kzen-host/budget`

Prepare the date in the UI first. Existing report URLs without a date and `--kzen.host.day-file=` remain available for the single-file embedding example.

## Verification

`./mvnw -o -B verify` in `backend/` covers catalog parsing, local fixtures served over HTTP, cancellation, checksums, store reuse, budget ownership, and packaged multi-date execution through the Spring proxy. Tests use isolated data directories and synthetic days; they do not download real market files.

### Analysis run progress

The ITCH card shows the current file and symbol, with read/total and remaining counts for symbols,
messages, and analysis bytes. The progress bar follows bytes; expand **Progress by file** for the
per-file breakdown. A symbol is one selected date/symbol batch. Message and byte totals include
market-wide records replayed into each batch. Analysis bytes measure prepared-store frames, including
frame headers, rather than the compressed download or arena allocation size.

**Run elapsed** measures the entire Job, including pauses, memory waits, and downstream processing.
It survives a browser refresh and freezes as **Run duration** after completion, failure, or cancellation.
Input reading can finish before the Job does. Starting a new run resets both counters and timing.

### Prepared-store performance (2026-09-09)

Measured on the development Windows machine with JDK 25.0.4.1, an isolated packaged host, a 4 GiB analysis
budget and a 4 GiB JVM heap. The Job was ITCH → Preview (sample 1,000), all 8,906 symbols for 2019-12-30.
Downloads and preparation are excluded from Job timing. No OS cache flush was performed.

| Measurement | Result |
|---|---:|
| Uncompressed v1 partition files | 10,401,366,149 bytes |
| Zstd v2 partition files | 3,773,697,470 bytes (63.7% smaller) |
| Final v2 preparation, from the existing gzip download | 636.1 s |
| Existing packaged Job, isolated control run | 54.2 s |
| Updated Job, first run in its JVM | 20.9 s |
| Updated Job, three subsequent runs | 20.3 / 21.8 / 22.0 s |
| Peak admitted batch and loader-buffer memory | 119,455,744 bytes (113.9 MiB) |
| Memory waits / leaked batches / remaining leases | 0 / 0 / 0 |

Each updated run reported Success, 268,833,830 messages and 10,403,957,504 logical analysis bytes, including
market-wide records replayed into each symbol. The isolated control did not reproduce the reported
5.5-minute interactive run, so that figure is not used as a measured speedup baseline.

The standalone core loader took 47.4 s before the change; v2 materialize-and-close passes took
22.2 / 21.2 / 19.5 / 19.3 s. Pull-thread heap allocation fell from 30.3 GiB to roughly 92–95 MiB per pass;
this excludes background prefetch allocation, whose live buffer is capped at 8 MiB. The removed per-symbol
fingerprint loop independently took 35.9 s and allocated 17.4 GiB on the source implementation. These
component measurements are not additive estimates of the packaged Job's elapsed time.
