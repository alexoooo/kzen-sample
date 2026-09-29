package tech.kzen.sample.itch.message;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static tech.kzen.sample.itch.wire.ItchLayout.*;

/** Binary handle. The batch owner keeps its Arena reachable; explicit batch close invalidates every view. */
public final class ItchRecord {
    private static final ValueLayout.OfShort shortLayout = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt intLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong longLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private final MemorySegment memory;
    private final long offset;
    private final int length;
    private final long ordinal;
    private final Object owner;

    public ItchRecord(MemorySegment memory, long offset, int length, long ordinal, Object owner) {
        java.util.Objects.checkFromIndexSize(offset, (long) length, memory.byteSize());
        this.memory = memory.asReadOnly();
        this.offset = offset;
        this.length = length;
        this.ordinal = ordinal;
        this.owner = owner;
    }

    private ItchRecord(MemorySegment memory, long ordinal) {
        this.memory = memory;
        this.offset = 0;
        this.length = Math.toIntExact(memory.byteSize());
        this.ordinal = ordinal;
        this.owner = null;
    }

    public int length() { return length; }
    public long ordinal() { return ordinal; }
    public char type() { return character(typeOffset); }

    private long position(int field, int bytes) {
        java.util.Objects.checkFromIndexSize(field, bytes, length);
        return offset + field;
    }

    public char character(int field) {
        try { return (char) Byte.toUnsignedInt(memory.get(ValueLayout.JAVA_BYTE, position(field, 1))); }
        finally { Reference.reachabilityFence(owner); }
    }

    public int unsignedShort(int field) {
        try { return Short.toUnsignedInt(memory.get(shortLayout, position(field, Short.BYTES))); }
        finally { Reference.reachabilityFence(owner); }
    }

    public long unsignedInt(int field) {
        try { return Integer.toUnsignedLong(memory.get(intLayout, position(field, Integer.BYTES))); }
        finally { Reference.reachabilityFence(owner); }
    }

    public long longValue(int field) {
        try { return memory.get(longLayout, position(field, Long.BYTES)); }
        finally { Reference.reachabilityFence(owner); }
    }

    public String alpha(int field, int bytes) {
        byte[] value = new byte[bytes];
        try { MemorySegment.copy(memory, ValueLayout.JAVA_BYTE, position(field, bytes), value, 0, bytes); }
        finally { Reference.reachabilityFence(owner); }
        return new String(value, StandardCharsets.US_ASCII);
    }

    public String trimmed(int field, int bytes) { return alpha(field, bytes).stripTrailing(); }

    public ItchHeader header() {
        long timestamp = 0;
        for (int i = 0; i < timestampLength; i++) timestamp = (timestamp << Byte.SIZE) | character(timestampOffset + i);
        return new ItchHeader(ordinal, unsignedShort(stockLocateOffset), unsignedShort(trackingNumberOffset), timestamp);
    }

    public void copyTo(MemorySegment target, long targetOffset) {
        try { MemorySegment.copy(memory, offset, target, targetOffset, length); }
        finally { Reference.reachabilityFence(owner); }
    }

    public byte[] copyBytes() {
        byte[] bytes = new byte[length];
        copyTo(MemorySegment.ofArray(bytes), 0);
        return bytes;
    }

    static ItchRecord allocate(ItchHeader header, char type) {
        ItchRecord record = new ItchRecord(MemorySegment.ofArray(new byte[lengthOf(type)]), header.ordinal());
        record.memory.fill((byte) ' ');
        record.putByte(typeOffset, type);
        record.memory.set(shortLayout, stockLocateOffset, (short) header.stockLocate());
        record.memory.set(shortLayout, trackingNumberOffset, (short) header.trackingNumber());
        long timestamp = header.timestampNanos();
        for (int i = timestampLength - 1; i >= 0; i--) {
            record.putByte(timestampOffset + i, (int) timestamp);
            timestamp >>>= Byte.SIZE;
        }
        return record;
    }

    ItchRecord readOnly() { return new ItchRecord(memory, offset, length, ordinal, owner); }
    void putByte(int field, int value) { memory.set(ValueLayout.JAVA_BYTE, position(field, 1), (byte) value); }
    void putInt(int field, long value) { memory.set(intLayout, position(field, Integer.BYTES), (int) value); }
    void putLong(int field, long value) { memory.set(longLayout, position(field, Long.BYTES), value); }
    void putAlpha(int field, int bytes, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > bytes) throw new IllegalArgumentException("Alpha field '" + value + "' exceeds " + bytes + " bytes");
        MemorySegment.copy(encoded, 0, memory, ValueLayout.JAVA_BYTE, position(field, bytes), encoded.length);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ItchRecord record) || ordinal != record.ordinal || length != record.length) return false;
        try { return MemorySegment.mismatch(memory, offset, offset + length,
                record.memory, record.offset, record.offset + length) == -1; }
        finally { Reference.reachabilityFence(owner); Reference.reachabilityFence(record.owner); }
    }

    @Override public int hashCode() {
        int hash = Long.hashCode(ordinal);
        for (int i = 0; i < length; i++) hash = 31 * hash + character(i);
        return hash;
    }
}
