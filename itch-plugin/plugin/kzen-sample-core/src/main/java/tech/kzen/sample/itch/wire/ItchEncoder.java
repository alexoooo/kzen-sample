package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;

import java.io.IOException;
import java.io.OutputStream;

import static tech.kzen.sample.itch.wire.ItchLayout.*;


/** Encodes a message record back to its exact ITCH 5.0 wire bytes; the inverse of {@link ItchDecoder}. */
public final class ItchEncoder {
    private ItchEncoder() {}

    private static final int lengthPrefixBytes = 2;


    /** The message bytes without the two-byte length prefix. */
    public static byte[] encode(ItchMessage message) {
        return message.record().copyBytes();
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


}
