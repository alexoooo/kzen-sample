package tech.kzen.sample.itch.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.ByteBuffer;


/**
 * Identity of a source feed file, cheap enough to recompute on every store open: size, last-modified time and a
 * SHA-256 over the first and last {@link #sampledBytes} of the file. A day file is several gigabytes, so a full
 * hash is paid once at build time only when explicitly requested; the sampled form catches a replaced or
 * truncated file, which is what a stale-store check is for.
 */
public record SourceFingerprint(
        long size,
        long lastModifiedMillis,
        String sampledSha256
) {
    private static final int sampledBytes = 1 << 20;
    private static final String algorithm = "SHA-256";


    public static SourceFingerprint of(Path source) throws IOException {
        long size = Files.size(source);
        long modified = Files.getLastModifiedTime(source).toMillis();
        MessageDigest digest = digest();
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(sampledBytes, size));
            channel.read(head, 0);
            head.flip();
            digest.update(head);
            if (size > sampledBytes) {
                ByteBuffer tail = ByteBuffer.allocate((int) Math.min(sampledBytes, size - sampledBytes));
                channel.read(tail, size - tail.capacity());
                tail.flip();
                digest.update(tail);
            }
        }
        return new SourceFingerprint(size, modified, HexFormat.of().formatHex(digest.digest()));
    }


    /** Whether [source] still has this fingerprint; a missing file is a mismatch, not an error. */
    public boolean matches(Path source) {
        try {
            return Files.exists(source) && equals(of(source));
        }
        catch (IOException e) {
            return false;
        }
    }


    public String encode() {
        return size + ":" + lastModifiedMillis + ":" + sampledSha256;
    }


    public static SourceFingerprint decode(String encoded) {
        String[] parts = encoded.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed fingerprint: " + encoded);
        }
        return new SourceFingerprint(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]);
    }


    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(algorithm);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    /** Full-content SHA-256, for a build log entry; not part of the stale check. */
    public static String fullSha256(Path source) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[sampledBytes];
        try (InputStream in = Files.newInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
