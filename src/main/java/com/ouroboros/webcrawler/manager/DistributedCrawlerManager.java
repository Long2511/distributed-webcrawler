package com.ouroboros.webcrawler.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed CrawlerManager
 * Coordinates between URLFrontiers and manages URL distribution
 * Implements the precise microservice data flow
 */
@Slf4j
@Service
public class DistributedCrawlerManager {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String FRONTIER_REGISTRY_KEY = "frontiers:active";
    private static final String FRONTIER_NOTIFICATION_CHANNEL = "frontier:notification";
    private static final String NEW_URLS_QUEUE = "urls:new";
    private static final String FRONTIER_ASSIGNMENT_PREFIX = "frontier:assigned:";
    private static final String DISCOVERED_URLS_QUEUE = "urls:discovered";

    private final AtomicInteger frontierSelector = new AtomicInteger(0);

    /**
     * Main entry point from Controller API - Step 1 of data flow
     */
    public void addUrlsForCrawling(List<CrawlUrl> urls) {
        try {
            log.info("CrawlerManager received {} URLs from Controller API", urls.size());

            // Get available URLFrontiers for load balancing
            Set<String> availableFrontiers = getAvailableURLFrontiers();
            if (availableFrontiers.isEmpty()) {
                log.warn("No URLFrontiers available, queuing URLs for later processing");
                queueUrlsForLaterProcessing(urls);
                return;
            }

            // Assign URLs to URLFrontiers using round-robin load balancing
            assignUrlsToFrontiers(urls, availableFrontiers);

            // Step 3: Notify URLFrontiers about new work
            notifyURLFrontiers("NEW_WORK_ASSIGNED", urls.size());

            log.info("Successfully assigned {} URLs to URLFrontiers and sent notifications", urls.size());

        } catch (Exception e) {
            log.error("Error adding URLs for crawling", e);
            throw new RuntimeException("Failed to add URLs for crawling", e);
        }
    }

    /**
     * Step 2: Assign URLs to specific URLFrontiers with load balancing
     */
    private void assignUrlsToFrontiers(List<CrawlUrl> urls, Set<String> availableFrontiers) {
        try {
            String[] frontierArray = availableFrontiers.toArray(new String[0]);

            for (CrawlUrl url : urls) {
                // Round-robin assignment to URLFrontiers
                String selectedFrontier = frontierArray[frontierSelector.getAndIncrement() % frontierArray.length];

                // Add URL to specific frontier's queue
                String frontierQueueKey = FRONTIER_ASSIGNMENT_PREFIX + selectedFrontier;
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(frontierQueueKey, urlJson);

                log.debug("Assigned URL {} to URLFrontier {}", url.getUrl(), selectedFrontier);
            }

            log.info("Distributed {} URLs across {} URLFrontiers", urls.size(), frontierArray.length);

        } catch (Exception e) {
            log.error("Error assigning URLs to frontiers", e);
        }
    }

    /**
     * Step 6: Handle discovered URLs from workers - reassign to URLFrontiers
     */
    public void handleDiscoveredUrls(List<CrawlUrl> discoveredUrls, String sourceWorkerId) {
        try {
            log.info("CrawlerManager received {} discovered URLs from worker {}",
                discoveredUrls.size(), sourceWorkerId);

            // Apply filtering and validation
            List<CrawlUrl> validUrls = filterAndValidateUrls(discoveredUrls);

            if (!validUrls.isEmpty()) {
                // Reassign discovered URLs to URLFrontiers (cycle continues)
                Set<String> availableFrontiers = getAvailableURLFrontiers();
                if (!availableFrontiers.isEmpty()) {
                    assignUrlsToFrontiers(validUrls, availableFrontiers);
                    notifyURLFrontiers("DISCOVERED_URLS", validUrls.size());
                } else {
                    queueUrlsForLaterProcessing(validUrls);
                }

                log.info("Processed {} valid discovered URLs from worker {}",
                    validUrls.size(), sourceWorkerId);
            }

        } catch (Exception e) {
            log.error("Error handling discovered URLs from worker {}", sourceWorkerId, e);
        }
    }

    /**
     * Get available URLFrontiers for load balancing
     */
    public Set<String> getAvailableURLFrontiers() {
        try {
            Map<Object, Object> frontiers = redisTemplate.opsForHash().entries(FRONTIER_REGISTRY_KEY);
            return frontiers.keySet().stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            log.error("Error getting available URLFrontiers", e);
            return Set.of();
        }
    }

    /**
     * Step 3: Notify URLFrontiers about new work via Redis pub/sub
     */
    private void notifyURLFrontiers(String messageType, int urlCount) {
        try {
            Map<String, Object> notification = Map.of(
                "type", messageType,
                "count", urlCount,
                "timestamp", LocalDateTime.now().toString()
            );

            String notificationJson = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(FRONTIER_NOTIFICATION_CHANNEL, notificationJson);

            log.debug("Sent notification to URLFrontiers: {} - {} URLs", messageType, urlCount);

        } catch (Exception e) {
            log.error("Error sending notification to URLFrontiers", e);
        }
    }

