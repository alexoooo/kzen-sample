package tech.kzen.sample.itch.message;

import org.junit.jupiter.api.Test;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.wire.ItchEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ItchRecordTest {
    private static final byte[] directory = HexFormat.of().parseHex(
            "520001000b0000000000104141504c20202020514e000000644e435a20504e4e314e000000004e");

    @Test void everyWireFamilyExposesReadableTypedGettersAtItsExactRecordBoundary() throws Exception {
        char[] types = "SRHYL VWKJhAFECXDUPQBIN O".replace(" ", "").toCharArray();
        int[] lengths = {12, 39, 25, 20, 26, 35, 12, 28, 35, 21, 36, 40, 31, 36, 23, 19, 35, 44, 40, 19, 50, 20, 48};
        assertEquals(types.length, lengths.length);
        for (int i = 0; i < types.length; i++) {
            byte[] bytes = new byte[lengths[i]];
            java.util.Arrays.fill(bytes, (byte) 'X');
            bytes[0] = (byte) types[i];
            if (types[i] == 'A' || types[i] == 'F' || types[i] == 'P') bytes[19] = 'B';
            if (types[i] == 'C') bytes[31] = 'Y';
            ItchMessage message = ItchDecoder.decode(bytes, 0, bytes.length, i);
            assertEquals(types[i], message.type());
            assertArrayEquals(bytes, ItchEncoder.encode(message));
            for (var method : message.getClass().getMethods()) {
                if (method.getParameterCount() != 0 || method.getName().equals("getClass")) continue;
                if (method.getName().startsWith("get") || method.getName().startsWith("is")) {
                    Object field = method.invoke(message);
                    if (!method.getName().equals("getAttribution") || types[i] == 'F')
                        assertNotNull(field, types[i] + " " + method.getName());
                }
            }
        }
    }

    @Test void typedGettersReadTheBackingBytesAndFailAfterBatchMemoryCloses() {
        ItchMessage.StockDirectory message;
        try (Arena arena = Arena.ofShared()) {
            MemorySegment memory = arena.allocate(directory.length + 1);
            MemorySegment.copy(directory, 0, memory, ValueLayout.JAVA_BYTE, 1, directory.length);
            message = (ItchMessage.StockDirectory) ItchDecoder.view(new ItchRecord(memory, 1, directory.length, 42, arena));
            assertEquals("AAPL", message.getStock());
            assertEquals("Z", message.getIssueSubType());
            assertEquals(100, message.getRoundLotSize());
            assertEquals(42, message.getHeader().ordinal());
            memory.set(ValueLayout.JAVA_BYTE, 1 + 27, (byte) 'C');
            assertEquals("C", message.getIssueSubType());
            assertEquals('C', ItchEncoder.encode(message)[27]);
        }
        assertThrows(IllegalStateException.class, message::getStock);
    }

    @Test void heapDecodingOwnsItsBytesWhileRecordReadsStayWithinTheirRecord() {
        byte[] input = directory.clone();
        ItchMessage.StockDirectory message = (ItchMessage.StockDirectory) ItchDecoder.decode(input, 0, input.length, 0);
        input[27] = 'C';
        assertEquals("Z", message.getIssueSubType());
        assertThrows(IndexOutOfBoundsException.class, () -> message.record().longValue(directory.length - 1));
        assertThrows(IndexOutOfBoundsException.class, () -> new ItchRecord(MemorySegment.ofArray(input), 1, input.length, 0, null));
    }

    @Test void validationStillRejectsMalformedFieldsBeforeTheirGettersAreUsed() {
        byte[] order = ItchEncoder.encode(new ItchMessage.AddOrder(new ItchHeader(0, 1, 0, 0), 1, Side.BUY, 5, "AAPL", 10, null));
        order[19] = '?';
        assertThrows(IllegalArgumentException.class, () -> ItchDecoder.view(new ItchRecord(MemorySegment.ofArray(order), 0, order.length, 0, null)));
    }
}
