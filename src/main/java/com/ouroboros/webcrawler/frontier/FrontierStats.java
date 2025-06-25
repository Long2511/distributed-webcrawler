package com.ouroboros.webcrawler.frontier;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Statistics about the URL frontier
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrontierStats {
    private long queuedUrls;
    private long processingUrls;
    private long completedUrls;
    private long failedUrls;
    private long retryUrls;
    private long totalUrls;
    private int activeInstances;
    private int queuedInstances;
    private String instanceId;
    private LocalDateTime timestamp;

    /**
     * Calculate the percentage of URLs that have been processed (completed or failed)
     */
    public double getCompletionPercentage() {
        if (totalUrls == 0) {
            return 0.0;
        }
        return (double) (completedUrls + failedUrls) / totalUrls * 100.0;
    }

    /**
     * Calculate the percentage of processed URLs that failed
     */
    public double getFailureRate() {
        long processed = completedUrls + failedUrls;
        if (processed == 0) {
            return 0.0;
        }
        return (double) failedUrls / processed * 100.0;
    }
}
