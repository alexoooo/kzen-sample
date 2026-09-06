package tech.kzen.sample.itch.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;


/**
 * The store's identity record: format and parser versions, the source it was derived from and that source's
 * fingerprint, totals, and the completion flag. It is the last file the builder writes, inside the staging
 * directory, so a directory that lacks it (or carries {@code complete=false}) is never mistaken for a store.
 */
public record StoreManifest(
        int formatVersion,
        int parserVersion,
        String sourceFileName,
        SourceFingerprint sourceFingerprint,
        long messages,
        int partitions,
        Instant builtAt,
        boolean complete
) {
    private static final String keyFormatVersion = "formatVersion";
    private static final String keyParserVersion = "parserVersion";
    private static final String keySourceFileName = "sourceFileName";
    private static final String keySourceFingerprint = "sourceFingerprint";
    private static final String keyMessages = "messages";
    private static final String keyPartitions = "partitions";
    private static final String keyBuiltAt = "builtAt";
    private static final String keyComplete = "complete";


    public static StoreManifest load(Path storeDirectory) {
        Path file = storeDirectory.resolve(StoreFormat.manifestFileName);
        if (!Files.isRegularFile(file)) {
            throw new ItchStoreException("Not a store (no " + StoreFormat.manifestFileName + "): " + storeDirectory);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        catch (IOException e) {
            throw new ItchStoreException("Unable to read " + file, e);
        }
        try {
            return new StoreManifest(
                    Integer.parseInt(properties.getProperty(keyFormatVersion)),
                    Integer.parseInt(properties.getProperty(keyParserVersion)),
                    properties.getProperty(keySourceFileName),
                    SourceFingerprint.decode(properties.getProperty(keySourceFingerprint)),
                    Long.parseLong(properties.getProperty(keyMessages)),
                    Integer.parseInt(properties.getProperty(keyPartitions)),
                    Instant.parse(properties.getProperty(keyBuiltAt)),
                    Boolean.parseBoolean(properties.getProperty(keyComplete)));
        }
        catch (RuntimeException e) {
            throw new ItchStoreException("Malformed manifest " + file, e);
        }
    }


    void save(Path storeDirectory) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(keyFormatVersion, Integer.toString(formatVersion));
        properties.setProperty(keyParserVersion, Integer.toString(parserVersion));
        properties.setProperty(keySourceFileName, sourceFileName);
        properties.setProperty(keySourceFingerprint, sourceFingerprint.encode());
        properties.setProperty(keyMessages, Long.toString(messages));
        properties.setProperty(keyPartitions, Integer.toString(partitions));
        properties.setProperty(keyBuiltAt, builtAt.toString());
        properties.setProperty(keyComplete, Boolean.toString(complete));
        try (OutputStream out = Files.newOutputStream(storeDirectory.resolve(StoreFormat.manifestFileName))) {
            properties.store(out, null);
        }
    }


    /** Throws by name unless this manifest describes a complete store of the current format and parser. */
    public void requireCurrentAndComplete(Path storeDirectory) {
        if (!complete) {
            throw new ItchStoreException("Store is incomplete: " + storeDirectory);
        }
        if (formatVersion != StoreFormat.formatVersion) {
            throw new ItchStoreException("Store " + storeDirectory + " has format version " + formatVersion
                    + ", this build reads " + StoreFormat.formatVersion);
        }
        if (parserVersion != StoreFormat.parserVersion) {
            throw new ItchStoreException("Store " + storeDirectory + " was built with parser version " + parserVersion
                    + ", this build is " + StoreFormat.parserVersion);
        }
    }
}
