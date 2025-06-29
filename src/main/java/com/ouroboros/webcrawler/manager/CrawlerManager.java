package com.ouroboros.webcrawler.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    private CrawlUrlRepository crawlUrlRepository;

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

    //@Value("${webcrawler.max-depth:10}")
    private int maxDepth = 2;

    @Autowired
    private ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;

    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String HEARTBEAT_KEY_PREFIX = "heartbeat:";
    private static final String URL_PROCESSING_KEY_PREFIX = "processing:";
    private static final String URL_QUEUE_KEY_PREFIX = "queue:";
    private static final String URL_VISITED_KEY_PREFIX = "visited:";

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

    public boolean pauseCrawlSession(String sessionId) {
        log.info("Pausing crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && "RUNNING".equals(session.getStatus())) {
            session.setStatus("PAUSED");
            session.setPausedAt(LocalDateTime.now());
            sessionRepository.save(session);

            // Clean up Redis processing keys for this session
            Set<String> processingKeys = redisTemplate.keys(URL_PROCESSING_KEY_PREFIX + sessionId + ":*");
            if (processingKeys != null && !processingKeys.isEmpty()) {
                redisTemplate.delete(processingKeys);
                log.debug("Cleaned up {} processing keys for session {}", processingKeys.size(), sessionId);
            }

            // Clean up Kafka - send a special control message to stop processing
            try {
                CrawlJob controlJob = CrawlJob.builder()
                    .sessionId(sessionId)
                    .status("PAUSE")
                    .createdAt(LocalDateTime.now())
                    .build();
                String message = objectMapper.writeValueAsString(controlJob);
                kafkaTemplate.send(crawlTasksTopic, message);
                log.debug("Sent pause control message to Kafka for session: {}", sessionId);
            } catch (JsonProcessingException e) {
                log.error("Error sending pause control message to Kafka", e);
            }

            return true;
        }
        return false;
    }

    public boolean resumeCrawlSession(String sessionId) {
        log.info("Resuming crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && "PAUSED".equals(session.getStatus())) {
            session.setStatus("RUNNING");
            session.setResumedAt(LocalDateTime.now());
            sessionRepository.save(session);
            
            // Publish work available notification to restart crawling
            publishWorkAvailable(sessionId);
            return true;
        }
        return false;
    }

    public boolean deleteCrawlSession(String sessionId) {
        log.info("Deleting crawl session: {}", sessionId);
        
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            // Delete all crawled pages for this session
            pageRepository.deleteBySessionId(sessionId);
            
            // Delete all URLs for this session from MongoDB
            crawlUrlRepository.deleteBySessionId(sessionId);
            
            // Clean up Redis keys
            String queueKey = URL_QUEUE_KEY_PREFIX + sessionId;
            String visitedKey = URL_VISITED_KEY_PREFIX + sessionId;
            Set<String> processingKeys = redisTemplate.keys(URL_PROCESSING_KEY_PREFIX + sessionId + ":*");
            
            // Delete Redis keys
            redisTemplate.delete(queueKey);
            redisTemplate.delete(visitedKey);
            if (processingKeys != null && !processingKeys.isEmpty()) {
                redisTemplate.delete(processingKeys);
            }
            
            // Clean up Kafka - send a special control message to stop processing
            try {
                CrawlJob controlJob = CrawlJob.builder()
                    .sessionId(sessionId)
                    .status("DELETE")
                    .createdAt(LocalDateTime.now())
                    .build();
                String message = objectMapper.writeValueAsString(controlJob);
                kafkaTemplate.send(crawlTasksTopic, message);
                log.debug("Sent delete control message to Kafka for session: {}", sessionId);
            } catch (JsonProcessingException e) {
                log.error("Error sending delete control message to Kafka", e);
            }
            
            // Delete the session itself
            sessionRepository.deleteById(sessionId);
            
            log.info("Successfully deleted crawl session and all related data: {}", sessionId);
            return true;
        }
        return false;
    }

    @KafkaListener(topics = "${webcrawler.kafka.topics.crawl-tasks:webcrawler.tasks}")
    public void handleCrawlTask(String message, Acknowledgment ack) {
        try {
            log.debug("Received crawl task: {}", message);
            
            CrawlJob job = objectMapper.readValue(message, CrawlJob.class);
            
            // Handle control messages
            if (job.getUrl() == null) {
                if ("WORK_AVAILABLE".equals(job.getStatus())) {
                    log.debug("Received work available notification for session: {}", job.getSessionId());
                    requestWork();
                } else if ("PAUSE".equals(job.getStatus())) {
                    log.debug("Received pause control message for session: {}", job.getSessionId());
                    // Stop processing URLs for this session
                    stopProcessingSession(job.getSessionId());
                } else if ("DELETE".equals(job.getStatus())) {
                    log.debug("Received delete control message for session: {}", job.getSessionId());
                    // Stop processing URLs for this session
                    stopProcessingSession(job.getSessionId());
                }
                ack.acknowledge();
                return;
            }
            
            // Handle actual crawl jobs with URLs
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
            log.info("Processing crawl job for URL: {}", crawlUrl.getUrl());

            // Crawl the URL
            CrawledPageEntity crawledPage = crawlerWorker.crawl(crawlUrl);
            log.info("Crawl completed for URL: {}, status: {}", crawlUrl.getUrl(), crawledPage.getStatusCode());

            // Mark URL as completed in frontier
            if (crawledPage.getStatusCode() == 200) {
                urlFrontier.markCompleted(crawlUrl.getUrl(), crawlUrl.getSessionId());
                
                log.info("Crawl successful for URL: {}, depth: {}, maxDepth: {}",
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
                            .priority(Math.max(0.1, 1.0 - (crawlUrl.getDepth() * 0.1)))
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
            log.info("CrawlerManager not running, skipping work request");
            return;
        }
        
        log.info("Requesting work from frontier...");

        try {
            // Get URLs from frontier
            List<CrawlUrl> urls = urlFrontier.getNextUrls(instanceConfig.getMachineId(), batchSize);
            
            if (!urls.isEmpty()) {
                log.info("Retrieved {} URLs from frontier", urls.size());

                for (CrawlUrl url : urls) {
                    processCrawlJob(url);
                }
            } else {
                log.info("No URLs available from frontier");
            }
            
        } catch (Exception e) {
            log.error("Error requesting work from frontier", e);
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

    public List<Object> getSessionPages(String sessionId, int page, int size) {
        // This would need to be implemented with pagination
        // For now, return all pages
        List<CrawledPageEntity> pages = pageRepository.findBySessionId(sessionId);
        return new ArrayList<>(pages);
    }

    public Object getSessionStats(String sessionId) {
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        
        // Get additional stats
        long totalPages = pageRepository.countBySessionId(sessionId);
        long successfulPages = pageRepository.countBySessionIdAndStatusCode(sessionId, 200);
        long failedPages = totalPages - successfulPages;
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("session", session);
        stats.put("totalPages", totalPages);
        stats.put("successfulPages", successfulPages);
        stats.put("failedPages", failedPages);
        stats.put("successRate", totalPages > 0 ? (double) successfulPages / totalPages : 0.0);
        
        return stats;
    }

    private void stopProcessingSession(String sessionId) {
        // Clean up any in-progress tasks for this session
        Set<String> processingKeys = redisTemplate.keys(URL_PROCESSING_KEY_PREFIX + sessionId + ":*");
        if (processingKeys != null && !processingKeys.isEmpty()) {
            redisTemplate.delete(processingKeys);
            log.debug("Cleaned up {} processing keys for session {}", processingKeys.size(), sessionId);
        }
    }
}

