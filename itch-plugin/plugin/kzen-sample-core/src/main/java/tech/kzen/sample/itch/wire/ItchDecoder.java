package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.Side;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static tech.kzen.sample.itch.wire.ItchLayout.*;


/** Decodes one framed ITCH 5.0 message (the payload after the two-byte length prefix) into its record. */
public final class ItchDecoder {
    private ItchDecoder() {}


    /**
     * @param frame    the message bytes starting at the type byte
     * @param ordinal  the message's zero-based feed position, carried into the header
     * @throws ItchFormatException for an unknown type or a length other than the type's exact wire length
     */
    public static ItchMessage decode(byte[] frame, int offset, int length, long ordinal) {
        if (length < bodyOffset) {
            throw new ItchFormatException("Message " + ordinal + " is " + length + " bytes, shorter than any ITCH header");
        }
        char type = (char) (frame[offset + typeOffset] & 0xFF);
        int expectedLength = lengthOf(type);
        if (expectedLength == -1) {
            throw new ItchFormatException("Message " + ordinal + " has unknown type '" + type + "'");
        }
        if (length != expectedLength) {
            throw new ItchFormatException("Message " + ordinal + " of type '" + type + "' is " + length
                    + " bytes, expected " + expectedLength);
        }

        ByteBuffer buffer = ByteBuffer.wrap(frame, offset, length).slice();
        ItchHeader header = new ItchHeader(
                ordinal,
                unsignedShort(buffer, stockLocateOffset),
                unsignedShort(buffer, trackingNumberOffset),
                timestamp(buffer));

        return switch (type) {
            case ItchMessage.SystemEvent.typeCode ->
                    new ItchMessage.SystemEvent(header, alpha(buffer, bodyOffset, 1));

            case ItchMessage.StockDirectory.typeCode -> new ItchMessage.StockDirectory(
                    header,
                    stock(buffer, bodyOffset),
                    alpha(buffer, 19, 1),
                    alpha(buffer, 20, 1),
                    unsignedInt(buffer, 21),
                    alpha(buffer, 25, 1),
                    alpha(buffer, 26, 1),
                    trimmed(buffer, 27, issueSubTypeLength),
                    alpha(buffer, 29, 1),
                    alpha(buffer, 30, 1),
                    alpha(buffer, 31, 1),
                    alpha(buffer, 32, 1),
                    alpha(buffer, 33, 1),
                    unsignedInt(buffer, 34),
                    alpha(buffer, 38, 1));

            case ItchMessage.StockTradingAction.typeCode -> new ItchMessage.StockTradingAction(
                    header, stock(buffer, bodyOffset), alpha(buffer, 19, 1), alpha(buffer, 20, 1),
                    trimmed(buffer, 21, reasonLength));

            case ItchMessage.RegShoRestriction.typeCode -> new ItchMessage.RegShoRestriction(
                    header, stock(buffer, bodyOffset), alpha(buffer, 19, 1));

            case ItchMessage.MarketParticipantPosition.typeCode -> new ItchMessage.MarketParticipantPosition(
                    header, trimmed(buffer, bodyOffset, mpidLength), stock(buffer, 15),
                    alpha(buffer, 23, 1), alpha(buffer, 24, 1), alpha(buffer, 25, 1));

            case ItchMessage.MwcbDeclineLevel.typeCode -> new ItchMessage.MwcbDeclineLevel(
                    header, buffer.getLong(bodyOffset), buffer.getLong(19), buffer.getLong(27));

            case ItchMessage.MwcbStatus.typeCode ->
                    new ItchMessage.MwcbStatus(header, alpha(buffer, bodyOffset, 1));

            case ItchMessage.IpoQuotingPeriodUpdate.typeCode -> new ItchMessage.IpoQuotingPeriodUpdate(
                    header, stock(buffer, bodyOffset), unsignedInt(buffer, 19), alpha(buffer, 23, 1),
                    unsignedInt(buffer, 24));

            case ItchMessage.LuldAuctionCollar.typeCode -> new ItchMessage.LuldAuctionCollar(
                    header, stock(buffer, bodyOffset), unsignedInt(buffer, 19), unsignedInt(buffer, 23),
                    unsignedInt(buffer, 27), unsignedInt(buffer, 31));

            case ItchMessage.OperationalHalt.typeCode -> new ItchMessage.OperationalHalt(
                    header, stock(buffer, bodyOffset), alpha(buffer, 19, 1), alpha(buffer, 20, 1));

            case ItchMessage.AddOrder.typeCode -> new ItchMessage.AddOrder(
                    header, buffer.getLong(bodyOffset), side(buffer, 19), unsignedInt(buffer, 20),
                    stock(buffer, 24), unsignedInt(buffer, 32), null);

            case ItchMessage.AddOrder.attributedTypeCode -> new ItchMessage.AddOrder(
                    header, buffer.getLong(bodyOffset), side(buffer, 19), unsignedInt(buffer, 20),
                    stock(buffer, 24), unsignedInt(buffer, 32), trimmed(buffer, 36, mpidLength));

            case ItchMessage.OrderExecuted.typeCode -> new ItchMessage.OrderExecuted(
                    header, buffer.getLong(bodyOffset), unsignedInt(buffer, 19), buffer.getLong(23));

            case ItchMessage.OrderExecutedWithPrice.typeCode -> new ItchMessage.OrderExecutedWithPrice(
                    header, buffer.getLong(bodyOffset), unsignedInt(buffer, 19), buffer.getLong(23),
                    printable(buffer, 31, ordinal), unsignedInt(buffer, 32));

            case ItchMessage.OrderCancel.typeCode -> new ItchMessage.OrderCancel(
                    header, buffer.getLong(bodyOffset), unsignedInt(buffer, 19));

            case ItchMessage.OrderDelete.typeCode ->
                    new ItchMessage.OrderDelete(header, buffer.getLong(bodyOffset));

            case ItchMessage.OrderReplace.typeCode -> new ItchMessage.OrderReplace(
                    header, buffer.getLong(bodyOffset), buffer.getLong(19), unsignedInt(buffer, 27),
                    unsignedInt(buffer, 31));

            case ItchMessage.Trade.typeCode -> new ItchMessage.Trade(
                    header, buffer.getLong(bodyOffset), side(buffer, 19), unsignedInt(buffer, 20),
                    stock(buffer, 24), unsignedInt(buffer, 32), buffer.getLong(36));

            case ItchMessage.CrossTrade.typeCode -> new ItchMessage.CrossTrade(
                    header, buffer.getLong(bodyOffset), stock(buffer, 19), unsignedInt(buffer, 27),
                    buffer.getLong(31), alpha(buffer, 39, 1));

            case ItchMessage.BrokenTrade.typeCode ->
                    new ItchMessage.BrokenTrade(header, buffer.getLong(bodyOffset));

            case ItchMessage.NetOrderImbalance.typeCode -> new ItchMessage.NetOrderImbalance(
                    header, buffer.getLong(bodyOffset), buffer.getLong(19), alpha(buffer, 27, 1),
                    stock(buffer, 28), unsignedInt(buffer, 36), unsignedInt(buffer, 40), unsignedInt(buffer, 44),
                    alpha(buffer, 48, 1), alpha(buffer, 49, 1));

            case ItchMessage.RetailPriceImprovement.typeCode -> new ItchMessage.RetailPriceImprovement(
                    header, stock(buffer, bodyOffset), alpha(buffer, 19, 1));

            case ItchMessage.DirectListingCapitalRaise.typeCode -> new ItchMessage.DirectListingCapitalRaise(
                    header, stock(buffer, bodyOffset), alpha(buffer, 19, 1), unsignedInt(buffer, 20),
                    unsignedInt(buffer, 24), unsignedInt(buffer, 28), buffer.getLong(32), unsignedInt(buffer, 40),
                    unsignedInt(buffer, 44));

            default -> throw new IllegalStateException("Length table and decoder disagree on type '" + type + "'");
        };
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static long timestamp(ByteBuffer buffer) {
        long value = 0;
        for (int i = 0; i < timestampLength; i++) {
            value = (value << 8) | (buffer.get(timestampOffset + i) & 0xFF);
        }
        return value;
    }

    private static String alpha(ByteBuffer buffer, int offset, int length) {
        byte[] bytes = new byte[length];
        buffer.get(offset, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String trimmed(ByteBuffer buffer, int offset, int length) {
        return alpha(buffer, offset, length).stripTrailing();
    }

    private static String stock(ByteBuffer buffer, int offset) {
        return trimmed(buffer, offset, stockLength);
    }

    private static Side side(ByteBuffer buffer, int offset) {
        return Side.ofCode((char) (buffer.get(offset) & 0xFF));
    }

    private static boolean printable(ByteBuffer buffer, int offset, long ordinal) {
        char flag = (char) (buffer.get(offset) & 0xFF);
        return switch (flag) {
            case printableYes -> true;
            case printableNo -> false;
            default -> throw new ItchFormatException("Message " + ordinal + " has printable flag '" + flag + "'");
        };
    }
}
