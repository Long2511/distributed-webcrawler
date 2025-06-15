package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for CrawlUrl entity
 */
public interface CrawlUrlRepository extends JpaRepository<CrawlUrl, Long>, JpaSpecificationExecutor<CrawlUrl> {

    /**
     * Find if a URL exists in the system
     */
    boolean existsByUrl(String url);

    /**
     * Find a CrawlUrl by its URL
     */
    Optional<CrawlUrl> findByUrl(String url);

    /**
     * Find the next available URL to crawl with the highest priority
     */
    @Query("SELECT c FROM CrawlUrl c WHERE c.status = 'QUEUED' AND c.priority = :priority ORDER BY c.createdAt ASC LIMIT 1")
    Optional<CrawlUrl> findNextUrlToCrawl(@Param("priority") int priority);

    /**
     * Count URLs by status
     */
    long countByStatus(String status);

    /**
     * Find URLs in processing state that have not been updated for a while
     */
    @Query("SELECT c FROM CrawlUrl c WHERE c.status = 'PROCESSING' AND c.updatedAt < :cutoffTime")
    List<CrawlUrl> findStalledJobs(@Param("cutoffTime") java.time.LocalDateTime cutoffTime);
}
