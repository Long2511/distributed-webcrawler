package com.ouroboros.webcrawler.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.frontier.ProcessingTaskData;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import com.ouroboros.webcrawler.worker.CrawlerWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.kafka.support.Acknowledgment;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CrawlerManager {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlerWorker crawlerWorker;

    @Autowired
    private CrawlSessionRepository sessionRepository;

    @Autowired
    private CrawledPageRepository pageRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DistributedInstanceConfig instanceConfig;

    @Value("${webcrawler.kafka.topics.crawl-tasks:webcrawler.tasks}")
    private String crawlTasksTopic;

    @Value("${webcrawler.batch.size:10}")
    private int batchSize;

    private int heartbeatTimeoutSeconds = 90;

    //@Value("${webcrawler.max-depth:10}")
    private int maxDepth = 2;

    // Maximum number of pages to crawl for the currently-active session (simplified single-session config)
    private long maxPagesLimit = 1000L;

    // Base priority for newly-discovered URLs (can be overridden per session)
    private double seedPriority = 1.0;

    @Autowired
    private ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;

    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String HEARTBEAT_KEY_PREFIX = "heartbeat:";

    @PostConstruct
    public void initialize() {
        log.info("Initializing CrawlerManager for instance: {}", instanceConfig.getMachineId());
        registerWorker();
        startHeartbeat();
        requestWork();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CrawlerManager");
        running = false;
        unregisterWorker();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        crawlerWorker.shutdown();
    }

    public String startCrawlSession(CrawlSessionEntity session) {
        log.info("Starting crawl session: {}", session.getName());
        
        session.setStatus("RUNNING");
        session.setStartedAt(LocalDateTime.now());
        // Persist session
        CrawlSessionEntity savedSession = sessionRepository.save(session);

        // Update manager-wide limits to match this session (simplified: one active session per manager)
        this.maxDepth = savedSession.getMaxDepth();
        this.maxPagesLimit = savedSession.getMaxPages();

        // Add seed URLs to frontier
        for (String seedUrl : session.getSeedUrls()) {
            CrawlUrl crawlUrl = CrawlUrl.builder()
                .url(seedUrl)
                .sessionId(savedSession.getId())
                .depth(0)
                .priority(seedPriority)
                .status("PENDING")
                .discoveredAt(LocalDateTime.now())
                .build();
            
            urlFrontier.addUrl(crawlUrl);
        }

        // Publish work available message to distributed workers
        publishWorkAvailable(savedSession.getId());
        
        return savedSession.getId();
    }

    public void stopCrawlSession(String sessionId) {
        log.info("Stopping crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setStatus("STOPPED");
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
        }
    }

    @KafkaListener(topics = "${webcrawler.kafka.topics.crawl-tasks:webcrawler.tasks}")
    public void handleCrawlTask(String message, Acknowledgment ack) {
        try {
            log.debug("Received crawl task: {}", message);
            
            CrawlJob job = objectMapper.readValue(message, CrawlJob.class);
            
            // Handle "work available" notifications - just trigger work request
            if ("WORK_AVAILABLE".equals(job.getStatus()) && job.getUrl() == null) {
                log.debug("Received work available notification for session: {}", job.getSessionId());
                // Trigger immediate work request for this worker
                requestWork();
                ack.acknowledge();
                return;
            }
            
            // Handle actual crawl jobs with URLs
            if (job.getUrl() == null) {
                log.warn("Received crawl job with null URL, skipping: {}", message);
                ack.acknowledge();
                return;
            }
            
            CrawlUrl crawlUrl = CrawlUrl.builder()
                .url(job.getUrl())
                .sessionId(job.getSessionId())
                .depth(job.getDepth())
                .priority(job.getPriority())
                .parentUrl(job.getParentUrl())
                .assignedTo(instanceConfig.getMachineId())
                .assignedAt(LocalDateTime.now())
                .status("IN_PROGRESS")
                .build();

            processCrawlJob(crawlUrl).thenRun(() -> {
                ack.acknowledge();
                log.debug("Acknowledged crawl task for URL: {}", job.getUrl());
            });
            
        } catch (JsonProcessingException e) {
            log.error("Error parsing crawl task message: {}", message, e);
            ack.acknowledge(); // Acknowledge to avoid reprocessing bad messages
        }
    }

    @Async
    public CompletableFuture<Void> processCrawlJob(CrawlUrl crawlUrl) {
        try {
            log.debug("Processing crawl job for URL: {}", crawlUrl.getUrl());
            
            // Crawl the URL
            CrawledPageEntity crawledPage = crawlerWorker.crawl(crawlUrl);
            
            // Mark URL as completed in frontier
            if (crawledPage.getStatusCode() == 200) {
                urlFrontier.markCompleted(crawlUrl.getUrl(), crawlUrl.getSessionId());
                
                log.debug("Crawl successful for URL: {}, depth: {}, maxDepth: {}", 
                         crawlUrl.getUrl(), crawlUrl.getDepth(), maxDepth);
                
                // Extract and queue new URLs if within depth limit
                if (crawlUrl.getDepth() < maxDepth) {
                    log.debug("Extracting links from URL: {} at depth {}", crawlUrl.getUrl(), crawlUrl.getDepth());
                    
                    List<String> extractedUrls = crawlerWorker.extractLinks(
                        crawledPage.getRawHtml(), crawlUrl.getUrl());
                    
                    log.debug("Extracted {} links from URL: {}", extractedUrls.size(), crawlUrl.getUrl());
                    
                    for (String extractedUrl : extractedUrls) {
                        log.debug("Processing extracted URL: {}", extractedUrl);
                        
                        CrawlUrl newCrawlUrl = CrawlUrl.builder()
                            .url(extractedUrl)
                            .sessionId(crawlUrl.getSessionId())
                            .depth(crawlUrl.getDepth() + 1)
                            .priority(Math.max(0.1, seedPriority - (crawlUrl.getDepth() * 0.1)))
                            .parentUrl(crawlUrl.getUrl())
                            .status("PENDING")
                            .discoveredAt(LocalDateTime.now())
                            .build();
                        
                        urlFrontier.addUrl(newCrawlUrl);
                        log.debug("Added new URL to frontier: {} at depth {}", extractedUrl, newCrawlUrl.getDepth());
                    }
                } else {
                    log.debug("Skipping link extraction for URL: {} - depth {} >= maxDepth {}", 
                             crawlUrl.getUrl(), crawlUrl.getDepth(), maxDepth);
                }
            } else {
                urlFrontier.markFailed(crawlUrl.getUrl(), crawledPage.getErrorMessage(), crawlUrl.getSessionId());
            }
            
            log.debug("Completed processing crawl job for URL: {}", crawlUrl.getUrl());
            
        } catch (Exception e) {
            log.error("Error processing crawl job for URL: {}", crawlUrl.getUrl(), e);
            urlFrontier.markFailed(crawlUrl.getUrl(), e.getMessage(), crawlUrl.getSessionId());
        }
        
        return CompletableFuture.completedFuture(null);
    }

    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    public void requestWork() {
        if (!running) {
            log.debug("CrawlerManager not running, skipping work request");
            return;
        }
        
        log.debug("Requesting work from frontier...");
        
        try {
            // Get URLs from frontier
            List<CrawlUrl> urls = urlFrontier.getNextUrls(instanceConfig.getMachineId(), batchSize);
            
            if (!urls.isEmpty()) {
                log.debug("Retrieved {} URLs from frontier", urls.size());
                
                for (CrawlUrl url : urls) {
                    processCrawlJob(url);
                }
            } else {
                log.debug("No URLs available from frontier");
            }
            
        } catch (Exception e) {
            log.error("Error requesting work from frontier", e);
        }
    }

    /**
     * Check for inactive workers and remove them from Redis
     * Runs every 60 seconds to clean up dead nodes
     */
    @Scheduled(fixedDelay = 60000) // Every 60 seconds
    public void checkInactiveWorkers() {
        if (!running) {
            log.debug("CrawlerManager not running, skipping inactive worker check");
            return;
        }

        log.debug("Checking for inactive workers...");

        try {
            // Get all registered workers
            Set<Object> workers = redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
            if (workers == null || workers.isEmpty()) {
                log.debug("No workers registered, skipping inactive check");
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoffTime = now.minusSeconds(heartbeatTimeoutSeconds);
            int removedCount = 0;

            for (Object workerObj : workers) {
                try {
                    String workerInfo = workerObj.toString();

                    // Extract worker ID from JSON-like string
                    String workerId = extractWorkerIdFromInfo(workerInfo);
                    if (workerId == null) {
                        log.warn("Could not extract worker ID from: {}", workerInfo);
                        continue;
                    }

                    // Check heartbeat
                    String heartbeatKey = HEARTBEAT_KEY_PREFIX + workerId;
                    String lastHeartbeatStr = (String) redisTemplate.opsForValue().get(heartbeatKey);

                    if (lastHeartbeatStr == null) {
                        log.warn("No heartbeat found for worker: {}, removing from registry", workerId);
                        removeInactiveWorker(workerId, workerInfo);
                        removedCount++;
                        continue;
                    }

                    try {
                        LocalDateTime lastHeartbeat = LocalDateTime.parse(lastHeartbeatStr);

                        if (lastHeartbeat.isBefore(cutoffTime)) {
                            log.warn("Worker {} is inactive (last heartbeat: {}), removing from registry",
                                   workerId, lastHeartbeat);
                            removeInactiveWorker(workerId, workerInfo);
                            removedCount++;
                        } else {
                            log.debug("Worker {} is active (last heartbeat: {})", workerId, lastHeartbeat);
                        }

                    } catch (Exception e) {
                        log.error("Error parsing heartbeat timestamp for worker {}: {}", workerId, lastHeartbeatStr, e);
                        // Don't remove worker on parse error, could be temporary
                    }

                } catch (Exception e) {
                    log.error("Error checking worker: {}", workerObj, e);
                }
            }

            if (removedCount > 0) {
                log.info("Removed {} inactive workers from registry", removedCount);
            } else {
                log.debug("All registered workers are active");
            }

        } catch (Exception e) {
            log.error("Error during inactive worker check", e);
        }
    }

    /**
     * Extract worker ID from worker info JSON string
     */
    private String extractWorkerIdFromInfo(String workerInfo) {
        try {
            // Simple JSON parsing for worker ID
            if (workerInfo.contains("\"id\":\"")) {
                int startIndex = workerInfo.indexOf("\"id\":\"") + 6;
                int endIndex = workerInfo.indexOf("\"", startIndex);
                if (endIndex > startIndex) {
                    return workerInfo.substring(startIndex, endIndex);
                }
            }
        } catch (Exception e) {
            log.error("Error extracting worker ID from: {}", workerInfo, e);
        }
        return null;
    }

    /**
     * Remove an inactive worker from Redis
     */
    private void removeInactiveWorker(String workerId, String workerInfo) {
        try {
            // Remove from worker registry
            redisTemplate.opsForSet().remove(WORKER_REGISTRY_KEY, workerInfo);

            // Clean up heartbeat key
            String heartbeatKey = HEARTBEAT_KEY_PREFIX + workerId;
            redisTemplate.delete(heartbeatKey);

            // Clean up any processing URLs assigned to this worker
            cleanupWorkerProcessingUrls(workerId);

            log.info("Successfully removed inactive worker: {}", workerId);

        } catch (Exception e) {
            log.error("Error removing inactive worker {}: {}", workerId, e);
        }
    }

    /**
     * Clean up URLs that were being processed by an inactive worker
     * and reassign them to other active workers
     */
    private void cleanupWorkerProcessingUrls(String workerId) {
        try {
            // Find all processing keys assigned to this worker
            Set<String> processingKeys = redisTemplate.keys("url_processing:*");
            if (processingKeys == null || processingKeys.isEmpty()) {
                return;
            }

            int cleanedUpCount = 0;
            int reassignedCount = 0;

            for (String processingKey : processingKeys) {
                try {
                    Object taskDataObj = redisTemplate.opsForValue().get(processingKey);
                    if (taskDataObj == null) {
                        continue;
                    }

                    ProcessingTaskData taskData = null;
                    String assignedWorker = null;

                    try {
                        // Try to parse as ProcessingTaskData JSON first
                        String taskDataJson = taskDataObj.toString();
                        taskData = objectMapper.readValue(taskDataJson, ProcessingTaskData.class);
                        assignedWorker = taskData.getAssignedTo();

                        log.debug("Parsed complete task data for key {}: URL={}, assignedTo={}, depth={}",
                                processingKey, taskData.getUrl(), assignedWorker, taskData.getDepth());

                    } catch (Exception e) {
                        // Fallback: treat as simple machineId string (old format)
                        assignedWorker = taskDataObj.toString();
                        log.debug("Using fallback parsing for key {}: assignedTo={}", processingKey, assignedWorker);
                    }

                    if (workerId.equals(assignedWorker)) {
                        if (taskData != null) {
                            // We have complete task data - use it for proper recovery
                            boolean reassigned = reassignCompleteTask(taskData);

                            if (reassigned) {
                                reassignedCount++;
                                log.debug("Reassigned complete task from inactive worker {} to active worker: URL={}, depth={}",
                                        workerId, taskData.getUrl(), taskData.getDepth());
                            } else {
                                log.warn("Could not reassign complete task from inactive worker {}, adding back to frontier: URL={}, depth={}",
                                        workerId, taskData.getUrl(), taskData.getDepth());

                                // Add back to frontier with complete original data
                                addCompleteTaskBackToFrontier(taskData);
                            }
                        } else {
                            // Fallback to old recovery method for legacy processing keys
                            String urlAndSession = processingKey.substring("url_processing:".length());
                            String sessionId = null;
                            String url = null;

                            int httpIndex = urlAndSession.indexOf("http");
                            if (httpIndex > 0) {
                                sessionId = urlAndSession.substring(0, httpIndex);
                                url = urlAndSession.substring(httpIndex);

                                boolean reassigned = reassignUrlToActiveWorker(url, sessionId);

                                if (reassigned) {
                                    reassignedCount++;
                                    log.debug("Reassigned legacy task from inactive worker {} to active worker: {}",
                                            workerId, url);
                                } else {
                                    log.warn("Could not reassign legacy task from inactive worker {}, adding back to frontier: {}",
                                            workerId, url);
                                    addUrlBackToFrontier(url, sessionId);
                                }
                            }
                        }

                        // Remove the old processing key
                        redisTemplate.delete(processingKey);
                        cleanedUpCount++;

                        log.debug("Cleaned up processing task assigned to inactive worker {}: {}",
                                workerId, processingKey);
                    }
                } catch (Exception e) {
                    log.error("Error cleaning up processing key {}: {}", processingKey, e);
                }
            }

            if (cleanedUpCount > 0) {
                log.info("Cleaned up {} processing tasks from inactive worker: {} (reassigned: {}, returned to frontier: {})",
                        cleanedUpCount, workerId, reassignedCount, cleanedUpCount - reassignedCount);
            }

        } catch (Exception e) {
            log.error("Error cleaning up processing URLs for worker {}: {}", workerId, e);
        }
    }

    /**
     * Reassign a complete task with all metadata to another active worker
     */
    private boolean reassignCompleteTask(ProcessingTaskData taskData) {
        try {
            // Get all active workers
            Set<Object> activeWorkers = redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
            if (activeWorkers == null || activeWorkers.isEmpty()) {
                return false;
            }

            // Find a suitable active worker
            for (Object workerObj : activeWorkers) {
                String workerInfo = workerObj.toString();
                String activeWorkerId = extractWorkerIdFromInfo(workerInfo);

                if (activeWorkerId != null) {
                    // Check if this worker is actually active (has recent heartbeat)
                    String heartbeatKey = HEARTBEAT_KEY_PREFIX + activeWorkerId;
                    String lastHeartbeat = (String) redisTemplate.opsForValue().get(heartbeatKey);

                    if (lastHeartbeat != null) {
                        // Create updated task data with new assignment
                        ProcessingTaskData reassignedTask = ProcessingTaskData.builder()
                            .url(taskData.getUrl())
                            .sessionId(taskData.getSessionId())
                            .depth(taskData.getDepth())
                            .priority(taskData.getPriority())
                            .parentUrl(taskData.getParentUrl())
                            .assignedTo(activeWorkerId)
                            .assignedAt(LocalDateTime.now())
                            .originalCrawlUrl(taskData.getOriginalCrawlUrl())
                            .retryCount(taskData.getRetryCount() + 1)
                            .createdAt(taskData.getCreatedAt())
                            .build();

                        // Store the reassigned task
                        String newProcessingKey = "url_processing:" + taskData.getSessionId() + taskData.getUrl();
                        String taskDataJson = objectMapper.writeValueAsString(reassignedTask);
                        redisTemplate.opsForValue().set(newProcessingKey, taskDataJson, 30, TimeUnit.MINUTES);

                        log.debug("Reassigned complete task {} to active worker: {} (retry count: {})",
                                taskData.getUrl(), activeWorkerId, reassignedTask.getRetryCount());
                        return true;
                    }
                }
            }

            return false;

        } catch (Exception e) {
            log.error("Error reassigning complete task for URL {}: {}", taskData.getUrl(), e);
            return false;
        }
    }

    /**
     * Add a complete task back to frontier with all original metadata preserved
     */
    private void addCompleteTaskBackToFrontier(ProcessingTaskData taskData) {
        try {
            CrawlUrl originalUrl = taskData.getOriginalCrawlUrl();
            CrawlUrl crawlUrl;

            if (originalUrl != null) {
                // Use the complete original CrawlUrl data
                crawlUrl = CrawlUrl.builder()
                    .url(taskData.getUrl())
                    .sessionId(taskData.getSessionId())
                    .status("PENDING")
                    .priority(Math.max(0.1, taskData.getPriority() * 0.9)) // Slightly lower priority for recovered tasks
                    .depth(taskData.getDepth()) // Preserve exact depth
                    .parentUrl(taskData.getParentUrl()) // Preserve parent URL
                    .discoveredAt(originalUrl.getDiscoveredAt()) // Preserve original discovery time
                    .build();

                log.debug("Adding complete task back to frontier with preserved metadata: URL={}, depth={}, priority={}",
                        taskData.getUrl(), taskData.getDepth(), crawlUrl.getPriority());
            } else {
                // Create from available task data
                crawlUrl = CrawlUrl.builder()
                    .url(taskData.getUrl())
                    .sessionId(taskData.getSessionId())
                    .status("PENDING")
                    .priority(Math.max(0.1, taskData.getPriority() * 0.9))
                    .depth(taskData.getDepth())
                    .parentUrl(taskData.getParentUrl())
                    .discoveredAt(taskData.getCreatedAt())
                    .build();

                log.debug("Adding task back to frontier from task data: URL={}, depth={}, priority={}",
                        taskData.getUrl(), taskData.getDepth(), crawlUrl.getPriority());
            }

            // Only add back if depth is within limits
            if (crawlUrl.getDepth() >= maxDepth) {
                log.warn("Not adding task back to frontier - depth {} >= maxDepth {}: {}",
                        crawlUrl.getDepth(), maxDepth, taskData.getUrl());
                return;
            }

            urlFrontier.addUrl(crawlUrl);
            log.debug("Successfully added complete task back to frontier: {}", taskData.getUrl());

        } catch (Exception e) {
            log.error("Error adding complete task back to frontier: {}", taskData.getUrl(), e);
        }
    }

    /**
     * Try to reassign a URL to another active worker (legacy fallback method)
     * This is used when complete task data is not available
     */
    private boolean reassignUrlToActiveWorker(String url, String sessionId) {
        try {
            // Get all active workers
            Set<Object> activeWorkers = redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
            if (activeWorkers == null || activeWorkers.isEmpty()) {
                return false;
            }

            // Find a suitable active worker (simple round-robin approach)
            for (Object workerObj : activeWorkers) {
                String workerInfo = workerObj.toString();
                String activeWorkerId = extractWorkerIdFromInfo(workerInfo);

                if (activeWorkerId != null) {
                    // Check if this worker is actually active (has recent heartbeat)
                    String heartbeatKey = HEARTBEAT_KEY_PREFIX + activeWorkerId;
                    String lastHeartbeat = (String) redisTemplate.opsForValue().get(heartbeatKey);

                    if (lastHeartbeat != null) {
                        // Assign URL to this active worker using legacy format
                        String newProcessingKey = "url_processing:" + sessionId + url;
                        redisTemplate.opsForValue().set(newProcessingKey, activeWorkerId);

                        log.debug("Reassigned legacy URL {} to active worker: {}", url, activeWorkerId);
                        return true;
                    }
                }
            }

            return false;

        } catch (Exception e) {
            log.error("Error reassigning legacy URL {} to active worker: {}", url, e);
            return false;
        }
    }

    /**
     * Add URL back to frontier for future processing (legacy fallback method)
     * This is used when original CrawlUrl data is not available
     */
    private void addUrlBackToFrontier(String url, String sessionId) {
        try {
            // Estimate the depth of the URL when original data is not available
            int estimatedDepth = estimateUrlDepth(url, sessionId);

            // Only add back to frontier if estimated depth is within limits
            if (estimatedDepth >= maxDepth) {
                log.warn("Not adding legacy URL back to frontier - estimated depth {} >= maxDepth {}: {}",
                        estimatedDepth, maxDepth, url);
                return;
            }

            CrawlUrl crawlUrl = CrawlUrl.builder()
                .url(url)
                .sessionId(sessionId)
                .status("PENDING")
                .priority(0.3) // Low priority for recovered URLs without original data
                .depth(estimatedDepth) // Use estimated depth
                .discoveredAt(LocalDateTime.now())
                .build();

            urlFrontier.addUrl(crawlUrl);
            log.debug("Added legacy URL back to frontier with estimated depth {}: {}", estimatedDepth, url);

        } catch (Exception e) {
            log.error("Error adding legacy URL back to frontier: {}", url, e);
        }
    }

    /**
     * Estimate the depth of a URL when original data is not available (legacy support)
     * This is a best-effort approach to avoid infinite crawling
     */
    private int estimateUrlDepth(String url, String sessionId) {
        try {
            // Method 1: Check if any crawled pages in this session have this URL as parent
            // This would indicate this URL is at least depth 1
            List<CrawledPageEntity> pages = pageRepository.findBySessionId(sessionId);

            int maxFoundDepth = 0;
            boolean foundAsParent = false;

            for (CrawledPageEntity page : pages) {
                // Check if this URL appears as a parent URL
                if (url.equals(page.getParentUrl())) {
                    foundAsParent = true;
                    maxFoundDepth = Math.max(maxFoundDepth, page.getDepth() - 1);
                }
                // Check if this URL was already crawled
                if (url.equals(page.getUrl())) {
                    return page.getDepth(); // We found the exact depth!
                }
            }

            if (foundAsParent) {
                return Math.max(0, maxFoundDepth);
            }

            // Method 2: Estimate based on URL path depth (rough heuristic)
            try {
                java.net.URI uri = java.net.URI.create(url);
                String path = uri.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) {
                    return 0; // Root page
                }

                // Count path segments as a rough depth indicator
                int pathSegments = path.split("/").length - 1; // -1 because split on "/" creates empty first element
                return Math.min(pathSegments, maxDepth - 1); // Cap at maxDepth - 1 to be safe

            } catch (Exception e) {
                log.debug("Error parsing URL for depth estimation: {}", url);
                return 1; // Conservative estimate
            }

        } catch (Exception e) {
            log.error("Error estimating depth for URL {}: {}", url, e);
            return 1; // Conservative fallback
        }
    }

    private void publishWorkAvailable(String sessionId) {
        try {
            CrawlJob workNotification = CrawlJob.builder()
                .sessionId(sessionId)
                .status("WORK_AVAILABLE")
                .createdAt(LocalDateTime.now())
                .build();
            
            String message = objectMapper.writeValueAsString(workNotification);
            kafkaTemplate.send(crawlTasksTopic, message);
            
            log.debug("Published work available notification for session: {}", sessionId);
            
        } catch (JsonProcessingException e) {
            log.error("Error publishing work available notification", e);
        }
    }

    private void registerWorker() {
        String workerKey = WORKER_REGISTRY_KEY;
        String workerId = instanceConfig.getMachineId();
        String workerInfo = String.format("{\"id\":\"%s\",\"host\":\"%s\",\"registeredAt\":\"%s\"}", 
            workerId, instanceConfig.getAdvertisedHost(), LocalDateTime.now());
        
        redisTemplate.opsForSet().add(workerKey, workerInfo);
        log.info("Registered worker: {}", workerId);
    }

    private void unregisterWorker() {
        String workerKey = WORKER_REGISTRY_KEY;
        String workerId = instanceConfig.getMachineId();
        
        // Remove from active workers set (simplified removal)
        redisTemplate.opsForSet().members(workerKey).forEach(member -> {
            if (member.toString().contains(workerId)) {
                redisTemplate.opsForSet().remove(workerKey, member);
            }
        });
        
        log.info("Unregistered worker: {}", workerId);
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (running) {
                String heartbeatKey = HEARTBEAT_KEY_PREFIX + instanceConfig.getMachineId();
                redisTemplate.opsForValue().set(heartbeatKey, 
                    LocalDateTime.now().toString(), 
                    instanceConfig.getHeartbeatIntervalSeconds() * 2, 
                    TimeUnit.SECONDS);
            }
        }, 0, instanceConfig.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
    }

    public List<CrawlSessionEntity> getAllSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    public CrawlSessionEntity getSession(String sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public List<CrawledPageEntity> getSessionPages(String sessionId) {
        return pageRepository.findBySessionId(sessionId);
    }

    /**
     * Get active workers from Redis registry
     */
    public Set<Object> getActiveWorkers() {
        return redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
    }

    /**
     * Get worker statistics including heartbeat status
     */
    public int getActiveWorkerCount() {
        Set<Object> workers = getActiveWorkers();
        return workers != null ? workers.size() : 0;
    }
}
