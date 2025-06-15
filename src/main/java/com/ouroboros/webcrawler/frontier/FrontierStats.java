package com.ouroboros.webcrawler.frontier;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Statistics about the URL frontier state
 */
@Data
public class FrontierStats {
    // Queue sizes by priority (1-5)
    private Map<Integer, Long> queueSizes = new HashMap<>();

    // Number of URLs currently being processed
    private long processingCount;

    // Number of URLs that have been visited
    private long visitedCount;

    // Total URLs in the frontier (sum of all queues)
    public long getTotalQueuedCount() {
        return queueSizes.values().stream().mapToLong(Long::longValue).sum();
    }
}
