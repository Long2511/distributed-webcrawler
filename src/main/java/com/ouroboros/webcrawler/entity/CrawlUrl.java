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
    private LocalDateTime assignedAt;
    private LocalDateTime discoveredAt;
    private LocalDateTime addedAt; // When URL was added to frontier
    private LocalDateTime completedAt; // When URL processing was completed
    private int retryCount; // Number of retry attempts
    private String lastError; // Last error message
    private String errorMessage; // Current error message
}
