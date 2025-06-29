package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository interface for CrawlUrl entities
 * Provides data access methods for URL frontier management
 */
@Repository
public interface CrawlUrlRepository extends MongoRepository<CrawlUrl, String> {

    /**
     * Find URLs by status with pagination and ordering
     */
    @Query("{'status': ?0}")
    List<CrawlUrl> findByStatusOrderByPriorityDescDiscoveredAtAsc(String status, Pageable pageable);

    /**
     * Find a specific URL by URL and session ID
     */
    CrawlUrl findByUrlAndSessionId(String url, String sessionId);

    /**
     * Count URLs by session ID and status
     */
    int countBySessionIdAndStatus(String sessionId, String status);

    /**
     * Find all URLs for a specific session
     */
    List<CrawlUrl> findBySessionId(String sessionId);

    /**
     * Delete all URLs for a specific session
     */
    void deleteBySessionId(String sessionId);

    /**
     * Find distinct session IDs (for active sessions)
     */
    @Query(value = "{}", fields = "{'sessionId': 1}")
    List<String> findDistinctSessionIds();

    /**
     * Find URLs by session ID and status
     */
    List<CrawlUrl> findBySessionIdAndStatus(String sessionId, String status);

    /**
     * Find pending URLs for a specific session ordered by priority
     */
    @Query("{'sessionId': ?0, 'status': 'PENDING'}")
    List<CrawlUrl> findPendingUrlsBySessionIdOrderByPriorityDesc(String sessionId, Pageable pageable);

    /**
     * Count total URLs for a session
     */
    long countBySessionId(String sessionId);

    /**
     * Find URLs by status
     */
    List<CrawlUrl> findByStatus(String status);

    /**
     * Find URLs assigned to a specific worker
     */
    List<CrawlUrl> findByAssignedWorker(String workerId);

    /**
     * Find URLs by session ID and multiple statuses
     */
    List<CrawlUrl> findBySessionIdAndStatusIn(String sessionId, List<String> statuses);

    /**
     * Count URLs by status (across all sessions)
     */
    int countByStatus(String status);

    /**
     * Get distinct session IDs that have pending URLs
     */
    @Query(value = "{'status': 'PENDING'}", fields = "{'sessionId': 1}")
    List<String> findDistinctSessionIdsByStatus(String status);
}
