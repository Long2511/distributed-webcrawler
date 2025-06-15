package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for CrawledPageEntity to store crawled pages in PostgreSQL
 */
@Repository
public interface CrawledPageRepository extends JpaRepository<CrawledPageEntity, Long> {

    /**
     * Find a crawled page by its URL
     */
    Optional<CrawledPageEntity> findByUrl(String url);

    /**
     * Check if a URL has already been crawled
     */
    boolean existsByUrl(String url);

    /**
     * Count pages by domain
     */
    long countByDomain(String domain);
}
