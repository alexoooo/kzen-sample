package tech.kzen.sample.plugin.itch;

import tech.kzen.auto.common.data.format.FormatMaterializationRequest;
import tech.kzen.auto.common.data.format.FormatMaterializationResult;
import tech.kzen.auto.common.data.read.ContentCapabilityIdentity;
import tech.kzen.auto.common.data.read.ContentCodingSpec;
import tech.kzen.auto.common.data.read.ReaderCapabilityIdentity;
import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.auto.plugin.api.data.BlockingReaderCapability;
import tech.kzen.auto.plugin.api.data.BlockingReaderProbe;
import tech.kzen.auto.plugin.api.data.FormatAuthoringCapability;
import tech.kzen.auto.plugin.api.data.ReaderInspectionRequest;
import tech.kzen.auto.plugin.api.data.ReaderOpenRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeRequest;
import tech.kzen.auto.plugin.api.data.ReaderProbeResult;
import tech.kzen.auto.plugin.api.data.ReaderProbeStrength;
import tech.kzen.lib.common.exec.ExecutionValue;
import tech.kzen.lib.common.exec.data.shape.ShapeProvenance;
import tech.kzen.lib.common.exec.data.shape.ShapeStability;
import tech.kzen.lib.common.model.attribute.AttributeSegment;
import tech.kzen.lib.common.model.structure.notation.AttributeNotation;
import tech.kzen.lib.common.model.structure.notation.ListAttributeNotation;
import tech.kzen.lib.common.model.structure.notation.MapAttributeNotation;
import tech.kzen.lib.common.model.structure.notation.ScalarAttributeNotation;
import tech.kzen.lib.platform.collect.PersistentCollectionsKt;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.wire.ItchFormatException;
import tech.kzen.sample.itch.wire.ItchFrameInput;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;


/**
 * The NASDAQ TotalView-ITCH 5.0 reader as a kzen plugin capability, written in plain Java over the core:
 * a {@link BlockingReaderCapability} (config decode / validate / canonicalize / encode, sequential-byte
 * content, a blocking cursor of {@link ItchRow} records, bounded inspection), a {@link BlockingReaderProbe}
 * (automatic detection by framing a bounded sample — never by file name alone) and a
 * {@link FormatAuthoringCapability} (materializing a detected or corrected format as a concrete
 * {@code ItchFormat} document). Compressed feeds are the host's content coding: the bytes this reader gets
 * are already inflated, and it declares no framing from an extension.
 */
