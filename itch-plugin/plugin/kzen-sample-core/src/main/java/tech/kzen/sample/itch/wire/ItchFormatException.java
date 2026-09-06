package tech.kzen.sample.itch.wire;


/** A feed that violates the ITCH 5.0 framing or message layout; the message names the feed ordinal. */
public class ItchFormatException extends RuntimeException {
    public ItchFormatException(String message) {
        super(message);
    }
}
