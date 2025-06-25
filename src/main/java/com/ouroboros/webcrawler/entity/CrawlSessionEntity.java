package com.ouroboros.webcrawler.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Document representing a crawl session with specific configuration and targets
 */
@Document(collection = "crawlSessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlSessionEntity {

    @Id
    private String id;

    private String name;

    private String description;

    private Set<String> seedUrls = new HashSet<>();

    private int maxDepth;

    private int maxPagesPerDomain;

    private boolean respectRobotsTxt;

    private Map<String, String> customHeaders = new HashMap<>();

    private String status; // RUNNING, COMPLETED, STOPPED, FAILED

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    private Set<String> includePatterns = new HashSet<>();

    private Set<String> excludePatterns = new HashSet<>();
}
