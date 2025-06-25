package com.ouroboros.webcrawler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Model representing a crawl session with specific configuration and targets
 * Used for API interactions
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlSession {

    private String id;
    private String name;
    private String description;
    private Set<String> seedUrls = new HashSet<>();
    private int maxDepth;
    private int maxPagesPerDomain;
    private boolean respectRobotsTxt;
    private boolean sameDomainOnly; // Added new field for restricting crawling to the same domain
    private Map<String, String> customHeaders;
    private String status; // RUNNING, COMPLETED, STOPPED, FAILED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private Set<String> includePatterns;
    private Set<String> excludePatterns;
    private int progress; // Progress percentage (0-100)

    /**
     * Add a seed URL to the crawl session
     */
    public void addSeedUrl(String url) {
        if (seedUrls == null) {
            seedUrls = new HashSet<>();
        }
        seedUrls.add(url);
    }
}
