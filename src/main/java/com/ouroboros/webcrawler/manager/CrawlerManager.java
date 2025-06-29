package com.ouroboros.webcrawler.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(name = "webcrawler.enable.session-management", havingValue = "true", matchIfMissing = false)
public class CrawlerManager {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlSessionRepository sessionRepository;

    @Autowired
    private CrawlUrlRepository crawlUrlRepository;

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

    @Value("${webcrawler.batch.size:10}")
    private int batchSize;

    @Value("${webcrawler.worker.poll-interval:3000}")
    private int pollInterval;

    @Value("${webcrawler.worker.heartbeat-interval:30000}")
    private int heartbeatInterval;

    @Value("${webcrawler.redis.work-queue-prefix:work:queue:}")
    private String workQueuePrefix;

    @Value("${webcrawler.redis.worker-heartbeat-prefix:worker:heartbeat:}")
    private String heartbeatPrefix;

    @Value("${webcrawler.redis.session-control-channel:session:control}")
    private String sessionControlChannel;

    @Value("${webcrawler.redis.work-notification-channel:work:notification}")
    private String workNotificationChannel;

    private int maxDepth = 2;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;
    private boolean redisAvailable = false;

    private static final String WORKER_REGISTRY_KEY = "workers:active";

    @PostConstruct
    public void initialize() {
        log.info("Initializing Master CrawlerManager (Coordinator Only): {}", instanceConfig.getMachineId());

        // Test Redis connection with better error handling
        try {
            log.info("Testing Redis connection...");
            redisTemplate.opsForValue().set("test:connection", "test-value");
            String testResult = (String) redisTemplate.opsForValue().get("test:connection");
            redisTemplate.delete("test:connection");

            if ("test-value".equals(testResult)) {
                redisAvailable = true;
                log.info("Master connected to Redis successfully - enabling coordination features");

                // Subscribe to Redis channels for distributed coordination
                subscribeToRedisChannels();

                // Listen for worker completion reports
                subscribeToWorkerReports();
            } else {
                log.error("Redis connection test failed - test value mismatch");
                redisAvailable = false;
            }

        } catch (Exception e) {
            log.error("Redis not available, cannot coordinate distributed workers: {}", e.getMessage(), e);
            redisAvailable = false;
        }

        log.info("Master CrawlerManager initialized successfully (Redis available: {})", redisAvailable);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Master CrawlerManager");
        running = false;

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Master CrawlerManager shutdown complete");
    }

    /**
     * Start a new crawl session with Redis-based coordination
     */
    public String startCrawlSession(CrawlSessionEntity session) {
        log.info("Starting crawl session: {}", session.getName());
        
        session.setStatus("RUNNING");
        session.setStartedAt(LocalDateTime.now());
        CrawlSessionEntity savedSession = sessionRepository.save(session);

        // Add seed URLs to frontier
        for (String seedUrl : session.getSeedUrls()) {
            CrawlUrl crawlUrl = CrawlUrl.builder()
                .url(seedUrl)
                .sessionId(savedSession.getId())
                .depth(0)
                .priority(1.0)
                .status("PENDING")
                .discoveredAt(LocalDateTime.now())
                .build();
            
            urlFrontier.addUrl(crawlUrl);

            // Also add to Redis work queue for distributed processing
            addToRedisWorkQueue(crawlUrl);
        }

        // Notify all workers that new work is available
        publishWorkNotification(savedSession.getId(), "NEW_SESSION");

        log.info("Added {} seed URLs to frontier for session: {}", session.getSeedUrls().size(), savedSession.getId());
        return savedSession.getId();
    }

