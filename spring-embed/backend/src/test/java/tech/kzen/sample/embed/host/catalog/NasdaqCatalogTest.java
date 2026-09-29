package tech.kzen.sample.embed.host.catalog;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class NasdaqCatalogTest {
    @Test void readsTradingDatesAndRejectsUndatedOrOtherFeeds() {
        assertEquals(LocalDate.of(2019, 12, 30), NasdaqCatalog.date("12302019.NASDAQ_ITCH50.gz"));
        assertEquals(LocalDate.of(2025, 12, 8), NasdaqCatalog.date("S120825-v50.txt.gz"));
        assertNull(NasdaqCatalog.date("itch50_05_15.gz"));
        assertNull(NasdaqCatalog.date("02302019.NASDAQ_ITCH50.gz"));
        assertNull(NasdaqCatalog.date("12302019.NASDAQ_ITCH50.gz.md5sum"));
    }

    @Test void parsesCatalogSizeAndChecksumWithoutUsingUploadDates() {
        var rows = NasdaqCatalog.parse(NasdaqCatalog.source, """
            8/5/2026 11:01 AM 8775891119 <A HREF="S120825-v50.txt.gz">file</A>
            12/31/2019 1:13 AM 3524013057 <a href="12302019.NASDAQ_ITCH50.gz">day</a>
            72 <a href="12302019.NASDAQ_ITCH50.gz.md5sum">checksum</a>
            <a href="https://other.example/12302019.NASDAQ_ITCH50.gz">external</a>
            <a href="../12302019.NASDAQ_ITCH50.gz">outside</a>
            """);
        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2025, 12, 8), rows.getFirst().date());
        assertEquals(8775891119L, rows.getFirst().size());
        assertNotNull(rows.getLast().checksum());
    }
}
