package com.ouroboros.webcrawler.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Document representing a URL in the frontier queue
 */
@Document(collection = "crawlUrls")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlUrl {

    @Id
    private String id;

    @Indexed(unique = true)
    private String url;

    private int priority;

    private int depth;

    @Indexed
    private String status; // QUEUED, PROCESSING, COMPLETED, FAILED, RETRY

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private int retryCount;

    private String errorMessage;

    private LocalDateTime nextRetryAt;

    @Indexed
    private String sessionId;

    @Indexed
    private String domain;

    @Indexed
    private String processingInstance;

    /**
     * Extract domain from URL
     */
    public void extractDomain() {
        if (domain == null && url != null) {
            try {
                java.net.URL parsedUrl = new java.net.URL(url);
                domain = parsedUrl.getHost();
            } catch (java.net.MalformedURLException e) {
                // Ignore malformed URLs
            }
        }
    }
}
