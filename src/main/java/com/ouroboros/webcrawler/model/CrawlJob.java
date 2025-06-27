package com.ouroboros.webcrawler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlJob {

    private String id;
    private String url;
    private String sessionId;
    private int depth;
    private double priority;
    private String parentUrl;
    private LocalDateTime createdAt;
    private String assignedTo;
    private LocalDateTime assignedAt;
    private int retryCount;
    private String status;
}
