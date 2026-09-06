package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;


/**
 * Single-pass iteration over decoded messages that owns a resource: a feed stream, or a store's partition
 * files. A decode or framing failure inside {@link #hasNext} closes the resource before it propagates, so a
 * failed iteration never leaks a handle; a successful iteration still needs {@link #close} (try-with-resources,
 * or {@link ItchReader#forEach} / {@link ItchReader#stream} which own it).
 */
public interface ItchCursor extends Iterator<ItchMessage>, AutoCloseable {
    @Override
    void close();


    /** A cursor over a raw or gzip feed stream. */
    static ItchCursor open(InputStream stream) throws IOException {
        return new FrameCursor(ItchFrameInput.open(stream));
    }


    /** Wraps any closeable message iterator with the failure-closes-first rule. */
    static <T extends Iterator<ItchMessage> & AutoCloseable> ItchCursor adopt(T delegate) {
        return new ItchCursor() {
            private boolean closed;

            @Override
            public boolean hasNext() {
                if (closed) {
                    return false;
                }
                try {
                    return delegate.hasNext();
                }
                catch (RuntimeException e) {
                    closeSuppressing(this, e);
                    throw e;
                }
            }

            @Override
            public ItchMessage next() {
                try {
                    return delegate.next();
                }
                catch (RuntimeException e) {
                    closeSuppressing(this, e);
                    throw e;
                }
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    delegate.close();
                }
                catch (RuntimeException e) {
                    throw e;
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }


    private static void closeSuppressing(ItchCursor cursor, RuntimeException primary) {
        try {
            cursor.close();
        }
        catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
