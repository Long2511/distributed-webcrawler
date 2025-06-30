package com.ouroboros.webcrawler.frontier;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data structure to store complete processing task information in Redis
 * This enables proper task recovery when workers fail
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingTaskData {

    /**
     * The URL being processed
     */
    private String url;

    /**
     * Session ID this URL belongs to
     */
    private String sessionId;

    /**
     * Crawl depth of this URL
     */
    private int depth;

    /**
     * Priority of this URL (for queue ordering)
     */
    private double priority;

    /**
     * Parent URL that discovered this URL
     */
    private String parentUrl;

    /**
     * Machine ID that is processing this URL
     */
    private String assignedTo;

    /**
     * When this URL was assigned to a worker
     */
    private LocalDateTime assignedAt;

    /**
     * Original CrawlUrl object for complete restoration
     */
    private CrawlUrl originalCrawlUrl;

    /**
     * Number of retry attempts (for future retry logic)
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * When this task was first created
     */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
