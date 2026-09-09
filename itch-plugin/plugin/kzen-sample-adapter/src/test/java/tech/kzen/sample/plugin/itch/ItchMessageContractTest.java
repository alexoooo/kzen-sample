package tech.kzen.sample.plugin.itch;

import org.junit.jupiter.api.Test;
import tech.kzen.lib.common.exec.data.type.DataType;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.exec.data.value.DataSnapshot;
import tech.kzen.lib.common.exec.data.value.SnapshotPolicy;
import tech.kzen.lib.common.exec.data.value.SnapshotResult;
import tech.kzen.lib.common.exec.data.value.DefaultDataAdapterRegistry;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.message.ItchRecord;
import tech.kzen.sample.itch.wire.ItchDecoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ItchMessageContractTest {
    @Test void contractExposesTypedFieldsAndReadsThemLazilyWithoutExposingStorage() {
        byte[] bytes = HexFormat.of().parseHex("520001000b0000000000104141504c20202020514e000000644e435a20504e4e314e000000004e");
        MapExecutionValue captured;
        try (Arena arena = Arena.ofShared(); var adapters = new DefaultDataAdapterRegistry()) {
            MemorySegment memory = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, memory, ValueLayout.JAVA_BYTE, 0, bytes.length);
            ItchMessage message = ItchDecoder.view(new ItchRecord(memory, 0, bytes.length, 7, arena));
            var value = adapters.lift(message, null);
            var fields = ((DataType.Record) value.getType()).getFields().stream().map(field -> field.getId().getName()).toList();
            assertTrue(fields.contains("stock"), fields.toString());
            assertTrue(fields.contains("issueSubType"), fields.toString());
            assertFalse(fields.contains("record"), fields.toString());
            memory.set(ValueLayout.JAVA_BYTE, 27, (byte) 'C');
            captured = snapshot(value);
            assertEquals("C", captured.get("issueSubType").get());
            memory.set(ValueLayout.JAVA_BYTE, 27, (byte) 'D');
            assertEquals("D", snapshot(adapters.lift(message, null)).get("issueSubType").get());
            assertEquals("100", captured.get("roundLotSize").get());
            var lotsType = ((DataType.Record) value.getType()).getFields().stream()
                    .filter(field -> field.getId().getName().equals("roundLotSize")).findFirst().orElseThrow().getType();
            assertInstanceOf(tech.kzen.lib.common.exec.data.type.ScalarKind.Integer.class, ((DataType.Scalar) lotsType).getKind());
        }
        assertEquals("C", captured.get("issueSubType").get(), "the snapshot is detached from closed native storage");
    }

    private static MapExecutionValue snapshot(tech.kzen.lib.common.exec.data.value.DataValue value) {
        var result = DataSnapshot.Companion.capture(value, new SnapshotPolicy(), false);
        assertInstanceOf(SnapshotResult.Complete.class, result, result.toString());
        return (MapExecutionValue) ((SnapshotResult.Complete) result).getSnapshot().getValue();
    }
}
