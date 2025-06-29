package com.ouroboros.webcrawler.frontier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private ObjectMapper objectMapper;

    private final String URL_QUEUE_KEY_PREFIX = "url_queue:";
    private final String URL_VISITED_KEY_PREFIX = "url_visited:";
    private final String URL_PROCESSING_KEY_PREFIX = "url_processing:";

    private boolean redisAvailable = false;

    @PostConstruct
    public void initialize() {
        try {
            redisTemplate.opsForValue().get("test:connection");
            redisAvailable = true;
            log.info("URLFrontier: Redis connection successful");
        } catch (Exception e) {
            log.warn("URLFrontier: Redis not available, using MongoDB only: {}", e.getMessage());
            redisAvailable = false;
        }
    }

    private String getQueueKey(String sessionId) {
        return URL_QUEUE_KEY_PREFIX + sessionId;
    }

    private String getVisitedKey(String sessionId) {
        return URL_VISITED_KEY_PREFIX + sessionId;
    }

    private String getProcessingKey(String sessionId) {
        return URL_PROCESSING_KEY_PREFIX + sessionId;
    }

    /**
     * Add URL to frontier with priority-based queuing
     */
    public void addUrl(CrawlUrl crawlUrl) {
        try {
            // Always save to MongoDB for persistence
            crawlUrl.setAddedAt(LocalDateTime.now());
            crawlUrl.setStatus("PENDING");
            crawlUrlRepository.save(crawlUrl);

            if (redisAvailable) {
                // Add to Redis priority queue using sorted sets
                String queueKey = getQueueKey(crawlUrl.getSessionId());
                String visitedKey = getVisitedKey(crawlUrl.getSessionId());

                // Check if URL was already visited
                Boolean alreadyVisited = redisTemplate.opsForSet().isMember(visitedKey, crawlUrl.getUrl());
                if (Boolean.TRUE.equals(alreadyVisited)) {
                    log.debug("URL already visited, skipping: {}", crawlUrl.getUrl());
                    return;
                }

                // Add to priority queue with score based on priority
                String urlJson = objectMapper.writeValueAsString(crawlUrl);
                redisTemplate.opsForZSet().add(queueKey, urlJson, crawlUrl.getPriority());

                log.debug("Added URL to Redis frontier: {} with priority: {}",
                    crawlUrl.getUrl(), crawlUrl.getPriority());
            }
        } catch (Exception e) {
            log.error("Error adding URL to frontier: {}", crawlUrl.getUrl(), e);
        }
    }

    /**
     * Get next URLs for processing from frontier
     */
    public List<CrawlUrl> getNextUrls(String workerId, int batchSize) {
        List<CrawlUrl> urls = new ArrayList<>();

        if (redisAvailable) {
            urls = getUrlsFromRedis(workerId, batchSize);
        }

        // Fallback to MongoDB if Redis not available or no URLs found
        if (urls.isEmpty()) {
            urls = getUrlsFromMongoDB(workerId, batchSize);
        }

        return urls;
    }

    /**
     * Get URLs from Redis sorted sets (priority-based)
     */
    private List<CrawlUrl> getUrlsFromRedis(String workerId, int batchSize) {
        List<CrawlUrl> urls = new ArrayList<>();

        try {
            // Get all active sessions
            List<String> sessionIds = crawlUrlRepository.findDistinctSessionIds();

            for (String sessionId : sessionIds) {
                if (urls.size() >= batchSize) break;

                String queueKey = getQueueKey(sessionId);
                String processingKey = getProcessingKey(sessionId);

                // Get highest priority URLs (reverse order - highest score first)
                Set<Object> highPriorityUrls = redisTemplate.opsForZSet()
                    .reverseRange(queueKey, 0, batchSize - urls.size() - 1);

                if (highPriorityUrls != null) {
                    for (Object urlObj : highPriorityUrls) {
                        try {
                            CrawlUrl crawlUrl = objectMapper.readValue(urlObj.toString(), CrawlUrl.class);

                            // Move URL from queue to processing
                            redisTemplate.opsForZSet().remove(queueKey, urlObj);
                            redisTemplate.opsForSet().add(processingKey, crawlUrl.getUrl());

                            // Update status in MongoDB
                            crawlUrl.setStatus("PROCESSING");
                            crawlUrl.setAssignedWorker(workerId);
                            crawlUrl.setAssignedAt(LocalDateTime.now());
                            crawlUrlRepository.save(crawlUrl);

                            urls.add(crawlUrl);

                            if (urls.size() >= batchSize) break;
                        } catch (Exception e) {
                            log.error("Error parsing URL from Redis: {}", urlObj, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error getting URLs from Redis frontier", e);
        }

        return urls;
    }

    /**
     * Get URLs from MongoDB (fallback)
     */
    private List<CrawlUrl> getUrlsFromMongoDB(String workerId, int batchSize) {
        List<CrawlUrl> urls = new ArrayList<>();

        try {
            // Find pending URLs ordered by priority
            List<CrawlUrl> pendingUrls = crawlUrlRepository
                .findByStatusOrderByPriorityDescDiscoveredAtAsc("PENDING",
                    org.springframework.data.domain.PageRequest.of(0, batchSize));

            for (CrawlUrl crawlUrl : pendingUrls) {
                crawlUrl.setStatus("PROCESSING");
                crawlUrl.setAssignedWorker(workerId);
                crawlUrl.setAssignedAt(LocalDateTime.now());
                crawlUrlRepository.save(crawlUrl);
                urls.add(crawlUrl);
            }

            log.debug("Retrieved {} URLs from MongoDB frontier", urls.size());
        } catch (Exception e) {
            log.error("Error getting URLs from MongoDB frontier", e);
        }

        return urls;
    }

    /**
     * Mark URL as completed
     */
    public void markCompleted(String url, String sessionId) {
        try {
            // Update in MongoDB
            CrawlUrl crawlUrl = crawlUrlRepository.findByUrlAndSessionId(url, sessionId);
            if (crawlUrl != null) {
                crawlUrl.setStatus("COMPLETED");
                crawlUrl.setCompletedAt(LocalDateTime.now());
                crawlUrlRepository.save(crawlUrl);
            }

            if (redisAvailable) {
                // Add to visited set and remove from processing
                String visitedKey = getVisitedKey(sessionId);
                String processingKey = getProcessingKey(sessionId);

                redisTemplate.opsForSet().add(visitedKey, url);
                redisTemplate.opsForSet().remove(processingKey, url);
            }

            log.debug("Marked URL as completed: {}", url);
        } catch (Exception e) {
            log.error("Error marking URL as completed: {}", url, e);
        }
    }

    /**
     * Mark URL as failed
     */
    public void markFailed(String url, String errorMessage, String sessionId) {
        try {
            // Update in MongoDB
            CrawlUrl crawlUrl = crawlUrlRepository.findByUrlAndSessionId(url, sessionId);
            if (crawlUrl != null) {
                crawlUrl.setStatus("FAILED");
                crawlUrl.setErrorMessage(errorMessage);
                crawlUrl.setCompletedAt(LocalDateTime.now());
                crawlUrlRepository.save(crawlUrl);
            }

            if (redisAvailable) {
                // Remove from processing
                String processingKey = getProcessingKey(sessionId);
                redisTemplate.opsForSet().remove(processingKey, url);
            }

            log.debug("Marked URL as failed: {} - {}", url, errorMessage);
        } catch (Exception e) {
            log.error("Error marking URL as failed: {}", url, e);
        }
    }

    /**
     * Clear all data for a session (used when deleting session)
     */
    public void clearSession(String sessionId) {
        try {
            if (redisAvailable) {
                String queueKey = getQueueKey(sessionId);
                String visitedKey = getVisitedKey(sessionId);
                String processingKey = getProcessingKey(sessionId);

                redisTemplate.delete(queueKey);
                redisTemplate.delete(visitedKey);
                redisTemplate.delete(processingKey);

                log.info("Cleared Redis frontier data for session: {}", sessionId);
            }

            // Note: MongoDB cleanup is handled by the CrawlerManager
            log.info("Cleared frontier data for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error clearing frontier data for session: {}", sessionId, e);
        }
    }

    /**
     * Get frontier statistics for a session
     */
    public FrontierStats getStats(String sessionId) {
        FrontierStats stats = FrontierStats.builder()
            .sessionId(sessionId)
            .timestamp(System.currentTimeMillis())
            .build();

        try {
            if (redisAvailable) {
                String queueKey = getQueueKey(sessionId);
                String visitedKey = getVisitedKey(sessionId);
                String processingKey = getProcessingKey(sessionId);

                Long queueSize = redisTemplate.opsForZSet().count(queueKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                Long visitedSize = redisTemplate.opsForSet().size(visitedKey);
                Long processingSize = redisTemplate.opsForSet().size(processingKey);

                stats.setPendingUrls(queueSize != null ? queueSize.intValue() : 0);
                stats.setVisitedUrls(visitedSize != null ? visitedSize.intValue() : 0);
                stats.setProcessingUrls(processingSize != null ? processingSize.intValue() : 0);
            } else {
                // Fallback to MongoDB
                int pendingCount = crawlUrlRepository.countBySessionIdAndStatus(sessionId, "PENDING");
                int completedCount = crawlUrlRepository.countBySessionIdAndStatus(sessionId, "COMPLETED");
                int processingCount = crawlUrlRepository.countBySessionIdAndStatus(sessionId, "PROCESSING");
                int failedCount = crawlUrlRepository.countBySessionIdAndStatus(sessionId, "FAILED");

                stats.setPendingUrls(pendingCount);
                stats.setCompletedUrls(completedCount);
                stats.setFailedUrls(failedCount);
                stats.setVisitedUrls(completedCount + failedCount);
                stats.setProcessingUrls(processingCount);
            }

            // Calculate total URLs and crawl rate
            stats.setTotalUrls(stats.getPendingUrls() + stats.getVisitedUrls() + stats.getProcessingUrls());
            stats.setCrawlRate(0.0); // Placeholder - would need historical data to calculate actual rate

        } catch (Exception e) {
            log.error("Error getting frontier stats for session: {}", sessionId, e);
        }

        return stats;
    }

    /**
     * Get overall frontier statistics (aggregated across all sessions)
     */
    public FrontierStats getStats() {
        FrontierStats aggregatedStats = FrontierStats.builder()
            .sessionId("ALL")
            .timestamp(System.currentTimeMillis())
            .build();

        try {
            if (redisAvailable) {
                // Get all session IDs and aggregate stats
                List<String> sessionIds = crawlUrlRepository.findDistinctSessionIds();
                int totalPending = 0, totalVisited = 0, totalProcessing = 0;

                for (String sessionId : sessionIds) {
                    FrontierStats sessionStats = getStats(sessionId);
                    totalPending += sessionStats.getPendingUrls();
                    totalVisited += sessionStats.getVisitedUrls();
                    totalProcessing += sessionStats.getProcessingUrls();
                }

                aggregatedStats.setPendingUrls(totalPending);
                aggregatedStats.setVisitedUrls(totalVisited);
                aggregatedStats.setProcessingUrls(totalProcessing);
            } else {
                // Fallback to MongoDB aggregation
                int pendingCount = crawlUrlRepository.countByStatus("PENDING");
                int completedCount = crawlUrlRepository.countByStatus("COMPLETED");
                int processingCount = crawlUrlRepository.countByStatus("PROCESSING");
                int failedCount = crawlUrlRepository.countByStatus("FAILED");

                aggregatedStats.setPendingUrls(pendingCount);
                aggregatedStats.setCompletedUrls(completedCount);
                aggregatedStats.setFailedUrls(failedCount);
                aggregatedStats.setVisitedUrls(completedCount + failedCount);
                aggregatedStats.setProcessingUrls(processingCount);
            }

            // Calculate totals
            aggregatedStats.setTotalUrls(
                aggregatedStats.getPendingUrls() +
                aggregatedStats.getVisitedUrls() +
                aggregatedStats.getProcessingUrls()
            );
            aggregatedStats.setCrawlRate(0.0); // Placeholder

        } catch (Exception e) {
            log.error("Error getting aggregated frontier stats", e);
        }

        return aggregatedStats;
    }

    /**
     * Get pending URL count (for backward compatibility)
     */
    public int getPendingUrlCount() {
        try {
            FrontierStats stats = getStats();
            return stats.getPendingUrls();
        } catch (Exception e) {
            log.error("Error getting pending URL count", e);
            return 0;
        }
    }

    /**
     * Check if Redis is available
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }
}
