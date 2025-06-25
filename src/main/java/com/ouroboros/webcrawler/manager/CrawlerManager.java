package com.ouroboros.webcrawler.manager;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.frontier.FrontierStats;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.model.CrawlSession;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Central coordination unit for the distributed crawler system
 */
@Service
@Slf4j
public class CrawlerManager {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlSessionRepository sessionRepository;

    // Used internally for kafka message publishing - keep this field
    @Autowired
    @SuppressWarnings("unused")
    private KafkaTemplate<String, CrawlJob> kafkaTemplate;

    private final Map<String, CrawlSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AtomicLong>> sessionStats = new ConcurrentHashMap<>();

    @Value("${webcrawler.batch.size:50}")
    private int batchSize;

    @Value("${webcrawler.max-depth:10}")
    private int defaultMaxDepth;

    /**
     * Start a new crawling session
     * Used by CrawlerController and DashboardController
     */
    @Transactional
    @SuppressWarnings("unused")
    public CrawlSession startCrawlSession(CrawlSession session) {
        // Generate ID if not provided
        if (session.getId() == null) {
            session.setId(UUID.randomUUID().toString());
        }

        session.setStatus("RUNNING");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        // Set default values if not provided
        if (session.getMaxDepth() <= 0) {
            session.setMaxDepth(defaultMaxDepth);
        }

        // Save session to database
        CrawlSessionEntity sessionEntity = mapToEntity(session);
        sessionRepository.save(sessionEntity);

        // Initialize session statistics
        Map<String, AtomicLong> stats = new HashMap<>();
        stats.put("discoveredUrls", new AtomicLong(0));
        stats.put("crawledPages", new AtomicLong(0));
        stats.put("failedUrls", new AtomicLong(0));
        stats.put("totalBytes", new AtomicLong(0));
        sessionStats.put(session.getId(), stats);

        // Add to active sessions
        activeSessions.put(session.getId(), session);

        // Add seed URLs to frontier
        for (String seedUrl : session.getSeedUrls()) {
            urlFrontier.addUrl(seedUrl, 1000, 0, session.getId());
            stats.get("discoveredUrls").incrementAndGet();
        }

        log.info("Started crawl session: {} with {} seed URLs", session.getId(), session.getSeedUrls().size());
        return session;
    }

    /**
     * Stop an active crawl session
     * Used by CrawlerController and DashboardController
     */
    @Transactional
    @SuppressWarnings("unused")
    public CrawlSession stopCrawlSession(String sessionId) {
        CrawlSession session = activeSessions.get(sessionId);
        if (session != null) {
            // Update session status
            session.setStatus("STOPPED");
            session.setUpdatedAt(LocalDateTime.now());

            CrawlSessionEntity sessionEntity = sessionRepository.findById(sessionId).orElse(null);
            if (sessionEntity != null) {
                sessionEntity.setStatus("STOPPED");
                sessionEntity.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(sessionEntity);
            }

            // Stop processing URLs for this session
            // This is similar to delete but doesn't remove the data
            urlFrontier.stopSessionUrls(sessionId);

            // Remove from active sessions
            activeSessions.remove(sessionId);
            log.info("Stopped crawl session: {}", sessionId);
        }
        return session;
    }

    /**
     * Pause an active crawl session
     * Used by SessionsApiController
     */
    @Transactional
    public CrawlSession pauseSession(String sessionId) {
        log.info("Pausing crawl session: {}", sessionId);
        CrawlSession session = activeSessions.get(sessionId);
        if (session != null && "RUNNING".equals(session.getStatus())) {
            // Update status
            session.setStatus("PAUSED");
            session.setUpdatedAt(LocalDateTime.now());

            CrawlSessionEntity sessionEntity = sessionRepository.findById(sessionId).orElse(null);
            if (sessionEntity != null) {
                sessionEntity.setStatus("PAUSED");
                sessionEntity.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(sessionEntity);
            }

            // Mark all PROCESSING URLs for this session as QUEUED to stop their processing
            urlFrontier.pauseSessionUrls(sessionId);

            log.info("Paused crawl session: {}", sessionId);
        } else {
            log.warn("Cannot pause session {}: not found or not in RUNNING state", sessionId);
        }
        return session;
    }

