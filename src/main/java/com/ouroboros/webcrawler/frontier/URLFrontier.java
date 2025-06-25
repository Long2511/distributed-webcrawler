package com.ouroboros.webcrawler.frontier;

import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * URL Frontier implementation using MongoDB and Redis
 * Handles prioritization and distribution of URLs to be crawled
 */
@Component
@Slf4j
public class URLFrontier {

    @Autowired
    private CrawlUrlRepository crawlUrlRepository;

    @Autowired
    private KafkaTemplate<String, CrawlJob> kafkaTemplate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DistributedInstanceConfig distributedInstanceConfig;

    private final String BLOOM_FILTER_KEY = "url_bloom_filter";

    @Value("${webcrawler.kafka.topics.crawl-tasks}")
    private String crawlTasksTopic;

    @Value("${webcrawler.frontier.bloom-filter.expected-insertions}")
    private long expectedInsertions;

    @Value("${webcrawler.frontier.bloom-filter.false-positive-probability}")
    private double falsePositiveProbability;

    @Value("${webcrawler.frontier.batch-size:10}")
    private int defaultBatchSize;

    @Value("${webcrawler.frontier.adaptive-allocation:true}")
    private boolean adaptiveAllocation;

    @Autowired
    private String instanceId;

    @PostConstruct
    public void init() {
        // Initialize the Redis-based URL tracker
        log.info("URL deduplication system initialized with capacity: {}, false positive probability: {}",
                expectedInsertions, falsePositiveProbability);

        log.info("URL Frontier using adaptive resource allocation: {}", adaptiveAllocation);
    }

    // Check if this instance should process the given URL using the distributed instance config
    private boolean shouldProcessUrl(String url) {
        return distributedInstanceConfig.shouldProcessUrl(url);
    }

    // Legacy method - delegating to the new distributed instance config
    private boolean shouldProcessDomain(String domain) {
        return distributedInstanceConfig.shouldProcessUrl(domain);
    }

