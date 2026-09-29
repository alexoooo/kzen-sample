package tech.kzen.sample.itch.analysis;

import tech.kzen.sample.itch.model.BookSnapshot;

import java.util.ArrayList;
import java.util.List;


/**
 * Samples a symbol-day's book history at a fixed cadence: one sample per interval from the first timed
 * snapshot's interval to the last's, each the book as it stood when the interval closed (the last snapshot at
 * or before the close; a quiet interval carries the previous state forward). The history's origin — the empty
 * book before any message, with no timestamp — is not an interval of its own. Snapshots share structure, so
 * sampling copies nothing.
 */
public final class BookHistorySampler {
    private BookHistorySampler() {}


    /** One interval's sample: the interval's close (nanoseconds since midnight, exclusive) and the book then. */
    public record Sample(long intervalEndNanos, BookSnapshot book) {}


    public static List<Sample> sample(List<BookSnapshot> history, long intervalNanos) {
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("Sampling interval must be positive: " + intervalNanos);
        }
        List<Sample> samples = new ArrayList<>();
        int start = 0;
        while (start < history.size() && history.get(start).timestampNanos() < 0) {
            start++;
        }
        if (start == history.size()) {
            return samples;
        }
        BookSnapshot current = history.get(start);
        long bucket = current.timestampNanos() / intervalNanos;
        for (int i = start + 1; i < history.size(); i++) {
            BookSnapshot next = history.get(i);
            long nextBucket = next.timestampNanos() / intervalNanos;
            while (bucket < nextBucket) {
                samples.add(new Sample((bucket + 1) * intervalNanos, current));
                bucket++;
            }
            current = next;
        }
        samples.add(new Sample((bucket + 1) * intervalNanos, current));
        return samples;
    }
}
