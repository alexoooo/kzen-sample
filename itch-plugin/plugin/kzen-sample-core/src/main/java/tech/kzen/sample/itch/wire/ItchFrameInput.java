package tech.kzen.sample.itch.wire;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;


/**
 * Strict length-prefixed framing over a raw or gzip-compressed ITCH feed: {@code [2-byte length][message]}
 * repeated. The feed may end only at a frame boundary; a partial length prefix or a partial message is a
 * truncation error, and a zero length is a bad length. Decoding is separate ({@link ItchDecoder}), so the
 * frame buffer is the only live wire object and is reused between frames.
 */
public final class ItchFrameInput implements AutoCloseable {
    private static final int gzipMagicFirst = 0x1f;
    private static final int gzipMagicSecond = 0x8b;
    private static final int lengthPrefixBytes = 2;
    private static final int maximumFrameLength = 0xFFFF;
    private static final int readAheadBytes = 1 << 16;

    private final InputStream input;
    private final byte[] frame = new byte[maximumFrameLength];
    private long nextOrdinal = 0;
    private long ordinal = -1;
    private int frameLength = -1;


    /** Wraps [stream], transparently inflating a gzip feed (detected by its magic bytes). */
    public static ItchFrameInput open(InputStream stream) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(stream, readAheadBytes);
        buffered.mark(lengthPrefixBytes);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        if (first == gzipMagicFirst && second == gzipMagicSecond) {
            return new ItchFrameInput(new GZIPInputStream(buffered, readAheadBytes));
        }
        return new ItchFrameInput(buffered);
    }


    private ItchFrameInput(InputStream input) {
        this.input = input;
    }


    /**
     * Reads the next frame into the shared buffer; false at a clean end of feed.
     *
     * @throws ItchFormatException on truncation or a zero length
     */
    public boolean next() {
        try {
            int high = input.read();
            if (high == -1) {
                frameLength = -1;
                return false;
            }
            int low = input.read();
            if (low == -1) {
                throw new ItchFormatException("Feed truncated inside the length prefix of message " + nextOrdinal);
            }
            int length = (high << 8) | low;
            if (length == 0) {
                throw new ItchFormatException("Message " + nextOrdinal + " has a zero length prefix");
            }
            int read = input.readNBytes(frame, 0, length);
            if (read != length) {
                throw new ItchFormatException("Feed truncated inside message " + nextOrdinal
                        + " (" + read + " of " + length + " bytes)");
            }
            frameLength = length;
            ordinal = nextOrdinal++;
            return true;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    /** The current frame's message bytes: the shared buffer, valid until the next call to {@link #next}. */
    public byte[] frame() {
        return frame;
    }

    public int frameLength() {
        return frameLength;
    }

    /** Zero-based feed position of the current frame. */
    public long ordinal() {
        return ordinal;
    }


    @Override
    public void close() throws IOException {
        input.close();
    }
}
