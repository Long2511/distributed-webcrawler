package com.ouroboros.webcrawler.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed Worker - Implements precise microservice data flow
 * 1. Gets assigned URLs with WorkerIDs from URLFrontier
 * 2. Crawls the URLs
 * 3. Sends discovered URLs back to CrawlerManager
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "webcrawler.enable.worker", havingValue = "true")
public class DistributedWorker {

    @Autowired
    private CrawlerWorker crawlerWorker;

    @Autowired
    private CrawledPageRepository pageRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisMessageListenerContainer messageListenerContainer;

    @Autowired
    private DistributedInstanceConfig instanceConfig;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private URLFrontier urlFrontier;

    private volatile boolean running = true;
    private boolean redisAvailable = false;
    private String workerId;

    @Value("${webcrawler.worker.poll-interval:3000}")
    private int pollInterval;

    @Value("${webcrawler.worker.heartbeat-interval:30000}")
    private int heartbeatInterval;

    @Value("${webcrawler.worker.batch-size:5}")
    private int batchSize;

    // Redis keys following your exact data flow
    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String WORKER_HEARTBEAT_PREFIX = "worker:heartbeat:";
    private static final String WORKER_ASSIGNMENT_PREFIX = "worker:assigned:";
    private static final String WORKER_NOTIFICATION_CHANNEL = "worker:notification";
    private static final String DISCOVERED_URLS_QUEUE = "urls:discovered";
    private static final String CRAWLER_MANAGER_NOTIFICATION_CHANNEL = "crawlermanager:notification";

    @PostConstruct
    public void initialize() {
        this.workerId = instanceConfig.getMachineId();
        log.info("Initializing Distributed Worker: {}", workerId);

        // Test Redis connection
        try {
            redisTemplate.opsForValue().get("test:connection");
            redisAvailable = true;
            log.info("Worker {} connected to Redis successfully", workerId);

            // Register as worker and start services
            registerWorker();
            startHeartbeat();
            subscribeToNotifications();

        } catch (Exception e) {
            log.error("Worker {} cannot connect to Redis: {}", workerId, e.getMessage());
            redisAvailable = false;
        }

        log.info("Worker {} initialized successfully", workerId);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Worker {}", workerId);
        running = false;

        if (redisAvailable) {
            try {
                unregisterWorker();
            } catch (Exception e) {
                log.debug("Error unregistering worker: {}", e.getMessage());
            }
        }

        log.info("Worker {} shutdown complete", workerId);
    }

    /**
     * Step 5: Main work processing loop - gets assigned URLs with WorkerIDs and crawls them
     */
    @Scheduled(fixedDelayString = "${webcrawler.worker.poll-interval:3000}")
    public void processAssignedWork() {
        if (!running || !redisAvailable) {
            return;
        }

        try {
            // Step 5: Get URLs assigned to this specific worker (with WorkerID)
            List<CrawlUrl> assignedUrls = getAssignedUrlsForThisWorker();
            if (assignedUrls.isEmpty()) {
                log.debug("No URLs assigned to worker {}", workerId);
                return;
            }

            log.info("Worker {} processing {} assigned URLs", workerId, assignedUrls.size());

            // Step 6: Crawl each assigned URL
            for (CrawlUrl url : assignedUrls) {
                try {
                    List<CrawlUrl> discoveredUrls = crawlUrlAndDiscoverNew(url);

                    // Step 6: Send discovered URLs back to CrawlerManager
                    if (!discoveredUrls.isEmpty()) {
                        sendDiscoveredUrlsToCrawlerManager(discoveredUrls);
                    }

                } catch (Exception e) {
                    log.error("Error crawling URL {} by worker {}", url.getUrl(), workerId, e);
                }
            }

            log.info("Worker {} completed processing {} URLs", workerId, assignedUrls.size());

        } catch (Exception e) {
            log.error("Error processing work in worker {}", workerId, e);
        }
    }

