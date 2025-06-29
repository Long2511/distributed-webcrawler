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
import java.util.Set;

/**
 * Distributed Worker - Only handles crawling work, no coordination
 * This runs ONLY on worker nodes
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

    @Value("${webcrawler.worker.poll-interval:3000}")
    private int pollInterval;

    @Value("${webcrawler.worker.heartbeat-interval:30000}")
    private int heartbeatInterval;

    @Value("${webcrawler.redis.work-queue-prefix:work:queue:}")
    private String workQueuePrefix;

    @Value("${webcrawler.redis.worker-heartbeat-prefix:worker:heartbeat:}")
    private String heartbeatPrefix;

    @Value("${webcrawler.redis.work-notification-channel:work:notification}")
    private String workNotificationChannel;

    @Value("${webcrawler.crawler.max-depth:3}")
    private int maxCrawlDepth;

    @Value("${webcrawler.crawler.max-urls-per-page:50}")
    private int maxUrlsPerPage;

    private volatile boolean running = true;
    private boolean redisAvailable = false;

    private static final String WORKER_REGISTRY_KEY = "workers:active";

    @PostConstruct
    public void initialize() {
        log.info("Initializing Distributed Worker: {}", instanceConfig.getMachineId());

        // Test Redis connection
        try {
            redisTemplate.opsForValue().get("test:connection");
            redisAvailable = true;
            log.info("Worker connected to Redis successfully");

            // Register as worker and start heartbeat
            registerWorker();
            startHeartbeat();

            // Subscribe to work notifications
            subscribeToWorkNotifications();

        } catch (Exception e) {
            log.error("Worker cannot connect to Redis: {}", e.getMessage());
            redisAvailable = false;
        }

        log.info("Distributed Worker initialized successfully");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Distributed Worker");
        running = false;

        if (redisAvailable) {
            try {
                unregisterWorker();
            } catch (Exception e) {
                log.debug("Error unregistering worker: {}", e.getMessage());
            }
        }

        log.info("Distributed Worker shutdown complete");
    }

    /**
     * Main work polling method - ONLY polls for work, doesn't coordinate
     */
    @Scheduled(fixedDelayString = "${webcrawler.worker.poll-interval:3000}")
    public void pollForWork() {
        if (!running || !redisAvailable) {
            return;
        }

        try {
            CrawlUrl work = getWorkFromRedis();
            if (work != null) {
                log.info("Worker got work: {}", work.getUrl());
                processWork(work);
            } else {
                log.debug("No work available for worker");
            }
        } catch (Exception e) {
            log.error("Error polling for work", e);
        }
    }

    /**
     * Process crawl work - crawl URL and save result
     */
    private void processWork(CrawlUrl crawlUrl) {
        try {
            log.info("Worker processing URL: {}", crawlUrl.getUrl());

            // Do the actual crawling
            CrawledPageEntity crawledPage = crawlerWorker.crawl(crawlUrl);

            // Save the crawled page to MongoDB
            pageRepository.save(crawledPage);
            log.debug("Worker saved crawled page: {}", crawlUrl.getUrl());

            // Mark URL as completed in URLFrontier (CRITICAL for deduplication!)
            urlFrontier.markCompleted(crawlUrl.getUrl(), crawlUrl.getSessionId());

            // Extract and queue new URLs found on this page
            extractAndQueueNewUrls(crawlUrl, crawledPage);

            // Report completion back to Redis (for coordinator)
            reportWorkCompletion(crawlUrl, crawledPage);

        } catch (Exception e) {
            log.error("Worker error processing URL: {}", crawlUrl.getUrl(), e);

            // Mark URL as failed in URLFrontier
            try {
                urlFrontier.markFailed(crawlUrl.getUrl(), crawlUrl.getSessionId(), e.getMessage());
            } catch (Exception markFailedException) {
                log.debug("Error marking URL as failed: {}", markFailedException.getMessage());
            }

            reportWorkFailure(crawlUrl, e.getMessage());
        }
    }

    /**
     * Extract links from crawled page and queue them for future crawling
     * This is the critical missing piece for distributed web crawling!
     */
    private void extractAndQueueNewUrls(CrawlUrl parentCrawlUrl, CrawledPageEntity crawledPage) {
        try {
            // Only extract links from successfully crawled HTML pages
            if (crawledPage.getStatusCode() != 200 || crawledPage.getRawHtml() == null) {
                log.debug("Skipping link extraction for URL: {} (status: {}, has HTML: {})",
                    parentCrawlUrl.getUrl(), crawledPage.getStatusCode(), crawledPage.getRawHtml() != null);
                return;
            }

            // Check depth limit to prevent infinite crawling
            if (parentCrawlUrl.getDepth() >= maxCrawlDepth) {
                log.info("Reached maximum crawl depth {} for URL: {}, skipping link extraction",
                    maxCrawlDepth, parentCrawlUrl.getUrl());
                return;
            }

            // Extract links from the HTML content
            List<String> discoveredUrls = crawlerWorker.extractLinks(crawledPage.getRawHtml(), parentCrawlUrl.getUrl());
            log.info("Extracted {} links from {} (depth: {})", discoveredUrls.size(), parentCrawlUrl.getUrl(), parentCrawlUrl.getDepth());

            if (discoveredUrls.isEmpty()) {
                return;
            }

            // Limit the number of URLs to prevent system overload
            int urlsToProcess = Math.min(discoveredUrls.size(), maxUrlsPerPage);
            if (urlsToProcess < discoveredUrls.size()) {
                log.info("Limiting URL extraction to {} out of {} discovered URLs from {}",
                    urlsToProcess, discoveredUrls.size(), parentCrawlUrl.getUrl());
                discoveredUrls = discoveredUrls.subList(0, urlsToProcess);
            }

            // Create CrawlUrl objects for each discovered URL and queue them
            int queuedUrls = 0;
            int skippedUrls = 0;

            for (String url : discoveredUrls) {
                try {
                    // Create new CrawlUrl with inherited session and incremented depth
                    CrawlUrl newCrawlUrl = CrawlUrl.builder()
                        .url(url)
                        .sessionId(parentCrawlUrl.getSessionId()) // Inherit session
                        .status("PENDING")
                        .depth(parentCrawlUrl.getDepth() + 1) // Increment depth
                        .priority(calculatePriority(url, parentCrawlUrl.getDepth() + 1)) // Lower priority for deeper URLs
                        .parentUrl(parentCrawlUrl.getUrl()) // Set parent reference
                        .discoveredAt(LocalDateTime.now())
                        .retryCount(0)
                        .build();

                    // Queue the URL using URLFrontier (this handles deduplication at Redis level)
                    urlFrontier.addUrl(newCrawlUrl);
                    queuedUrls++;

                } catch (Exception e) {
                    log.debug("Error queuing discovered URL: {} - {}", url, e.getMessage());
                }
            }

            log.info("Successfully queued {}/{} discovered URLs from {} (skipped {} already crawled)",
                queuedUrls, discoveredUrls.size(), parentCrawlUrl.getUrl(), skippedUrls);

            // Notify other workers about new work available (for better distribution)
            if (queuedUrls > 0) {
                notifyWorkersOfNewUrls(parentCrawlUrl.getSessionId(), queuedUrls);
            }

        } catch (Exception e) {
            log.error("Error extracting and queuing URLs from {}", parentCrawlUrl.getUrl(), e);
        }
    }

    /**
     * Check if a URL was already crawled in this session to prevent duplicates
     */
    private boolean isUrlAlreadyCrawled(String url, String sessionId) {
        try {
            // Check if page already exists in MongoDB for this session
            return pageRepository.existsByUrlAndSessionId(url, sessionId);
        } catch (Exception e) {
            log.debug("Error checking if URL already crawled: {} - {}", url, e.getMessage());
            return false; // If we can't check, assume it's not crawled to avoid missing URLs
        }
    }

    /**
     * Calculate priority for discovered URLs
     * Lower depth = higher priority, but decrease as we go deeper
     */
    private double calculatePriority(String url, int depth) {
        // Base priority decreases with depth to prioritize breadth-first crawling
        double basePriority = Math.max(0.1, 1.0 - (depth * 0.1));

        // Bonus for common important pages
        if (url.contains("/index") || url.contains("/home") || url.endsWith("/")) {
            basePriority += 0.2;
        }

        return basePriority;
    }

    /**
     * Notify other workers that new URLs are available for processing
     */
    private void notifyWorkersOfNewUrls(String sessionId, int urlCount) {
        if (!redisAvailable) return;

        try {
            String notification = String.format("NEW_URLS:%s:%s:%d",
                sessionId, instanceConfig.getMachineId(), urlCount);

            redisTemplate.convertAndSend(workNotificationChannel, notification);
            log.debug("Notified workers of {} new URLs for session {}", urlCount, sessionId);

        } catch (Exception e) {
            log.debug("Error notifying workers of new URLs", e);
        }
    }

    /**
     * Get work from Redis work queues
     */
    private CrawlUrl getWorkFromRedis() {
        if (!redisAvailable) return null;

        try {
            // Check all session work queues for available work
            String workQueuePattern = workQueuePrefix + "*";
            Set<String> workQueueKeys = redisTemplate.keys(workQueuePattern);
            if (workQueueKeys != null && !workQueueKeys.isEmpty()) {
                log.debug("Checking {} work queue keys with pattern: {}", workQueueKeys.size(), workQueuePattern);
                for (String queueKey : workQueueKeys) {
                    try {
                        Object workItem = redisTemplate.opsForList().rightPop(queueKey);
                        if (workItem != null) {
                            log.info("Worker got work from work queue (list): {}", queueKey);
                            return objectMapper.readValue(workItem.toString(), CrawlUrl.class);
                        }
                    } catch (Exception e) {
                        log.debug("Skipping non-list key or empty queue: {}", queueKey);
                        continue;
                    }
                }
            }

            // Use URLFrontier properly instead of direct Redis access to prevent duplicates!
            List<CrawlUrl> urls = urlFrontier.getNextUrls(instanceConfig.getMachineId(), 1);
            if (!urls.isEmpty()) {
                CrawlUrl work = urls.get(0);
                log.info("Worker got work from URLFrontier: {}", work.getUrl());
                return work;
            } else {
                log.debug("No URLFrontier work found");
            }
        } catch (Exception e) {
            log.error("Worker error getting work from Redis", e);
        }

        return null;
    }

    /**
     * Report successful work completion to coordinator
     */
    private void reportWorkCompletion(CrawlUrl crawlUrl, CrawledPageEntity result) {
        if (!redisAvailable) return;

        try {
            String completionData = String.format("COMPLETED:%s:%s:%d:%s",
                crawlUrl.getSessionId(),
                crawlUrl.getUrl(),
                result.getStatusCode(),
                instanceConfig.getMachineId());

            redisTemplate.convertAndSend("work:completed", completionData);
            log.debug("Worker reported completion: {}", crawlUrl.getUrl());
        } catch (Exception e) {
            log.error("Error reporting work completion", e);
        }
    }

    /**
     * Report work failure to coordinator
     */
    private void reportWorkFailure(CrawlUrl crawlUrl, String errorMessage) {
        if (!redisAvailable) return;

        try {
            String failureData = String.format("FAILED:%s:%s:%s:%s",
                crawlUrl.getSessionId(),
                crawlUrl.getUrl(),
                errorMessage,
                instanceConfig.getMachineId());

            redisTemplate.convertAndSend("work:completed", failureData);
            log.debug("Worker reported failure: {}", crawlUrl.getUrl());
        } catch (Exception e) {
            log.error("Error reporting work failure", e);
        }
    }

    /**
     * Register this worker in Redis registry
     */
    private void registerWorker() {
        if (!redisAvailable) return;

        try {
            String workerId = instanceConfig.getMachineId();
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
            String workerId = instanceConfig.getMachineId();
            redisTemplate.opsForHash().delete(WORKER_REGISTRY_KEY, workerId);

            // Also clear heartbeat
            String heartbeatKey = heartbeatPrefix + workerId;
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

        // Use a separate thread for heartbeat to avoid blocking
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
        heartbeatThread.setName("worker-heartbeat-" + instanceConfig.getMachineId());
        heartbeatThread.start();

        log.info("Worker heartbeat started");
    }

    /**
     * Send heartbeat to Redis
     */
    private void sendHeartbeat() {
        if (!redisAvailable) return;

        try {
            String workerId = instanceConfig.getMachineId();
            String heartbeatKey = heartbeatPrefix + workerId;
            String heartbeatValue = LocalDateTime.now().toString();

            // Set heartbeat with expiration (2x heartbeat interval)
            Duration expiration = Duration.ofMillis(heartbeatInterval * 2);
            redisTemplate.opsForValue().set(heartbeatKey, heartbeatValue, expiration);

            log.debug("Worker heartbeat sent: {}", workerId);
        } catch (Exception e) {
            log.debug("Error sending heartbeat", e);
        }
    }

    /**
     * Subscribe to work notifications from coordinator
     */
    private void subscribeToWorkNotifications() {
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
                new ChannelTopic(workNotificationChannel));

            log.info("Worker subscribed to work notifications");
        } catch (Exception e) {
            log.error("Error subscribing to work notifications", e);
        }
    }

    /**
     * Handle work notification from coordinator
     */
    private void handleWorkNotification(String message) {
        try {
            log.debug("Worker received work notification: {}", message);
            String[] parts = message.split(":");
            if (parts.length >= 4) {
                String eventType = parts[0];
                String sessionId = parts[1];
                String fromMachineId = parts[2];

                // Don't process our own messages
                if (fromMachineId.equals(instanceConfig.getMachineId())) {
                    return;
                }

                switch (eventType) {
                    case "NEW_SESSION":
                    case "NEW_URLS":
                    case "RESUME":
                        log.info("Work notification received - checking for work immediately");
                        // Trigger immediate work check
                        try {
                            CrawlUrl work = getWorkFromRedis();
                            if (work != null) {
                                log.info("Worker found immediate work: {}", work.getUrl());
                                processWork(work);
                            }
                        } catch (Exception e) {
                            log.error("Error processing immediate work", e);
                        }
                        break;
                    default:
                        log.debug("Unknown work notification type: {}", eventType);
                }
            }
        } catch (Exception e) {
            log.error("Error handling work notification: {}", message, e);
        }
    }
}
