package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.frontier.FrontierStats;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API controller for dashboard
 */
@RestController
@RequestMapping("/api/dashboard")
@Slf4j
public class DashboardApiController {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private CrawledPageRepository crawledPageRepository;

    /**
     * Get frontier statistics for the dashboard
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getFrontierStats() {
        log.debug("API - Fetching frontier statistics for dashboard");

        FrontierStats stats = urlFrontier.getStats();
        Map<String, Object> result = new HashMap<>();

        result.put("queuedUrls", stats.getQueuedUrls());
        result.put("processingUrls", stats.getProcessingUrls());
        result.put("completedUrls", stats.getCompletedUrls());
        result.put("failedUrls", stats.getFailedUrls());
        result.put("totalUrls", stats.getTotalUrls());

        // Calculate completion percentage
        double completionPercentage = 0.0;
        if (stats.getTotalUrls() > 0) {
            completionPercentage = (double) stats.getCompletedUrls() / stats.getTotalUrls() * 100;
        }
        result.put("completionPercentage", Math.round(completionPercentage * 10) / 10.0);

        // Calculate failure rate
        double failureRate = 0.0;
        long processedUrls = stats.getCompletedUrls() + stats.getFailedUrls();
        if (processedUrls > 0) {
            failureRate = (double) stats.getFailedUrls() / processedUrls * 100;
        }
        result.put("failureRate", Math.round(failureRate * 10) / 10.0);

        // Add timestamp
        result.put("timestamp", LocalDateTime.now());

        // Add system status
        if (stats.getProcessingUrls() > 0) {
            result.put("systemStatus", "RUNNING");
        } else if (stats.getTotalUrls() > 0) {
            result.put("systemStatus", "IDLE");
        } else {
            result.put("systemStatus", "INITIALIZED");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get domain statistics for the dashboard
     */
    @GetMapping("/domains")
    public ResponseEntity<List<Map<String, Object>>> getDomainStats() {
        log.debug("API - Fetching domain statistics for dashboard");

        // Use int parameter instead of PageRequest
        List<CrawledPageRepository.DomainStats> domainStats =
                crawledPageRepository.getDomainStatistics(10);

        // Find most recent pages for each domain to get additional information
        List<Map<String, Object>> result = new ArrayList<>();
        for (CrawledPageRepository.DomainStats stats : domainStats) {
            Map<String, Object> domain = new HashMap<>();

            // Get domain name from _id field using getDomain() method
            String domainName = stats.getDomain();
            domain.put("name", domainName);

            // Use count field for page count
            domain.put("pageCount", stats.getCount());

            // Get most recent page for this domain to get average size and last crawled date
            List<CrawledPageEntity> recentPages = crawledPageRepository.findByDomain(
                    domainName,
                    PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "crawlTimestamp"))
            );

            // Calculate average size - simplified as the average of available pages
            long averageSize = 0;
            if (!recentPages.isEmpty()) {
                averageSize = recentPages.get(0).getContentLength() / 1024; // Convert to KB
                domain.put("averagePageSize", averageSize);
                domain.put("lastCrawled", recentPages.get(0).getCrawlTimestamp());
            } else {
                domain.put("averagePageSize", 0);
                domain.put("lastCrawled", null);
            }

            result.add(domain);
        }

        return ResponseEntity.ok(result);
    }
}
