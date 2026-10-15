package tech.kzen.sample.plugin.itch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.kzen.auto.common.data.format.ConfiguredRecordFormat;
import tech.kzen.auto.common.data.format.FileFormatNames;
import tech.kzen.auto.common.data.model.DataRef;
import tech.kzen.auto.common.data.read.ResolvedReadSpec;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.lib.common.reflect.Reflect;
import tech.kzen.lib.common.util.digest.Digest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * The ITCH feed as a registered record format (a graph object of the bundled {@code ItchFormat} archetype):
 * names the reader and carries the reader's config as attributes, is chosen under Automatic by its extensions
 * ({@code .itch}, {@code .nasdaq_itch50}), and declares the static shape. A {@code .gz} name gets the gzip
 * content coding ({@link FileFormatNames}).
 */
@Reflect
public final class ItchFormat implements ConfiguredRecordFormat {
    public static final String symbolsAttribute = "symbols";
    public static final String messageTypesAttribute = "messageTypes";
    public static final String fromAttribute = "fromNanos";
    public static final String toAttribute = "toNanos";

    private final String title;
    private final List<String> extensions;
    private final boolean catalogVisible;
    private final ItchReadConfig config;


    public ItchFormat(
            String title,
            List<String> extensions,
            boolean catalogVisible,
            List<String> symbols,
            List<String> messageTypes,
            String fromNanos,
            String toNanos
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
        return new ResolvedReadSpec(
                ItchReaderCapability.identity,
                List.of(FileFormatNames.INSTANCE.contentCoding(ref.getId())),
                config.encode());
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
