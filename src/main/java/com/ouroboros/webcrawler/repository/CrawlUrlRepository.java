package com.ouroboros.webcrawler.repository;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public class CrawlUrlRepository {

    private static final Logger log = LoggerFactory.getLogger(CrawlUrlRepository.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String URL_QUEUE_KEY_PREFIX = "url_queue:";
    
    private String getQueueKey(String sessionId) {
        return URL_QUEUE_KEY_PREFIX + sessionId;
    }

    public void save(CrawlUrl crawlUrl) {
        String queueKey = getQueueKey(crawlUrl.getSessionId());
        redisTemplate.opsForZSet().add(queueKey, crawlUrl, crawlUrl.getPriority());
    }

    public Set<Object> getTopUrls(String sessionId, int count) {
        String queueKey = getQueueKey(sessionId);
        log.debug("Getting top {} URLs from queue key: {}", count, queueKey);
        
        Set<Object> result = redisTemplate.opsForZSet().reverseRange(queueKey, 0, count - 1);
        log.debug("Retrieved {} objects from Redis queue", result != null ? result.size() : 0);
        
        if (result != null && !result.isEmpty()) {
            log.debug("First object type: {}", result.iterator().next().getClass().getSimpleName());
        }
        
        return result;
    }

    public void remove(CrawlUrl crawlUrl) {
        String queueKey = getQueueKey(crawlUrl.getSessionId());
        redisTemplate.opsForZSet().remove(queueKey, crawlUrl);
    }

    public long count() {
        // For legacy compatibility, return total count across all sessions
        Set<String> keys = redisTemplate.keys(URL_QUEUE_KEY_PREFIX + "*");
        long totalCount = 0;
        if (keys != null) {
            for (String key : keys) {
                Long count = redisTemplate.opsForZSet().count(key, 0, Double.MAX_VALUE);
                totalCount += (count != null ? count : 0L);
            }
        }
        return totalCount;
    }
    
    public long count(String sessionId) {
        String queueKey = getQueueKey(sessionId);
        Long count = redisTemplate.opsForZSet().count(queueKey, 0, Double.MAX_VALUE);
        return count != null ? count : 0L;
    }
}