    /**
     * Add a URL to the frontier with a specified priority
     * Higher number means higher priority
     */
    @Transactional
    public void addUrl(String url, int priority, int depth, String sessionId) {
        // First check in Redis for quick rejection - now checks specifically for this session
        if (isUrlVisited(url, sessionId)) {
            log.debug("URL already visited or queued in session {}: {}", sessionId, url);
            return;
        }

        // Add URL to Redis with session-specific key
        String urlHash = String.valueOf(url.hashCode());
        // Store both a session-specific entry and a general entry
        redisTemplate.opsForValue().set(BLOOM_FILTER_KEY + ":" + sessionId + ":" + urlHash, "1");
        redisTemplate.opsForValue().set(BLOOM_FILTER_KEY + ":" + urlHash, "1");

        CrawlUrl crawlUrl = CrawlUrl.builder()
                .id(UUID.randomUUID().toString())
                .url(url)
                .priority(priority)
                .depth(depth)
                .status("QUEUED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .retryCount(0)
                .sessionId(sessionId)
                .build();

        // Extract domain for politeness policy
        crawlUrl.extractDomain();

        // Save to database
        crawlUrlRepository.save(crawlUrl);
        log.debug("Added URL to frontier: {} with priority {} for session {}", url, priority, sessionId);
    }

    /**
     * Check if a URL has been visited or is in the queue for a specific session
     */
    public boolean isUrlVisited(String url, String sessionId) {
        // First check Redis for quick rejection with session-specific key
        String urlHash = String.valueOf(url.hashCode());
        Boolean existsInSession = redisTemplate.hasKey(BLOOM_FILTER_KEY + ":" + sessionId + ":" + urlHash);

        if (existsInSession != null && existsInSession) {
            // Double-check in the database to handle hash collisions
            return crawlUrlRepository.existsByUrlAndSessionId(url, sessionId);
        }
        return false;
    }

    /**
     * Check if a URL has been visited or is in the queue (in any session)
     * Kept for backward compatibility
     */
    public boolean isUrlVisited(String url) {
        // First check Redis for quick rejection
        String urlHash = String.valueOf(url.hashCode());
        Boolean exists = redisTemplate.hasKey(BLOOM_FILTER_KEY + ":" + urlHash);

        if (exists != null && exists) {
            // Double-check in the database to handle hash collisions
            return crawlUrlRepository.existsByUrl(url);
        }
        return false;
    }

    /**
     * Mark a job as completed in the frontier
     */
    @Transactional
    public void markAsCompleted(CrawlJob job) {
        // Changed to handle multiple URLs with the same URL string
        List<CrawlUrl> urlEntries = crawlUrlRepository.findByUrl(job.getUrl());

        // Try to find URL for the specific session first
        Optional<CrawlUrl> sessionUrl = crawlUrlRepository.findByUrlAndSessionId(job.getUrl(), job.getSessionId());

        if (sessionUrl.isPresent()) {
            // If we found the exact URL for this session, update it
            CrawlUrl crawlUrl = sessionUrl.get();
            crawlUrl.setStatus("COMPLETED");
            crawlUrl.setUpdatedAt(LocalDateTime.now());
            crawlUrlRepository.save(crawlUrl);
            log.debug("Marked URL as completed for session {}: {}", job.getSessionId(), job.getUrl());
        } else if (!urlEntries.isEmpty()) {
            // If we couldn't find the exact match but found other entries with this URL,
            // update the first one and log a warning
            CrawlUrl crawlUrl = urlEntries.get(0);
            crawlUrl.setStatus("COMPLETED");
            crawlUrl.setUpdatedAt(LocalDateTime.now());
            crawlUrlRepository.save(crawlUrl);
            log.warn("URL '{}' found in multiple sessions but not in session {}. Marking first entry as completed.",
                    job.getUrl(), job.getSessionId());
        } else {
            log.warn("Cannot mark URL as completed - not found: {}", job.getUrl());
        }
    }

    /**
     * Mark a job as failed in the frontier
     */
    @Transactional
    public void markAsFailed(CrawlJob job, String errorMessage, boolean shouldRetry) {
        // Changed to handle multiple URLs with the same URL string
        // Try to find URL for the specific session first
        Optional<CrawlUrl> sessionUrl = crawlUrlRepository.findByUrlAndSessionId(job.getUrl(), job.getSessionId());

        if (sessionUrl.isPresent()) {
            // If we found the exact URL for this session, update it
            CrawlUrl crawlUrl = sessionUrl.get();
            updateFailedUrl(crawlUrl, errorMessage, shouldRetry);
            log.debug("Marked URL as failed/retry for session {}: {} - {}",
                      job.getSessionId(), job.getUrl(), crawlUrl.getStatus());
        } else {
            // Fall back to any URL with this string
            List<CrawlUrl> urlEntries = crawlUrlRepository.findByUrl(job.getUrl());

            if (!urlEntries.isEmpty()) {
                CrawlUrl crawlUrl = urlEntries.get(0);
                updateFailedUrl(crawlUrl, errorMessage, shouldRetry);
                log.warn("URL '{}' found in multiple sessions but not in session {}. Marking first entry as failed.",
                        job.getUrl(), job.getSessionId());
            } else {
                log.warn("Cannot mark URL as failed - not found: {}", job.getUrl());
            }
        }
    }

    /**
     * Helper method to update a failed URL
     */
    private void updateFailedUrl(CrawlUrl crawlUrl, String errorMessage, boolean shouldRetry) {
        if (shouldRetry && crawlUrl.getRetryCount() < 3) {
            // Retry with exponential backoff
            crawlUrl.setStatus("RETRY");
            crawlUrl.setRetryCount(crawlUrl.getRetryCount() + 1);
            crawlUrl.setErrorMessage(errorMessage);
            crawlUrl.setNextRetryAt(LocalDateTime.now().plusSeconds(10 * (long) Math.pow(2, crawlUrl.getRetryCount())));
        } else {
            // Mark as permanently failed
            crawlUrl.setStatus("FAILED");
            crawlUrl.setErrorMessage(errorMessage);
        }

        crawlUrl.setUpdatedAt(LocalDateTime.now());
        crawlUrlRepository.save(crawlUrl);
    }

    /**
     * Schedule next batch of URLs for crawling based on priority, domain politeness, and instance partitioning
     */
    @Transactional
    public void scheduleNextBatch(int batchSize) {
        // Find all URLs that are ready to be crawled
        List<CrawlUrl> readyUrls = findUrlsForCrawling(batchSize * 2); // Get more than we need for filtering

        if (readyUrls.isEmpty()) {
            log.debug("No URLs ready for crawling");
            return;
        }

        // Filter URLs by instance responsibility using the new shouldProcessUrl method
        List<CrawlUrl> myUrls = readyUrls.stream()
            .filter(url -> shouldProcessUrl(url.getUrl()))
            .limit(batchSize)
            .collect(Collectors.toList());

        if (myUrls.isEmpty()) {
            log.debug("No URLs assigned to this instance");
            return;
        }

        log.debug("Scheduling {} URLs for crawling (from {} ready URLs)", myUrls.size(), readyUrls.size());

        // Create and send crawl jobs to Kafka
        for (CrawlUrl url : myUrls) {
            CrawlJob job = CrawlJob.builder()
                    .id(UUID.randomUUID().toString())
                    .url(url.getUrl())
                    .priority(url.getPriority())
                    .depth(url.getDepth())
                    .jobStatus("PROCESSING")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .retryCount(url.getRetryCount())
                    .sessionId(url.getSessionId())
                    .processingInstance(instanceId) // Mark which instance is processing this URL
                    .build();

            // Update URL status in database
            url.setStatus("PROCESSING");
            url.setUpdatedAt(LocalDateTime.now());
            url.setProcessingInstance(instanceId);
            crawlUrlRepository.save(url);

            // Send job to Kafka queue
            kafkaTemplate.send(crawlTasksTopic, job.getUrl(), job);
            log.debug("Sent crawl job to Kafka: {} for session {}", job.getUrl(), job.getSessionId());
        }
    }

    /**
     * Schedule next batch of URLs with default batch size
     */
    @Transactional
    public void scheduleNextBatch() {
        int batchSize = defaultBatchSize;

        // If adaptive allocation is enabled, adjust batch size based on number of instances
        if (adaptiveAllocation) {
            int instanceCount = distributedInstanceConfig.getActiveInstanceCount();
            if (instanceCount > 1) {
                // Proportionally adjust batch size based on instance count
                // More instances = smaller batch size per instance
                batchSize = Math.max(1, defaultBatchSize / instanceCount);
                log.debug("Adaptive batch size: {} (based on {} active instances)",
                         batchSize, instanceCount);
            }
        }

        scheduleNextBatch(batchSize);
    }

    /**
     * Find URLs that are ready to be crawled
     */
    private List<CrawlUrl> findUrlsForCrawling(int limit) {
        LocalDateTime now = LocalDateTime.now();

        // First try to find URLs in QUEUED status
        List<CrawlUrl> queuedUrls = crawlUrlRepository.findByStatusAndProcessingInstanceIsNull(
                "QUEUED",
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "priority")));

