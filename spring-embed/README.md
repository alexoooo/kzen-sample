# Embedded Kzen with ITCH market data

Build with Java 25 and Maven after publishing kzen-auto and installing kzen-sample-plugin locally (see [AGENTS.md](AGENTS.md)). Launch from this directory:

```powershell
& "$env:JAVA_HOME/bin/java" -Xmx16g -XX:+UseG1GC -jar target/kzen-sample-embed-spring-0.0.1-SNAPSHOT.jar
```

Open http://127.0.0.1:18280/ and choose **trading** or **risk**.

## Analyze a day

1. Open or create a Job. Choose **ITCH** in its **Sources** palette and insert the source.
2. Select one or more dates. The table shows downloaded files, sizes, source URLs, and preparation status. **Refresh dates** reloads the Nasdaq public sample catalog.
3. Click **Download and prepare selected**. Existing downloads and fresh analysis stores are reused. Each file shows download progress, verification, and store preparation. Cancellation retains completed files; the same button retries failed or cancelled preparation.
4. Leave **Symbols: All**, or search and select symbols such as AAPL or QQQ. Choices appear after preparation. Missing symbol/date combinations are shown and skipped.
5. Add **ITCH trade volume** or **ITCH orders** from the transforms palette, followed by a Preview or CSV writer, then run the Job.

The source emits a typed dated symbol-day with `date`, `symbol`, `sourceUrl`, and `day`. Trade volume produces `date`, `symbol`, `tradeEvents`, and `shares`. Orders produce `date`, `symbol`, and a typed `order` with its lifecycle fields. The type display and field picker are available before running. A native symbol-day stays alive while downstream processing uses it; scalar summaries release its memory.

Dates and symbols are saved in the Job. They are fixed during a run; stop and start a new run to change them. Downloads run independently of Jobs and are shared by both workspaces.

## Storage and memory

The default durable data area is **`<user.home>/kzen-data/itch`**, outside the project:

- `sources/`: downloaded Nasdaq files.
- `stores/`: prepared analysis stores, reused while fresh.
- `catalog.properties`: saved source URLs and catalog metadata for offline use.

Override it with `--kzen.host.data-root=<directory>`. The existing `12302019.NASDAQ_ITCH50.gz` download under that default directory is discovered automatically. Recognized filenames carry their trading date; catalog upload timestamps are never treated as trading dates. Public samples cover selected dates, not every trading day.

The default shared analysis budget is **4 GiB** (`--kzen.host.budget-bytes=`). The launch command allows a **16 GiB heap**; these are limits, not eager allocations. Store preparation also uses bounded working buffers. A full day can take several minutes to prepare. A symbol too large for the budget fails with a capacity message.

A mismatched existing file is left intact and reported for operator inspection. Failed downloads never replace it. Partial transfers created by the downloader are removed on failure/cancellation; retries restart the transfer. A host stopped abruptly can leave `.part` files, which are ignored by the catalog.

## Host reports

The Spring host uses the same catalog and budget:

- `/host/trades?date=2019-12-30`
- `/host/book/QQQ?date=2019-12-30&levels=2`
- `/kzen-host/budget`

Prepare the date in the UI first. Existing report URLs without a date and `--kzen.host.day-file=` remain available for the single-file embedding example.

## Verification

`mvn -o -B verify` covers catalog parsing, local fixtures served over HTTP, cancellation, checksums, store reuse, budget ownership, and packaged multi-date execution through the Spring proxy. Tests use isolated data directories and synthetic days; they do not download real market files.
