package com.ouroboros.webcrawler.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

/**
 * Monitor Service for Distributed Web Crawler
 * Implements fault tolerance by monitoring health of URLFrontiers and Workers
 * Reassigns tasks from inactive nodes to active ones
 */
@Slf4j
@Service
public class DistributedMonitorService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${webcrawler.monitor.check-interval:30000}")
    private int checkInterval;

    @Value("${webcrawler.monitor.timeout-threshold:60000}")
    private int timeoutThreshold;

    // Redis keys for monitoring
    private static final String FRONTIER_REGISTRY_KEY = "frontiers:active";
    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String FRONTIER_HEARTBEAT_PREFIX = "frontier:heartbeat:";
    private static final String WORKER_HEARTBEAT_PREFIX = "worker:heartbeat:";
    private static final String FRONTIER_ASSIGNMENT_PREFIX = "frontier:assigned:";
    private static final String WORKER_ASSIGNMENT_PREFIX = "worker:assigned:";

    // Notification channels
    private static final String FRONTIER_NOTIFICATION_CHANNEL = "frontier:notification";
    private static final String WORKER_NOTIFICATION_CHANNEL = "worker:notification";
    private static final String CRAWLER_MANAGER_NOTIFICATION_CHANNEL = "crawlermanager:notification";

    @PostConstruct
    public void initialize() {
        log.info("Monitor Service initialized - checking every {}ms with timeout threshold {}ms",
            checkInterval, timeoutThreshold);
    }

    /**
     * Main monitoring loop - checks health of all services and handles failover
     */
    @Scheduled(fixedDelayString = "${webcrawler.monitor.check-interval:30000}")
    public void monitorServices() {
        try {
            log.debug("Starting health check cycle");

            // Monitor URLFrontiers and handle failover
            monitorURLFrontiers();

            // Monitor Workers and handle failover
            monitorWorkers();

            // Clean up expired heartbeats
            cleanupExpiredHeartbeats();

            log.debug("Health check cycle completed");

        } catch (Exception e) {
            log.error("Error during monitoring cycle", e);
        }
    }

    /**
     * Monitor URLFrontiers and reassign work from inactive ones
     */
    private void monitorURLFrontiers() {
        try {
            Map<Object, Object> registeredFrontiers = redisTemplate.opsForHash().entries(FRONTIER_REGISTRY_KEY);
            List<String> inactiveFrontiers = new ArrayList<>();

            for (Object frontierIdObj : registeredFrontiers.keySet()) {
                String frontierId = frontierIdObj.toString();

                if (!isNodeActive(frontierId, FRONTIER_HEARTBEAT_PREFIX)) {
                    inactiveFrontiers.add(frontierId);
                    log.warn("URLFrontier {} detected as inactive", frontierId);
                }
            }

            // Handle failover for inactive frontiers
            for (String inactiveFrontier : inactiveFrontiers) {
                handleURLFrontierFailover(inactiveFrontier);
            }

            if (!inactiveFrontiers.isEmpty()) {
                log.info("Processed failover for {} inactive URLFrontiers", inactiveFrontiers.size());
            }

        } catch (Exception e) {
            log.error("Error monitoring URLFrontiers", e);
        }
    }

    /**
     * Monitor Workers and reassign work from inactive ones
     */
    private void monitorWorkers() {
        try {
            Map<Object, Object> registeredWorkers = redisTemplate.opsForHash().entries(WORKER_REGISTRY_KEY);
            List<String> inactiveWorkers = new ArrayList<>();

            for (Object workerIdObj : registeredWorkers.keySet()) {
                String workerId = workerIdObj.toString();

                if (!isNodeActive(workerId, WORKER_HEARTBEAT_PREFIX)) {
                    inactiveWorkers.add(workerId);
                    log.warn("Worker {} detected as inactive", workerId);
                }
            }

            // Handle failover for inactive workers
            for (String inactiveWorker : inactiveWorkers) {
                handleWorkerFailover(inactiveWorker);
            }

            if (!inactiveWorkers.isEmpty()) {
                log.info("Processed failover for {} inactive Workers", inactiveWorkers.size());
            }

        } catch (Exception e) {
            log.error("Error monitoring Workers", e);
        }
    }

    /**
     * Check if a node is active based on heartbeat
     */
    private boolean isNodeActive(String nodeId, String heartbeatPrefix) {
        try {
            String heartbeatKey = heartbeatPrefix + nodeId;
            String lastHeartbeat = (String) redisTemplate.opsForValue().get(heartbeatKey);

            if (lastHeartbeat == null) {
                return false;
            }

            LocalDateTime lastSeen = LocalDateTime.parse(lastHeartbeat);
            LocalDateTime threshold = LocalDateTime.now().minusSeconds(timeoutThreshold / 1000);

            return lastSeen.isAfter(threshold);

        } catch (Exception e) {
            log.debug("Error checking node {} activity", nodeId, e);
            return false;
        }
    }

    /**
     * Handle failover for an inactive URLFrontier
     * Reassign its pending work to active frontiers
     */
    private void handleURLFrontierFailover(String inactiveFrontierId) {
        try {
            log.info("Handling failover for inactive URLFrontier: {}", inactiveFrontierId);

            // Get pending URLs assigned to this frontier
            String frontierQueueKey = FRONTIER_ASSIGNMENT_PREFIX + inactiveFrontierId;
            List<CrawlUrl> pendingUrls = new ArrayList<>();

            // Retrieve all pending URLs from the inactive frontier's queue
            while (true) {
                String urlJson = (String) redisTemplate.opsForList().rightPop(frontierQueueKey);
                if (urlJson == null) {
                    break;
                }

                try {
                    CrawlUrl url = objectMapper.readValue(urlJson, CrawlUrl.class);
                    pendingUrls.add(url);
                } catch (Exception e) {
                    log.error("Error parsing URL from inactive frontier", e);
                }
            }

            if (!pendingUrls.isEmpty()) {
                // Reassign URLs to active frontiers
                reassignUrlsToActiveFrontiers(pendingUrls, inactiveFrontierId);

                log.info("Reassigned {} pending URLs from inactive URLFrontier {}",
                    pendingUrls.size(), inactiveFrontierId);
            }

            // Remove the inactive frontier from registry
            redisTemplate.opsForHash().delete(FRONTIER_REGISTRY_KEY, inactiveFrontierId);

            // Clean up heartbeat
            String heartbeatKey = FRONTIER_HEARTBEAT_PREFIX + inactiveFrontierId;
            redisTemplate.delete(heartbeatKey);

            log.info("Completed failover for URLFrontier: {}", inactiveFrontierId);

        } catch (Exception e) {
            log.error("Error handling URLFrontier failover for {}", inactiveFrontierId, e);
        }
    }

    /**
     * Handle failover for an inactive Worker
     * Reassign its pending work to active workers
     */
    private void handleWorkerFailover(String inactiveWorkerId) {
        try {
            log.info("Handling failover for inactive Worker: {}", inactiveWorkerId);

            // Get pending URLs assigned to this worker
            String workerQueueKey = WORKER_ASSIGNMENT_PREFIX + inactiveWorkerId;
            List<CrawlUrl> pendingUrls = new ArrayList<>();

            // Retrieve all pending URLs from the inactive worker's queue
            while (true) {
                String urlJson = (String) redisTemplate.opsForList().rightPop(workerQueueKey);
                if (urlJson == null) {
                    break;
                }

                try {
                    CrawlUrl url = objectMapper.readValue(urlJson, CrawlUrl.class);
                    pendingUrls.add(url);
                } catch (Exception e) {
                    log.error("Error parsing URL from inactive worker", e);
                }
            }

            if (!pendingUrls.isEmpty()) {
                // Reassign URLs to active workers
                reassignUrlsToActiveWorkers(pendingUrls, inactiveWorkerId);

                log.info("Reassigned {} pending URLs from inactive Worker {}",
                    pendingUrls.size(), inactiveWorkerId);
            }

            // Remove the inactive worker from registry
            redisTemplate.opsForHash().delete(WORKER_REGISTRY_KEY, inactiveWorkerId);

            // Clean up heartbeat
            String heartbeatKey = WORKER_HEARTBEAT_PREFIX + inactiveWorkerId;
            redisTemplate.delete(heartbeatKey);

            log.info("Completed failover for Worker: {}", inactiveWorkerId);

        } catch (Exception e) {
            log.error("Error handling Worker failover for {}", inactiveWorkerId, e);
        }
    }

    /**
     * Reassign URLs to active URLFrontiers using load balancing
     */
    private void reassignUrlsToActiveFrontiers(List<CrawlUrl> urls, String originalFrontierId) {
        try {
            // Get active frontiers
            Set<String> activeFrontiers = getActiveFrontiers();

            if (activeFrontiers.isEmpty()) {
                // No active frontiers - send URLs back to CrawlerManager
                log.warn("No active URLFrontiers available - sending URLs back to CrawlerManager");
                sendUrlsBackToCrawlerManager(urls, "FRONTIER_FAILOVER");
                return;
            }

            // Distribute URLs among active frontiers using round-robin
            String[] frontierArray = activeFrontiers.toArray(new String[0]);
            int frontierIndex = 0;

            for (CrawlUrl url : urls) {
                String selectedFrontier = frontierArray[frontierIndex % frontierArray.length];
                frontierIndex++;

                // Reset assignment info
                url.setAssignedFrontierId(selectedFrontier);
                url.setAssignedAt(LocalDateTime.now());

                // Add to selected frontier's queue
                String frontierQueueKey = FRONTIER_ASSIGNMENT_PREFIX + selectedFrontier;
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(frontierQueueKey, urlJson);
            }

            // Notify active frontiers about new work
            notifyFrontiers("FAILOVER_REASSIGNMENT", urls.size());

            log.info("Redistributed {} URLs from failed frontier {} to {} active frontiers",
                urls.size(), originalFrontierId, activeFrontiers.size());

        } catch (Exception e) {
            log.error("Error reassigning URLs to active frontiers", e);
        }
    }

    /**
     * Reassign URLs to active Workers using load balancing
     */
    private void reassignUrlsToActiveWorkers(List<CrawlUrl> urls, String originalWorkerId) {
        try {
            // Get active workers
            Set<String> activeWorkers = getActiveWorkers();

            if (activeWorkers.isEmpty()) {
                // No active workers - send URLs back to URLFrontiers
                log.warn("No active Workers available - sending URLs back to URLFrontiers");
                sendUrlsBackToFrontiers(urls, "WORKER_FAILOVER");
                return;
            }

            // Distribute URLs among active workers using round-robin
            String[] workerArray = activeWorkers.toArray(new String[0]);
            int workerIndex = 0;

            for (CrawlUrl url : urls) {
                String selectedWorker = workerArray[workerIndex % workerArray.length];
                workerIndex++;

                // Reset assignment info
                url.setAssignedWorkerId(selectedWorker);
                url.setAssignedAt(LocalDateTime.now());

                // Add to selected worker's queue
                String workerQueueKey = WORKER_ASSIGNMENT_PREFIX + selectedWorker;
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(workerQueueKey, urlJson);
            }

            // Notify active workers about new work
            notifyWorkers("FAILOVER_REASSIGNMENT", urls.size());

            log.info("Redistributed {} URLs from failed worker {} to {} active workers",
                urls.size(), originalWorkerId, activeWorkers.size());

        } catch (Exception e) {
            log.error("Error reassigning URLs to active workers", e);
        }
    }

    /**
     * Get system health metrics
     */
    public SystemHealthMetrics getSystemHealth() {
        try {
            int activeFrontiers = redisTemplate.opsForHash().size(FRONTIER_REGISTRY_KEY).intValue();
            int activeWorkers = redisTemplate.opsForHash().size(WORKER_REGISTRY_KEY).intValue();

            return SystemHealthMetrics.builder()
                .activeFrontiers(activeFrontiers)
                .activeWorkers(activeWorkers)
                .lastCheckTime(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("Error getting system health metrics", e);
            return SystemHealthMetrics.builder()
                .activeFrontiers(0)
                .activeWorkers(0)
                .lastCheckTime(LocalDateTime.now())
                .build();
        }
    }

    /**
     * System health metrics data class
     */
    public static class SystemHealthMetrics {
        public final int activeFrontiers;
        public final int activeWorkers;
        public final LocalDateTime lastCheckTime;

        private SystemHealthMetrics(Builder builder) {
            this.activeFrontiers = builder.activeFrontiers;
            this.activeWorkers = builder.activeWorkers;
            this.lastCheckTime = builder.lastCheckTime;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int activeFrontiers;
            private int activeWorkers;
            private LocalDateTime lastCheckTime;

            public Builder activeFrontiers(int activeFrontiers) {
                this.activeFrontiers = activeFrontiers;
                return this;
            }

            public Builder activeWorkers(int activeWorkers) {
                this.activeWorkers = activeWorkers;
                return this;
            }

            public Builder lastCheckTime(LocalDateTime lastCheckTime) {
                this.lastCheckTime = lastCheckTime;
                return this;
            }

            public SystemHealthMetrics build() {
                return new SystemHealthMetrics(this);
            }
        }
    }

    /**
     * Get currently active URLFrontiers
     */
    private Set<String> getActiveFrontiers() {
        Set<String> activeFrontiers = new java.util.HashSet<>();

        try {
            Map<Object, Object> registeredFrontiers = redisTemplate.opsForHash().entries(FRONTIER_REGISTRY_KEY);

            for (Object frontierIdObj : registeredFrontiers.keySet()) {
                String frontierId = frontierIdObj.toString();

                if (isNodeActive(frontierId, FRONTIER_HEARTBEAT_PREFIX)) {
                    activeFrontiers.add(frontierId);
                }
            }

        } catch (Exception e) {
            log.error("Error getting active frontiers", e);
        }

        return activeFrontiers;
    }

    /**
     * Get currently active Workers
     */
    private Set<String> getActiveWorkers() {
        Set<String> activeWorkers = new java.util.HashSet<>();

        try {
            Map<Object, Object> registeredWorkers = redisTemplate.opsForHash().entries(WORKER_REGISTRY_KEY);

            for (Object workerIdObj : registeredWorkers.keySet()) {
                String workerId = workerIdObj.toString();

                if (isNodeActive(workerId, WORKER_HEARTBEAT_PREFIX)) {
                    activeWorkers.add(workerId);
                }
            }

        } catch (Exception e) {
            log.error("Error getting active workers", e);
        }

        return activeWorkers;
    }

    /**
     * Send URLs back to CrawlerManager when no frontiers are available
     */
    private void sendUrlsBackToCrawlerManager(List<CrawlUrl> urls, String reason) {
        try {
            log.info("Sending {} URLs back to CrawlerManager due to: {}", urls.size(), reason);

            // Add URLs to the new URLs queue for CrawlerManager to reassign
            for (CrawlUrl url : urls) {
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush("urls:new", urlJson);
            }

            // Notify CrawlerManager
            Map<String, Object> notification = Map.of(
                "type", "FAILOVER_URLS_RETURNED",
                "reason", reason,
                "count", urls.size(),
                "timestamp", LocalDateTime.now().toString()
            );

            String notificationJson = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(CRAWLER_MANAGER_NOTIFICATION_CHANNEL, notificationJson);

        } catch (Exception e) {
            log.error("Error sending URLs back to CrawlerManager", e);
        }
    }

    /**
     * Send URLs back to URLFrontiers when no workers are available
     */
    private void sendUrlsBackToFrontiers(List<CrawlUrl> urls, String reason) {
        try {
            log.info("Sending {} URLs back to URLFrontiers due to: {}", urls.size(), reason);

            // Get active frontiers
            Set<String> activeFrontiers = getActiveFrontiers();

            if (activeFrontiers.isEmpty()) {
                // No frontiers available - send back to CrawlerManager
                sendUrlsBackToCrawlerManager(urls, "NO_ACTIVE_FRONTIERS");
                return;
            }

            // Distribute URLs among active frontiers
            String[] frontierArray = activeFrontiers.toArray(new String[0]);
            int frontierIndex = 0;

            for (CrawlUrl url : urls) {
                String selectedFrontier = frontierArray[frontierIndex % frontierArray.length];
                frontierIndex++;

                // Reset assignment info
                url.setAssignedFrontierId(selectedFrontier);
                url.setAssignedWorkerId(null); // Clear worker assignment
                url.setAssignedAt(LocalDateTime.now());

                // Add to selected frontier's queue
                String frontierQueueKey = FRONTIER_ASSIGNMENT_PREFIX + selectedFrontier;
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(frontierQueueKey, urlJson);
            }

            // Notify frontiers
            notifyFrontiers("WORKER_FAILOVER_REASSIGNMENT", urls.size());

        } catch (Exception e) {
            log.error("Error sending URLs back to frontiers", e);
        }
    }

    /**
     * Notify URLFrontiers about events
     */
    private void notifyFrontiers(String messageType, int urlCount) {
        try {
            Map<String, Object> notification = Map.of(
                "type", messageType,
                "count", urlCount,
                "timestamp", LocalDateTime.now().toString()
            );

            String notificationJson = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(FRONTIER_NOTIFICATION_CHANNEL, notificationJson);

            log.debug("Sent notification to frontiers: {} - {} URLs", messageType, urlCount);

        } catch (Exception e) {
            log.error("Error sending notification to frontiers", e);
        }
    }

    /**
     * Notify Workers about events
     */
    private void notifyWorkers(String messageType, int urlCount) {
        try {
            Map<String, Object> notification = Map.of(
                "type", messageType,
                "count", urlCount,
                "timestamp", LocalDateTime.now().toString()
            );

            String notificationJson = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(WORKER_NOTIFICATION_CHANNEL, notificationJson);

            log.debug("Sent notification to workers: {} - {} URLs", messageType, urlCount);

        } catch (Exception e) {
            log.error("Error sending notification to workers", e);
        }
    }

    /**
     * Clean up expired heartbeats and stale data
     */
    private void cleanupExpiredHeartbeats() {
        try {
            // Clean up expired frontier heartbeats
            Map<Object, Object> frontiers = redisTemplate.opsForHash().entries(FRONTIER_REGISTRY_KEY);
            for (Object frontierIdObj : frontiers.keySet()) {
                String frontierId = frontierIdObj.toString();
                String heartbeatKey = FRONTIER_HEARTBEAT_PREFIX + frontierId;

                if (redisTemplate.opsForValue().get(heartbeatKey) == null) {
                    redisTemplate.opsForHash().delete(FRONTIER_REGISTRY_KEY, frontierId);
                    log.debug("Cleaned up expired frontier registration: {}", frontierId);
                }
            }

            // Clean up expired worker heartbeats
            Map<Object, Object> workers = redisTemplate.opsForHash().entries(WORKER_REGISTRY_KEY);
            for (Object workerIdObj : workers.keySet()) {
                String workerId = workerIdObj.toString();
                String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerId;

                if (redisTemplate.opsForValue().get(heartbeatKey) == null) {
                    redisTemplate.opsForHash().delete(WORKER_REGISTRY_KEY, workerId);
                    log.debug("Cleaned up expired worker registration: {}", workerId);
                }
            }

        } catch (Exception e) {
            log.error("Error cleaning up expired heartbeats", e);
        }
    }
}
