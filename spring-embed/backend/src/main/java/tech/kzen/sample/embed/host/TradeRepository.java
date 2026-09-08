package tech.kzen.sample.embed.host;

import tech.kzen.sample.itch.analysis.SymbolTradeSummary;

import java.util.SortedMap;


/**
 * The host's own trade view: per symbol, the standing printed trade events and shares of the loaded day — the
 * one named result the file route, the store route and this host route all produce. Computed by the host
 * through the plain core (the message fold, no materialization), served on the host's own endpoint and handed
 * to kzen as a {@code @Service}.
 */
public interface TradeRepository {
    boolean available();

    SortedMap<String, SymbolTradeSummary> tradeVolume();
}
