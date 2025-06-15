package com.ouroboros.webcrawler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Represents a crawl session with specific configuration and targets
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlSession {
    private String id;

    private String name;
    private String description;
    private Set<String> seedUrls;
    private int maxDepth;
    private int maxPagesPerDomain;
    private boolean respectRobotsTxt;
    private Map<String, String> customHeaders;
    private int crawlDelay;
    private String status; // CREATED, RUNNING, PAUSED, COMPLETED, FAILED
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long totalPagesCrawled;
    private long totalUrlsDiscovered;
    private Set<String> allowedDomains; // null means all domains are allowed
    private Set<String> urlPatterns; // patterns to include/exclude
}
