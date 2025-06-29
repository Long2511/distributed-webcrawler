package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CrawledPageRepository extends MongoRepository<CrawledPageEntity, String> {

    List<CrawledPageEntity> findBySessionId(String sessionId);

    Page<CrawledPageEntity> findBySessionId(String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);

    long countBySessionIdAndStatusCode(String sessionId, int statusCode);

    @Query("{ 'sessionId': ?0, 'crawlTime': { $gte: ?1, $lte: ?2 } }")
    List<CrawledPageEntity> findBySessionIdAndCrawlTimeBetween(String sessionId, LocalDateTime start, LocalDateTime end);

    @Query(value = "{ 'sessionId': ?0 }", fields = "{ 'url': 1, 'statusCode': 1, 'contentLength': 1 }")
    List<DomainStats> getStatsForSession(String sessionId);

    void deleteBySessionId(String sessionId);

    interface DomainStats {
        String getUrl();
        int getStatusCode();
        long getContentLength();
    }
}
