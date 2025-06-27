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

    @Value("${webcrawler.max-depth:10}")
    private int maxDepth;

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
            
            // Save crawled page
            pageRepository.save(crawledPage);
            
            // Mark URL as completed in frontier
            if (crawledPage.getStatusCode() == 200) {
                urlFrontier.markCompleted(crawlUrl.getUrl(), crawlUrl.getSessionId());
                
                // Extract and queue new URLs if within depth limit
                if (crawlUrl.getDepth() < maxDepth) {
                    List<String> extractedUrls = crawlerWorker.extractLinks(
                        crawledPage.getContent(), crawlUrl.getUrl());
                    
                    for (String extractedUrl : extractedUrls) {
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
                    }
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
}
