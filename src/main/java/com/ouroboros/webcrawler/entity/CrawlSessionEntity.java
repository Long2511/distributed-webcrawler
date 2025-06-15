package com.ouroboros.webcrawler.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Entity representing a crawl session with specific configuration and targets
 */
@Entity
@Table(name = "crawl_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlSessionEntity {

    @Id
    private String id;

    @Column(length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection
    @CollectionTable(name = "session_seed_urls", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "seed_url", length = 2048)
    private Set<String> seedUrls = new HashSet<>();

    @Column
    private int maxDepth;

    @Column
    private int maxPagesPerDomain;

    @Column
    private boolean respectRobotsTxt;

    @ElementCollection
    @CollectionTable(name = "session_custom_headers", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "header_name")
    @Column(name = "header_value")
    private Map<String, String> customHeaders;

    @Column
    private int crawlDelay;

    @Column(length = 20)
    private String status; // CREATED, RUNNING, PAUSED, COMPLETED, FAILED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;

    @Column
    private long totalPagesCrawled;

    @Column
    private long totalUrlsDiscovered;

    @ElementCollection
    @CollectionTable(name = "session_allowed_domains", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "domain", length = 255)
    private Set<String> allowedDomains;

    @ElementCollection
    @CollectionTable(name = "session_url_patterns", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "pattern", length = 255)
    private Set<String> urlPatterns;
}
