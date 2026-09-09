package tech.kzen.sample.itch.message;

import static tech.kzen.sample.itch.wire.ItchLayout.*;

/** Typed field views over encoded ITCH records. Text is decoded only when an accessor requests it. */
public sealed interface ItchMessage {
    long priceScale4 = 10_000L;
    long priceScale8 = 100_000_000L;

    ItchRecord record();
    default ItchHeader header() { return record().header(); }
    default char type() { return record().type(); }
    default ItchHeader getHeader() { return header(); }
    default String getType() { return Character.toString(type()); }

    abstract non-sealed class View implements ItchMessage {
        protected final ItchRecord record;

        protected View(ItchRecord record) { this.record = java.util.Objects.requireNonNull(record); }
        @Override public final ItchRecord record() { return record; }
        @Override public final boolean equals(Object other) {
            return this == other || other != null && getClass() == other.getClass() && record.equals(((View) other).record);
        }
        @Override public final int hashCode() { return record.hashCode(); }
        @Override public String toString() { return getClass().getSimpleName() + "[ordinal=" + record.ordinal() + "]"; }
    }

    final class SystemEvent extends View {
        public static final char typeCode = 'S';
        public SystemEvent(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for SystemEvent");
        }

        public SystemEvent(ItchHeader header, String eventCode) {
            this(encode(header, eventCode));
        }

