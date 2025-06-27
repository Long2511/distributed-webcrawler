package com.ouroboros.webcrawler.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Document representing a crawled web page stored in MongoDB
 */
@Document(collection = "crawledPages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawledPageEntity {

    @Id
    private String id;

    @Indexed(unique = true)
    private String url;

    private String title;

    private String content;

    // Add field to store raw HTML content
    private String html;

    private Set<String> outgoingLinks = new HashSet<>();

    private int statusCode;

    private String contentType;

    private long contentLength;

    private LocalDateTime crawlTimestamp;

    private int crawlDepth;

    @Indexed
    private String domain;

    private boolean parsed;

    private String errorMessage;

    @Indexed
    private String sessionId;

    private Map<String, String> metaInfo = new HashMap<>();
}
