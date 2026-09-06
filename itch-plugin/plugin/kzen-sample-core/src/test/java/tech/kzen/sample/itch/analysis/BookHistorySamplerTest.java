package tech.kzen.sample.itch.analysis;

import org.junit.jupiter.api.Test;
import tech.kzen.sample.itch.model.BookLevel;
import tech.kzen.sample.itch.model.BookSnapshot;
import tech.kzen.sample.itch.model.PersistentSortedMap;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BookHistorySamplerTest {
    private static final long second = 1_000_000_000L;


    @Test
    void oneSamplePerIntervalCarryingQuietIntervalsForward() {
        BookSnapshot a = at(1, 10 * second + 100);
        BookSnapshot b = at(2, 10 * second + 900);
        BookSnapshot c = at(3, 11 * second + 500);
        BookSnapshot d = at(4, 14 * second);
        List<BookHistorySampler.Sample> samples =
                BookHistorySampler.sample(List.of(BookSnapshot.empty(), a, b, c, d), second);

        assertEquals(List.of(11 * second, 12 * second, 13 * second, 14 * second, 15 * second),
                samples.stream().map(BookHistorySampler.Sample::intervalEndNanos).toList());
        assertEquals(List.of(b, c, c, c, d), samples.stream().map(BookHistorySampler.Sample::book).toList(),
                "the last state before each close; quiet seconds repeat the previous book; the origin is no interval");
    }


    @Test
    void originOnlyEmptyHistoryAndInvalidIntervalAreExplicit() {
        assertTrue(BookHistorySampler.sample(List.of(), second).isEmpty());
        assertTrue(BookHistorySampler.sample(List.of(BookSnapshot.empty()), second).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BookHistorySampler.sample(List.of(at(1, 0)), 0));
    }


    private static BookSnapshot at(long ordinal, long timestampNanos) {
        PersistentSortedMap<Long, BookLevel> bids = PersistentSortedMap.<Long, BookLevel>empty(Comparator.reverseOrder())
                .put(100L, new BookLevel(100, ordinal * 100, 1));
        return new BookSnapshot(ordinal, timestampNanos, bids, PersistentSortedMap.empty(Comparator.naturalOrder()));
    }
}
