package tech.kzen.sample.itch.wire;

import tech.kzen.sample.itch.message.ItchMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;


/** {@link ItchCursor} over a framed feed stream, decoding one message per pull. */
final class FrameCursor implements ItchCursor {
    private final ItchFrameInput frames;
    private ItchMessage pending;
    private boolean finished;
    private boolean closed;


    FrameCursor(ItchFrameInput frames) {
        this.frames = frames;
    }


    @Override
    public boolean hasNext() {
        if (pending != null) {
            return true;
        }
        if (finished || closed) {
            return false;
        }
        try {
            if (!frames.next()) {
                finished = true;
                close();
                return false;
            }
            pending = ItchDecoder.decode(frames.frame(), 0, frames.frameLength(), frames.ordinal());
            return true;
        }
        catch (RuntimeException e) {
            try {
                close();
            }
            catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }


    @Override
    public ItchMessage next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        ItchMessage message = pending;
        pending = null;
        return message;
    }


    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending = null;
        try {
            frames.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
