package tech.kzen.sample.plugin.io;

import tech.kzen.auto.plugin.api.data.ReaderByteInput;

import java.io.InputStream;


/**
 * The host's sequential-byte contract as a {@link InputStream}, for cores that read streams and never see kzen
 * (the ITCH framing, a line reader). Closing the stream is the cursor's business, not the host input's.
 */
public final class ReaderByteInputStream extends InputStream {
    private final ReaderByteInput bytes;
    private final byte[] one = new byte[1];


    public ReaderByteInputStream(ReaderByteInput bytes) {
        this.bytes = bytes;
    }


    @Override
    public int read() {
        int count = bytes.read(one, 0, 1);
        return count == -1 ? -1 : one[0] & 0xff;
    }


    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (length == 0) {
            return 0;
        }
        return bytes.read(buffer, offset, length);
    }
}