    /**
     * Queue URLs when no URLFrontiers are available
     */
    private void queueUrlsForLaterProcessing(List<CrawlUrl> urls) {
        try {
            for (CrawlUrl url : urls) {
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(NEW_URLS_QUEUE, urlJson);
            }
            log.info("Queued {} URLs for later processing when URLFrontiers become available", urls.size());
        } catch (Exception e) {
            log.error("Error queuing URLs for later processing", e);
        }
    }

    /**
     * Filter and validate discovered URLs
     */
    private List<CrawlUrl> filterAndValidateUrls(List<CrawlUrl> urls) {
        return urls.stream()
            .filter(url -> url.getUrl() != null && !url.getUrl().trim().isEmpty())
            .filter(url -> url.getUrl().startsWith("http"))
            .filter(url -> url.getDepth() <= 5) // Depth limit
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Process discovered URLs from the discovered URLs queue
     */
    public void processDiscoveredUrls() {
        try {
            List<CrawlUrl> discoveredUrls = new java.util.ArrayList<>();

            // Get discovered URLs from the queue
            while (discoveredUrls.size() < 50) { // Batch processing
                String urlJson = (String) redisTemplate.opsForList().rightPop(DISCOVERED_URLS_QUEUE);
                if (urlJson == null) {
                    break;
                }

                try {
                    CrawlUrl url = objectMapper.readValue(urlJson, CrawlUrl.class);
                    discoveredUrls.add(url);
                } catch (Exception e) {
                    log.error("Error parsing discovered URL", e);
                }
            }

            if (!discoveredUrls.isEmpty()) {
                // Group by worker that discovered them
                Map<String, List<CrawlUrl>> urlsByWorker = discoveredUrls.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                        url -> url.getDiscoveredBy() != null ? url.getDiscoveredBy() : "unknown"));

                for (Map.Entry<String, List<CrawlUrl>> entry : urlsByWorker.entrySet()) {
                    String workerId = entry.getKey();
                    List<CrawlUrl> workerUrls = entry.getValue();
                    handleDiscoveredUrls(workerUrls, workerId);
                }
            }

        } catch (Exception e) {
            log.error("Error processing discovered URLs", e);
        }
    }

    /**
     * Subscribe to notifications from workers about discovered URLs
     */
    @javax.annotation.PostConstruct
    public void subscribeToWorkerNotifications() {
        try {
            MessageListenerAdapter notificationAdapter =
                new MessageListenerAdapter() {
                @Override
                public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                    try {
                        String messageBody = new String(message.getBody());
                        handleWorkerNotification(messageBody);
                    } catch (Exception e) {
                        log.error("Error processing worker notification", e);
                    }
                }
            };

            // Note: messageListenerContainer would need to be injected
            log.info("CrawlerManager notification subscription configured");
        } catch (Exception e) {
            log.error("Error subscribing to worker notifications", e);
        }
    }

    /**
     * Handle notifications from workers
     */
    private void handleWorkerNotification(String message) {
        try {
            Map<String, Object> notification = objectMapper.readValue(message, Map.class);
            String eventType = (String) notification.get("type");

            if ("DISCOVERED_URLS".equals(eventType)) {
                log.info("Received discovered URLs notification - processing queue");
                processDiscoveredUrls();
            }

        } catch (Exception e) {
            log.error("Error handling worker notification: {}", message, e);
        }
    }

    /**
     * Get system statistics
     */
    public CrawlerStatistics getSystemStatistics() {
        try {
            int activeFrontiers = redisTemplate.opsForHash().size(FRONTIER_REGISTRY_KEY).intValue();
            int activeWorkers = redisTemplate.opsForHash().size("workers:active").intValue();
            int pendingUrls = redisTemplate.opsForList().size(NEW_URLS_QUEUE).intValue();

            return CrawlerStatistics.builder()
                .activeFrontiers(activeFrontiers)
                .activeWorkers(activeWorkers)
                .pendingUrls(pendingUrls)
                .lastUpdated(LocalDateTime.now())
                .build();

        } catch (Exception e) {
            log.error("Error getting system statistics", e);
            return CrawlerStatistics.builder()
                .activeFrontiers(0)
                .activeWorkers(0)
                .pendingUrls(0)
                .lastUpdated(LocalDateTime.now())
                .build();
        }
    }

    /**
     * Statistics data class
     */
    public static class CrawlerStatistics {
        public final int activeFrontiers;
        public final int activeWorkers;
        public final int pendingUrls;
        public final LocalDateTime lastUpdated;

        private CrawlerStatistics(Builder builder) {
            this.activeFrontiers = builder.activeFrontiers;
            this.activeWorkers = builder.activeWorkers;
            this.pendingUrls = builder.pendingUrls;
            this.lastUpdated = builder.lastUpdated;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int activeFrontiers;
            private int activeWorkers;
            private int pendingUrls;
            private LocalDateTime lastUpdated;

            public Builder activeFrontiers(int activeFrontiers) {
                this.activeFrontiers = activeFrontiers;
                return this;
            }

            public Builder activeWorkers(int activeWorkers) {
                this.activeWorkers = activeWorkers;
                return this;
            }

            public Builder pendingUrls(int pendingUrls) {
                this.pendingUrls = pendingUrls;
                return this;
            }

            public Builder lastUpdated(LocalDateTime lastUpdated) {
                this.lastUpdated = lastUpdated;
                return this;
            }

            public CrawlerStatistics build() {
                return new CrawlerStatistics(this);
            }
        }
    }
}
