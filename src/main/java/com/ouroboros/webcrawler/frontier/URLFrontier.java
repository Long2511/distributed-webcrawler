package com.ouroboros.webcrawler.frontier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * URL Frontier implementation using Redis
 * Handles prioritization and distribution of URLs to be crawled
 */
@Component
@Slf4j
public class URLFrontier {

    @Autowired
    private CrawlUrlRepository crawlUrlRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DistributedInstanceConfig distributedInstanceConfig;

    @Autowired
    private ObjectMapper objectMapper;

    private final String URL_QUEUE_KEY_PREFIX = "url_queue:";
    private final String URL_VISITED_KEY_PREFIX = "url_visited:";
    private final String URL_PROCESSING_KEY_PREFIX = "url_processing:";

    private String getQueueKey(String sessionId) {
        return URL_QUEUE_KEY_PREFIX + sessionId;
    }
    
    private String getVisitedKey(String sessionId) {
        return URL_VISITED_KEY_PREFIX + sessionId;
    }
    
    private String getProcessingKey(String sessionId) {
        return URL_PROCESSING_KEY_PREFIX + sessionId;
    }

    @PostConstruct
    public void init() {
        log.info("URL Frontier initialized");
    }

    /**
     * Add a URL to the frontier
     */
    @Transactional
    public void addUrl(CrawlUrl crawlUrl) {
        // Check if URL is already visited or being processed for this session
        if (isUrlVisited(crawlUrl.getUrl(), crawlUrl.getSessionId())) {
            log.debug("URL already visited or queued: {} for session {}", 
                     crawlUrl.getUrl(), crawlUrl.getSessionId());
            return;
        }

        // Mark URL as visited to prevent duplicates within this session
        redisTemplate.opsForSet().add(getVisitedKey(crawlUrl.getSessionId()), crawlUrl.getUrl());
        
        // Add to priority queue using Redis sorted set
        crawlUrlRepository.save(crawlUrl);
        
        log.debug("Added URL to frontier: {} with priority {} for session {}", 
                 crawlUrl.getUrl(), crawlUrl.getPriority(), crawlUrl.getSessionId());
    }

    /**
     * Get next URLs for a specific machine to process from all active sessions
     */
    public List<CrawlUrl> getNextUrls(String machineId, int batchSize) {
        List<CrawlUrl> result = new ArrayList<>();
        
        log.debug("Getting next URLs for machine: {}, batchSize: {}", machineId, batchSize);
        
        // Get all session-specific queue keys
        Set<String> queueKeys = redisTemplate.keys(URL_QUEUE_KEY_PREFIX + "*");
        log.debug("Found {} queue keys: {}", queueKeys != null ? queueKeys.size() : 0, queueKeys);
        
        if (queueKeys == null || queueKeys.isEmpty()) {
            log.debug("No queue keys found, returning empty result");
            return result;
        }
        
        // Get URLs from each session queue (round-robin style)
        int urlsPerSession = Math.max(1, batchSize / queueKeys.size());
        int remaining = batchSize;
        
        for (String queueKey : queueKeys) {
            if (remaining <= 0) break;
            
            String sessionId = queueKey.substring(URL_QUEUE_KEY_PREFIX.length());
            int toTake = Math.min(urlsPerSession, remaining);
            
            log.debug("Processing session queue: {}, sessionId: {}, toTake: {}", queueKey, sessionId, toTake);
            
            Set<Object> urlObjects = crawlUrlRepository.getTopUrls(sessionId, toTake * 2); // Get more for filtering
            
            log.debug("Retrieved {} URL objects from repository for session {}", 
                     urlObjects != null ? urlObjects.size() : 0, sessionId);
            
            if (urlObjects == null || urlObjects.isEmpty()) {
                continue;
            }

            for (Object obj : urlObjects) {
                if (result.size() >= batchSize) {
                    break;
                }
                
                CrawlUrl crawlUrl = null;
                
                if (obj instanceof CrawlUrl) {
                    crawlUrl = (CrawlUrl) obj;
                } else if (obj instanceof LinkedHashMap) {
                    // Convert LinkedHashMap back to CrawlUrl
                    try {
                        @SuppressWarnings("unchecked")
                        LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) obj;
                        crawlUrl = objectMapper.convertValue(map, CrawlUrl.class);
                        log.debug("Converted LinkedHashMap to CrawlUrl: {}", crawlUrl.getUrl());
                    } catch (Exception e) {
                        log.error("Error converting LinkedHashMap to CrawlUrl: {}", obj, e);
                        continue;
                    }
                } else {
                    log.warn("Unexpected object type in queue: {}", obj.getClass().getSimpleName());
                    continue;
                }
                
                if (crawlUrl == null) {
                    continue;
                }
                
                // Check if this URL is already being processed
                String processingKey = getProcessingKey(crawlUrl.getSessionId()) + crawlUrl.getUrl();
                if (redisTemplate.opsForValue().get(processingKey) != null) {
                    continue; // Skip URLs already being processed
                }
                
                // Store complete task data in Redis for recovery purposes
                try {
                    ProcessingTaskData taskData = ProcessingTaskData.builder()
                        .url(crawlUrl.getUrl())
                        .sessionId(crawlUrl.getSessionId())
                        .depth(crawlUrl.getDepth())
                        .priority(crawlUrl.getPriority())
                        .parentUrl(crawlUrl.getParentUrl())
                        .assignedTo(machineId)
                        .assignedAt(LocalDateTime.now())
                        .originalCrawlUrl(crawlUrl)
                        .build();

                    String taskDataJson = objectMapper.writeValueAsString(taskData);

                    // Store complete task data instead of just machineId
                    redisTemplate.opsForValue().set(processingKey, taskDataJson,
                        30, TimeUnit.MINUTES); // Set expiration for safety

                    log.debug("Stored complete task data for URL: {} assigned to machine: {}",
                            crawlUrl.getUrl(), machineId);

                } catch (Exception e) {
                    log.error("Error storing task data for URL {}: {}", crawlUrl.getUrl(), e);
                    // Fallback to old behavior
                    redisTemplate.opsForValue().set(processingKey, machineId);
                }

                // Remove from queue before mutating so the original entry is deleted
                crawlUrlRepository.remove(crawlUrl);

                // Update status and assignment after removal
                crawlUrl.setStatus("IN_PROGRESS");
                crawlUrl.setAssignedTo(machineId);
                crawlUrl.setAssignedAt(LocalDateTime.now());
                
                result.add(crawlUrl);
                remaining--;
                log.debug("Assigned URL to machine {}: {}", machineId, crawlUrl.getUrl());
            }
        }
        
