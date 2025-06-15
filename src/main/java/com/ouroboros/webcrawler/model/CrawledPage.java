package com.ouroboros.webcrawler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawledPage {

    private String id;

    private String url;

    private String title;
    private String content;
    private Set<String> outgoingLinks;
    private Map<String, String> metadata;
    private int statusCode;
    private String contentType;
    private long contentLength;
    private LocalDateTime crawlTimestamp;
    private LocalDateTime lastModified;
    private int crawlDepth;
    private String domain;
    private boolean parsed;
}