    /**
     * Resume a paused crawl session
     * Used by SessionsApiController
     */
    @Transactional
    public CrawlSession resumeSession(String sessionId) {
        log.info("Resuming crawl session: {}", sessionId);

        // First check if it's already in active sessions
        CrawlSession session = activeSessions.get(sessionId);

        // If not in active sessions, try to get it from the database
        if (session == null) {
            Optional<CrawlSessionEntity> sessionEntityOpt = sessionRepository.findById(sessionId);
            if (sessionEntityOpt.isPresent()) {
                session = mapFromEntity(sessionEntityOpt.get());

                // Initialize session statistics if needed
                if (!sessionStats.containsKey(sessionId)) {
                    Map<String, AtomicLong> stats = new HashMap<>();
                    stats.put("discoveredUrls", new AtomicLong(0));
                    stats.put("crawledPages", new AtomicLong(0));
                    stats.put("failedUrls", new AtomicLong(0));
                    stats.put("totalBytes", new AtomicLong(0));
                    sessionStats.put(sessionId, stats);
                }

                activeSessions.put(sessionId, session);
            }
        }

        // If we found the session, change its status to RUNNING
        if (session != null && "PAUSED".equals(session.getStatus())) {
            session.setStatus("RUNNING");
            session.setUpdatedAt(LocalDateTime.now());

            CrawlSessionEntity sessionEntity = sessionRepository.findById(sessionId).orElse(null);
            if (sessionEntity != null) {
                sessionEntity.setStatus("RUNNING");
                sessionEntity.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(sessionEntity);
            }

            log.info("Resumed crawl session: {}", sessionId);
        } else {
            log.warn("Cannot resume session {}: not found or not in PAUSED state", sessionId);
        }

        return session;
    }

    /**
     * Delete a crawl session
     * Used by SessionsApiController
     */
    @Transactional
    public void deleteSession(String sessionId) {
        log.info("Deleting crawl session: {}", sessionId);

        // First remove from active sessions if present
        activeSessions.remove(sessionId);

        // Also remove from session stats
        sessionStats.remove(sessionId);

        // Then delete from the database
        sessionRepository.deleteById(sessionId);

        // Also try to remove any URLs for this session from the frontier
        urlFrontier.removeSessionUrls(sessionId);

        log.info("Deleted crawl session: {}", sessionId);
    }

