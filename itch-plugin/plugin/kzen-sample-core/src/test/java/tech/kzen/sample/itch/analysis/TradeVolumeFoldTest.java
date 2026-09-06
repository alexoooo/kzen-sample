package tech.kzen.sample.itch.analysis;

import org.junit.jupiter.api.Test;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.synth.SyntheticItchDay;

import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TradeVolumeFoldTest {
    private static final long seed = 42L;


    @Test
    void foldMatchesTheGeneratorsIndependentTally() {
        SyntheticItchDay day = SyntheticItchDay.generate(seed);
        TradeVolumeFold fold = new TradeVolumeFold();
        day.messages().forEach(fold::observe);

        SortedMap<String, SymbolTradeSummary> summaries = fold.summaries();
        assertEquals(day.expectedTrades(), summaries);
        assertFalse(summaries.containsKey(SyntheticItchDay.quiet));
        assertEquals(day.symbolsByLocate(), fold.catalog().symbolsByLocate());

        // The scripted prefix alone: AAPL 40 + 60 + 30; MSFT printable C broken, P 250, opening cross 1000;
        // GOOG P broken, E 10 — every random contribution is on top of these.
        assertTrue(summaries.get(SyntheticItchDay.aapl).shares() >= 130);
        assertTrue(summaries.get(SyntheticItchDay.msft).shares() >= 1250);
        assertTrue(summaries.get(SyntheticItchDay.goog).shares() >= 10);
    }


    @Test
    void scriptedScenarioAloneHasTheHandComputedResult() {
        SyntheticItchDay day = SyntheticItchDay.generate(seed, 0);
        TradeVolumeFold fold = new TradeVolumeFold();
        day.messages().forEach(fold::observe);

        assertEquals(new SymbolTradeSummary(SyntheticItchDay.aapl, 3, 130), fold.summaries().get(SyntheticItchDay.aapl));
        assertEquals(new SymbolTradeSummary(SyntheticItchDay.msft, 2, 1250), fold.summaries().get(SyntheticItchDay.msft));
        assertEquals(new SymbolTradeSummary(SyntheticItchDay.goog, 1, 10), fold.summaries().get(SyntheticItchDay.goog));
    }


    @Test
    void executionOnUnknownLocateAndBreakOfUnknownMatchAreNamedFailures() {
        TradeVolumeFold fold = new TradeVolumeFold();
        ItchMessage.OrderExecuted orphan = new ItchMessage.OrderExecuted(new ItchHeader(0, 9, 1, 1), 1, 100, 500);
        IllegalArgumentException locateFailure = assertThrows(IllegalArgumentException.class, () -> fold.observe(orphan));
        assertTrue(locateFailure.getMessage().contains("Locate 9"), locateFailure.getMessage());

        ItchMessage.BrokenTrade orphanBreak = new ItchMessage.BrokenTrade(new ItchHeader(1, 0, 2, 2), 777);
        IllegalStateException breakFailure = assertThrows(IllegalStateException.class, () -> fold.observe(orphanBreak));
        assertTrue(breakFailure.getMessage().contains("match number 777"), breakFailure.getMessage());
    }


    @Test
    void bothSidesOfOneMatchCountOnceAndBreakOnce() {
        TradeVolumeFold fold = new TradeVolumeFold();
        fold.observe(new ItchMessage.StockDirectory(new ItchHeader(0, 1, 1, 1), "AAPL", "Q", "N", 100, "N", "C",
                "Z", "P", "N", "N", "1", "N", 0, "N"));
        fold.observe(new ItchMessage.OrderExecuted(new ItchHeader(1, 1, 2, 2), 1, 100, 500));
        fold.observe(new ItchMessage.OrderExecuted(new ItchHeader(2, 1, 3, 3), 2, 100, 500));
        assertEquals(new SymbolTradeSummary("AAPL", 1, 100), fold.summaries().get("AAPL"), "one trade, two sides");
        fold.observe(new ItchMessage.BrokenTrade(new ItchHeader(3, 1, 4, 4), 500));
        assertEquals(new SymbolTradeSummary("AAPL", 0, 0), fold.summaries().get("AAPL"), "broken once");

        // A non-printable side first, then the printable side of the same match: it prints once.
        fold.observe(new ItchMessage.OrderExecutedWithPrice(new ItchHeader(6, 1, 7, 7), 4, 70, 600, false, 1));
        assertEquals(new SymbolTradeSummary("AAPL", 0, 0), fold.summaries().get("AAPL"));
        fold.observe(new ItchMessage.OrderExecuted(new ItchHeader(7, 1, 8, 8), 5, 70, 600));
        assertEquals(new SymbolTradeSummary("AAPL", 1, 70), fold.summaries().get("AAPL"), "counted on the printing side");

        // The same number on another symbol is that symbol's own trade.
        fold.observe(new ItchMessage.StockDirectory(new ItchHeader(4, 2, 5, 5), "MSFT", "Q", "N", 100, "N", "C",
                "Z", "P", "N", "N", "1", "N", 0, "N"));
        fold.observe(new ItchMessage.OrderExecuted(new ItchHeader(5, 2, 6, 6), 3, 40, 500));
        assertEquals(new SymbolTradeSummary("MSFT", 1, 40), fold.summaries().get("MSFT"));
    }


    @Test
    void breakOfNonPrintableExecutionIsNotAnError() {
        TradeVolumeFold fold = new TradeVolumeFold();
        fold.observe(new ItchMessage.StockDirectory(new ItchHeader(0, 1, 1, 1), "AAPL", "Q", "N", 100, "N", "C",
                "Z", "P", "N", "N", "1", "N", 0, "N"));
        fold.observe(new ItchMessage.OrderExecutedWithPrice(new ItchHeader(1, 1, 2, 2), 1, 100, 500, false, 1));
        fold.observe(new ItchMessage.BrokenTrade(new ItchHeader(2, 1, 3, 3), 500));
        assertTrue(fold.summaries().isEmpty());
    }
}
