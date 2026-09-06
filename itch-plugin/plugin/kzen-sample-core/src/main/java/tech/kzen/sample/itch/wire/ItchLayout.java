package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;


/**
 * Field offsets and message lengths of the ITCH 5.0 wire layout, shared by the decoder and the encoder so a
 * mistake cannot be hidden by a round trip. Offsets are from the message-type byte at offset 0.
 */
final class ItchLayout {
    private ItchLayout() {}

    static final int typeOffset = 0;
    static final int stockLocateOffset = 1;
    static final int trackingNumberOffset = 3;
    static final int timestampOffset = 5;
    static final int timestampLength = 6;
    static final int bodyOffset = 11;

    static final int stockLength = 8;
    static final int mpidLength = 4;
    static final int reasonLength = 4;
    static final int issueSubTypeLength = 2;

    static final int systemEventLength = 12;
    static final int stockDirectoryLength = 39;
    static final int stockTradingActionLength = 25;
    static final int regShoLength = 20;
    static final int marketParticipantPositionLength = 26;
    static final int mwcbDeclineLevelLength = 35;
    static final int mwcbStatusLength = 12;
    static final int ipoQuotingPeriodLength = 28;
    static final int luldAuctionCollarLength = 35;
    static final int operationalHaltLength = 21;
    static final int addOrderLength = 36;
    static final int addOrderAttributedLength = 40;
    static final int orderExecutedLength = 31;
    static final int orderExecutedWithPriceLength = 36;
    static final int orderCancelLength = 23;
    static final int orderDeleteLength = 19;
    static final int orderReplaceLength = 35;
    static final int tradeLength = 44;
    static final int crossTradeLength = 40;
    static final int brokenTradeLength = 19;
    static final int netOrderImbalanceLength = 50;
    static final int retailPriceImprovementLength = 20;
    static final int directListingLength = 48;

    static final char printableYes = 'Y';
    static final char printableNo = 'N';


    /** The exact wire length of a message type, or -1 for a type the specification does not define. */
    static int lengthOf(char type) {
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
