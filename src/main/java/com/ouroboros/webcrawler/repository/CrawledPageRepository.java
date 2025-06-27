package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import lombok.Getter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for accessing crawled pages
 */
@Repository
public interface CrawledPageRepository extends MongoRepository<CrawledPageEntity, String> {

    /**
     * Find a page by its URL
     */
    Optional<CrawledPageEntity> findByUrl(String url);

    /**
     * Find pages by domain
     */
    List<CrawledPageEntity> findByDomain(String domain, Pageable pageable);

    /**
     * Find pages by session ID
     */
    List<CrawledPageEntity> findBySessionId(String sessionId, Pageable pageable);

    /**
     * Count pages by session ID
     */
    long countBySessionId(String sessionId);

    /**
     * Count pages by domain
     */
    long countByDomain(String domain);

    /**
     * Search for pages containing specific text in content
     */
    @Query("{ 'content': { '$regex': ?0, '$options': 'i' }}")
    List<CrawledPageEntity> searchByContent(String searchText, Pageable pageable);

    /**
     * Find pages that were crawled in a specific time range
     */
    List<CrawledPageEntity> findByCrawlTimestampBetween(
        LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * Find pages with specific content type
     */
    List<CrawledPageEntity> findByContentTypeContaining(String contentType, Pageable pageable);

    /**
     * Find pages by status code
     */
    List<CrawledPageEntity> findByStatusCode(int statusCode, Pageable pageable);

    /**
     * Find pages by crawl depth
     */
    List<CrawledPageEntity> findByCrawlDepth(int depth, Pageable pageable);

    /**
     * Get domain statistics - count of pages per domain
     */
    @Aggregation(pipeline = {
        "{ $group: { _id: '$domain', count: { $sum: 1 } } }",
        "{ $sort: { count: -1 } }",
        "{ $limit: ?0 }"
    })
    List<DomainStats> getDomainStatistics(int limit);

    /**
     * Class to hold domain statistics result
     */
    class DomainStats {
        private String _id; // domain name
        @Getter
        private long count;

        public String getDomain() {
            return _id;
        }

    }
}
