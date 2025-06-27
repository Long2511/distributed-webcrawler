package com.ouroboros.webcrawler.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "crawled_pages")
public class CrawledPageEntity {

    @Id
    private String id;
    private String url;
    private String title;
    private String content;
    private int statusCode;
    private String contentType;
    private long contentLength;
    private String sessionId;
    private String crawlerInstanceId;
    private LocalDateTime crawlTime;
    private LocalDateTime lastModified;
    private Map<String, String> headers;
    private int depth;
    private String parentUrl;
    private long crawlDurationMs;
    private String errorMessage;
}