public final class ItchReaderCapability
        extends BlockingReaderCapability
        implements BlockingReaderProbe, FormatAuthoringCapability
{
    public static final ReaderCapabilityIdentity identity = new ReaderCapabilityIdentity("tech.kzen.sample", "itch-5", "1");
    public static final String authoringIdentity = "tech.kzen.sample/itch-5-authoring-v1";

    /** A sample must frame at least this many messages cleanly before it counts as ITCH. */
    private static final int minimumProbeFrames = 2;


    @Override
    public ReaderCapabilityIdentity getIdentity() {
        return identity;
    }


    @Override
    public String getReaderCompatibility() {
        return identity.getCompatibility();
    }


    @Override
    public String getAuthoringIdentity() {
        return authoringIdentity;
    }


    @Override
    public boolean getSupportsColumnLocking() {
        return false;
    }


    //-----------------------------------------------------------------------------------------------------------------
    @Override
    public ReaderConfig decode(ExecutionValue config) {
        return ItchReadConfig.decode(config);
    }


    @Override
    public void validate(ReaderConfig config) {
        itchConfig(config).validate();
    }


    @Override
    public ReaderConfig canonicalize(ReaderConfig config) {
        ItchReadConfig typed = itchConfig(config);
        typed.validate();
        return typed;
    }


    @Override
    public ExecutionValue encode(ReaderConfig config) {
        return ((ItchReadConfig) canonicalize(config)).encode();
    }


    @Override
    public ContentCapabilityIdentity requiredContent(ReaderConfig config) {
        validate(config);
        return ContentCapabilityIdentity.Companion.getSequentialBytes();
    }


    //-----------------------------------------------------------------------------------------------------------------
    @Override
    public tech.kzen.auto.common.data.api.DataCursor openBlocking(ReaderOpenRequest request) {
        return new ItchDataCursor(request.getBytes(), itchConfig(request.getConfig()), shape(), Long.MAX_VALUE);
    }


    /** Frames up to the record limit so a malformed feed fails by name at inspection; the shape itself is static. */
    @Override
    public DataShape inspectBlocking(ReaderInspectionRequest request) {
        try (var cursor = new ItchDataCursor(
                request.getOpen().getBytes(), itchConfig(request.getOpen().getConfig()), shape(),
                request.getMaximumRecords())) {
            while (cursor.hasNext()) {
                cursor.next();
            }
            return cursor.getShape();
        }
    }


    public static DataShape shape() {
        return new DataShape(ItchRow.contract(), ShapeProvenance.Declared, ShapeStability.Stable.INSTANCE, List.of());
    }


    //-----------------------------------------------------------------------------------------------------------------
    /**
     * Frames the (inflated) sample: a clean run of messages up to the policy's record limit is content-strong
     * evidence; a feed that frames nothing is no match; a feed the file name claimed to be ITCH but which does
     * not frame is a named rejection. A sample cut mid-frame is fine — what framed before the cut counts.
     */
    @Override
    public ReaderProbeResult probeBlocking(ReaderProbeRequest request) {
        ItchReadConfig candidate = itchConfig(request.getCandidateConfig());
        byte[] sample = request.getSample().toByteArray();
        int limit = request.getPolicy().getMaximumLogicalRecords();
        int framed = 0;
        TreeSet<Character> types = new TreeSet<>();
        String failure = null;
        try (ItchFrameInput frames = ItchFrameInput.open(new ByteArrayInputStream(sample))) {
            while (framed < limit && frames.next()) {
                ItchMessage message = ItchDecoder.decode(frames.frame(), 0, frames.frameLength(), frames.ordinal());
                types.add(message.type());
                framed++;
            }
        }
        catch (ItchFormatException | IOException e) {
            // A cut sample ends mid-frame; a corrupt one fails on its first frames
            failure = e.getMessage();
        }
        request.getObserver().completeLogicalRecordsConsidered(framed);
        boolean enough = framed >= minimumProbeFrames || framed >= 1 && request.getEndOfInput();
        if (enough) {
            return new ReaderProbeResult.Matched(
                    ReaderProbeStrength.ContentStrong,
                    candidate,
                    framed + " ITCH 5.0 frames decoded (types " + types + ")");
        }
        if (request.getStructuredHint()) {
            return new ReaderProbeResult.Rejected(
                    "Named as ITCH but the content does not frame as ITCH 5.0" +
                            (failure == null ? "" : ": " + failure));
        }
        return ReaderProbeResult.NoMatch.INSTANCE;
    }


    //-----------------------------------------------------------------------------------------------------------------
    /** Materializes a detected feed as a concrete format document: the base format's identity plus this config. */
    @Override
    public FormatMaterializationResult materialize(FormatMaterializationRequest request) {
        if (!request.getOverrides().isEmpty()) {
            throw new IllegalArgumentException("ITCH format authoring does not accept quick overrides");
        }
        if (request.getObservedSchema() != null) {
            throw new IllegalArgumentException("ITCH columns are fixed by the reader and do not require a locked schema");
        }
        if (!request.getResolvedRead().getReader().equals(identity)) {
            throw new IllegalArgumentException("ITCH authoring requires the ITCH reader");
        }
        ItchReadConfig config = (ItchReadConfig) canonicalize(decode(request.getResolvedRead().getConfig()));
        Map<AttributeSegment, AttributeNotation> entries = new LinkedHashMap<>();
        entries.put(AttributeSegment.Companion.ofKey("is"), new ScalarAttributeNotation(request.getBaseFormatReference()));
        entries.put(AttributeSegment.Companion.ofKey("catalogVisible"), new ScalarAttributeNotation("false"));
        entries.put(AttributeSegment.Companion.ofKey(ItchFormat.symbolsAttribute), texts(config.symbols().stream().toList()));
        entries.put(AttributeSegment.Companion.ofKey(ItchFormat.messageTypesAttribute),
                texts(config.messageTypes().stream().map(String::valueOf).toList()));
        entries.put(AttributeSegment.Companion.ofKey(ItchFormat.fromAttribute),
                new ScalarAttributeNotation(config.fromNanos() == ItchReadConfig.noBound ? "" : Long.toString(config.fromNanos())));
        entries.put(AttributeSegment.Companion.ofKey(ItchFormat.toAttribute),
                new ScalarAttributeNotation(config.toNanos() == ItchReadConfig.noBound ? "" : Long.toString(config.toNanos())));
        List<String> codings = new ArrayList<>();
        for (ContentCodingSpec coding : request.getResolvedRead().getContentCodings()) {
            codings.add(coding.getIdentity());
        }
        entries.put(AttributeSegment.Companion.ofKey(ItchFormat.contentCodingsAttribute), texts(codings));
        return new FormatMaterializationResult(
                new MapAttributeNotation(PersistentCollectionsKt.toPersistentMap(entries)),
                null,
                null,
                null,
                null);
    }


    private static ListAttributeNotation texts(List<String> values) {
        List<AttributeNotation> items = new ArrayList<>();
        for (String value : values) {
            items.add(new ScalarAttributeNotation(value));
        }
        return new ListAttributeNotation(PersistentCollectionsKt.toPersistentList(items));
    }


    private static ItchReadConfig itchConfig(ReaderConfig config) {
        if (!(config instanceof ItchReadConfig typed)) {
            throw new IllegalArgumentException("ITCH reader config expected, not " + config);
        }
        return typed;
    }
}
