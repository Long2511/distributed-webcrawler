package com.ouroboros.webcrawler.metrics;

import com.ouroboros.webcrawler.frontier.FrontierStats;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class CrawlerMetrics {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlSessionRepository sessionRepository;

    @Autowired
    private CrawledPageRepository pageRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String WORKER_REGISTRY_KEY = "workers:active";

    public SystemMetrics getSystemMetrics() {
        FrontierStats frontierStats = urlFrontier.getStats();
        
        SystemMetrics metrics = new SystemMetrics();
        metrics.setFrontierStats(frontierStats);
        
        // Get active sessions count
        long activeSessions = sessionRepository.findByStatus("RUNNING").size();
        metrics.setActiveSessions(activeSessions);
        
        // Get total sessions count
        long totalSessions = sessionRepository.count();
        metrics.setTotalSessions(totalSessions);
        
        // Get total pages crawled
        long totalPages = pageRepository.count();
        metrics.setTotalPagesCrawled(totalPages);
        
        // Get active workers count
        Set<Object> activeWorkers = redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
        int activeWorkersCount = activeWorkers != null ? activeWorkers.size() : 0;
        metrics.setActiveWorkers(activeWorkersCount);
        
        // Calculate rates
        if (frontierStats.getCompletedUrls() > 0) {
            metrics.setCrawlRate(frontierStats.getCrawlRate());
        }
        
        return metrics;
    }

    public Map<String, Object> getDetailedMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        SystemMetrics systemMetrics = getSystemMetrics();
        metrics.put("system", systemMetrics);
        
        // Add frontier details
        metrics.put("frontier", Map.of(
            "pendingUrls", urlFrontier.getPendingUrlCount(),
            "stats", urlFrontier.getStats()
        ));
        
        // Add session breakdown
        metrics.put("sessions", Map.of(
            "running", sessionRepository.findByStatus("RUNNING").size(),
            "completed", sessionRepository.findByStatus("COMPLETED").size(),
            "failed", sessionRepository.findByStatus("FAILED").size(),
            "stopped", sessionRepository.findByStatus("STOPPED").size()
        ));
        
        return metrics;
    }

    @Data
    public static class SystemMetrics {
        private FrontierStats frontierStats;
        private long activeSessions;
        private long totalSessions;
        private long totalPagesCrawled;
        private int activeWorkers;
        private double crawlRate;
    }
}