    /**
     * Get all crawl sessions
     * Used by CrawlerController and DashboardController
     */
    @SuppressWarnings("unused")
    public List<CrawlSession> getAllSessions() {
        List<CrawlSessionEntity> sessionEntities = sessionRepository.findAll();
        return sessionEntities.stream()
                .map(this::mapFromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get active crawl sessions
     * Used by DashboardController
     */
    @SuppressWarnings("unused")
    public List<CrawlSession> getActiveSessions() {
        return new ArrayList<>(activeSessions.values());
    }

    /**
     * Get a specific crawl session by ID
     * Used by CrawlerController and DashboardController
     */
    @SuppressWarnings("unused")
    public Optional<CrawlSession> getSession(String sessionId) {
        // First check active sessions
        CrawlSession activeSession = activeSessions.get(sessionId);
        if (activeSession != null) {
            return Optional.of(activeSession);
        }

        // Then check database
        return sessionRepository.findById(sessionId)
                .map(this::mapFromEntity);
    }

    /**
     * Update crawl statistics for a session
     * Used by CrawlerWorker
     */
    @SuppressWarnings("unused")
    public void updateCrawlStats(String sessionId, int newUrls, int crawledPages) {
        Map<String, AtomicLong> stats = sessionStats.get(sessionId);
        if (stats != null) {
            stats.get("discoveredUrls").addAndGet(newUrls);
            stats.get("crawledPages").addAndGet(crawledPages);
        }
    }

    /**
     * Get crawl statistics for a session
     * Used by CrawlerController and DashboardController
     */
    @SuppressWarnings("unused")
    public Map<String, Long> getSessionStats(String sessionId) {
        Map<String, AtomicLong> stats = sessionStats.get(sessionId);
        if (stats != null) {
            Map<String, Long> result = new HashMap<>();
            stats.forEach((key, value) -> result.put(key, value.get()));
            return result;
        }
        return Collections.emptyMap();
    }

    /**
     * Schedule the next batch of URLs for crawling
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduleCrawlBatch() {
        // Only schedule if there are active sessions
        if (!activeSessions.isEmpty()) {
            urlFrontier.scheduleNextBatch(batchSize);
        }
    }

    /**
     * Check for completed sessions
     */
    @Scheduled(fixedDelay = 30000)
    public void checkCompletedSessions() {
        // Get frontier stats
        FrontierStats stats = urlFrontier.getStats();

        // Session is complete if no URLs are queued or processing
        for (String sessionId : new ArrayList<>(activeSessions.keySet())) {
            CrawlSession session = activeSessions.get(sessionId);
            if (session != null && "RUNNING".equals(session.getStatus())) {
                // For now, we consider a session complete if there's been no activity for a while
                // In a real system, you'd track URLs per session more precisely
                if (stats.getQueuedUrls() == 0 && stats.getProcessingUrls() == 0) {
                    session.setStatus("COMPLETED");
                    session.setUpdatedAt(LocalDateTime.now());
                    session.setCompletedAt(LocalDateTime.now());

                    CrawlSessionEntity sessionEntity = sessionRepository.findById(sessionId).orElse(null);
                    if (sessionEntity != null) {
                        sessionEntity.setStatus("COMPLETED");
                        sessionEntity.setUpdatedAt(LocalDateTime.now());
                        sessionEntity.setCompletedAt(LocalDateTime.now());
                        sessionRepository.save(sessionEntity);
                    }

                    activeSessions.remove(sessionId);
                    log.info("Completed crawl session: {}", sessionId);
                }
            }
        }
    }

    /**
     * Map from CrawlSessionEntity to CrawlSession
     */
    private CrawlSession mapFromEntity(CrawlSessionEntity entity) {
        if (entity == null) {
            return null;
        }

        return CrawlSession.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .seedUrls(entity.getSeedUrls() != null ? entity.getSeedUrls() : new HashSet<>())
                .maxDepth(entity.getMaxDepth())
                .maxPagesPerDomain(entity.getMaxPagesPerDomain())
                .respectRobotsTxt(entity.isRespectRobotsTxt())
                .customHeaders(entity.getCustomHeaders())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .completedAt(entity.getCompletedAt())
                .includePatterns(entity.getIncludePatterns())
                .excludePatterns(entity.getExcludePatterns())
                .build();
    }

    /**
     * Map from CrawlSession to CrawlSessionEntity
     */
    private CrawlSessionEntity mapToEntity(CrawlSession session) {
        return CrawlSessionEntity.builder()
                .id(session.getId())
                .name(session.getName())
                .description(session.getDescription())
                .seedUrls(new HashSet<>(session.getSeedUrls() != null ? session.getSeedUrls() : Collections.emptySet()))
                .maxDepth(session.getMaxDepth())
                .maxPagesPerDomain(session.getMaxPagesPerDomain())
                .respectRobotsTxt(session.isRespectRobotsTxt())
                .customHeaders(session.getCustomHeaders())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .completedAt(session.getCompletedAt())
                .includePatterns(session.getIncludePatterns())
                .excludePatterns(session.getExcludePatterns())
                .build();
    }
}
