package tech.kzen.sample.itch.store;


/** A store that is absent, incomplete, of another format version, or stale against its source. */
public class ItchStoreException extends RuntimeException {
    public ItchStoreException(String message) {
        super(message);
    }

    public ItchStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