    /**
     * Step 5: Get URLs assigned specifically to this worker (with WorkerID)
     */
    private List<CrawlUrl> getAssignedUrlsForThisWorker() {
        List<CrawlUrl> assignedUrls = new java.util.ArrayList<>();

        try {
            String workerQueueKey = WORKER_ASSIGNMENT_PREFIX + workerId;

            // Pop URLs assigned specifically to this worker
            while (assignedUrls.size() < batchSize) {
                String urlJson = (String) redisTemplate.opsForList().rightPop(workerQueueKey);
                if (urlJson == null) {
                    break; // No more URLs assigned to this worker
                }

                CrawlUrl url = objectMapper.readValue(urlJson, CrawlUrl.class);

                // Verify this URL was actually assigned to this worker
                if (workerId.equals(url.getAssignedWorkerId())) {
                    assignedUrls.add(url);
                    log.debug("Worker {} retrieved assigned URL: {}", workerId, url.getUrl());
                } else {
                    log.warn("Worker {} received URL assigned to different worker: {}",
                        workerId, url.getAssignedWorkerId());
                }
            }

        } catch (Exception e) {
            log.error("Error getting assigned URLs for worker {}", workerId, e);
        }

        return assignedUrls;
    }

    /**
     * Step 6: Crawl URL and discover new URLs
     */
    private List<CrawlUrl> crawlUrlAndDiscoverNew(CrawlUrl url) {
        List<CrawlUrl> discoveredUrls = new java.util.ArrayList<>();

        try {
            log.info("Worker {} crawling URL: {}", workerId, url.getUrl());

            // Mark start time
            url.setCrawlStartTime(LocalDateTime.now());

            // Perform actual crawling using your existing crawler - FIX: Use correct method
            CrawledPageEntity crawledPage = crawlerWorker.crawl(url);

            if (crawledPage != null) {
                // Save crawled page
                pageRepository.save(crawledPage);

                // Extract discovered URLs from the crawled page
                List<String> extractedUrls = extractUrlsFromPage(crawledPage);

                // Convert to CrawlUrl objects
                for (String extractedUrl : extractedUrls) {
                    CrawlUrl discoveredUrl = new CrawlUrl();
                    discoveredUrl.setUrl(extractedUrl);
                    discoveredUrl.setDepth(url.getDepth() + 1);
                    discoveredUrl.setDiscoveredBy(workerId);
                    discoveredUrl.setDiscoveredAt(LocalDateTime.now());
                    discoveredUrl.setParentUrl(url.getUrl());

                    discoveredUrls.add(discoveredUrl);
                }

                // Mark completion
                url.setCrawlEndTime(LocalDateTime.now());
                url.setCrawlStatus("COMPLETED");

                log.info("Worker {} successfully crawled {} and discovered {} new URLs",
                    workerId, url.getUrl(), discoveredUrls.size());

            } else {
                url.setCrawlStatus("FAILED");
                log.warn("Worker {} failed to crawl URL: {}", workerId, url.getUrl());
            }

        } catch (Exception e) {
            url.setCrawlStatus("ERROR");
            url.setCrawlEndTime(LocalDateTime.now());
            log.error("Error crawling URL {} by worker {}", url.getUrl(), workerId, e);
        }

        return discoveredUrls;
    }

    /**
     * Step 6: Send discovered URLs back to CrawlerManager (completing the cycle)
     */
    private void sendDiscoveredUrlsToCrawlerManager(List<CrawlUrl> discoveredUrls) {
        try {
            log.info("Worker {} sending {} discovered URLs to CrawlerManager",
                workerId, discoveredUrls.size());

            // Add discovered URLs to the queue for CrawlerManager
            for (CrawlUrl url : discoveredUrls) {
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(DISCOVERED_URLS_QUEUE, urlJson);
            }

            // Notify CrawlerManager about new discovered URLs
            notifyCrawlerManager("DISCOVERED_URLS", discoveredUrls.size());

            log.info("Worker {} successfully sent {} discovered URLs to CrawlerManager",
                workerId, discoveredUrls.size());

        } catch (Exception e) {
            log.error("Error sending discovered URLs to CrawlerManager from worker {}", workerId, e);
        }
    }

    /**
     * Notify CrawlerManager about discovered URLs
     */
    private void notifyCrawlerManager(String messageType, int urlCount) {
        try {
            Map<String, Object> notification = Map.of(
                "type", messageType,
                "workerId", workerId,
                "count", urlCount,
                "timestamp", LocalDateTime.now().toString()
            );

            String notificationJson = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(CRAWLER_MANAGER_NOTIFICATION_CHANNEL, notificationJson);

            log.debug("Worker {} sent notification to CrawlerManager: {} - {} URLs",
                workerId, messageType, urlCount);

        } catch (Exception e) {
            log.error("Error sending notification to CrawlerManager", e);
        }
    }

