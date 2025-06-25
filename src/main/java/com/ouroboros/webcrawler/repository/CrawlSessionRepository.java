package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for accessing crawl sessions
 */
@Repository
public interface CrawlSessionRepository extends MongoRepository<CrawlSessionEntity, String> {

    /**
     * Find sessions by status
     */
    List<CrawlSessionEntity> findByStatus(String status);

    /**
     * Find sessions created after a certain date
     */
    List<CrawlSessionEntity> findByCreatedAtAfter(LocalDateTime date);

    /**
     * Find sessions by name (partial match)
     */
    List<CrawlSessionEntity> findByNameContaining(String namePattern);

    /**
     * Count sessions with a specific status
     */
    long countByStatus(String status);
}
