package com.ouroboros.webcrawler.frontier;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics for URL frontier performance and state
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrontierStats {

    private int pendingUrls;     // URLs waiting to be crawled
    private int visitedUrls;     // URLs that have been crawled (completed + failed)
    private int processingUrls;  // URLs currently being processed
    private int completedUrls;   // URLs successfully crawled
    private int failedUrls;      // URLs that failed to crawl
    private int totalUrls;       // Total URLs discovered
    private double avgPriority;  // Average priority of pending URLs
    private double crawlRate;    // Crawl rate (URLs per minute)
    private String sessionId;    // Session these stats belong to
    private long timestamp;      // When these stats were calculated
}
