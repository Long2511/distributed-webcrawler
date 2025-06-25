package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlSession;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST API controller for session details page
 */
@RestController
@RequestMapping("/api/sessions")
@Slf4j
public class SessionDetailsApiController {

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private CrawledPageRepository crawledPageRepository;

    /**
     * Get session statistics
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> getSessionStats(@PathVariable("id") String sessionId) {
        log.debug("Fetching statistics for session: {}", sessionId);

        Map<String, Long> sessionStats = crawlerManager.getSessionStats(sessionId);
        if (sessionStats.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Calculate additional stats
        Map<String, Object> result = new HashMap<>();
        long totalPages = sessionStats.getOrDefault("totalUrls", 0L);
        long completedPages = sessionStats.getOrDefault("completedUrls", 0L);
        long failedPages = sessionStats.getOrDefault("failedUrls", 0L);
        long queuedPages = sessionStats.getOrDefault("queuedUrls", 0L);

        // Calculate completion percentage
        double completionPercentage = 0.0;
        if (totalPages > 0) {
            completionPercentage = (double) completedPages / totalPages * 100;
        }

        // Calculate error rate
        double errorRate = 0.0;
        long processedPages = completedPages + failedPages;
        if (processedPages > 0) {
            errorRate = (double) failedPages / processedPages * 100;
        }

        // Add all stats to result
        result.put("totalPages", totalPages);
        result.put("completedPages", completedPages);
        result.put("failedPages", failedPages);
        result.put("queuedPages", queuedPages);
        result.put("completionPercentage", Math.round(completionPercentage * 10) / 10.0);
        result.put("errorRate", Math.round(errorRate * 10) / 10.0);

        // Add domain count if available
        result.put("domainCount", sessionStats.getOrDefault("uniqueDomains", 0L));

        return ResponseEntity.ok(result);
    }

    /**
     * Get recently crawled URLs for a session
     */
    @GetMapping("/{id}/urls")
    public ResponseEntity<List<Map<String, Object>>> getRecentUrls(@PathVariable("id") String sessionId,
                                                            @RequestParam(defaultValue = "10") int limit) {
        log.debug("Fetching recent URLs for session {}, limit: {}", sessionId, limit);

        Optional<CrawlSession> sessionOpt = crawlerManager.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Find the most recent crawled pages for this session
        List<CrawledPageEntity> recentPages = crawledPageRepository.findBySessionId(
            sessionId,
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "crawlTimestamp"))
        );

        // Map entities to a format suitable for UI
        List<Map<String, Object>> result = recentPages.stream().map(page -> {
            Map<String, Object> urlData = new HashMap<>();
            urlData.put("url", page.getUrl());
            urlData.put("statusCode", page.getStatusCode());
            urlData.put("contentType", page.getContentType());
            urlData.put("contentLength", page.getContentLength());
            urlData.put("linkCount", page.getOutgoingLinks() != null ? page.getOutgoingLinks().size() : 0);
            urlData.put("crawlTimestamp", page.getCrawlTimestamp());
            urlData.put("domain", page.getDomain());

            return urlData;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * Get session details
     */
    @GetMapping("/{id}")
    public ResponseEntity<CrawlSession> getSession(@PathVariable("id") String sessionId) {
        log.debug("Fetching session details: {}", sessionId);

        Optional<CrawlSession> session = crawlerManager.getSession(sessionId);
        return session.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
