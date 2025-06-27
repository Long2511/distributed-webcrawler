package com.ouroboros.webcrawler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlSession {

    private String id;
    private String name;
    private List<String> seedUrls;
    private String status;
    private int maxDepth;
    private long maxPages;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long totalPagesCrawled;
    private long totalPagesDiscovered;
    private long totalBytesCrawled;
    private List<String> allowedDomains;
    private List<String> disallowedUrls;
    private String userAgent;
    private int politenessDelay;
    private boolean respectRobotsTxt;
}