        // If not enough queued URLs, also look for retry-ready URLs
        if (queuedUrls.size() < limit) {
            List<CrawlUrl> retryUrls = crawlUrlRepository.findByStatusAndNextRetryAtBeforeAndProcessingInstanceIsNull(
                    "RETRY",
                    now,
                    PageRequest.of(0, limit - queuedUrls.size(), Sort.by(Sort.Direction.DESC, "priority")));
            queuedUrls.addAll(retryUrls);
        }

        // Also find URLs that were being processed by instances that are no longer active
        List<String> activeInstanceIds = distributedInstanceConfig.getQueuedInstances();
        if (!activeInstanceIds.isEmpty()) {
            // Find URLs assigned to non-active instances and reassign them
            List<CrawlUrl> staleUrls = crawlUrlRepository.findByStatusAndProcessingInstanceNotIn(
                "PROCESSING",
                activeInstanceIds,
                PageRequest.of(0, limit - queuedUrls.size(), Sort.by(Sort.Direction.DESC, "updatedAt")));

            if (!staleUrls.isEmpty()) {
                log.info("Found {} stale URLs assigned to inactive instances - reclaiming them", staleUrls.size());
                for (CrawlUrl staleUrl : staleUrls) {
                    staleUrl.setStatus("QUEUED");
                    staleUrl.setProcessingInstance(null);
                    staleUrl.setUpdatedAt(LocalDateTime.now());
                }
                crawlUrlRepository.saveAll(staleUrls);
                queuedUrls.addAll(staleUrls);
            }
        }

