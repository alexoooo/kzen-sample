package tech.kzen.sample.plugin.itch;

import tech.kzen.sample.itch.message.ItchMessage;


/** ITCH prices on the wire are fixed-point with four decimals; rows carry them as decimal doubles. */
public final class ItchPrices {
    private ItchPrices() {}


    public static double toDecimal(long price4) {
        return price4 / (double) ItchMessage.priceScale4;
    }
}
