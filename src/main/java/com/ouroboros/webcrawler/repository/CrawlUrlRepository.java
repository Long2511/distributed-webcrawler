package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CrawlUrlRepository extends MongoRepository<CrawlUrl, String> {

    /**
     * Check if a URL exists in the repository
     */
    boolean existsByUrl(String url);

    /**
     * Check if a URL exists in a specific session
     */
    boolean existsByUrlAndSessionId(String url, String sessionId);

    /**
     * Find a URL by its string value
     * Changed to return List instead of Optional to handle duplicate URLs
     */
    List<CrawlUrl> findByUrl(String url);

    /**
     * Find a URL by its string value and session ID
     * This ensures we get the correct URL for a specific session
     */
    Optional<CrawlUrl> findByUrlAndSessionId(String url, String sessionId);

    /**
     * Count URLs with a specific status
     */
    long countByStatus(String status);

    /**
     * Find URLs for a specific session
     */
    List<CrawlUrl> findBySessionId(String sessionId);

    /**
     * Find URLs with specific status and null processing instance
     * For distributed processing - finds unassigned URLs
     */
    List<CrawlUrl> findByStatusAndProcessingInstanceIsNull(String status, Pageable pageable);

    /**
     * Find URLs with RETRY status, ready for retry and null processing instance
     * For distributed processing - finds unassigned retry URLs
     */
    List<CrawlUrl> findByStatusAndNextRetryAtBeforeAndProcessingInstanceIsNull(
            String status, LocalDateTime now, Pageable pageable);

    /**
     * Find URLs with specific status and processing instance not in the given list
     * For distributed processing - finds URLs assigned to inactive instances
     */
    List<CrawlUrl> findByStatusAndProcessingInstanceNotIn(
            String status, List<String> activeInstanceIds, Pageable pageable);

    /**
     * Delete URLs for a specific session
     * Returns the number of documents deleted
     */
    long deleteBySessionId(String sessionId);

    /**
     * Find URLs for a specific session with a specific status
     */
    List<CrawlUrl> findBySessionIdAndStatus(String sessionId, String status);

    /**
     * Find URLs for a specific domain with a specific status
     */
    List<CrawlUrl> findByDomainAndStatus(String domain, String status, Pageable pageable);

    /**
     * Find URLs that are ready to be crawled (either QUEUED or RETRY with nextRetryAt in the past)
     * Order by priority (ascending - lower number means higher priority)
     */
    @Query("{ '$or': [ { 'status': 'QUEUED' }, { 'status': 'RETRY', 'nextRetryAt': { '$lte': ?0 } } ] }")
    List<CrawlUrl> findUrlsForCrawling(LocalDateTime now, Pageable pageable);
}
