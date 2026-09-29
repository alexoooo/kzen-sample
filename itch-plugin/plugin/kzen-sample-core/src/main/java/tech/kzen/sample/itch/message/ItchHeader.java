package tech.kzen.sample.itch.message;


/**
 * The fields every ITCH 5.0 message carries at the same position, plus the feed ordinal.
 *
 * @param ordinal        zero-based position of the message in the feed it was read from; not a wire field,
 *                       it is the tie-breaker for messages with equal timestamps and the encoder ignores it
 * @param stockLocate    day-local instrument code, 0 for messages that are not stock dependent
 * @param trackingNumber Nasdaq internal tracking number
 * @param timestampNanos nanoseconds since midnight
 */
public record ItchHeader(
        long ordinal,
        int stockLocate,
        int trackingNumber,
        long timestampNanos
) {
    public static final int marketWideLocate = 0;
}
