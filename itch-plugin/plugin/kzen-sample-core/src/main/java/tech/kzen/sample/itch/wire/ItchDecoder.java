package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.ItchRecord;
import tech.kzen.sample.itch.message.Side;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import static tech.kzen.sample.itch.wire.ItchLayout.*;

public final class ItchDecoder {
    private ItchDecoder() {}

    /** Owns a copy because feed readers reuse their input buffer. Batch loading uses record views directly. */
    public static ItchMessage decode(byte[] frame, int offset, int length, long ordinal) {
        java.util.Objects.checkFromIndexSize(offset, length, frame.length);
        ItchRecord record = new ItchRecord(MemorySegment.ofArray(Arrays.copyOfRange(frame, offset, offset + length)),
                0, length, ordinal, null);
        return view(record);
    }

    public static void validate(ItchRecord record) {
        int length = record.length();
        long ordinal = record.ordinal();
        if (length < bodyOffset) {
            throw new ItchFormatException("Message " + ordinal + " is " + length + " bytes, shorter than any ITCH header");
        }
        char type = record.character(typeOffset);
        int expectedLength = lengthOf(type);
        if (expectedLength == -1) {
            throw new ItchFormatException("Message " + ordinal + " has unknown type '" + type + "'");
        }
        if (length != expectedLength) {
            throw new ItchFormatException("Message " + ordinal + " of type '" + type + "' is " + length
                    + " bytes, expected " + expectedLength);
        }


        if (type == ItchMessage.AddOrder.typeCode || type == ItchMessage.AddOrder.attributedTypeCode
                || type == ItchMessage.Trade.typeCode) Side.ofCode(record.character(19));
        if (type == ItchMessage.OrderExecutedWithPrice.typeCode) {
            char flag = record.character(31);
            if (flag != printableYes && flag != printableNo)
                throw new ItchFormatException("Message " + ordinal + " has printable flag '" + flag + "'");
        }
    }

    public static ItchMessage view(ItchRecord record) {
        validate(record);
        return switch (record.type()) {
            case ItchMessage.SystemEvent.typeCode -> new ItchMessage.SystemEvent(record);
            case ItchMessage.StockDirectory.typeCode -> new ItchMessage.StockDirectory(record);
            case ItchMessage.StockTradingAction.typeCode -> new ItchMessage.StockTradingAction(record);
            case ItchMessage.RegShoRestriction.typeCode -> new ItchMessage.RegShoRestriction(record);
            case ItchMessage.MarketParticipantPosition.typeCode -> new ItchMessage.MarketParticipantPosition(record);
            case ItchMessage.MwcbDeclineLevel.typeCode -> new ItchMessage.MwcbDeclineLevel(record);
            case ItchMessage.MwcbStatus.typeCode -> new ItchMessage.MwcbStatus(record);
            case ItchMessage.IpoQuotingPeriodUpdate.typeCode -> new ItchMessage.IpoQuotingPeriodUpdate(record);
            case ItchMessage.LuldAuctionCollar.typeCode -> new ItchMessage.LuldAuctionCollar(record);
            case ItchMessage.OperationalHalt.typeCode -> new ItchMessage.OperationalHalt(record);
            case ItchMessage.AddOrder.typeCode, ItchMessage.AddOrder.attributedTypeCode -> new ItchMessage.AddOrder(record);
            case ItchMessage.OrderExecuted.typeCode -> new ItchMessage.OrderExecuted(record);
            case ItchMessage.OrderExecutedWithPrice.typeCode -> new ItchMessage.OrderExecutedWithPrice(record);
            case ItchMessage.OrderCancel.typeCode -> new ItchMessage.OrderCancel(record);
            case ItchMessage.OrderDelete.typeCode -> new ItchMessage.OrderDelete(record);
            case ItchMessage.OrderReplace.typeCode -> new ItchMessage.OrderReplace(record);
            case ItchMessage.Trade.typeCode -> new ItchMessage.Trade(record);
            case ItchMessage.CrossTrade.typeCode -> new ItchMessage.CrossTrade(record);
            case ItchMessage.BrokenTrade.typeCode -> new ItchMessage.BrokenTrade(record);
            case ItchMessage.NetOrderImbalance.typeCode -> new ItchMessage.NetOrderImbalance(record);
            case ItchMessage.RetailPriceImprovement.typeCode -> new ItchMessage.RetailPriceImprovement(record);
            case ItchMessage.DirectListingCapitalRaise.typeCode -> new ItchMessage.DirectListingCapitalRaise(record);
            default -> throw new IllegalStateException("Validated record has unknown type");
        };
    }
}
