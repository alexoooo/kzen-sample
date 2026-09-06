package tech.kzen.sample.itch.wire;

import org.junit.jupiter.api.Test;
import tech.kzen.sample.itch.message.ItchHeader;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.Side;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Hand-authored wire bytes per the ITCH 5.0 specification tables, so the encoder and the decoder are each
 * checked against an independent expectation rather than only against each other.
 */
class GoldenBytesTest {
    private static final HexFormat hex = HexFormat.of();

    private record Golden(String hex, ItchMessage message) {}

    private static final long timestamp = 0x3B9ACA00L;   // 1 000 000 000 ns

    private static final List<Golden> goldens = List.of(
            new Golden("53" + "0000" + "0001" + "000000001234" + "4f",
                    new ItchMessage.SystemEvent(new ItchHeader(0, 0, 1, 0x1234), "O")),

            new Golden("52" + "0001" + "000b" + "000000000010" + "4141504c20202020" + "51" + "4e" + "00000064"
                            + "4e" + "43" + "5a20" + "50" + "4e" + "4e" + "31" + "4e" + "00000000" + "4e",
                    new ItchMessage.StockDirectory(new ItchHeader(0, 1, 11, 0x10), "AAPL", "Q", "N", 100, "N",
                            "C", "Z", "P", "N", "N", "1", "N", 0, "N")),

            new Golden("41" + "0001" + "0002" + "00003b9aca00" + "0000000000000001" + "42" + "00000064"
                            + "4141504c20202020" + "0016e360",
                    new ItchMessage.AddOrder(new ItchHeader(0, 1, 2, timestamp), 1, Side.BUY, 100, "AAPL",
                            1_500_000, null)),

            new Golden("46" + "0002" + "0002" + "00003b9aca00" + "000000000000000a" + "53" + "000001f4"
                            + "4d53465420202020" + "002dc6c0" + "4e534451",
                    new ItchMessage.AddOrder(new ItchHeader(0, 2, 2, timestamp), 10, Side.SELL, 500, "MSFT",
                            3_000_000, "NSDQ")),

            new Golden("45" + "0001" + "0003" + "00003b9aca01" + "0000000000000001" + "00000028"
                            + "00000000000003e9",
                    new ItchMessage.OrderExecuted(new ItchHeader(0, 1, 3, timestamp + 1), 1, 40, 1001)),

            new Golden("43" + "0002" + "0008" + "00003b9aca06" + "000000000000000a" + "00000064"
                            + "00000000000007d1" + "4e" + "002dc6c0",
                    new ItchMessage.OrderExecutedWithPrice(new ItchHeader(0, 2, 8, timestamp + 6), 10, 100, 2001,
                            false, 3_000_000)),

            new Golden("58" + "0001" + "0009" + "00003b9aca07" + "0000000000000003" + "00000014",
                    new ItchMessage.OrderCancel(new ItchHeader(0, 1, 9, timestamp + 7), 3, 20)),

            new Golden("44" + "0001" + "000a" + "00003b9aca08" + "0000000000000003",
                    new ItchMessage.OrderDelete(new ItchHeader(0, 1, 10, timestamp + 8), 3)),

            new Golden("55" + "0001" + "0004" + "00003b9aca02" + "0000000000000002" + "0000000000000003"
                            + "00000050" + "0016f300",
                    new ItchMessage.OrderReplace(new ItchHeader(0, 1, 4, timestamp + 2), 2, 3, 80, 1_504_000)),

            new Golden("50" + "0002" + "0005" + "00003b9aca03" + "0000000000000000" + "42" + "000000fa"
                            + "4d53465420202020" + "002dcaa8" + "00000000000007d3",
                    new ItchMessage.Trade(new ItchHeader(0, 2, 5, timestamp + 3), 0, Side.BUY, 250, "MSFT",
                            3_001_000, 2003)),

            new Golden("51" + "0002" + "0006" + "00003b9aca04" + "00000000000003e8" + "4d53465420202020"
                            + "002dc6c0" + "00000000000007d4" + "4f",
                    new ItchMessage.CrossTrade(new ItchHeader(0, 2, 6, timestamp + 4), 1000, "MSFT", 3_000_000,
                            2004, "O")),

            new Golden("42" + "0002" + "0007" + "00003b9aca05" + "00000000000007d1",
                    new ItchMessage.BrokenTrade(new ItchHeader(0, 2, 7, timestamp + 5), 2001)),

            new Golden("56" + "0000" + "0001" + "000000000010" + "00000045d964b800" + "00000041314cf000"
                            + "0000003a35294400",
                    new ItchMessage.MwcbDeclineLevel(new ItchHeader(0, 0, 1, 0x10), 300_000_000_000L,
                            280_000_000_000L, 250_000_000_000L)));


    @Test
    void encoderProducesTheSpecifiedBytes() {
        for (Golden golden : goldens) {
            assertEquals(golden.hex(), hex.formatHex(ItchEncoder.encode(golden.message())),
                    "encoding " + golden.message().type());
        }
    }


    @Test
    void decoderReadsTheSpecifiedBytes() {
        for (Golden golden : goldens) {
            byte[] bytes = hex.parseHex(golden.hex());
            ItchMessage decoded = ItchDecoder.decode(bytes, 0, bytes.length, golden.message().header().ordinal());
            assertEquals(golden.message(), decoded, "decoding " + golden.message().type());
        }
    }


    @Test
    void unknownTypeAndWrongLengthAreNamedFailures() {
        byte[] unknown = hex.parseHex("5a" + "0000" + "0001" + "000000001234" + "4f");
        ItchFormatException unknownFailure = assertThrows(ItchFormatException.class,
                () -> ItchDecoder.decode(unknown, 0, unknown.length, 7));
        assertTrue(unknownFailure.getMessage().contains("unknown type 'Z'"), unknownFailure.getMessage());
        assertTrue(unknownFailure.getMessage().contains("Message 7"), unknownFailure.getMessage());

        byte[] shortAdd = hex.parseHex("41" + "0001" + "0002" + "00003b9aca00" + "0000000000000001" + "42");
        ItchFormatException lengthFailure = assertThrows(ItchFormatException.class,
                () -> ItchDecoder.decode(shortAdd, 0, shortAdd.length, 8));
        assertTrue(lengthFailure.getMessage().contains("expected 36"), lengthFailure.getMessage());

        byte[] badPrintable = hex.parseHex("43" + "0002" + "0008" + "00003b9aca06" + "000000000000000a"
                + "00000064" + "00000000000007d1" + "58" + "002dc6c0");
        assertThrows(ItchFormatException.class, () -> ItchDecoder.decode(badPrintable, 0, badPrintable.length, 9));
    }
}
