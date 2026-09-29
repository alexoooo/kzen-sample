package tech.kzen.sample.plugin.itch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.kzen.auto.common.data.format.ConfiguredRecordFormat;
import tech.kzen.auto.common.data.format.detection.FormatHintMetadata;
import tech.kzen.auto.common.data.model.DataRef;
import tech.kzen.auto.common.data.read.ContentCodingSpec;
import tech.kzen.auto.common.data.read.ResolvedReadSpec;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.lib.common.reflect.Reflect;
import tech.kzen.lib.common.util.digest.Digest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * The ITCH feed as a registered record format (a graph object of the bundled {@code ItchFormat} archetype):
 * names the reader and carries the reader's config as attributes, is an automatic-detection candidate through
 * the reader's probe, and declares the static shape. A {@code .gz} source gets the gzip content coding unless
 * codings are configured explicitly — the coding contract, never a guess at binary framing from a name.
 */
@Reflect
public final class ItchFormat implements ConfiguredRecordFormat {
    public static final String symbolsAttribute = "symbols";
    public static final String messageTypesAttribute = "messageTypes";
    public static final String fromAttribute = "fromNanos";
    public static final String toAttribute = "toNanos";
    public static final String contentCodingsAttribute = "contentCodings";

    private static final String structuredFamily = "itch";

    private final String title;
    private final List<String> extensions;
    private final boolean catalogVisible;
    private final ItchReadConfig config;
    private final List<String> contentCodings;
    private final List<FormatHintMetadata> hintMetadata;


    public ItchFormat(
            String title,
            List<String> extensions,
            boolean catalogVisible,
            List<String> symbols,
            List<String> messageTypes,
            String fromNanos,
            String toNanos,
            List<String> contentCodings
    ) {
        this.title = title;
        this.extensions = List.copyOf(extensions);
        this.catalogVisible = catalogVisible;
        Set<String> symbolSet = new LinkedHashSet<>();
        for (String symbol : symbols) {
            symbolSet.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        Set<Character> typeSet = new LinkedHashSet<>();
        for (String type : messageTypes) {
            String trimmed = type.trim();
            if (trimmed.length() != 1) {
                throw new IllegalArgumentException("ITCH message type must be one letter, not '" + type + "'");
            }
            typeSet.add(trimmed.charAt(0));
        }
        this.config = new ItchReadConfig(symbolSet, typeSet, bound(fromNanos, fromAttribute), bound(toNanos, toAttribute));
        this.config.validate();
        this.contentCodings = List.copyOf(contentCodings);
        this.hintMetadata = extensions.isEmpty()
                ? List.of()
                : List.of(FormatHintMetadata.Companion.structured(structuredFamily, extensions, List.of()));
    }


    @NotNull
    @Override
    public String getTitle() {
        return title;
    }


    @NotNull
    @Override
    public List<String> getExtensions() {
        return extensions;
    }


    @Override
    public boolean getCatalogVisible() {
        return catalogVisible;
    }


    @NotNull
    @Override
    public List<FormatHintMetadata> getHintMetadata() {
        return hintMetadata;
    }


    @Nullable
    @Override
    public String getAuthoringCapabilityIdentity() {
        return ItchReaderCapability.authoringIdentity;
    }


    public ItchReadConfig config() {
        return config;
    }


    @SuppressWarnings("deprecation")
    @NotNull
    @Override
    public ResolvedReadSpec resolvedRead(@NotNull DataRef ref) {
        List<ContentCodingSpec> codings = new ArrayList<>();
        if (!contentCodings.isEmpty()) {
            for (String coding : contentCodings) {
                codings.add(new ContentCodingSpec(coding));
            }
        }
        else if (ref.getId().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            codings.add(ContentCodingSpec.Companion.getGzip());
        }
        else {
            codings.add(ContentCodingSpec.Companion.getIdentity());
        }
        return new ResolvedReadSpec(ItchReaderCapability.identity, codings, config.encode());
    }


    @Nullable
    @Override
    public DataShape declaredShape() {
        return ItchReaderCapability.shape();
    }


    @Override
    public void digest(@NotNull Digest.Sink sink) {
        sink.addUtf8(title);
        sink.addDigestible(config.encode());
        sink.addInt(contentCodings.size());
        for (String coding : contentCodings) {
            sink.addUtf8(coding);
        }
    }


    private static long bound(String text, String attribute) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return ItchReadConfig.noBound;
        }
        try {
            return Long.parseLong(trimmed);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("ITCH format '" + attribute + "' must be nanoseconds since midnight, not '" + trimmed + "'");
        }
    }
}
