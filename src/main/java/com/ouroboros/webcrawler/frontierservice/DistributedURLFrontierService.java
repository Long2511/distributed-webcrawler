package com.ouroboros.webcrawler.frontierservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed URLFrontier Service
 * Implements the precise microservice data flow:
 * 1. Gets assigned URLs from CrawlerManager
 * 2. Assigns them to registered workers with WorkerIDs
 * 3. Publishes notifications to wake workers
 */
@Slf4j
@Service
public class DistributedURLFrontierService {

    @Autowired
    private CrawlUrlRepository crawlUrlRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisMessageListenerContainer messageListenerContainer;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${webcrawler.frontier.instance-id:#{T(java.util.UUID).randomUUID().toString()}}")
    private String frontierInstanceId;

    @Value("${webcrawler.frontier.heartbeat-interval:30000}")
    private int heartbeatInterval;

    @Value("${webcrawler.frontier.batch-size:10}")
    private int batchSize;

    private volatile boolean running = true;
    private boolean redisAvailable = false;

    // Redis keys following your exact data flow
    private static final String FRONTIER_REGISTRY_KEY = "frontiers:active";
    private static final String FRONTIER_HEARTBEAT_PREFIX = "frontier:heartbeat:";
    private static final String FRONTIER_ASSIGNMENT_PREFIX = "frontier:assigned:";
    private static final String WORKER_ASSIGNMENT_PREFIX = "worker:assigned:";
    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String WORKER_HEARTBEAT_PREFIX = "worker:heartbeat:";
    private static final String FRONTIER_NOTIFICATION_CHANNEL = "frontier:notification";
    private static final String WORKER_NOTIFICATION_CHANNEL = "worker:notification";

    // Worker management for this frontier
    private final Set<String> registeredWorkers = ConcurrentHashMap.newKeySet();
    private final Map<String, LocalDateTime> workerLastSeen = new ConcurrentHashMap<>();
    private final AtomicInteger workerSelector = new AtomicInteger(0);

    @PostConstruct
    public void initialize() {
        log.info("Initializing URLFrontier Service: {}", frontierInstanceId);

        try {
            redisTemplate.opsForValue().get("test:connection");
            redisAvailable = true;
            log.info("URLFrontier connected to Redis successfully");

            // Register this frontier instance
            registerFrontier();
            startHeartbeat();
            subscribeToNotifications();

        } catch (Exception e) {
            log.error("URLFrontier cannot connect to Redis: {}", e.getMessage());
            redisAvailable = false;
        }

        log.info("URLFrontier Service initialized successfully");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down URLFrontier Service");
        running = false;

        if (redisAvailable) {
            try {
                unregisterFrontier();
            } catch (Exception e) {
                log.debug("Error unregistering frontier: {}", e.getMessage());
            }
        }

        log.info("URLFrontier Service shutdown complete");
    }

    /**
     * Step 4: Main processing loop - gets assigned URLs from CrawlerManager
     * and assigns them to workers with WorkerIDs
     */
    @Scheduled(fixedDelayString = "${webcrawler.frontier.process-interval:5000}")
    public void processURLAssignments() {
        if (!running || !redisAvailable) {
            return;
        }

        try {
            // Step 4: Get URLs assigned to this frontier by CrawlerManager
            List<CrawlUrl> assignedUrls = getAssignedUrlsFromCrawlerManager();
            if (assignedUrls.isEmpty()) {
                log.debug("No URLs assigned by CrawlerManager to frontier {}", frontierInstanceId);
                return;
            }

            // Get available workers registered with this frontier
            Set<String> availableWorkers = getActiveRegisteredWorkers();
            if (availableWorkers.isEmpty()) {
                log.debug("No active workers registered with frontier {}", frontierInstanceId);
                // Put URLs back for later processing
                requeueUrls(assignedUrls);
                return;
            }

            // Step 4: Assign URLs to workers with specific WorkerIDs
            assignUrlsToWorkersWithIds(assignedUrls, availableWorkers);

            // Step 5: Publish notification to wake workers
            notifyWorkers("NEW_WORK_ASSIGNED", assignedUrls.size());

            log.info("Frontier {} processed {} URLs and assigned to {} workers",
                frontierInstanceId, assignedUrls.size(), availableWorkers.size());

        } catch (Exception e) {
            log.error("Error processing URL assignments in frontier {}", frontierInstanceId, e);
        }
    }

    /**
     * Step 4: Get URLs assigned to this frontier by CrawlerManager
     */
    private List<CrawlUrl> getAssignedUrlsFromCrawlerManager() {
        List<CrawlUrl> assignedUrls = new ArrayList<>();

        try {
            String frontierQueueKey = FRONTIER_ASSIGNMENT_PREFIX + frontierInstanceId;

            // Pop URLs assigned by CrawlerManager from Redis
            while (assignedUrls.size() < batchSize) {
                String urlJson = (String) redisTemplate.opsForList().rightPop(frontierQueueKey);
                if (urlJson == null) {
                    break; // No more URLs
                }

                CrawlUrl url = objectMapper.readValue(urlJson, CrawlUrl.class);
                assignedUrls.add(url);
            }

            if (!assignedUrls.isEmpty()) {
                log.debug("Frontier {} retrieved {} URLs from CrawlerManager",
                    frontierInstanceId, assignedUrls.size());
            }

        } catch (Exception e) {
            log.error("Error getting assigned URLs from CrawlerManager", e);
        }

        return assignedUrls;
    }

    /**
     * Step 4: Assign URLs to workers with specific WorkerIDs (your exact requirement)
     */
    private void assignUrlsToWorkersWithIds(List<CrawlUrl> urls, Set<String> availableWorkers) {
        try {
            String[] workerArray = availableWorkers.toArray(new String[0]);

            for (CrawlUrl url : urls) {
                // Round-robin assignment to workers
                String selectedWorkerId = workerArray[workerSelector.getAndIncrement() % workerArray.length];

                // Add WorkerId to the URL object (your exact requirement)
                url.setAssignedWorkerId(selectedWorkerId);
                url.setAssignedAt(LocalDateTime.now());

                // Add URL to specific worker's queue
                String workerQueueKey = WORKER_ASSIGNMENT_PREFIX + selectedWorkerId;
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().leftPush(workerQueueKey, urlJson);

                log.debug("Frontier {} assigned URL {} to Worker {}",
                    frontierInstanceId, url.getUrl(), selectedWorkerId);
            }

            log.info("Frontier {} distributed {} URLs across {} workers",
                frontierInstanceId, urls.size(), workerArray.length);

        } catch (Exception e) {
            log.error("Error assigning URLs to workers", e);
            // No need to re-throw - just log and continue
        }
    }

    /**
     * Step 5: Notify workers about new work via Redis pub/sub
     */
    private void notifyWorkers(String messageType, int urlCount) {
        try {
            Map<String, Object> notification = Map.of(
                "type", messageType,
                "frontierInstance", frontierInstanceId,
                "count", urlCount,
                "timestamp", LocalDateTime.now().toString()
            );

            String notificationJson = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(WORKER_NOTIFICATION_CHANNEL, notificationJson);

            log.debug("Frontier {} sent notification to workers: {} - {} URLs",
                frontierInstanceId, messageType, urlCount);

        } catch (Exception e) {
            log.error("Error sending notification to workers", e);
        }
    }

    /**
     * Get active workers registered with this frontier
     */
    private Set<String> getActiveRegisteredWorkers() {
        Set<String> activeWorkers = ConcurrentHashMap.newKeySet();

        try {
            // Get all registered workers from Redis
            Map<Object, Object> allWorkers = redisTemplate.opsForHash().entries(WORKER_REGISTRY_KEY);

            for (Object workerId : allWorkers.keySet()) {
                String workerIdStr = workerId.toString();

                // Check if worker is still active (heartbeat within threshold)
                String heartbeatKey = WORKER_HEARTBEAT_PREFIX + workerIdStr;
                String lastHeartbeat = (String) redisTemplate.opsForValue().get(heartbeatKey);

                if (lastHeartbeat != null) {
                    try {
                        LocalDateTime lastSeen = LocalDateTime.parse(lastHeartbeat);
                        if (lastSeen.isAfter(LocalDateTime.now().minusSeconds(60))) {
                            activeWorkers.add(workerIdStr);
                            workerLastSeen.put(workerIdStr, lastSeen);
                        }
                    } catch (Exception e) {
                        log.debug("Error parsing heartbeat for worker {}", workerIdStr);
                    }
                }
            }

            // Update registered workers set
            registeredWorkers.clear();
            registeredWorkers.addAll(activeWorkers);

            log.debug("Frontier {} found {} active workers", frontierInstanceId, activeWorkers.size());

        } catch (Exception e) {
            log.error("Error getting active workers", e);
        }

        return activeWorkers;
    }

    /**
     * Register this frontier with the distributed system
     */
    private void registerFrontier() {
        try {
            Map<String, Object> frontierInfo = Map.of(
                "instanceId", frontierInstanceId,
                "startTime", LocalDateTime.now().toString(),
                "status", "ACTIVE"
            );

            redisTemplate.opsForHash().put(FRONTIER_REGISTRY_KEY, frontierInstanceId, frontierInfo);
            log.info("Frontier {} registered with distributed system", frontierInstanceId);

        } catch (Exception e) {
            log.error("Error registering frontier", e);
        }
    }

    /**
     * Start heartbeat to indicate this frontier is alive
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
        heartbeatThread.setName("frontier-heartbeat-" + frontierInstanceId);
        heartbeatThread.start();

        log.info("URLFrontier heartbeat started");
    }

    /**
     * Send heartbeat to Redis
     */
    private void sendHeartbeat() {
        if (!redisAvailable) return;

        try {
            String heartbeatKey = FRONTIER_HEARTBEAT_PREFIX + frontierInstanceId;
            String heartbeatValue = LocalDateTime.now().toString();
            redisTemplate.opsForValue().set(heartbeatKey, heartbeatValue,
                java.time.Duration.ofMillis(heartbeatInterval * 2));
        } catch (Exception e) {
            log.debug("Error sending heartbeat", e);
        }
    }

    /**
     * Subscribe to notifications from CrawlerManager
     */
    private void subscribeToNotifications() {
        if (!redisAvailable) return;

        try {
            MessageListenerAdapter notificationAdapter = new MessageListenerAdapter() {
                @Override
                public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                    try {
                        String messageBody = new String(message.getBody());
                        handleNotification(messageBody);
                    } catch (Exception e) {
                        log.error("Error processing notification", e);
                    }
                }
            };

            messageListenerContainer.addMessageListener(notificationAdapter,
                new ChannelTopic(FRONTIER_NOTIFICATION_CHANNEL));

            log.info("URLFrontier subscribed to notifications");
        } catch (Exception e) {
            log.error("Error subscribing to notifications", e);
        }
    }