        private static ItchRecord encode(ItchHeader header, String eventCode) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, 1, eventCode);
            return record.readOnly();
        }

        public String eventCode() { return record.alpha(bodyOffset, 1); }
        public String getEventCode() { return eventCode(); }
    }

    final class StockDirectory extends View {
        private static final int marketCategoryOffset = 19;
        private static final int financialStatusIndicatorOffset = 20;
        private static final int roundLotSizeOffset = 21;
        private static final int roundLotsOnlyOffset = 25;
        private static final int authenticityOffset = 29;
        private static final int shortSaleThresholdIndicatorOffset = 30;
        private static final int ipoFlagOffset = 31;
        private static final int luldReferencePriceTierOffset = 32;
        private static final int etpFlagOffset = 33;
        private static final int etpLeverageFactorOffset = 34;
        private static final int inverseIndicatorOffset = 38;

        public static final char typeCode = 'R';
        public StockDirectory(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for StockDirectory");
        }

        public StockDirectory(ItchHeader header, String stock, String marketCategory, String financialStatusIndicator, long roundLotSize, String roundLotsOnly, String issueClassification, String issueSubType, String authenticity, String shortSaleThresholdIndicator, String ipoFlag, String luldReferencePriceTier, String etpFlag, long etpLeverageFactor, String inverseIndicator) {
            this(encode(header, stock, marketCategory, financialStatusIndicator, roundLotSize, roundLotsOnly, issueClassification, issueSubType, authenticity, shortSaleThresholdIndicator, ipoFlag, luldReferencePriceTier, etpFlag, etpLeverageFactor, inverseIndicator));
        }

        private static ItchRecord encode(ItchHeader header, String stock, String marketCategory, String financialStatusIndicator, long roundLotSize, String roundLotsOnly, String issueClassification, String issueSubType, String authenticity, String shortSaleThresholdIndicator, String ipoFlag, String luldReferencePriceTier, String etpFlag, long etpLeverageFactor, String inverseIndicator) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putAlpha(marketCategoryOffset, 1, marketCategory);
            record.putAlpha(financialStatusIndicatorOffset, 1, financialStatusIndicator);
            record.putInt(roundLotSizeOffset, roundLotSize);
            record.putAlpha(roundLotsOnlyOffset, 1, roundLotsOnly);
            record.putAlpha(26, 1, issueClassification);
            record.putAlpha(27, issueSubTypeLength, issueSubType);
            record.putAlpha(authenticityOffset, 1, authenticity);
            record.putAlpha(shortSaleThresholdIndicatorOffset, 1, shortSaleThresholdIndicator);
            record.putAlpha(ipoFlagOffset, 1, ipoFlag);
            record.putAlpha(luldReferencePriceTierOffset, 1, luldReferencePriceTier);
            record.putAlpha(etpFlagOffset, 1, etpFlag);
            record.putInt(etpLeverageFactorOffset, etpLeverageFactor);
            record.putAlpha(inverseIndicatorOffset, 1, inverseIndicator);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public String marketCategory() { return record.alpha(marketCategoryOffset, 1); }
        public String getMarketCategory() { return marketCategory(); }
        public String financialStatusIndicator() { return record.alpha(financialStatusIndicatorOffset, 1); }
        public String getFinancialStatusIndicator() { return financialStatusIndicator(); }
        public long roundLotSize() { return record.unsignedInt(roundLotSizeOffset); }
        public long getRoundLotSize() { return roundLotSize(); }
        public String roundLotsOnly() { return record.alpha(roundLotsOnlyOffset, 1); }
        public String getRoundLotsOnly() { return roundLotsOnly(); }
        public String issueClassification() { return record.alpha(26, 1); }
        public String getIssueClassification() { return issueClassification(); }
        public String issueSubType() { return record.trimmed(27, issueSubTypeLength); }
        public String getIssueSubType() { return issueSubType(); }
        public String authenticity() { return record.alpha(authenticityOffset, 1); }
        public String getAuthenticity() { return authenticity(); }
        public String shortSaleThresholdIndicator() { return record.alpha(shortSaleThresholdIndicatorOffset, 1); }
        public String getShortSaleThresholdIndicator() { return shortSaleThresholdIndicator(); }
        public String ipoFlag() { return record.alpha(ipoFlagOffset, 1); }
        public String getIpoFlag() { return ipoFlag(); }
        public String luldReferencePriceTier() { return record.alpha(luldReferencePriceTierOffset, 1); }
        public String getLuldReferencePriceTier() { return luldReferencePriceTier(); }
        public String etpFlag() { return record.alpha(etpFlagOffset, 1); }
        public String getEtpFlag() { return etpFlag(); }
        public long etpLeverageFactor() { return record.unsignedInt(etpLeverageFactorOffset); }
        public long getEtpLeverageFactor() { return etpLeverageFactor(); }
        public String inverseIndicator() { return record.alpha(inverseIndicatorOffset, 1); }
        public String getInverseIndicator() { return inverseIndicator(); }
    }

    final class StockTradingAction extends View {
        private static final int tradingStateOffset = 19;
        private static final int reservedOffset = 20;
        private static final int reasonOffset = 21;

        public static final char typeCode = 'H';
        public StockTradingAction(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for StockTradingAction");
        }

        public StockTradingAction(ItchHeader header, String stock, String tradingState, String reserved, String reason) {
            this(encode(header, stock, tradingState, reserved, reason));
        }

        private static ItchRecord encode(ItchHeader header, String stock, String tradingState, String reserved, String reason) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putAlpha(tradingStateOffset, 1, tradingState);
            record.putAlpha(reservedOffset, 1, reserved);
            record.putAlpha(reasonOffset, reasonLength, reason);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public String tradingState() { return record.alpha(tradingStateOffset, 1); }
        public String getTradingState() { return tradingState(); }
        public String reserved() { return record.alpha(reservedOffset, 1); }
        public String getReserved() { return reserved(); }
        public String reason() { return record.trimmed(reasonOffset, reasonLength); }
        public String getReason() { return reason(); }
    }

    final class RegShoRestriction extends View {
        private static final int regShoActionOffset = 19;

        public static final char typeCode = 'Y';
        public RegShoRestriction(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for RegShoRestriction");
        }

        public RegShoRestriction(ItchHeader header, String stock, String regShoAction) {
            this(encode(header, stock, regShoAction));
        }

        private static ItchRecord encode(ItchHeader header, String stock, String regShoAction) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putAlpha(regShoActionOffset, 1, regShoAction);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public String regShoAction() { return record.alpha(regShoActionOffset, 1); }
        public String getRegShoAction() { return regShoAction(); }
    }

    final class MarketParticipantPosition extends View {
        private static final int stockOffset = 15;
        private static final int primaryMarketMakerOffset = 23;
        private static final int marketMakerModeOffset = 24;
        private static final int marketParticipantStateOffset = 25;

        public static final char typeCode = 'L';
        public MarketParticipantPosition(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for MarketParticipantPosition");
        }

        public MarketParticipantPosition(ItchHeader header, String mpid, String stock, String primaryMarketMaker, String marketMakerMode, String marketParticipantState) {
            this(encode(header, mpid, stock, primaryMarketMaker, marketMakerMode, marketParticipantState));
        }

        private static ItchRecord encode(ItchHeader header, String mpid, String stock, String primaryMarketMaker, String marketMakerMode, String marketParticipantState) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, mpidLength, mpid);
            record.putAlpha(stockOffset, stockLength, stock);
            record.putAlpha(primaryMarketMakerOffset, 1, primaryMarketMaker);
            record.putAlpha(marketMakerModeOffset, 1, marketMakerMode);
            record.putAlpha(marketParticipantStateOffset, 1, marketParticipantState);
            return record.readOnly();
        }

        public String mpid() { return record.trimmed(bodyOffset, mpidLength); }
        public String getMpid() { return mpid(); }
        public String stock() { return record.trimmed(stockOffset, stockLength); }
        public String getStock() { return stock(); }
        public String primaryMarketMaker() { return record.alpha(primaryMarketMakerOffset, 1); }
        public String getPrimaryMarketMaker() { return primaryMarketMaker(); }
        public String marketMakerMode() { return record.alpha(marketMakerModeOffset, 1); }
        public String getMarketMakerMode() { return marketMakerMode(); }
        public String marketParticipantState() { return record.alpha(marketParticipantStateOffset, 1); }
        public String getMarketParticipantState() { return marketParticipantState(); }
    }

    final class MwcbDeclineLevel extends View {
        private static final int level2Offset = 19;
        private static final int level3Offset = 27;

        public static final char typeCode = 'V';
        public MwcbDeclineLevel(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for MwcbDeclineLevel");
        }

        public MwcbDeclineLevel(ItchHeader header, long level1, long level2, long level3) {
            this(encode(header, level1, level2, level3));
        }

        private static ItchRecord encode(ItchHeader header, long level1, long level2, long level3) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, level1);
            record.putLong(level2Offset, level2);
            record.putLong(level3Offset, level3);
            return record.readOnly();
        }

        public long level1() { return record.longValue(bodyOffset); }
        public long getLevel1() { return level1(); }
        public long level2() { return record.longValue(level2Offset); }
        public long getLevel2() { return level2(); }
        public long level3() { return record.longValue(level3Offset); }
        public long getLevel3() { return level3(); }
    }

    final class MwcbStatus extends View {
        public static final char typeCode = 'W';
        public MwcbStatus(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for MwcbStatus");
        }

        public MwcbStatus(ItchHeader header, String breachedLevel) {
            this(encode(header, breachedLevel));
        }

        private static ItchRecord encode(ItchHeader header, String breachedLevel) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, 1, breachedLevel);
            return record.readOnly();
        }

        public String breachedLevel() { return record.alpha(bodyOffset, 1); }
        public String getBreachedLevel() { return breachedLevel(); }
    }

    final class IpoQuotingPeriodUpdate extends View {
        private static final int releaseTimeSecondsOffset = 19;
        private static final int releaseQualifierOffset = 23;
        private static final int ipoPriceOffset = 24;

        public static final char typeCode = 'K';
        public IpoQuotingPeriodUpdate(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for IpoQuotingPeriodUpdate");
        }

        public IpoQuotingPeriodUpdate(ItchHeader header, String stock, long releaseTimeSeconds, String releaseQualifier, long ipoPrice) {
            this(encode(header, stock, releaseTimeSeconds, releaseQualifier, ipoPrice));
        }

        private static ItchRecord encode(ItchHeader header, String stock, long releaseTimeSeconds, String releaseQualifier, long ipoPrice) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putInt(releaseTimeSecondsOffset, releaseTimeSeconds);
            record.putAlpha(releaseQualifierOffset, 1, releaseQualifier);
            record.putInt(ipoPriceOffset, ipoPrice);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public long releaseTimeSeconds() { return record.unsignedInt(releaseTimeSecondsOffset); }
        public long getReleaseTimeSeconds() { return releaseTimeSeconds(); }
        public String releaseQualifier() { return record.alpha(releaseQualifierOffset, 1); }
        public String getReleaseQualifier() { return releaseQualifier(); }
        public long ipoPrice() { return record.unsignedInt(ipoPriceOffset); }
        public long getIpoPrice() { return ipoPrice(); }
    }

    final class LuldAuctionCollar extends View {
        private static final int referencePriceOffset = 19;
        private static final int upperCollarPriceOffset = 23;
        private static final int lowerCollarPriceOffset = 27;
        private static final int extensionOffset = 31;

        public static final char typeCode = 'J';
        public LuldAuctionCollar(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for LuldAuctionCollar");
        }

        public LuldAuctionCollar(ItchHeader header, String stock, long referencePrice, long upperCollarPrice, long lowerCollarPrice, long extension) {
            this(encode(header, stock, referencePrice, upperCollarPrice, lowerCollarPrice, extension));
        }

        private static ItchRecord encode(ItchHeader header, String stock, long referencePrice, long upperCollarPrice, long lowerCollarPrice, long extension) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putInt(referencePriceOffset, referencePrice);
            record.putInt(upperCollarPriceOffset, upperCollarPrice);
            record.putInt(lowerCollarPriceOffset, lowerCollarPrice);
            record.putInt(extensionOffset, extension);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public long referencePrice() { return record.unsignedInt(referencePriceOffset); }
        public long getReferencePrice() { return referencePrice(); }
        public long upperCollarPrice() { return record.unsignedInt(upperCollarPriceOffset); }
        public long getUpperCollarPrice() { return upperCollarPrice(); }
        public long lowerCollarPrice() { return record.unsignedInt(lowerCollarPriceOffset); }
        public long getLowerCollarPrice() { return lowerCollarPrice(); }
        public long extension() { return record.unsignedInt(extensionOffset); }
        public long getExtension() { return extension(); }
    }

    final class OperationalHalt extends View {
        private static final int marketCodeOffset = 19;
        private static final int haltActionOffset = 20;

        public static final char typeCode = 'h';
        public OperationalHalt(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for OperationalHalt");
        }

        public OperationalHalt(ItchHeader header, String stock, String marketCode, String haltAction) {
            this(encode(header, stock, marketCode, haltAction));
        }

        private static ItchRecord encode(ItchHeader header, String stock, String marketCode, String haltAction) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putAlpha(marketCodeOffset, 1, marketCode);
            record.putAlpha(haltActionOffset, 1, haltAction);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public String marketCode() { return record.alpha(marketCodeOffset, 1); }
        public String getMarketCode() { return marketCode(); }
        public String haltAction() { return record.alpha(haltActionOffset, 1); }
        public String getHaltAction() { return haltAction(); }
    }

    final class AddOrder extends View {
        private static final int sideOffset = 19;
        private static final int sharesOffset = 20;
        private static final int stockOffset = 24;
        private static final int priceOffset = 32;
        private static final int attributionOffset = 36;

        public static final char typeCode = 'A';
        public static final char attributedTypeCode = 'F';
        public AddOrder(ItchRecord record) {
            super(record);
            if (record.type() != typeCode && record.type() != attributedTypeCode) throw new IllegalArgumentException("Wrong record type for AddOrder");
        }

        public AddOrder(ItchHeader header, long orderReference, Side side, long shares, String stock, long price, String attribution) {
            this(encode(header, orderReference, side, shares, stock, price, attribution));
        }

        private static ItchRecord encode(ItchHeader header, long orderReference, Side side, long shares, String stock, long price, String attribution) {
            ItchRecord record = ItchRecord.allocate(header, attribution == null ? typeCode : attributedTypeCode);
            record.putLong(bodyOffset, orderReference);
            record.putByte(sideOffset, side.code());
            record.putInt(sharesOffset, shares);
            record.putAlpha(stockOffset, stockLength, stock);
            record.putInt(priceOffset, price);
            if (attribution != null) record.putAlpha(attributionOffset, mpidLength, attribution);
            return record.readOnly();
        }

        public long orderReference() { return record.longValue(bodyOffset); }
        public long getOrderReference() { return orderReference(); }
        public Side side() { return Side.ofCode(record.character(sideOffset)); }
        public Side getSide() { return side(); }
        public long shares() { return record.unsignedInt(sharesOffset); }
        public long getShares() { return shares(); }
        public String stock() { return record.trimmed(stockOffset, stockLength); }
        public String getStock() { return stock(); }
        public long price() { return record.unsignedInt(priceOffset); }
        public long getPrice() { return price(); }
        public String attribution() { return type() == typeCode ? null : record.trimmed(attributionOffset, mpidLength); }
        public String getAttribution() { return attribution(); }
    }

    final class OrderExecuted extends View {
        private static final int executedSharesOffset = 19;
        private static final int matchNumberOffset = 23;

        public static final char typeCode = 'E';
        public OrderExecuted(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for OrderExecuted");
        }

        public OrderExecuted(ItchHeader header, long orderReference, long executedShares, long matchNumber) {
            this(encode(header, orderReference, executedShares, matchNumber));
        }

        private static ItchRecord encode(ItchHeader header, long orderReference, long executedShares, long matchNumber) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, orderReference);
            record.putInt(executedSharesOffset, executedShares);
            record.putLong(matchNumberOffset, matchNumber);
            return record.readOnly();
        }

        public long orderReference() { return record.longValue(bodyOffset); }
        public long getOrderReference() { return orderReference(); }
        public long executedShares() { return record.unsignedInt(executedSharesOffset); }
        public long getExecutedShares() { return executedShares(); }
        public long matchNumber() { return record.longValue(matchNumberOffset); }
        public long getMatchNumber() { return matchNumber(); }
    }

    final class OrderExecutedWithPrice extends View {
        private static final int executedSharesOffset = 19;
        private static final int matchNumberOffset = 23;
        private static final int printableOffset = 31;
        private static final int executionPriceOffset = 32;

        public static final char typeCode = 'C';
        public OrderExecutedWithPrice(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for OrderExecutedWithPrice");
        }

        public OrderExecutedWithPrice(ItchHeader header, long orderReference, long executedShares, long matchNumber, boolean printable, long executionPrice) {
            this(encode(header, orderReference, executedShares, matchNumber, printable, executionPrice));
        }

        private static ItchRecord encode(ItchHeader header, long orderReference, long executedShares, long matchNumber, boolean printable, long executionPrice) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, orderReference);
            record.putInt(executedSharesOffset, executedShares);
            record.putLong(matchNumberOffset, matchNumber);
            record.putByte(printableOffset, printable ? printableYes : printableNo);
            record.putInt(executionPriceOffset, executionPrice);
            return record.readOnly();
        }

        public long orderReference() { return record.longValue(bodyOffset); }
        public long getOrderReference() { return orderReference(); }
        public long executedShares() { return record.unsignedInt(executedSharesOffset); }
        public long getExecutedShares() { return executedShares(); }
        public long matchNumber() { return record.longValue(matchNumberOffset); }
        public long getMatchNumber() { return matchNumber(); }
        public boolean printable() { return record.character(printableOffset) == printableYes; }
        public boolean isPrintable() { return printable(); }
        public long executionPrice() { return record.unsignedInt(executionPriceOffset); }
        public long getExecutionPrice() { return executionPrice(); }
    }

    final class OrderCancel extends View {
        private static final int cancelledSharesOffset = 19;

        public static final char typeCode = 'X';
        public OrderCancel(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for OrderCancel");
        }

        public OrderCancel(ItchHeader header, long orderReference, long cancelledShares) {
            this(encode(header, orderReference, cancelledShares));
        }

        private static ItchRecord encode(ItchHeader header, long orderReference, long cancelledShares) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, orderReference);
            record.putInt(cancelledSharesOffset, cancelledShares);
            return record.readOnly();
        }

        public long orderReference() { return record.longValue(bodyOffset); }
        public long getOrderReference() { return orderReference(); }
        public long cancelledShares() { return record.unsignedInt(cancelledSharesOffset); }
        public long getCancelledShares() { return cancelledShares(); }
    }

    final class OrderDelete extends View {
        public static final char typeCode = 'D';
        public OrderDelete(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for OrderDelete");
        }

        public OrderDelete(ItchHeader header, long orderReference) {
            this(encode(header, orderReference));
        }

        private static ItchRecord encode(ItchHeader header, long orderReference) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, orderReference);
            return record.readOnly();
        }

        public long orderReference() { return record.longValue(bodyOffset); }
        public long getOrderReference() { return orderReference(); }
    }

    final class OrderReplace extends View {
        private static final int newOrderReferenceOffset = 19;
        private static final int sharesOffset = 27;
        private static final int priceOffset = 31;

        public static final char typeCode = 'U';
        public OrderReplace(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for OrderReplace");
        }

        public OrderReplace(ItchHeader header, long originalOrderReference, long newOrderReference, long shares, long price) {
            this(encode(header, originalOrderReference, newOrderReference, shares, price));
        }

        private static ItchRecord encode(ItchHeader header, long originalOrderReference, long newOrderReference, long shares, long price) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, originalOrderReference);
            record.putLong(newOrderReferenceOffset, newOrderReference);
            record.putInt(sharesOffset, shares);
            record.putInt(priceOffset, price);
            return record.readOnly();
        }

        public long originalOrderReference() { return record.longValue(bodyOffset); }
        public long getOriginalOrderReference() { return originalOrderReference(); }
        public long newOrderReference() { return record.longValue(newOrderReferenceOffset); }
        public long getNewOrderReference() { return newOrderReference(); }
        public long shares() { return record.unsignedInt(sharesOffset); }
        public long getShares() { return shares(); }
        public long price() { return record.unsignedInt(priceOffset); }
        public long getPrice() { return price(); }
    }

    final class Trade extends View {
        private static final int sideOffset = 19;
        private static final int sharesOffset = 20;
        private static final int stockOffset = 24;
        private static final int priceOffset = 32;
        private static final int matchNumberOffset = 36;

        public static final char typeCode = 'P';
        public Trade(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for Trade");
        }

        public Trade(ItchHeader header, long orderReference, Side side, long shares, String stock, long price, long matchNumber) {
            this(encode(header, orderReference, side, shares, stock, price, matchNumber));
        }

        private static ItchRecord encode(ItchHeader header, long orderReference, Side side, long shares, String stock, long price, long matchNumber) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, orderReference);
            record.putByte(sideOffset, side.code());
            record.putInt(sharesOffset, shares);
            record.putAlpha(stockOffset, stockLength, stock);
            record.putInt(priceOffset, price);
            record.putLong(matchNumberOffset, matchNumber);
            return record.readOnly();
        }

        public long orderReference() { return record.longValue(bodyOffset); }
        public long getOrderReference() { return orderReference(); }
        public Side side() { return Side.ofCode(record.character(sideOffset)); }
        public Side getSide() { return side(); }
        public long shares() { return record.unsignedInt(sharesOffset); }
        public long getShares() { return shares(); }
        public String stock() { return record.trimmed(stockOffset, stockLength); }
        public String getStock() { return stock(); }
        public long price() { return record.unsignedInt(priceOffset); }
        public long getPrice() { return price(); }
        public long matchNumber() { return record.longValue(matchNumberOffset); }
        public long getMatchNumber() { return matchNumber(); }
    }

    final class CrossTrade extends View {
        private static final int stockOffset = 19;
        private static final int crossPriceOffset = 27;
        private static final int matchNumberOffset = 31;
        private static final int crossTypeOffset = 39;

        public static final char typeCode = 'Q';
        public CrossTrade(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for CrossTrade");
        }

        public CrossTrade(ItchHeader header, long shares, String stock, long crossPrice, long matchNumber, String crossType) {
            this(encode(header, shares, stock, crossPrice, matchNumber, crossType));
        }

        private static ItchRecord encode(ItchHeader header, long shares, String stock, long crossPrice, long matchNumber, String crossType) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, shares);
            record.putAlpha(stockOffset, stockLength, stock);
            record.putInt(crossPriceOffset, crossPrice);
            record.putLong(matchNumberOffset, matchNumber);
            record.putAlpha(crossTypeOffset, 1, crossType);
            return record.readOnly();
        }

        public long shares() { return record.longValue(bodyOffset); }
        public long getShares() { return shares(); }
        public String stock() { return record.trimmed(stockOffset, stockLength); }
        public String getStock() { return stock(); }
        public long crossPrice() { return record.unsignedInt(crossPriceOffset); }
        public long getCrossPrice() { return crossPrice(); }
        public long matchNumber() { return record.longValue(matchNumberOffset); }
        public long getMatchNumber() { return matchNumber(); }
        public String crossType() { return record.alpha(crossTypeOffset, 1); }
        public String getCrossType() { return crossType(); }
    }

    final class BrokenTrade extends View {
        public static final char typeCode = 'B';
        public BrokenTrade(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for BrokenTrade");
        }

        public BrokenTrade(ItchHeader header, long matchNumber) {
            this(encode(header, matchNumber));
        }

        private static ItchRecord encode(ItchHeader header, long matchNumber) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, matchNumber);
            return record.readOnly();
        }

        public long matchNumber() { return record.longValue(bodyOffset); }
        public long getMatchNumber() { return matchNumber(); }
    }

    final class NetOrderImbalance extends View {
        private static final int imbalanceSharesOffset = 19;
        private static final int imbalanceDirectionOffset = 27;
        private static final int stockOffset = 28;
        private static final int farPriceOffset = 36;
        private static final int nearPriceOffset = 40;
        private static final int currentReferencePriceOffset = 44;
        private static final int crossTypeOffset = 48;
        private static final int priceVariationIndicatorOffset = 49;

        public static final char typeCode = 'I';
        public NetOrderImbalance(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for NetOrderImbalance");
        }

        public NetOrderImbalance(ItchHeader header, long pairedShares, long imbalanceShares, String imbalanceDirection, String stock, long farPrice, long nearPrice, long currentReferencePrice, String crossType, String priceVariationIndicator) {
            this(encode(header, pairedShares, imbalanceShares, imbalanceDirection, stock, farPrice, nearPrice, currentReferencePrice, crossType, priceVariationIndicator));
        }

        private static ItchRecord encode(ItchHeader header, long pairedShares, long imbalanceShares, String imbalanceDirection, String stock, long farPrice, long nearPrice, long currentReferencePrice, String crossType, String priceVariationIndicator) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putLong(bodyOffset, pairedShares);
            record.putLong(imbalanceSharesOffset, imbalanceShares);
            record.putAlpha(imbalanceDirectionOffset, 1, imbalanceDirection);
            record.putAlpha(stockOffset, stockLength, stock);
            record.putInt(farPriceOffset, farPrice);
            record.putInt(nearPriceOffset, nearPrice);
            record.putInt(currentReferencePriceOffset, currentReferencePrice);
            record.putAlpha(crossTypeOffset, 1, crossType);
            record.putAlpha(priceVariationIndicatorOffset, 1, priceVariationIndicator);
            return record.readOnly();
        }

        public long pairedShares() { return record.longValue(bodyOffset); }
        public long getPairedShares() { return pairedShares(); }
        public long imbalanceShares() { return record.longValue(imbalanceSharesOffset); }
        public long getImbalanceShares() { return imbalanceShares(); }
        public String imbalanceDirection() { return record.alpha(imbalanceDirectionOffset, 1); }
        public String getImbalanceDirection() { return imbalanceDirection(); }
        public String stock() { return record.trimmed(stockOffset, stockLength); }
        public String getStock() { return stock(); }
        public long farPrice() { return record.unsignedInt(farPriceOffset); }
        public long getFarPrice() { return farPrice(); }
        public long nearPrice() { return record.unsignedInt(nearPriceOffset); }
        public long getNearPrice() { return nearPrice(); }
        public long currentReferencePrice() { return record.unsignedInt(currentReferencePriceOffset); }
        public long getCurrentReferencePrice() { return currentReferencePrice(); }
        public String crossType() { return record.alpha(crossTypeOffset, 1); }
        public String getCrossType() { return crossType(); }
        public String priceVariationIndicator() { return record.alpha(priceVariationIndicatorOffset, 1); }
        public String getPriceVariationIndicator() { return priceVariationIndicator(); }
    }

    final class RetailPriceImprovement extends View {
        private static final int interestFlagOffset = 19;

        public static final char typeCode = 'N';
        public RetailPriceImprovement(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for RetailPriceImprovement");
        }

        public RetailPriceImprovement(ItchHeader header, String stock, String interestFlag) {
            this(encode(header, stock, interestFlag));
        }

        private static ItchRecord encode(ItchHeader header, String stock, String interestFlag) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putAlpha(interestFlagOffset, 1, interestFlag);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public String interestFlag() { return record.alpha(interestFlagOffset, 1); }
        public String getInterestFlag() { return interestFlag(); }
    }

    final class DirectListingCapitalRaise extends View {
        private static final int openEligibilityStatusOffset = 19;
        private static final int minimumAllowablePriceOffset = 20;
        private static final int maximumAllowablePriceOffset = 24;
        private static final int nearExecutionPriceOffset = 28;
        private static final int nearExecutionTimeOffset = 32;
        private static final int lowerPriceRangeCollarOffset = 40;
        private static final int upperPriceRangeCollarOffset = 44;

        public static final char typeCode = 'O';
        public DirectListingCapitalRaise(ItchRecord record) {
            super(record);
            if (record.type() != typeCode) throw new IllegalArgumentException("Wrong record type for DirectListingCapitalRaise");
        }

        public DirectListingCapitalRaise(ItchHeader header, String stock, String openEligibilityStatus, long minimumAllowablePrice, long maximumAllowablePrice, long nearExecutionPrice, long nearExecutionTime, long lowerPriceRangeCollar, long upperPriceRangeCollar) {
            this(encode(header, stock, openEligibilityStatus, minimumAllowablePrice, maximumAllowablePrice, nearExecutionPrice, nearExecutionTime, lowerPriceRangeCollar, upperPriceRangeCollar));
        }

        private static ItchRecord encode(ItchHeader header, String stock, String openEligibilityStatus, long minimumAllowablePrice, long maximumAllowablePrice, long nearExecutionPrice, long nearExecutionTime, long lowerPriceRangeCollar, long upperPriceRangeCollar) {
            ItchRecord record = ItchRecord.allocate(header, typeCode);
            record.putAlpha(bodyOffset, stockLength, stock);
            record.putAlpha(openEligibilityStatusOffset, 1, openEligibilityStatus);
            record.putInt(minimumAllowablePriceOffset, minimumAllowablePrice);
            record.putInt(maximumAllowablePriceOffset, maximumAllowablePrice);
            record.putInt(nearExecutionPriceOffset, nearExecutionPrice);
            record.putLong(nearExecutionTimeOffset, nearExecutionTime);
            record.putInt(lowerPriceRangeCollarOffset, lowerPriceRangeCollar);
            record.putInt(upperPriceRangeCollarOffset, upperPriceRangeCollar);
            return record.readOnly();
        }

        public String stock() { return record.trimmed(bodyOffset, stockLength); }
        public String getStock() { return stock(); }
        public String openEligibilityStatus() { return record.alpha(openEligibilityStatusOffset, 1); }
        public String getOpenEligibilityStatus() { return openEligibilityStatus(); }
        public long minimumAllowablePrice() { return record.unsignedInt(minimumAllowablePriceOffset); }
        public long getMinimumAllowablePrice() { return minimumAllowablePrice(); }
        public long maximumAllowablePrice() { return record.unsignedInt(maximumAllowablePriceOffset); }
        public long getMaximumAllowablePrice() { return maximumAllowablePrice(); }
        public long nearExecutionPrice() { return record.unsignedInt(nearExecutionPriceOffset); }
        public long getNearExecutionPrice() { return nearExecutionPrice(); }
        public long nearExecutionTime() { return record.longValue(nearExecutionTimeOffset); }
        public long getNearExecutionTime() { return nearExecutionTime(); }
        public long lowerPriceRangeCollar() { return record.unsignedInt(lowerPriceRangeCollarOffset); }
        public long getLowerPriceRangeCollar() { return lowerPriceRangeCollar(); }
        public long upperPriceRangeCollar() { return record.unsignedInt(upperPriceRangeCollarOffset); }
        public long getUpperPriceRangeCollar() { return upperPriceRangeCollar(); }
    }

}
