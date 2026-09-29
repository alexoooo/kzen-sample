package tech.kzen.sample.itch.store.block;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import tech.kzen.sample.itch.store.ItchStoreException;
import java.io.*;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Independent checksummed Zstd frames; big-endian envelope: magic, compressed bytes, raw bytes, records. */
public final class PartitionBlocks {
    public static final int maximumRawBytes = 1 << 20;
    public static final int maximumCompressedBytes = Math.toIntExact(Zstd.compressBound(maximumRawBytes));
    public static final int headerBytes = 4 * Integer.BYTES;
    public static final int magic = 0x49544332;
    public static final int compressionLevel = 1;
    // Single-shot Zstd has fixed decoder workspace, independent of the frame's advertised window.
    public static final long decoderScratchBytes = maximumCompressedBytes + (256L << 10);
    private PartitionBlocks() {}

    public static final class Writer implements AutoCloseable {
        private final ZstdCompressCtx codec = new ZstdCompressCtx().setLevel(compressionLevel).setChecksum(true);
        private final byte[] compressed = new byte[maximumCompressedBytes];
        private final ByteBuffer header = ByteBuffer.allocate(headerBytes);

        public void write(OutputStream output, byte[] raw, int length, int records) throws IOException {
            if (length <= 0 || length > maximumRawBytes || records <= 0)
                throw new IllegalArgumentException("Invalid partition block size");
            int size = codec.compressByteArray(compressed, 0, compressed.length, raw, 0, length);
            header.clear().putInt(magic).putInt(size).putInt(length).putInt(records);
            output.write(header.array());
            output.write(compressed, 0, size);
        }
        @Override public void close() { codec.close(); }
    }

    public static final class Decoder implements AutoCloseable {
        private final ZstdDecompressCtx codec = new ZstdDecompressCtx();
        private final byte[] compressed = new byte[maximumCompressedBytes];
        @Override public void close() { codec.close(); }
    }

    public static final class Reader implements AutoCloseable {
        private final Path path;
        private final InputStream input;
        private final Decoder decoder;
        private final boolean ownsDecoder;
        private final byte[] header = new byte[headerBytes];
        private int rawBytes;
        private int records;
        private int compressedSize;

        public Reader(Path path) { this(path, null, new Decoder(), true); }
        public Reader(Path path, byte[] prefix, Decoder decoder) { this(path, prefix, decoder, false); }
        private Reader(Path path, byte[] prefix, Decoder decoder, boolean ownsDecoder) {
            this.path = path;
            this.decoder = decoder;
            this.ownsDecoder = ownsDecoder;
            try {
                FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
                try {
                    if (prefix != null) channel.position(prefix.length);
                    InputStream tail = java.nio.channels.Channels.newInputStream(channel);
                    input = prefix == null ? tail : new SequenceInputStream(new ByteArrayInputStream(prefix), tail);
                }
                catch (Throwable failure) { channel.close(); throw failure; }
            }
            catch (IOException | RuntimeException failure) {
                if (ownsDecoder) decoder.close();
                throw new ItchStoreException("Cannot open partition " + path, failure);
            }
        }

        public boolean next() {
            try {
                int read = input.readNBytes(header, 0, header.length);
                if (read == 0) return false;
                if (read != header.length) throw new EOFException("Truncated block header");
                ByteBuffer fields = ByteBuffer.wrap(header);
                int foundMagic = fields.getInt();
                compressedSize = fields.getInt();
                rawBytes = fields.getInt();
                records = fields.getInt();
                if (foundMagic != magic || compressedSize <= 0 || compressedSize > maximumCompressedBytes
                        || rawBytes <= 0 || rawBytes > maximumRawBytes || records <= 0 || records > rawBytes / 10)
                    throw new IOException("Invalid block envelope");
                if (input.readNBytes(decoder.compressed, 0, compressedSize) != compressedSize)
                    throw new EOFException("Truncated compressed block");
                return true;
            }
            catch (IOException e) { throw new ItchStoreException("Cannot read partition " + path, e); }
        }
        public int rawBytes() { return rawBytes; }
        public int records() { return records; }

        public void decompress(MemorySegment target) {
            if (target.byteSize() != rawBytes) throw new IllegalArgumentException("Block destination size differs");
            try {
                int count = decoder.codec.decompressByteArrayToDirectByteBuffer(target.asByteBuffer(), 0, rawBytes,
                        decoder.compressed, 0, compressedSize);
                if (count != rawBytes) throw new ItchStoreException("Decompressed size differs in " + path);
            }
            catch (com.github.luben.zstd.ZstdException e) {
                throw new ItchStoreException("Corrupt compressed partition " + path, e);
            }
        }
        public void decompress(byte[] target) {
            try {
                if (decoder.codec.decompressByteArray(target, 0, rawBytes, decoder.compressed, 0, compressedSize) != rawBytes)
                    throw new ItchStoreException("Decompressed size differs in " + path);
            }
            catch (com.github.luben.zstd.ZstdException e) {
                throw new ItchStoreException("Corrupt compressed partition " + path, e);
            }
        }
        @Override public void close() {
            try { input.close(); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            finally { if (ownsDecoder) decoder.close(); }
        }
    }
}
