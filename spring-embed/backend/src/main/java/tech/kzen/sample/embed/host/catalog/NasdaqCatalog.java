package tech.kzen.sample.embed.host.catalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class NasdaqCatalog {
    public static final URI source = URI.create("https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/");
    private static final Pattern link = Pattern.compile("(?i)<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern size = Pattern.compile("([0-9,]+)\\s*$");
    private static final Pattern dated = Pattern.compile("(\\d{8})\\.NASDAQ_ITCH50\\.gz");
    private static final Pattern shortDated = Pattern.compile("S(\\d{6})-v50\\.txt\\.gz");
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("MMdduuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Duration requestTimeout = Duration.ofSeconds(30);

    private NasdaqCatalog() {}

    public static LocalDate date(String filename) {
        var full = dated.matcher(filename);
        var shortName = shortDated.matcher(filename);
        String value;
        if (full.matches()) value = full.group(1);
        else if (shortName.matches()) {
            String digits = shortName.group(1);
            value = digits.substring(0, 4) + "20" + digits.substring(4);
        }
        else return null;
        try { return LocalDate.parse(value, dateFormat); }
        catch (java.time.format.DateTimeParseException e) { return null; }
    }

    public static List<ItchCatalogFile> fetch(HttpClient client, URI directory) throws Exception {
        var response = client.send(HttpRequest.newBuilder(directory).timeout(requestTimeout).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Catalog returned HTTP " + response.statusCode());
        return parse(directory, response.body());
    }

    public static List<ItchCatalogFile> parse(URI directory, String html) {
        List<ItchCatalogFile> result = new ArrayList<>();
        var links = link.matcher(html);
        while (links.find()) {
            URI url = directory.resolve(links.group(1).replace("&amp;", "&")).normalize();
            if (!url.getScheme().equals(directory.getScheme()) || !url.getAuthority().equals(directory.getAuthority())
                    || !url.getPath().startsWith(directory.getPath())) continue;
            String path = url.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            LocalDate date = date(name);
            if (date == null) continue;
            var bytes = size.matcher(html.substring(0, links.start()));
            long length = bytes.find() ? Long.parseLong(bytes.group(1).replace(",", "")) : -1;
            String checksumName = name + ".md5sum";
            URI checksum = html.contains(checksumName) ? url.resolve(checksumName) : null;
            result.add(new ItchCatalogFile(name, date, url, length, checksum));
        }
        return result;
    }
}