    /**
     * Extract URLs from crawled page content
     */
    private List<String> extractUrlsFromPage(CrawledPageEntity page) {
        List<String> urls = new java.util.ArrayList<>();

        try {
            // Use your existing URL extraction logic
            if (page.getContent() != null && !page.getContent().isEmpty()) {
                // Simple regex-based URL extraction (enhance as needed)
                java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile(
                    "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
                java.util.regex.Matcher matcher = urlPattern.matcher(page.getContent());

                while (matcher.find()) {
                    String url = matcher.group();
                    if (isValidUrl(url)) {
                        urls.add(url);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error extracting URLs from page", e);
        }

        return urls.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    /**
     * Validate discovered URLs
     */
    private boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        // Basic validation
        return url.startsWith("http") &&
               !url.contains("javascript:") &&
               !url.contains("mailto:") &&
               url.length() < 2000; // Reasonable length limit
    }

    /**
     * Register this worker in Redis registry
     */
    private void registerWorker() {
        if (!redisAvailable) return;

        try {
            String workerInfo = String.format("WORKER:%s:%s",
                instanceConfig.getWorkerAddress(), LocalDateTime.now());

            redisTemplate.opsForHash().put(WORKER_REGISTRY_KEY, workerId, workerInfo);
            log.info("Worker registered in Redis: {}", workerId);
        } catch (Exception e) {
            log.error("Error registering worker", e);
        }
    }

    /**
     * Unregister this worker from Redis registry
     */
    private void unregisterWorker() {
        if (!redisAvailable) return;

        try {
            redisTemplate.opsForHash().delete(WORKER_REGISTRY_KEY, workerId);

            // Also clear heartbeat
            String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerId;
            redisTemplate.delete(heartbeatKey);

            log.info("Worker unregistered from Redis: {}", workerId);
        } catch (Exception e) {
            log.error("Error unregistering worker", e);
        }
    }

    /**
     * Start periodic heartbeat to indicate worker is alive
     */
    private void startHeartbeat() {
        if (!redisAvailable) return;

        Thread heartbeatThread = new Thread(() -> {
            while (running && redisAvailable) {
                try {
                    sendHeartbeat();
                    Thread.sleep(heartbeatInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("Heartbeat error: {}", e.getMessage());
                }
            }
        });

        heartbeatThread.setDaemon(true);
        heartbeatThread.setName("worker-heartbeat-" + workerId);
        heartbeatThread.start();

        log.info("Worker heartbeat started");
    }

    /**
     * Send heartbeat to Redis
     */
    private void sendHeartbeat() {
        if (!redisAvailable) return;

        try {
            String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerId;
            String heartbeatValue = LocalDateTime.now().toString();
            Duration expiration = Duration.ofMillis(heartbeatInterval * 2);
            redisTemplate.opsForValue().set(heartbeatKey, heartbeatValue, expiration);

            log.debug("Worker heartbeat sent: {}", workerId);
        } catch (Exception e) {
            log.debug("Error sending heartbeat", e);
        }
    }

    /**
     * Subscribe to work notifications from URLFrontiers
     */
    private void subscribeToNotifications() {
        if (!redisAvailable) return;

        try {
            MessageListenerAdapter workNotificationAdapter = new MessageListenerAdapter() {
                @Override
                public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                    try {
                        String messageBody = new String(message.getBody());
                        handleWorkNotification(messageBody);
                    } catch (Exception e) {
                        log.error("Error processing work notification", e);
                    }
                }
            };

            messageListenerContainer.addMessageListener(workNotificationAdapter,
                new ChannelTopic(WORKER_NOTIFICATION_CHANNEL));

            log.info("Worker subscribed to work notifications");
        } catch (Exception e) {
            log.error("Error subscribing to work notifications", e);
        }
    }

    /**
     * Handle work notification from URLFrontiers
     */
    private void handleWorkNotification(String message) {
        try {
            log.debug("Worker received work notification: {}", message);

            // Parse JSON notification
            Map<String, Object> notification = objectMapper.readValue(message, Map.class);
            String eventType = (String) notification.get("type");

            switch (eventType) {
                case "NEW_WORK_ASSIGNED":
                case "FAILOVER_REASSIGNMENT":
                    log.info("Work notification received - checking for work immediately");
                    // Trigger immediate work check
                    processAssignedWork();
                    break;
                default:
                    log.debug("Unknown work notification type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Error handling work notification: {}", message, e);
        }
    }
}
