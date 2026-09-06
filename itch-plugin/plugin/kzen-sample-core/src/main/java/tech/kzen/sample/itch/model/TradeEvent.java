package tech.kzen.sample.itch.model;


/**
 * One execution or print in feed order. Kinds stay distinct: {@link Kind#EXECUTED} ({@code E}/{@code C}) belongs
 * to a displayed order's lifecycle, {@link Kind#NON_DISPLAYED} ({@code P}) never touched the displayed book,
 * {@link Kind#CROSS} ({@code Q}) is a bulk print. [printable] is false for a non-printable {@code C} and for a
 * zero-share cross; a later break is recorded separately by match number.
 */
public record TradeEvent(
        Kind kind,
        long ordinal,
        long timestampNanos,
        long matchNumber,
        long shares,
        long price,
        long orderReference,
        boolean printable
) {
    public enum Kind { EXECUTED, NON_DISPLAYED, CROSS }

    public static final long noOrderReference = 0;
}
