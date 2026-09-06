package tech.kzen.sample.itch.day;


/**
 * The host-neutral admission seam: a {@link SymbolDay} acquires a lease for its weight before allocating anything
 * and returns it only after its native storage is released. The no-arg routes use {@link #unlimited()}; a host
 * implements this over its own weighted semaphore.
 */
public interface MaterializationBudget {
    /**
     * Blocks until [weight] can be admitted, or throws. An implementation must reject a weight it can never
     * admit before waiting (never block forever) and must honour interruption.
     */
    Lease acquire(MaterializationWeight weight) throws InterruptedException;


    /** Whether [weight] could ever be admitted; the loader consults this before blocking. */
    default boolean canEverAdmit(MaterializationWeight weight) {
        return true;
    }


    /** A held permit; closing returns it exactly once (a second close is a no-op). */
    interface Lease extends AutoCloseable {
        MaterializationWeight weight();

        @Override
        void close();
    }


    /** No admission control: every acquisition succeeds immediately. */
    static MaterializationBudget unlimited() {
        return Unlimited.instance;
    }


    enum Unlimited implements MaterializationBudget {
        instance;

        @Override
        public Lease acquire(MaterializationWeight weight) {
            return new Lease() {
                @Override public MaterializationWeight weight() { return weight; }
                @Override public void close() {}
            };
        }
    }
}