        return queuedUrls;
    }

    /**
     * Get statistics about the frontier
     */
    public FrontierStats getStats() {
        long queuedCount = crawlUrlRepository.countByStatus("QUEUED");
        long processingCount = crawlUrlRepository.countByStatus("PROCESSING");
        long completedCount = crawlUrlRepository.countByStatus("COMPLETED");
        long failedCount = crawlUrlRepository.countByStatus("FAILED");
        long retryCount = crawlUrlRepository.countByStatus("RETRY");
        long totalCount = crawlUrlRepository.count();

        return FrontierStats.builder()
                .queuedUrls(queuedCount)
                .processingUrls(processingCount)
                .completedUrls(completedCount)
                .failedUrls(failedCount)
                .retryUrls(retryCount)
                .totalUrls(totalCount)
                .activeInstances(distributedInstanceConfig.getActiveInstanceCount())
                .queuedInstances(distributedInstanceConfig.getQueuedInstances().size())
                .instanceId(instanceId)
                .build();
    }

    /**
     * Stop processing URLs for a specific session
     * This marks all queued and in-progress URLs as stopped so they won't be processed further
     *
     * @param sessionId The ID of the session to stop
     */
    @Transactional
    public void stopSessionUrls(String sessionId) {
        log.info("Stopping all URLs for session {}", sessionId);

        // Find all URLs for this session that are in QUEUED or PROCESSING status
        List<CrawlUrl> activeUrls = crawlUrlRepository.findBySessionIdAndStatus(sessionId, "QUEUED");
        activeUrls.addAll(crawlUrlRepository.findBySessionIdAndStatus(sessionId, "PROCESSING"));
        activeUrls.addAll(crawlUrlRepository.findBySessionIdAndStatus(sessionId, "RETRY"));

        if (activeUrls.isEmpty()) {
            log.info("No active URLs found for session {}", sessionId);
            return;
        }

        // Update all URLs to STOPPED status
        for (CrawlUrl url : activeUrls) {
            url.setStatus("STOPPED");
            url.setUpdatedAt(LocalDateTime.now());
            url.setProcessingInstance(null);
        }

        // Save the updated URLs
        crawlUrlRepository.saveAll(activeUrls);
        log.info("Marked {} URLs as STOPPED for session {}", activeUrls.size(), sessionId);
    }

    /**
     * Pause processing URLs for a specific session
     * This marks all queued URLs as paused so they won't be picked up for processing
     *
     * @param sessionId The ID of the session to pause
     */
    @Transactional
    public void pauseSessionUrls(String sessionId) {
        log.info("Pausing URLs for session {}", sessionId);

        // Find all QUEUED URLs for this session
        List<CrawlUrl> queuedUrls = crawlUrlRepository.findBySessionIdAndStatus(sessionId, "QUEUED");

        if (queuedUrls.isEmpty()) {
            log.info("No queued URLs found for session {}", sessionId);
            return;
        }

        // Update all URLs to PAUSED status
        for (CrawlUrl url : queuedUrls) {
            url.setStatus("PAUSED");
            url.setUpdatedAt(LocalDateTime.now());
        }

        // Save the updated URLs
        crawlUrlRepository.saveAll(queuedUrls);
        log.info("Marked {} URLs as PAUSED for session {}", queuedUrls.size(), sessionId);
    }

    /**
     * Remove all URLs for a specific session
     * This is used when deleting a session completely
     *
     * @param sessionId The ID of the session to remove
     * @return The number of URLs removed
     */
    @Transactional
    public long removeSessionUrls(String sessionId) {
        log.info("Removing all URLs for session {}", sessionId);

        // Find all URL hashes for this session to remove from bloom filter
        List<CrawlUrl> sessionUrls = crawlUrlRepository.findBySessionId(sessionId);

        // Remove session-specific entries from the bloom filter
        for (CrawlUrl url : sessionUrls) {
            String urlHash = String.valueOf(url.getUrl().hashCode());
            redisTemplate.delete(BLOOM_FILTER_KEY + ":" + sessionId + ":" + urlHash);
        }

        // Delete all URLs for this session from the database
        long removedCount = crawlUrlRepository.deleteBySessionId(sessionId);
        log.info("Removed {} URLs for session {}", removedCount, sessionId);

        return removedCount;
    }
}
