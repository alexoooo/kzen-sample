package tech.kzen.sample.embed.host;


/** A snapshot of {@link WeightedBudget}'s counters: sizes in bytes, the rest counts. */
public record BudgetStats(
        long capacityBytes,
        long currentBytes,
        long currentNativeBytes,
        long peakBytes,
        int outstandingItems,
        int waiting,
        long acquisitions,
        long releases,
        long waits,
        long interruptedWaits,
        long oversizedRejections
) {}