        return result;
    }

    /**
     * Mark a URL as completed
     */
    @Transactional
    public void markCompleted(String url, String sessionId) {
        // Remove from processing set
        String processingKey = getProcessingKey(sessionId) + url;
        redisTemplate.delete(processingKey);
        
        log.debug("Marked URL as completed: {} for session {}", url, sessionId);
    }

    /**
     * Mark a URL as failed and potentially requeue
     */
    @Transactional
    public void markFailed(String url, String errorMessage, String sessionId) {
        // Remove from processing set
        String processingKey = getProcessingKey(sessionId) + url;
        String machineId = (String) redisTemplate.opsForValue().get(processingKey);
        redisTemplate.delete(processingKey);
        
        // For now, just log the failure - could implement retry logic here
        log.warn("URL failed: {} - {} for session {}", url, errorMessage, sessionId);
        
        // Could requeue with lower priority or increment retry count
        // This depends on the specific retry strategy needed
        //TODO : handle retry logic
    }

    /**
     * Check if a URL has been visited or queued for a specific session
     */
    public boolean isUrlVisited(String url, String sessionId) {
        return redisTemplate.opsForSet().isMember(getVisitedKey(sessionId), url);
    }

    /**
     * Get frontier statistics (legacy method - returns basic stats)
     */
    public FrontierStats getStats() {
        long pendingUrls = crawlUrlRepository.count();
        // For legacy compatibility, we'll return basic stats
        return FrontierStats.builder()
                .pendingUrls(pendingUrls)
                .completedUrls(0)
                .failedUrls(0)
                .totalDiscovered(pendingUrls)
                .averageDepth(0.0)
                .bytesTransferred(0L)
                .crawlRate(0.0)
                .build();
    }
    
    /**
     * Get frontier statistics for a specific session
     */
    public FrontierStats getStats(String sessionId) {
        long pendingUrls = crawlUrlRepository.count();
        long totalVisited = redisTemplate.opsForSet().size(getVisitedKey(sessionId));
        
        // Count processing URLs for this session
        long processingUrls = 0;
        Set<String> keys = redisTemplate.keys(getProcessingKey(sessionId) + "*");
        if (keys != null) {
            processingUrls = keys.size();
        }
        
        return FrontierStats.builder()
                .pendingUrls(pendingUrls)
                .completedUrls(totalVisited - pendingUrls - processingUrls) // Approximation
                .failedUrls(0) // Would need separate tracking
                .totalDiscovered(totalVisited)
                .averageDepth(0.0) // Would need to calculate
                .bytesTransferred(0L) // Would need to track
                .crawlRate(0.0) // Would need to calculate
                .build();
    }

    /**
     * Get pending URL count for metrics
     */
    public long getPendingUrlCount() {
        return crawlUrlRepository.count();
    }
}