    /**
     * Handle notifications from CrawlerManager
     */
    private void handleNotification(String message) {
        try {
            log.debug("URLFrontier received notification: {}", message);

            // Parse JSON notification
            Map<String, Object> notification = objectMapper.readValue(message, Map.class);
            String eventType = (String) notification.get("type");

            switch (eventType) {
                case "NEW_WORK_ASSIGNED":
                case "DISCOVERED_URLS":
                case "FAILOVER_REASSIGNMENT":
                    log.info("Received {} notification - processing immediately", eventType);
                    processURLAssignments();
                    break;
                default:
                    log.debug("Unknown notification type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Error handling notification: {}", message, e);
        }
    }

    /**
     * Requeue URLs when no workers are available
     */
    private void requeueUrls(List<CrawlUrl> urls) {
        try {
            String frontierQueueKey = FRONTIER_ASSIGNMENT_PREFIX + frontierInstanceId;

            for (CrawlUrl url : urls) {
                String urlJson = objectMapper.writeValueAsString(url);
                redisTemplate.opsForList().rightPush(frontierQueueKey, urlJson);
            }

            log.info("Requeued {} URLs for later processing by frontier {}",
                urls.size(), frontierInstanceId);

        } catch (Exception e) {
            log.error("Error requeuing URLs", e);
        }
    }

    /**
     * Unregister this frontier from Redis
     */
    private void unregisterFrontier() {
        if (!redisAvailable) return;

        try {
            redisTemplate.opsForHash().delete(FRONTIER_REGISTRY_KEY, frontierInstanceId);

            String heartbeatKey = FRONTIER_HEARTBEAT_PREFIX + frontierInstanceId;
            redisTemplate.delete(heartbeatKey);

            log.info("URLFrontier unregistered: {}", frontierInstanceId);
        } catch (Exception e) {
            log.error("Error unregistering frontier", e);
        }
    }

    /**
     * Register a worker with this URLFrontier
     */
    public void registerWorker(String workerId, String workerAddress) {
        try {
            String workerInfo = String.format("WORKER:%s:%s:%s",
                workerId, workerAddress, LocalDateTime.now());

            // Add to this frontier's worker registry
            redisTemplate.opsForHash().put(
                "frontier:" + frontierInstanceId + ":workers",
                workerId, workerInfo);

            log.info("Worker {} registered with URLFrontier {}", workerId, frontierInstanceId);

        } catch (Exception e) {
            log.error("Error registering worker {} with URLFrontier {}", workerId, frontierInstanceId, e);
        }
    }

    /**
     * Unregister a worker from this URLFrontier
     */
    public void unregisterWorker(String workerId) {
        try {
            // Remove from this frontier's worker registry
            redisTemplate.opsForHash().delete(
                "frontier:" + frontierInstanceId + ":workers", workerId);

            log.info("Worker {} unregistered from URLFrontier {}", workerId, frontierInstanceId);

        } catch (Exception e) {
            log.error("Error unregistering worker {} from URLFrontier {}", workerId, frontierInstanceId, e);
        }
    }
}
