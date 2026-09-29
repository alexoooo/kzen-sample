package tech.kzen.sample.itch.store;


/**
 * On-disk layout of a derived store:
 * <pre>
 * day.store/                       the store root a caller names
 *   current                        one line: the name of the published version directory
 *   v-&lt;token&gt;/                     one version; a build writes a new one and never touches an old one
 *     manifest.properties          written last inside the version; complete=true
 *     catalog.tsv                  locate, symbol, per-partition statistics (the heap-estimate inputs)
 *     readers.lock                 shared session leases exclude cleanup
 *     partitions/&lt;locate&gt;.zst       independent blocks (see block.PartitionBlocks)
 * </pre>
 * Publishing replaces {@code current} atomically, so a reader that opened an earlier version keeps reading
 * its own complete files (a directory holding open files cannot be renamed on Windows), and a version is served
 * only once it is complete and pointed to. Locate 0 (market-wide messages) is stored once per version and
 * merged into every symbol replay by ordinal. Decompressed records are big-endian
 * [8-byte feed ordinal][2-byte length][message bytes]; catalog bytes count these logical records,
 * while storedBytes counts the compressed partition including block envelopes.
 */
public final class StoreFormat {
    private StoreFormat() {}

    /** Bump when the partition or catalog layout changes; an older store is rejected on open. */
    public static final int formatVersion = 2;

    /** Bump when the decoder's interpretation changes in a way that should invalidate stores. */
    public static final int parserVersion = 1;

    public static final String currentPointerFileName = "current";
    public static final String versionDirectoryPrefix = "v-";
    public static final String manifestFileName = "manifest.properties";
    public static final String catalogFileName = "catalog.tsv";
    public static final String partitionsDirectoryName = "partitions";
    public static final String partitionFileSuffix = ".zst";

    public static final java.lang.foreign.ValueLayout.OfLong ordinalLayout =
            java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);
    public static final java.lang.foreign.ValueLayout.OfShort lengthLayout =
            java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);

    public static final int ordinalBytes = Long.BYTES;
    public static final int lengthPrefixBytes = Short.BYTES;
    public static final int frameHeaderBytes = ordinalBytes + lengthPrefixBytes;
}
