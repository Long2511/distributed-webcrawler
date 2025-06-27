package com.ouroboros.webcrawler.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlUrl {

    private String url;
    private String sessionId;
    private int depth;
    private double priority;
    private String parentUrl;
    private LocalDateTime discoveredAt;
    private String status; // PENDING, IN_PROGRESS, COMPLETED, FAILED
    private int retryCount;
    private String assignedTo; // Worker instance ID
    private LocalDateTime assignedAt;
}
