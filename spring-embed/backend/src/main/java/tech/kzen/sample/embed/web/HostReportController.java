package tech.kzen.sample.embed.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.kzen.sample.embed.host.BudgetStats;
import tech.kzen.sample.embed.host.HostDay;
import tech.kzen.sample.embed.host.OrderBookService;
import tech.kzen.sample.embed.host.TradeRepository;
import tech.kzen.sample.embed.host.WeightedBudget;
import tech.kzen.sample.itch.analysis.SymbolTradeSummary;
import tech.kzen.sample.itch.day.MaterializationBudget;
import tech.kzen.sample.itch.day.MaterializationWeight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The host's ordinary report endpoints over its own services — the same objects the workspaces receive as
 * {@code @Service}s — plus the budget's counters and a way for a host report to occupy the budget for a while
 * ({@code hold}: what a long-running host analysis looks like to kzen), which is how a test proves the
 * workspaces' Jobs wait and then progress as the host's leases return.
 */
@RestController
public class HostReportController {
    private final HostDay day;
    private final TradeRepository trades;
    private final OrderBookService books;
    private final WeightedBudget budget;
    private final Map<Long, MaterializationBudget.Lease> holds = new ConcurrentHashMap<>();
    private final AtomicLong holdIds = new AtomicLong();


    public HostReportController(HostDay day, TradeRepository trades, OrderBookService books, WeightedBudget budget) {
        this.day = day;
        this.trades = trades;
        this.books = books;
        this.budget = budget;
    }


    @GetMapping(value = "/host/trades", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> trades() {
        if (!trades.available()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "no day loaded"));
        }
        List<Map<String, Object>> rows = trades.tradeVolume().values().stream().map(HostReportController::row).toList();
        return ResponseEntity.ok(rows);
    }


    @GetMapping(value = "/host/book/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> book(@PathVariable String symbol, @RequestParam(defaultValue = "1") int levels) {
        if (!books.available()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "no day loaded"));
        }
        try {
            return ResponseEntity.ok(books.bookTop(symbol, levels));
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        }
    }


    @GetMapping(value = "/kzen-host/budget", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> budget() {
        BudgetStats stats = budget.stats();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("capacityBytes", stats.capacityBytes());
        view.put("currentBytes", stats.currentBytes());
        view.put("currentNativeBytes", stats.currentNativeBytes());
        view.put("peakBytes", stats.peakBytes());
        view.put("outstandingItems", stats.outstandingItems());
        view.put("waiting", stats.waiting());
        view.put("acquisitions", stats.acquisitions());
        view.put("releases", stats.releases());
        view.put("waits", stats.waits());
        view.put("interruptedWaits", stats.interruptedWaits());
        view.put("oversizedRejections", stats.oversizedRejections());
        view.put("leaks", day.leaks());
        view.put("holds", holds.size());
        return view;
    }


    /** A host report occupying [bytes] of the budget until released; answers the hold's id. */
    @PostMapping(value = "/kzen-host/budget/hold", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> hold(@RequestParam long bytes) {
        try {
            MaterializationBudget.Lease lease = budget.acquire(new MaterializationWeight(0, bytes));
            long id = holdIds.incrementAndGet();
            holds.put(id, lease);
            return ResponseEntity.ok(Map.of("hold", id, "bytes", bytes));
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "interrupted"));
        }
    }


    @DeleteMapping("/kzen-host/budget/hold/{id}")
    public ResponseEntity<String> release(@PathVariable long id) {
        MaterializationBudget.Lease lease = holds.remove(id);
        if (lease == null) {
            return ResponseEntity.notFound().build();
        }
        lease.close();
        return ResponseEntity.ok("released " + id);
    }


    private static Map<String, Object> row(SymbolTradeSummary summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", summary.symbol());
        row.put("tradeEvents", summary.tradeEvents());
        row.put("shares", summary.shares());
        return row;
    }
}
