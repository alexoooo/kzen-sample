package tech.kzen.sample.plugin.cities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.kzen.auto.common.data.format.ConfiguredRecordFormat;
import tech.kzen.auto.common.data.format.detection.FormatHintMetadata;
import tech.kzen.auto.common.data.model.DataRef;
import tech.kzen.auto.common.data.read.ContentCodingSpec;
import tech.kzen.auto.common.data.read.ResolvedReadSpec;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.reflect.Reflect;
import tech.kzen.lib.common.util.digest.Digest;

import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * The world-cities file as a registered record format: no configuration, detected by its header row through
 * the reader's probe (no extension hint — the file is a `.txt`, which must stay plain text for everything
 * else), a `.gz` source read through the gzip content coding.
 */
@Reflect
public final class WorldCitiesFormat implements ConfiguredRecordFormat {
    private final String title;
    private final boolean catalogVisible;


    public WorldCitiesFormat(String title, boolean catalogVisible) {
        this.title = title;
        this.catalogVisible = catalogVisible;
    }


    @NotNull
    @Override
    public String getTitle() {
        return title;
    }


    @NotNull
    @Override
    public List<String> getExtensions() {
        return List.of();
    }


    @Override
    public boolean getCatalogVisible() {
        return catalogVisible;
    }


    @NotNull
    @Override
    public List<FormatHintMetadata> getHintMetadata() {
        return List.of();
    }


    @SuppressWarnings("deprecation")
    @NotNull
    @Override
    public ResolvedReadSpec resolvedRead(@NotNull DataRef ref) {
        ContentCodingSpec coding = ref.getId().toLowerCase(Locale.ROOT).endsWith(".gz")
                ? ContentCodingSpec.Companion.getGzip()
                : ContentCodingSpec.Companion.getIdentity();
        return new ResolvedReadSpec(WorldCitiesReaderCapability.identity, List.of(coding), new MapExecutionValue(Map.of()));
    }


    @Nullable
    @Override
    public DataShape declaredShape() {
        return WorldCitiesReaderCapability.shape();
    }


    @Override
    public void digest(@NotNull Digest.Sink sink) {
        sink.addUtf8(title);
    }
}
