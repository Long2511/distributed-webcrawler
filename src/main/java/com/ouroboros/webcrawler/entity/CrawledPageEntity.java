package com.ouroboros.webcrawler.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Entity representing a crawled web page stored in PostgreSQL
 */
@Entity
@Table(name = "crawled_pages", indexes = {
    @Index(name = "url_idx", columnList = "url", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawledPageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 1024)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @ElementCollection
    @CollectionTable(name = "page_outgoing_links", joinColumns = @JoinColumn(name = "page_id"))
    @Column(name = "link", length = 2048)
    private Set<String> outgoingLinks = new HashSet<>();

    @Column
    private int statusCode;

    @Column(length = 255)
    private String contentType;

    @Column
    private long contentLength;

    @Column(nullable = false)
    private LocalDateTime crawlTimestamp;

    @Column
    private LocalDateTime lastModified;

    @Column
    private int crawlDepth;

    @Column(length = 255)
    private String domain;

    @Column
    private boolean parsed;
}
