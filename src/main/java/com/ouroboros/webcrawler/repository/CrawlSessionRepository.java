package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for CrawlSessionEntity to store crawl sessions in PostgreSQL
 */
@Repository
public interface CrawlSessionRepository extends JpaRepository<CrawlSessionEntity, String> {

    /**
     * Find sessions by status
     */
    List<CrawlSessionEntity> findByStatus(String status);

}
