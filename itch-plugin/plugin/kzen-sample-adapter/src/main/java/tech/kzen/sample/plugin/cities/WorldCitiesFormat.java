package tech.kzen.sample.plugin.cities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tech.kzen.auto.common.data.format.ConfiguredRecordFormat;
import tech.kzen.auto.common.data.format.FileFormatNames;
import tech.kzen.auto.common.data.model.DataRef;
import tech.kzen.auto.common.data.read.ResolvedReadSpec;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.reflect.Reflect;
import tech.kzen.lib.common.util.digest.Digest;

import java.util.List;
import java.util.Map;


/**
 * The world-cities file as a registered record format: no configuration and no extension (the file is a
 * `.txt`, which must stay plain text for everything else), so it is selected explicitly; a `.gz` name is read
 * through the gzip content coding.
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


    @SuppressWarnings("deprecation")
    @NotNull
    @Override
    public ResolvedReadSpec resolvedRead(@NotNull DataRef ref) {
        return new ResolvedReadSpec(
                WorldCitiesReaderCapability.identity,
                List.of(FileFormatNames.INSTANCE.contentCoding(ref.getId())),
                new MapExecutionValue(Map.of()));
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
