package tech.kzen.sample.plugin.model;

import org.jetbrains.annotations.Nullable;
import tech.kzen.lib.common.exec.data.type.DataContract;

import java.util.Arrays;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static tech.kzen.sample.plugin.value.LiteralRecords.field;
import static tech.kzen.sample.plugin.value.LiteralRecords.floatingOrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.integer32OrNull;
import static tech.kzen.sample.plugin.value.LiteralRecords.text;


/**
 * One row of the world-cities population file (`worldcitiespop.txt`: seven comma-separated columns, ISO-8859-1,
 * a header row first): the simple sample beside ITCH. Parsing is the same as before the Report definer was
 * retired; the typed contract is what the reader declares statically.
 */
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

    public static final String headerRow = String.join(",", header);

    private static final DataContract contract = LiteralRecords.contract(List.of(
            field("Country", text),
            field("City", text),
            field("AccentCity", text),
            field("Region", text),
            field("Population", integer32OrNull),
            field("Latitude", floatingOrNull),
            field("Longitude", floatingOrNull)));


    public static DataContract contract() {
        return contract;
    }


    public static boolean isHeaderRow(String line) {
        return headerRow.equals(line);
    }


    /** Parses one data line; a short line or a non-numeric number fails by name. */
    public static WcpRow parse(String line) {
        Iterator<String> values = Arrays.asList(line.split(",", -1)).iterator();
        try {
            String country = values.next();
            String city = values.next();
            String accentCity = values.next();
            String region = values.next();
            String populationText = values.next();
            Integer populationOrNull = populationText.isEmpty() ? null : Integer.parseInt(populationText);
            String latitudeText = values.next();
            Double latitudeOrNull = latitudeText.isEmpty() ? null : Double.parseDouble(latitudeText);
            String longitudeText = values.next();
            Double longitudeOrNull = longitudeText.isEmpty() ? null : Double.parseDouble(longitudeText);
            return new WcpRow(country, city, accentCity, region, populationOrNull, latitudeOrNull, longitudeOrNull);
        }
        catch (java.util.NoSuchElementException e) {
            throw new IllegalArgumentException("World-cities row has fewer than " + header.size() + " columns: " + line);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("World-cities row has a malformed number: " + line, e);
        }
    }


    //-----------------------------------------------------------------------------------------------------------------
    public Map<String, Object> asColumns() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Country", country);
        row.put("City", city);
        row.put("AccentCity", accentCity);
        row.put("Region", region);
        row.put("Population", populationOrNull == null ? null : populationOrNull.longValue());
        row.put("Latitude", latitudeOrNull);
        row.put("Longitude", longitudeOrNull);
        return row;
    }
}
