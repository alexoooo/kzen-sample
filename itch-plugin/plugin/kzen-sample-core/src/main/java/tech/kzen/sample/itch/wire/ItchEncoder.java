package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static tech.kzen.sample.itch.wire.ItchLayout.*;


/** Encodes a message record back to its exact ITCH 5.0 wire bytes; the inverse of {@link ItchDecoder}. */
public final class ItchEncoder {
    private ItchEncoder() {}

    private static final byte padding = ' ';
    private static final int lengthPrefixBytes = 2;


    /** The message bytes without the two-byte length prefix. */
    public static byte[] encode(ItchMessage message) {
        char type = message.type();
        ByteBuffer buffer = ByteBuffer.allocate(lengthOf(type));
        Arrays.fill(buffer.array(), padding);
        buffer.put(typeOffset, (byte) type);
        header(buffer, message.header());

        switch (message) {
            case ItchMessage.SystemEvent m -> alpha(buffer, bodyOffset, 1, m.eventCode());

            case ItchMessage.StockDirectory m -> {
                stock(buffer, bodyOffset, m.stock());
                alpha(buffer, 19, 1, m.marketCategory());
                alpha(buffer, 20, 1, m.financialStatusIndicator());
                buffer.putInt(21, (int) m.roundLotSize());
                alpha(buffer, 25, 1, m.roundLotsOnly());
                alpha(buffer, 26, 1, m.issueClassification());
                alpha(buffer, 27, issueSubTypeLength, m.issueSubType());
                alpha(buffer, 29, 1, m.authenticity());
                alpha(buffer, 30, 1, m.shortSaleThresholdIndicator());
                alpha(buffer, 31, 1, m.ipoFlag());
                alpha(buffer, 32, 1, m.luldReferencePriceTier());
                alpha(buffer, 33, 1, m.etpFlag());
                buffer.putInt(34, (int) m.etpLeverageFactor());
                alpha(buffer, 38, 1, m.inverseIndicator());
            }

            case ItchMessage.StockTradingAction m -> {
                stock(buffer, bodyOffset, m.stock());
                alpha(buffer, 19, 1, m.tradingState());
                alpha(buffer, 20, 1, m.reserved());
                alpha(buffer, 21, reasonLength, m.reason());
            }

            case ItchMessage.RegShoRestriction m -> {
                stock(buffer, bodyOffset, m.stock());
                alpha(buffer, 19, 1, m.regShoAction());
            }

            case ItchMessage.MarketParticipantPosition m -> {
                alpha(buffer, bodyOffset, mpidLength, m.mpid());
                stock(buffer, 15, m.stock());
                alpha(buffer, 23, 1, m.primaryMarketMaker());
                alpha(buffer, 24, 1, m.marketMakerMode());
                alpha(buffer, 25, 1, m.marketParticipantState());
            }

            case ItchMessage.MwcbDeclineLevel m -> {
                buffer.putLong(bodyOffset, m.level1());
                buffer.putLong(19, m.level2());
                buffer.putLong(27, m.level3());
            }

            case ItchMessage.MwcbStatus m -> alpha(buffer, bodyOffset, 1, m.breachedLevel());

            case ItchMessage.IpoQuotingPeriodUpdate m -> {
                stock(buffer, bodyOffset, m.stock());
                buffer.putInt(19, (int) m.releaseTimeSeconds());
                alpha(buffer, 23, 1, m.releaseQualifier());
                buffer.putInt(24, (int) m.ipoPrice());
            }

            case ItchMessage.LuldAuctionCollar m -> {
                stock(buffer, bodyOffset, m.stock());
                buffer.putInt(19, (int) m.referencePrice());
                buffer.putInt(23, (int) m.upperCollarPrice());
                buffer.putInt(27, (int) m.lowerCollarPrice());
                buffer.putInt(31, (int) m.extension());
            }

            case ItchMessage.OperationalHalt m -> {
                stock(buffer, bodyOffset, m.stock());
                alpha(buffer, 19, 1, m.marketCode());
                alpha(buffer, 20, 1, m.haltAction());
            }

            case ItchMessage.AddOrder m -> {
                buffer.putLong(bodyOffset, m.orderReference());
                buffer.put(19, (byte) m.side().code());
                buffer.putInt(20, (int) m.shares());
                stock(buffer, 24, m.stock());
                buffer.putInt(32, (int) m.price());
                if (m.attribution() != null) {
                    alpha(buffer, 36, mpidLength, m.attribution());
                }
            }

            case ItchMessage.OrderExecuted m -> {
                buffer.putLong(bodyOffset, m.orderReference());
                buffer.putInt(19, (int) m.executedShares());
                buffer.putLong(23, m.matchNumber());
            }

            case ItchMessage.OrderExecutedWithPrice m -> {
                buffer.putLong(bodyOffset, m.orderReference());
                buffer.putInt(19, (int) m.executedShares());
                buffer.putLong(23, m.matchNumber());
                buffer.put(31, (byte) (m.printable() ? printableYes : printableNo));
                buffer.putInt(32, (int) m.executionPrice());
            }

            case ItchMessage.OrderCancel m -> {
                buffer.putLong(bodyOffset, m.orderReference());
                buffer.putInt(19, (int) m.cancelledShares());
            }

            case ItchMessage.OrderDelete m -> buffer.putLong(bodyOffset, m.orderReference());

            case ItchMessage.OrderReplace m -> {
                buffer.putLong(bodyOffset, m.originalOrderReference());
                buffer.putLong(19, m.newOrderReference());
                buffer.putInt(27, (int) m.shares());
                buffer.putInt(31, (int) m.price());
            }

            case ItchMessage.Trade m -> {
                buffer.putLong(bodyOffset, m.orderReference());
                buffer.put(19, (byte) m.side().code());
                buffer.putInt(20, (int) m.shares());
                stock(buffer, 24, m.stock());
                buffer.putInt(32, (int) m.price());
                buffer.putLong(36, m.matchNumber());
            }

            case ItchMessage.CrossTrade m -> {
                buffer.putLong(bodyOffset, m.shares());
                stock(buffer, 19, m.stock());
                buffer.putInt(27, (int) m.crossPrice());
                buffer.putLong(31, m.matchNumber());
                alpha(buffer, 39, 1, m.crossType());
            }

            case ItchMessage.BrokenTrade m -> buffer.putLong(bodyOffset, m.matchNumber());

            case ItchMessage.NetOrderImbalance m -> {
                buffer.putLong(bodyOffset, m.pairedShares());
                buffer.putLong(19, m.imbalanceShares());
                alpha(buffer, 27, 1, m.imbalanceDirection());
                stock(buffer, 28, m.stock());
                buffer.putInt(36, (int) m.farPrice());
                buffer.putInt(40, (int) m.nearPrice());
                buffer.putInt(44, (int) m.currentReferencePrice());
                alpha(buffer, 48, 1, m.crossType());
                alpha(buffer, 49, 1, m.priceVariationIndicator());
            }

            case ItchMessage.RetailPriceImprovement m -> {
                stock(buffer, bodyOffset, m.stock());
                alpha(buffer, 19, 1, m.interestFlag());
            }

            case ItchMessage.DirectListingCapitalRaise m -> {
                stock(buffer, bodyOffset, m.stock());
                alpha(buffer, 19, 1, m.openEligibilityStatus());
                buffer.putInt(20, (int) m.minimumAllowablePrice());
                buffer.putInt(24, (int) m.maximumAllowablePrice());
                buffer.putInt(28, (int) m.nearExecutionPrice());
                buffer.putLong(32, m.nearExecutionTime());
                buffer.putInt(40, (int) m.lowerPriceRangeCollar());
                buffer.putInt(44, (int) m.upperPriceRangeCollar());
            }
        }
        return buffer.array();
    }


    /** Writes the message as one feed frame: two-byte big-endian length, then the message bytes. */
    public static void writeFrame(OutputStream out, ItchMessage message) throws IOException {
        byte[] bytes = encode(message);
        out.write(bytes.length >>> 8);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
    }


    public static int frameLength(ItchMessage message) {
        return lengthPrefixBytes + lengthOf(message.type());
    }


    //-----------------------------------------------------------------------------------------------------------------
    private static void header(ByteBuffer buffer, ItchHeader header) {
        buffer.putShort(stockLocateOffset, (short) header.stockLocate());
        buffer.putShort(trackingNumberOffset, (short) header.trackingNumber());
        long timestamp = header.timestampNanos();
        for (int i = timestampLength - 1; i >= 0; i--) {
            buffer.put(timestampOffset + i, (byte) (timestamp & 0xFF));
            timestamp >>>= 8;
        }
    }

    private static void alpha(ByteBuffer buffer, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > length) {
            throw new IllegalArgumentException("Alpha field '" + value + "' exceeds " + length + " bytes");
        }
        buffer.put(offset, bytes);
    }

    private static void stock(ByteBuffer buffer, int offset, String stock) {
        alpha(buffer, offset, stockLength, stock);
    }
}
