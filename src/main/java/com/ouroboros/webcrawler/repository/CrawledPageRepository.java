package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository interface for CrawledPageEntity
 * Provides data access methods for crawled page management
 */
@Repository
public interface CrawledPageRepository extends MongoRepository<CrawledPageEntity, String> {

    /**
     * Find all pages for a specific session with pagination
     */
    List<CrawledPageEntity> findBySessionId(String sessionId, Pageable pageable);

    /**
     * Find all pages for a specific session
     */
    List<CrawledPageEntity> findBySessionId(String sessionId);

    /**
     * Delete all pages for a specific session
     */
    void deleteBySessionId(String sessionId);

    /**
     * Count pages by session ID
     */
    long countBySessionId(String sessionId);

    /**
     * Find pages by status code
     */
    List<CrawledPageEntity> findByStatusCode(int statusCode);

    /**
     * Find pages by session ID and status code
     */
    List<CrawledPageEntity> findBySessionIdAndStatusCode(String sessionId, int statusCode);

    /**
     * Check if a URL was already crawled in a specific session
     * Used for deduplication to prevent crawling the same URL multiple times
     */
    boolean existsByUrlAndSessionId(String url, String sessionId);

    /**
     * Find a page by URL and session ID
     */
    CrawledPageEntity findByUrlAndSessionId(String url, String sessionId);
}
