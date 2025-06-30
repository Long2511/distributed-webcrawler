package com.ouroboros.webcrawler.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "crawl_sessions")
public class CrawlSessionEntity {

    @Id
    private String id;
    private String name;
    private List<String> seedUrls;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED, STOPPED
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
    private double seedPriority;
}
