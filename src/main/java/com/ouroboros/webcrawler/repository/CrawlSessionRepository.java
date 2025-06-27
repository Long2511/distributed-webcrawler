package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CrawlSessionRepository extends MongoRepository<CrawlSessionEntity, String> {

    List<CrawlSessionEntity> findByStatus(String status);

    List<CrawlSessionEntity> findByStatusIn(List<String> statuses);

    List<CrawlSessionEntity> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    List<CrawlSessionEntity> findAllByOrderByCreatedAtDesc();
}
