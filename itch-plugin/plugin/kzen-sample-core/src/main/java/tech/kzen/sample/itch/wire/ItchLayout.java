package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;


/**
 * Field offsets and message lengths of the ITCH 5.0 wire layout, shared by the decoder and the encoder so a
 * mistake cannot be hidden by a round trip. Offsets are from the message-type byte at offset 0.
 */
public final class ItchLayout {
    private ItchLayout() {}

    public static final int typeOffset = 0;
    public static final int stockLocateOffset = 1;
    public static final int trackingNumberOffset = 3;
    public static final int timestampOffset = 5;
    public static final int timestampLength = 6;
    public static final int bodyOffset = 11;

    public static final int stockLength = 8;
    public static final int mpidLength = 4;
    public static final int reasonLength = 4;
    public static final int issueSubTypeLength = 2;

    public static final int systemEventLength = 12;
    public static final int stockDirectoryLength = 39;
    public static final int stockTradingActionLength = 25;
    public static final int regShoLength = 20;
    public static final int marketParticipantPositionLength = 26;
    public static final int mwcbDeclineLevelLength = 35;
    public static final int mwcbStatusLength = 12;
    public static final int ipoQuotingPeriodLength = 28;
    public static final int luldAuctionCollarLength = 35;
    public static final int operationalHaltLength = 21;
    public static final int addOrderLength = 36;
    public static final int addOrderAttributedLength = 40;
    public static final int orderExecutedLength = 31;
    public static final int orderExecutedWithPriceLength = 36;
    public static final int orderCancelLength = 23;
    public static final int orderDeleteLength = 19;
    public static final int orderReplaceLength = 35;
    public static final int tradeLength = 44;
    public static final int crossTradeLength = 40;
    public static final int brokenTradeLength = 19;
    public static final int netOrderImbalanceLength = 50;
    public static final int retailPriceImprovementLength = 20;
    public static final int directListingLength = 48;

    public static final char printableYes = 'Y';
    public static final char printableNo = 'N';


    /** The exact wire length of a message type, or -1 for a type the specification does not define. */
    public static int lengthOf(char type) {
        return switch (type) {
            case ItchMessage.SystemEvent.typeCode -> systemEventLength;
            case ItchMessage.StockDirectory.typeCode -> stockDirectoryLength;
            case ItchMessage.StockTradingAction.typeCode -> stockTradingActionLength;
            case ItchMessage.RegShoRestriction.typeCode -> regShoLength;
            case ItchMessage.MarketParticipantPosition.typeCode -> marketParticipantPositionLength;
            case ItchMessage.MwcbDeclineLevel.typeCode -> mwcbDeclineLevelLength;
            case ItchMessage.MwcbStatus.typeCode -> mwcbStatusLength;
            case ItchMessage.IpoQuotingPeriodUpdate.typeCode -> ipoQuotingPeriodLength;
            case ItchMessage.LuldAuctionCollar.typeCode -> luldAuctionCollarLength;
            case ItchMessage.OperationalHalt.typeCode -> operationalHaltLength;
            case ItchMessage.AddOrder.typeCode -> addOrderLength;
            case ItchMessage.AddOrder.attributedTypeCode -> addOrderAttributedLength;
            case ItchMessage.OrderExecuted.typeCode -> orderExecutedLength;
            case ItchMessage.OrderExecutedWithPrice.typeCode -> orderExecutedWithPriceLength;
            case ItchMessage.OrderCancel.typeCode -> orderCancelLength;
            case ItchMessage.OrderDelete.typeCode -> orderDeleteLength;
            case ItchMessage.OrderReplace.typeCode -> orderReplaceLength;
            case ItchMessage.Trade.typeCode -> tradeLength;
            case ItchMessage.CrossTrade.typeCode -> crossTradeLength;
            case ItchMessage.BrokenTrade.typeCode -> brokenTradeLength;
            case ItchMessage.NetOrderImbalance.typeCode -> netOrderImbalanceLength;
            case ItchMessage.RetailPriceImprovement.typeCode -> retailPriceImprovementLength;
            case ItchMessage.DirectListingCapitalRaise.typeCode -> directListingLength;
            default -> -1;
        };
    }
}
