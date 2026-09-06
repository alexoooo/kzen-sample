package tech.kzen.sample.itch.message;


/**
 * One decoded Nasdaq TotalView-ITCH 5.0 message. Every kind is its own record, so replay and book
 * reconstruction can distinguish the event families the specification distinguishes; the wire layout of each
 * is owned by {@code tech.kzen.sample.itch.wire.ItchDecoder} / {@code ItchEncoder}.
 *
 * Prices are the raw fixed-point integers of the feed: {@code Price(4)} fields carry four implied decimals
 * ({@link #priceScale4}), the MWCB levels carry eight. Alpha fields are trimmed of their right padding except
 * single-character codes, which are kept verbatim (a space is a documented value for several of them).
 */
public sealed interface ItchMessage {
    long priceScale4 = 10_000L;
    long priceScale8 = 100_000_000L;

    ItchHeader header();

    /** The message-type letter of the wire format. */
    char type();


    //-----------------------------------------------------------------------------------------------------------------
    record SystemEvent(ItchHeader header, String eventCode) implements ItchMessage {
        public static final char typeCode = 'S';
        @Override public char type() { return typeCode; }
    }

    record StockDirectory(
            ItchHeader header,
            String stock,
            String marketCategory,
            String financialStatusIndicator,
            long roundLotSize,
            String roundLotsOnly,
            String issueClassification,
            String issueSubType,
            String authenticity,
            String shortSaleThresholdIndicator,
            String ipoFlag,
            String luldReferencePriceTier,
            String etpFlag,
            long etpLeverageFactor,
            String inverseIndicator
    ) implements ItchMessage {
        public static final char typeCode = 'R';
        @Override public char type() { return typeCode; }
    }

    record StockTradingAction(
            ItchHeader header, String stock, String tradingState, String reserved, String reason
    ) implements ItchMessage {
        public static final char typeCode = 'H';
        @Override public char type() { return typeCode; }
    }

    record RegShoRestriction(ItchHeader header, String stock, String regShoAction) implements ItchMessage {
        public static final char typeCode = 'Y';
        @Override public char type() { return typeCode; }
    }

    record MarketParticipantPosition(
            ItchHeader header,
            String mpid,
            String stock,
            String primaryMarketMaker,
            String marketMakerMode,
            String marketParticipantState
    ) implements ItchMessage {
        public static final char typeCode = 'L';
        @Override public char type() { return typeCode; }
    }

    /** Levels are {@code Price(8)}. Always stock locate 0. */
    record MwcbDeclineLevel(ItchHeader header, long level1, long level2, long level3) implements ItchMessage {
        public static final char typeCode = 'V';
        @Override public char type() { return typeCode; }
    }

    record MwcbStatus(ItchHeader header, String breachedLevel) implements ItchMessage {
        public static final char typeCode = 'W';
        @Override public char type() { return typeCode; }
    }

    record IpoQuotingPeriodUpdate(
            ItchHeader header, String stock, long releaseTimeSeconds, String releaseQualifier, long ipoPrice
    ) implements ItchMessage {
        public static final char typeCode = 'K';
        @Override public char type() { return typeCode; }
    }

    record LuldAuctionCollar(
            ItchHeader header,
            String stock,
            long referencePrice,
            long upperCollarPrice,
            long lowerCollarPrice,
            long extension
    ) implements ItchMessage {
        public static final char typeCode = 'J';
        @Override public char type() { return typeCode; }
    }

    record OperationalHalt(ItchHeader header, String stock, String marketCode, String haltAction) implements ItchMessage {
        public static final char typeCode = 'h';
        @Override public char type() { return typeCode; }
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * Add Order, with ({@code F}) or without ({@code A}) market-participant attribution: {@link #attribution} is
     * null for the unattributed form.
     */
    record AddOrder(
            ItchHeader header,
            long orderReference,
            Side side,
            long shares,
            String stock,
            long price,
            String attribution
    ) implements ItchMessage {
        public static final char typeCode = 'A';
        public static final char attributedTypeCode = 'F';
        @Override public char type() { return attribution == null ? typeCode : attributedTypeCode; }
    }

    record OrderExecuted(ItchHeader header, long orderReference, long executedShares, long matchNumber) implements ItchMessage {
        public static final char typeCode = 'E';
        @Override public char type() { return typeCode; }
    }

    record OrderExecutedWithPrice(
            ItchHeader header,
            long orderReference,
            long executedShares,
            long matchNumber,
            boolean printable,
            long executionPrice
    ) implements ItchMessage {
        public static final char typeCode = 'C';
        @Override public char type() { return typeCode; }
    }

    record OrderCancel(ItchHeader header, long orderReference, long cancelledShares) implements ItchMessage {
        public static final char typeCode = 'X';
        @Override public char type() { return typeCode; }
    }

    record OrderDelete(ItchHeader header, long orderReference) implements ItchMessage {
        public static final char typeCode = 'D';
        @Override public char type() { return typeCode; }
    }

    record OrderReplace(
            ItchHeader header, long originalOrderReference, long newOrderReference, long shares, long price
    ) implements ItchMessage {
        public static final char typeCode = 'U';
        @Override public char type() { return typeCode; }
    }


    //-----------------------------------------------------------------------------------------------------------------
    /** A match of a non-displayable order: does not touch the displayed book. */
    record Trade(
            ItchHeader header,
            long orderReference,
            Side side,
            long shares,
            String stock,
            long price,
            long matchNumber
    ) implements ItchMessage {
        public static final char typeCode = 'P';
        @Override public char type() { return typeCode; }
    }

    record CrossTrade(
            ItchHeader header, long shares, String stock, long crossPrice, long matchNumber, String crossType
    ) implements ItchMessage {
        public static final char typeCode = 'Q';
        @Override public char type() { return typeCode; }
    }

    /** Breaks a prior {@link OrderExecuted}, {@link OrderExecutedWithPrice} or {@link Trade} by match number. */
    record BrokenTrade(ItchHeader header, long matchNumber) implements ItchMessage {
        public static final char typeCode = 'B';
        @Override public char type() { return typeCode; }
    }

    record NetOrderImbalance(
            ItchHeader header,
            long pairedShares,
            long imbalanceShares,
            String imbalanceDirection,
            String stock,
            long farPrice,
            long nearPrice,
            long currentReferencePrice,
            String crossType,
            String priceVariationIndicator
    ) implements ItchMessage {
        public static final char typeCode = 'I';
        @Override public char type() { return typeCode; }
    }

    record RetailPriceImprovement(ItchHeader header, String stock, String interestFlag) implements ItchMessage {
        public static final char typeCode = 'N';
        @Override public char type() { return typeCode; }
    }

    record DirectListingCapitalRaise(
            ItchHeader header,
            String stock,
            String openEligibilityStatus,
            long minimumAllowablePrice,
            long maximumAllowablePrice,
            long nearExecutionPrice,
            long nearExecutionTime,
            long lowerPriceRangeCollar,
            long upperPriceRangeCollar
    ) implements ItchMessage {
        public static final char typeCode = 'O';
        @Override public char type() { return typeCode; }
    }
}
