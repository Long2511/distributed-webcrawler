package com.ouroboros.webcrawler.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "crawl_urls")
public class CrawlUrl {

    @Id
    private String id;
    private String url;
    private String sessionId;
    private String status;
    private int depth;
    private double priority; // Priority for crawling (higher = more important)
    private String parentUrl;
    private String assignedTo;
    private String assignedWorker; // Worker assigned to process this URL

    // Enhanced fields for precise microservice data flow
    private String assignedWorkerId; // Specific WorkerID assigned by URLFrontier
    private String assignedFrontierId; // Which URLFrontier assigned this URL
    private String discoveredBy; // Which worker discovered this URL
    private String crawlStatus; // PENDING, ASSIGNED, CRAWLING, COMPLETED, FAILED, ERROR

    // Timing fields for tracking the complete flow
    private LocalDateTime assignedAt;
    private LocalDateTime discoveredAt;
    private LocalDateTime addedAt; // When URL was added to frontier
    private LocalDateTime completedAt; // When URL processing was completed
    private LocalDateTime crawlStartTime; // When worker started crawling
    private LocalDateTime crawlEndTime; // When worker finished crawling

    // Error handling and retry logic
    private int retryCount; // Number of retry attempts
    private String lastError; // Last error message
    private String errorMessage; // Current error message

    // Constructor for discovered URLs
    public static CrawlUrl createDiscoveredUrl(String url, String parentUrl, String discoveredBy, int depth) {
        return CrawlUrl.builder()
            .url(url)
            .parentUrl(parentUrl)
            .discoveredBy(discoveredBy)
            .depth(depth)
            .crawlStatus("PENDING")
            .discoveredAt(LocalDateTime.now())
            .priority(1.0)
            .retryCount(0)
            .build();
    }

    // Constructor for initial URLs from API
    public static CrawlUrl createInitialUrl(String url, String sessionId) {
        return CrawlUrl.builder()
            .url(url)
            .sessionId(sessionId)
            .depth(0)
            .crawlStatus("PENDING")
            .addedAt(LocalDateTime.now())
            .priority(10.0) // Higher priority for initial URLs
            .retryCount(0)
            .build();
    }
}
