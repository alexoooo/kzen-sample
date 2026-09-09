package tech.kzen.sample.itch.day;

/** Observes a batch load without receiving or retaining its native storage. Counts include store frame headers. */
@FunctionalInterface
public interface MaterializationProgress {
    MaterializationProgress none = (messages, bytes) -> {};

    /** Called after admission, periodically while copying, and after the final validated record. */
    void update(long messages, long bytes);
}
