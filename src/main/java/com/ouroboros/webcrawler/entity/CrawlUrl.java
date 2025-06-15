package com.ouroboros.webcrawler.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity representing a URL in the frontier queue
 */
@Entity
@Table(name = "crawl_urls", indexes = {
    @Index(name = "url_idx", columnList = "url", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private int depth;

    @Column(length = 20)
    private String status; // QUEUED, PROCESSING, COMPLETED, FAILED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @Column
    private int retryCount;

    @Column(length = 1024)
    private String errorMessage;

    @Column(length = 36)
    private String sessionId;
}
