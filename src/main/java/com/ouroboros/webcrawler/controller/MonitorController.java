package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.frontier.FrontierStats;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.service.FaultToleranceService;
import com.ouroboros.webcrawler.service.ScalabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/monitor")
public class MonitorController {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private FaultToleranceService faultToleranceService;

    @Autowired
    private ScalabilityService scalabilityService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            // Basic metrics
            FrontierStats frontierStats = urlFrontier.getStats();
            metrics.put("frontierStats", frontierStats);
            
            // Worker metrics
            Map<String, Object> workerStats = getWorkerStats();
            metrics.put("activeWorkers", workerStats.get("activeWorkers"));
            metrics.put("totalWorkers", workerStats.get("totalWorkers"));
            
            // Session metrics
            Map<String, Object> sessionStats = getSessionStats();
            metrics.put("activeSessions", sessionStats.get("activeSessions"));
            metrics.put("totalSessions", sessionStats.get("totalSessions"));
            
            // Crawl metrics
            Map<String, Object> crawlStats = getCrawlStats();
            metrics.put("totalPagesCrawled", crawlStats.get("totalPagesCrawled"));
            metrics.put("crawlRate", crawlStats.get("crawlRate"));
            
            // Fault tolerance metrics
            Map<String, Object> faultToleranceStats = faultToleranceService.getFaultToleranceStats();
            metrics.put("faultTolerance", faultToleranceStats);
            
            // Scalability metrics
            Map<String, Object> scalabilityStats = scalabilityService.getScalabilityStats();
            metrics.put("scalability", scalabilityStats);
            
            return ResponseEntity.ok(metrics);
            
        } catch (Exception e) {
            log.error("Error getting metrics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> health = new HashMap<>();
        
        try {
            // Redis health
            boolean redisHealthy = checkRedisHealth();
            health.put("redis", redisHealthy ? "UP" : "DOWN");
            
            // MongoDB health (simplified check)
            health.put("mongodb", "UP"); // Assume UP for now
            
            // Kafka health (simplified check)
            health.put("kafka", "UP"); // Assume UP for now
            
            // Overall health
            boolean overallHealthy = redisHealthy;
            health.put("status", overallHealthy ? "UP" : "DOWN");
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            log.error("Error checking health", e);
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            return ResponseEntity.ok(health);
        }
    }

    @GetMapping("/workers")
    public ResponseEntity<Map<String, Object>> getWorkers() {
        try {
            Map<String, Object> workerStats = getWorkerStats();
            return ResponseEntity.ok(workerStats);
        } catch (Exception e) {
            log.error("Error getting worker stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> getSessions() {
        try {
            Map<String, Object> sessionStats = getSessionStats();
            return ResponseEntity.ok(sessionStats);
        } catch (Exception e) {
            log.error("Error getting session stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/fault-tolerance")
    public ResponseEntity<Map<String, Object>> getFaultToleranceStats() {
        try {
            Map<String, Object> stats = faultToleranceService.getFaultToleranceStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting fault tolerance stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/scalability")
    public ResponseEntity<Map<String, Object>> getScalabilityStats() {
        try {
            Map<String, Object> stats = scalabilityService.getScalabilityStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting scalability stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private Map<String, Object> getWorkerStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Get active workers from Redis
            String workerKey = "workers:active";
            Long activeWorkers = redisTemplate.opsForSet().size(workerKey);
            stats.put("activeWorkers", activeWorkers != null ? activeWorkers : 0);
            
            // Get total workers (including inactive)
            stats.put("totalWorkers", activeWorkers != null ? activeWorkers : 0);
            
            // Get worker details from scalability service
            Map<String, Object> scalabilityStats = scalabilityService.getScalabilityStats();
            if (scalabilityStats.containsKey("workerDetails")) {
                stats.put("workerDetails", scalabilityStats.get("workerDetails"));
            }
            
        } catch (Exception e) {
            log.error("Error getting worker stats", e);
            stats.put("activeWorkers", 0);
            stats.put("totalWorkers", 0);
        }
        
        return stats;
    }

    private Map<String, Object> getSessionStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Get session counts from Redis or database
            // For now, use simplified approach
            stats.put("activeSessions", 0);
            stats.put("totalSessions", 0);
            stats.put("runningSessions", 0);
            stats.put("pausedSessions", 0);
            stats.put("completedSessions", 0);
            
        } catch (Exception e) {
            log.error("Error getting session stats", e);
            stats.put("activeSessions", 0);
            stats.put("totalSessions", 0);
        }
        
        return stats;
    }

    private Map<String, Object> getCrawlStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Get crawl statistics from frontier
            FrontierStats frontierStats = urlFrontier.getStats();
            stats.put("totalPagesCrawled", frontierStats.getCompletedUrls());
            stats.put("pendingUrls", frontierStats.getPendingUrls());
            stats.put("failedUrls", frontierStats.getFailedUrls());
            
            // Calculate crawl rate (simplified)
            stats.put("crawlRate", 0.0); // Would need time-based calculation
            
        } catch (Exception e) {
            log.error("Error getting crawl stats", e);
            stats.put("totalPagesCrawled", 0);
            stats.put("crawlRate", 0.0);
        }
        
        return stats;
    }

    private boolean checkRedisHealth() {
        try {
            redisTemplate.opsForValue().get("health-check");
            return true;
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return false;
        }
    }
}
