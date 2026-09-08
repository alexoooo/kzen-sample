package tech.kzen.sample.embed.host.catalog;

import java.io.InputStream;
import java.io.IOException;

final class ItchPreparation {
    volatile String state = "queued";
    volatile String detail = "Waiting to prepare";
    volatile long bytes;
    volatile boolean cancelled;
    volatile boolean finished;
    private Thread thread;
    private InputStream input;

    synchronized void started() { thread = Thread.currentThread(); }
    synchronized void finished() { thread = null; input = null; finished = true; }
    synchronized void input(InputStream value) throws IOException {
        input = value;
        if (cancelled && value != null) value.close();
    }
    synchronized void cancel() {
        if (finished) return;
        cancelled = true;
        if (thread != null) thread.interrupt();
        if (input != null) {
            try { input.close(); }
            catch (IOException e) { detail = "Cancelling: " + e.getMessage(); }
        }
    }
    boolean proceed() { return !cancelled && !Thread.currentThread().isInterrupted(); }
}
