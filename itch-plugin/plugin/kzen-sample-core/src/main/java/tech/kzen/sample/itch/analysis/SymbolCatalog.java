package tech.kzen.sample.itch.analysis;

import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;


/**
 * The day's Stock Locate → symbol mapping, built from Stock Directory messages. Locate codes are assigned per
 * day starting at 1 and appear in every message at the same position, so they are the routing key for
 * per-symbol work; the catalog is what turns a locate back into a symbol.
 */
public final class SymbolCatalog {
    private final Map<Integer, String> symbolByLocate = new LinkedHashMap<>();
    private final Map<String, Integer> locateBySymbol = new LinkedHashMap<>();


    /** Records a directory entry; the same locate may be re-announced with the same symbol only. */
    public void register(ItchMessage.StockDirectory directory) {
        int locate = directory.header().stockLocate();
        if (locate == ItchHeader.marketWideLocate) {
            throw new IllegalArgumentException(
                    "Stock Directory at ordinal " + directory.header().ordinal() + " has the market-wide locate 0");
        }
        String previous = symbolByLocate.putIfAbsent(locate, directory.stock());
        if (previous != null && !previous.equals(directory.stock())) {
            throw new IllegalArgumentException("Locate " + locate + " re-announced as '" + directory.stock()
                    + "' after '" + previous + "' at ordinal " + directory.header().ordinal());
        }
        locateBySymbol.putIfAbsent(directory.stock(), locate);
    }


    /** Feeds one message: directory entries are registered, everything else is ignored. */
    public void observe(ItchMessage message) {
        if (message instanceof ItchMessage.StockDirectory directory) {
            register(directory);
        }
    }


    public boolean contains(int locate) {
        return symbolByLocate.containsKey(locate);
    }


    /** @throws IllegalArgumentException for a locate no Stock Directory message announced */
    public String symbol(int locate) {
        String symbol = symbolByLocate.get(locate);
        if (symbol == null) {
            throw new IllegalArgumentException("Locate " + locate + " is not in the Stock Directory");
        }
        return symbol;
    }


    public Integer locateOrNull(String symbol) {
        return locateBySymbol.get(symbol);
    }


    public Map<Integer, String> symbolsByLocate() {
        return Collections.unmodifiableMap(symbolByLocate);
    }


    public SortedMap<String, Integer> locatesBySymbol() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(locateBySymbol));
    }
}
