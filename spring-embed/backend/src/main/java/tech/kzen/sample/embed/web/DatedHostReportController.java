package tech.kzen.sample.embed.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import tech.kzen.sample.embed.host.GovernedOrderBookService;
import tech.kzen.sample.embed.host.catalog.ItchCatalog;
import java.util.ArrayList;
import java.util.Map;

@RestController
public class DatedHostReportController {
    private final ItchCatalog catalog;
    public DatedHostReportController(ItchCatalog catalog) { this.catalog = catalog; }

    @GetMapping(value = "/host/trades", params = "date")
    public ResponseEntity<?> trades(@RequestParam String date) throws InterruptedException {
        try {
            String id = catalog.entryForDate(date);
            var symbols = catalog.readyStore(id).symbols().keySet().stream().sorted().toList();
            var rows = new ArrayList<Map<String, Object>>();
            for (String symbol : symbols) {
                try (var day = catalog.materialize(id, symbol)) {
                    long[] tally = day.day().graph().standingTradeEventsAndShares();
                    rows.add(Map.of("date", date, "symbol", symbol, "tradeEvents", tally[0], "shares", tally[1]));
                }
            }
            return ResponseEntity.ok(rows);
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/host/book/{symbol}", params = "date")
    public ResponseEntity<?> book(@PathVariable String symbol, @RequestParam String date,
            @RequestParam(defaultValue = "1") int levels) throws InterruptedException {
        try (var day = catalog.materialize(catalog.entryForDate(date), symbol)) {
            return ResponseEntity.ok(Map.of("date", date, "book", GovernedOrderBookService.top(day.day(), levels)));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        }
    }
}