    /**
     * Stop crawl session with distributed notification
     */
    public void stopCrawlSession(String sessionId) {
        log.info("Stopping crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setStatus("STOPPED");
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);

            // Notify all workers to stop processing this session
            publishSessionControl(sessionId, "STOP");
        }
    }

    /**
     * Pause crawl session
     */
    public boolean pauseCrawlSession(String sessionId) {
        log.info("Pausing crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && "RUNNING".equals(session.getStatus())) {
            session.setStatus("PAUSED");
            session.setPausedAt(LocalDateTime.now());
            sessionRepository.save(session);

            publishSessionControl(sessionId, "PAUSE");
            return true;
        }
        return false;
    }

    /**
     * Resume crawl session
     */
    public boolean resumeCrawlSession(String sessionId) {
        log.info("Resuming crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && "PAUSED".equals(session.getStatus())) {
            session.setStatus("RUNNING");
            session.setResumedAt(LocalDateTime.now());
            sessionRepository.save(session);
            
            publishWorkNotification(sessionId, "RESUME");
            return true;
        }
        return false;
    }

    // Redis-based distributed coordination methods

    private void addToRedisWorkQueue(CrawlUrl crawlUrl) {
        if (!redisAvailable) {
            log.warn("Cannot add URL to Redis work queue - Redis not available: {}", crawlUrl.getUrl());
            return;
        }

        try {
            String queueKey = workQueuePrefix + crawlUrl.getSessionId();
            String crawlUrlJson = objectMapper.writeValueAsString(crawlUrl);
            redisTemplate.opsForList().leftPush(queueKey, crawlUrlJson);
            log.info("Master added URL to work queue: {} (queue: {})", crawlUrl.getUrl(), queueKey);
        } catch (Exception e) {
            log.error("Error adding URL to work queue: {}", crawlUrl.getUrl(), e);
        }
    }

    private void publishWorkNotification(String sessionId, String eventType) {
        if (!redisAvailable) return;

        try {
            String message = String.format("%s:%s:%s:%s",
                eventType, sessionId, instanceConfig.getMachineId(), LocalDateTime.now());
            redisTemplate.convertAndSend(workNotificationChannel, message);
            log.debug("Published work notification: {} for session: {}", eventType, sessionId);
        } catch (Exception e) {
            log.error("Error publishing work notification", e);
        }
    }

    private void publishSessionControl(String sessionId, String action) {
        if (!redisAvailable) return;

        try {
            String message = String.format("%s:%s:%s:%s",
                action, sessionId, instanceConfig.getMachineId(), LocalDateTime.now());
            redisTemplate.convertAndSend(sessionControlChannel, message);
            log.debug("Published session control: {} for session: {}", action, sessionId);
        } catch (Exception e) {
            log.error("Error publishing session control", e);
        }
    }

    private void subscribeToRedisChannels() {
        if (!redisAvailable) return;

        try {
            // Create custom message listeners with explicit method invocation
            MessageListenerAdapter workAdapter = new MessageListenerAdapter() {
                @Override
                public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                    try {
                        String messageBody = new String(message.getBody());
                        handleWorkNotification(messageBody);
                    } catch (Exception e) {
                        log.error("Error processing work notification message", e);
                    }
                }
            };

            MessageListenerAdapter controlAdapter = new MessageListenerAdapter() {
                @Override
                public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                    try {
                        String messageBody = new String(message.getBody());
                        handleSessionControl(messageBody);
                    } catch (Exception e) {
                        log.error("Error processing session control message", e);
                    }
                }
            };

            messageListenerContainer.addMessageListener(workAdapter, new ChannelTopic(workNotificationChannel));
            messageListenerContainer.addMessageListener(controlAdapter, new ChannelTopic(sessionControlChannel));

            log.info("Subscribed to Redis channels for distributed coordination");
        } catch (Exception e) {
            log.error("Error subscribing to Redis channels", e);
        }
    }

    /**
     * Handle work notification messages from Redis pub/sub
     */
    public void handleWorkNotification(String message) {
        try {
            log.debug("Received work notification: {}", message);
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
                        // As a manager, we just log that work is available
                        // Workers will handle the actual work polling
                        log.info("Work notification received for session: {} (event: {})", sessionId, eventType);
                        break;
                    default:
                        log.debug("Unknown work notification type: {}", eventType);
                }
            }
        } catch (Exception e) {
            log.error("Error handling work notification: {}", message, e);
        }
    }

    /**
     * Handle session control messages from Redis pub/sub
     */
    public void handleSessionControl(String message) {
        try {
            log.debug("Received session control: {}", message);
            String[] parts = message.split(":");
            if (parts.length >= 4) {
                String action = parts[0];
                String sessionId = parts[1];
                String fromMachineId = parts[2];

                // Don't process our own messages
                if (fromMachineId.equals(instanceConfig.getMachineId())) {
                    return;
                }

                switch (action) {
                    case "STOP":
                        log.info("Received STOP command for session: {}", sessionId);
                        // Stop processing any pending work for this session
                        clearSessionWorkQueue(sessionId);
                        break;
                    case "PAUSE":
                        log.info("Received PAUSE command for session: {}", sessionId);
                        // Workers will check session status before processing
                        break;
                    case "DELETE":
                        log.info("Received DELETE command for session: {}", sessionId);
                        clearSessionWorkQueue(sessionId);
                        break;
                    default:
                        log.debug("Unknown session control action: {}", action);
                }
            }
        } catch (Exception e) {
            log.error("Error handling session control: {}", message, e);
        }
    }

    /**
     * Handle worker completion reports
     */
    private void subscribeToWorkerReports() {
        if (!redisAvailable) return;

        try {
            MessageListenerAdapter completionAdapter = new MessageListenerAdapter() {
                @Override
                public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                    try {
                        String messageBody = new String(message.getBody());
                        handleWorkerCompletion(messageBody);
                    } catch (Exception e) {
                        log.error("Error processing worker completion", e);
                    }
                }
            };

            messageListenerContainer.addMessageListener(completionAdapter, new ChannelTopic("work:completed"));
            log.info("Master subscribed to worker completion reports");
        } catch (Exception e) {
            log.error("Error subscribing to worker reports", e);
        }
    }

    /**
     * Handle completion reports from workers
     */
    public void handleWorkerCompletion(String message) {
        try {
            log.debug("Master received worker completion: {}", message);
            String[] parts = message.split(":");
            if (parts.length >= 4) {
                String status = parts[0]; // COMPLETED or FAILED
                String sessionId = parts[1];
                String url = parts[2];
                String workerId = parts[4];

                if ("COMPLETED".equals(status)) {
                    int statusCode = Integer.parseInt(parts[3]);
                    urlFrontier.markCompleted(url, sessionId);

                    // If successful, we might need to extract links and add new URLs
                    // This would be handled by checking if the worker reported extracted links

                } else if ("FAILED".equals(status)) {
                    String errorMessage = parts[3];
                    urlFrontier.markFailed(url, errorMessage, sessionId);
                }

                log.debug("Master processed {} report from worker {} for URL: {}", status, workerId, url);
            }
        } catch (Exception e) {
            log.error("Error handling worker completion: {}", message, e);
        }
    }

    /**
     * Clear work queue for a specific session
     */
    private void clearSessionWorkQueue(String sessionId) {
        if (!redisAvailable) return;

        try {
            String queueKey = workQueuePrefix + sessionId;
            Boolean cleared = redisTemplate.delete(queueKey);
            log.info("Cleared work queue for session: {} (success: {})", sessionId, cleared);
        } catch (Exception e) {
            log.error("Error clearing work queue for session: {}", sessionId, e);
        }
    }

    /**
     * Delete session with Redis coordination
     */
    public boolean deleteCrawlSession(String sessionId) {
        log.info("Deleting crawl session: {}", sessionId);

        try {
            CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                return false;
            }

            // Notify all workers to stop processing and clear work
            publishSessionControl(sessionId, "DELETE");

            // Clear Redis work queue
            clearSessionWorkQueue(sessionId);

            // Delete from MongoDB
            sessionRepository.deleteById(sessionId);
            crawlUrlRepository.deleteBySessionId(sessionId);

            // Clear from URL frontier
            urlFrontier.clearSession(sessionId);

            log.info("Successfully deleted session: {}", sessionId);
            return true;
        } catch (Exception e) {
            log.error("Error deleting session: {}", sessionId, e);
            return false;
        }
    }

    /**
     * Get active workers from Redis registry
     */
    public List<Map<String, Object>> getActiveWorkers() {
        List<Map<String, Object>> workers = new ArrayList<>();

        if (!redisAvailable) {
            return workers;
        }

        try {
            Map<Object, Object> workerRegistry = redisTemplate.opsForHash().entries(WORKER_REGISTRY_KEY);

            for (Map.Entry<Object, Object> entry : workerRegistry.entrySet()) {
                String workerId = entry.getKey().toString();
                String workerInfo = entry.getValue().toString();
                String[] parts = workerInfo.split(":");

                if (parts.length >= 3) {
                    Map<String, Object> worker = new HashMap<>();
                    worker.put("workerId", workerId);
                    worker.put("address", parts[1]);
                    worker.put("lastSeen", parts[2]);

                    // Check heartbeat
                    String heartbeatKey = heartbeatPrefix + workerId;
                    String lastHeartbeat = (String) redisTemplate.opsForValue().get(heartbeatKey);
                    worker.put("lastHeartbeat", lastHeartbeat);
                    worker.put("isActive", lastHeartbeat != null);

                    workers.add(worker);
                }
            }
        } catch (Exception e) {
            log.error("Error getting active workers", e);
        }

        return workers;
    }

    /**
     * Get session queue statistics
     */
    public Map<String, Object> getSessionQueueStats(String sessionId) {
        Map<String, Object> stats = new HashMap<>();

        if (!redisAvailable) {
            stats.put("redisQueueSize", 0);
            stats.put("redisAvailable", false);
            return stats;
        }

        try {
            String queueKey = workQueuePrefix + sessionId;
            Long queueSize = redisTemplate.opsForList().size(queueKey);
            stats.put("redisQueueSize", queueSize != null ? queueSize : 0);
            stats.put("redisAvailable", true);
        } catch (Exception e) {
            log.error("Error getting queue stats for session: {}", sessionId, e);
            stats.put("redisQueueSize", 0);
            stats.put("redisAvailable", false);
        }

        return stats;
    }

    /**
     * Get all crawl sessions
     */
    public List<CrawlSessionEntity> getAllSessions() {
        try {
            return sessionRepository.findAll();
        } catch (Exception e) {
            log.error("Error getting all sessions", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get a specific crawl session
     */
    public CrawlSessionEntity getSession(String sessionId) {
        try {
            return sessionRepository.findById(sessionId).orElse(null);
        } catch (Exception e) {
            log.error("Error getting session: {}", sessionId, e);
            return null;
        }
    }

    /**
     * Get pages for a specific session with pagination
     */
    public List<Object> getSessionPages(String sessionId, int page, int size) {
        List<Object> pages = new ArrayList<>();
        try {
            org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);

            List<CrawledPageEntity> crawledPages = pageRepository.findBySessionId(sessionId, pageable);

            for (CrawledPageEntity crawledPage : crawledPages) {
                Map<String, Object> pageInfo = new HashMap<>();
                pageInfo.put("url", crawledPage.getUrl());
                pageInfo.put("title", crawledPage.getTitle());
                pageInfo.put("statusCode", crawledPage.getStatusCode());
                pageInfo.put("crawledAt", crawledPage.getCrawlTime()); // Use getCrawlTime() instead of getCrawledAt()
                pageInfo.put("contentLength", crawledPage.getContentLength());
                pageInfo.put("errorMessage", crawledPage.getErrorMessage());
                pages.add(pageInfo);
            }
        } catch (Exception e) {
            log.error("Error getting session pages: {}", sessionId, e);
        }
        return pages;
    }
}
