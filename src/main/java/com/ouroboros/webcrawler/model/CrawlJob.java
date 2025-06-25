package com.ouroboros.webcrawler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Represents a crawl job to be executed by a worker
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlJob implements Serializable {
    private String id;
    private String url;
    private int priority;
    private int depth;
    private String jobStatus; // QUEUED, PROCESSING, COMPLETED, FAILED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int retryCount;
    private String errorMessage;
    private String sessionId;
    private String processingInstance;
}
