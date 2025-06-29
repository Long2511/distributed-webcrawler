package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/crawler")
public class CrawlerController {

    @Autowired
    private CrawlerManager crawlerManager;

    @PostMapping("/sessions")
    public ResponseEntity<String> startCrawlSession(@RequestBody CrawlSessionRequest request) {
        log.info("Starting crawl session: {}", request.getName());
        
        CrawlSessionEntity session = CrawlSessionEntity.builder()
            .id(UUID.randomUUID().toString())
            .name(request.getName())
            .seedUrls(request.getSeedUrls())
            .status("PENDING")
            .maxDepth(request.getMaxDepth() != null ? request.getMaxDepth() : 3)
            .maxPages(request.getMaxPages() != null ? request.getMaxPages() : 1000L)
            .createdBy("console")
            .createdAt(LocalDateTime.now())
            .allowedDomains(request.getAllowedDomains())
            .disallowedUrls(request.getDisallowedUrls())
            .userAgent("Ouroboros Web Crawler/1.0")
            .politenessDelay(500)
            .respectRobotsTxt(true)
            .build();
        
        String sessionId = crawlerManager.startCrawlSession(session);
        
        return ResponseEntity.ok(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<Void> stopCrawlSession(@PathVariable String sessionId) {
        log.info("Stopping crawl session: {}", sessionId);
        crawlerManager.stopCrawlSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<CrawlSessionEntity>> getAllSessions() {
        List<CrawlSessionEntity> sessions = crawlerManager.getAllSessions();
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<CrawlSessionEntity> getSession(@PathVariable String sessionId) {
        CrawlSessionEntity session = crawlerManager.getSession(sessionId);
        if (session != null) {
            return ResponseEntity.ok(session);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/quick-crawl")
    public ResponseEntity<String> quickCrawl(@RequestBody QuickCrawlRequest request) {
        log.info("Starting quick crawl for URL: {}", request.getUrl());
        
        CrawlSessionEntity session = CrawlSessionEntity.builder()
            .id(UUID.randomUUID().toString())
            .name("Quick Crawl - " + request.getUrl())
            .seedUrls(Arrays.asList(request.getUrl()))
            .status("PENDING")
            .maxDepth(request.getMaxDepth() != null ? request.getMaxDepth() : 2)
            .maxPages(request.getMaxPages() != null ? request.getMaxPages() : 100L)
            .createdBy("console")
            .createdAt(LocalDateTime.now())
            .userAgent("Ouroboros Web Crawler/1.0")
            .politenessDelay(500)
            .respectRobotsTxt(true)
            .priority(request.getPriority() != null ? request.getPriority() : "MEDIUM")
            .description("Quick crawl session")
            .autoResume(true)
            .build();
        
        String sessionId = crawlerManager.startCrawlSession(session);
        
        return ResponseEntity.ok(sessionId);
    }

    // Request DTOs
    public static class CrawlSessionRequest {
        private String name;
        private List<String> seedUrls;
        private Integer maxDepth;
        private Long maxPages;
        private List<String> allowedDomains;
        private List<String> disallowedUrls;

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getSeedUrls() { return seedUrls; }
        public void setSeedUrls(List<String> seedUrls) { this.seedUrls = seedUrls; }
        public Integer getMaxDepth() { return maxDepth; }
        public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
        public Long getMaxPages() { return maxPages; }
        public void setMaxPages(Long maxPages) { this.maxPages = maxPages; }
        public List<String> getAllowedDomains() { return allowedDomains; }
        public void setAllowedDomains(List<String> allowedDomains) { this.allowedDomains = allowedDomains; }
        public List<String> getDisallowedUrls() { return disallowedUrls; }
        public void setDisallowedUrls(List<String> disallowedUrls) { this.disallowedUrls = disallowedUrls; }
    }

    public static class QuickCrawlRequest {
        private String url;
        private Integer maxDepth;
        private Long maxPages;
        private String priority;

        // Getters and setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Integer getMaxDepth() { return maxDepth; }
        public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
        public Long getMaxPages() { return maxPages; }
        public void setMaxPages(Long maxPages) { this.maxPages = maxPages; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
    }
}
