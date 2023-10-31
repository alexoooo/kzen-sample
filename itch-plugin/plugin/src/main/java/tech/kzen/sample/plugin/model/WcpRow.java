package tech.kzen.sample.plugin.model;


import com.google.common.base.Joiner;
import org.jetbrains.annotations.Nullable;
import tech.kzen.auto.plugin.model.record.FlatFileRecord;

import java.util.Arrays;
import java.util.List;


public record WcpRow(
        String country,
        String city,
        String accentCity,
        String region,
        @Nullable Integer populationOrNull,
        @Nullable Double latitudeOrNull,
        @Nullable Double longitudeOrNull
) {
    //-----------------------------------------------------------------------------------------------------------------
    public static final List<String> header = List.of(
            "Country",
            "City",
            "AccentCity",
            "Region",
            "Population",
            "Latitude",
            "Longitude");

    public static final String headerRow = Joiner.on(',').join(header);
    private static final char[] headerRowChars = headerRow.toCharArray();


    public static boolean isHeaderRow(char[] chars, int length) {
        if (headerRowChars.length != length) {
            return false;
        }

        return Arrays.equals(
                headerRowChars, 0, length,
                chars, 0, length);
    }


    //-----------------------------------------------------------------------------------------------------------------
    public void flatten(FlatFileRecord builder) {
        builder.add(country);
        builder.add(city);
        builder.add(accentCity);
        builder.add(region);
        builder.add(populationOrNull == null ? "" : populationOrNull.toString());
        builder.add(latitudeOrNull == null ? "" : latitudeOrNull.toString());
        builder.add(longitudeOrNull == null ? "" : longitudeOrNull.toString());
    }
}
