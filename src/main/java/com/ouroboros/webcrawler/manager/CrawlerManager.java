package com.ouroboros.webcrawler.manager;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.model.CrawlSession;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central coordination unit for the distributed crawler system
 */
@Service
@Slf4j
public class CrawlerManager {

    private static final String CRAWL_TASK_TOPIC = "webcrawler.tasks";

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlSessionRepository sessionRepository;

    @Autowired
    private KafkaTemplate<String, CrawlJob> kafkaTemplate;

    private final Map<String, CrawlSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Start a new crawling session
     */
    @Transactional
    public CrawlSession startCrawlSession(CrawlSession session) {
        // Generate ID if not provided
        if (session.getId() == null) {
            session.setId(UUID.randomUUID().toString());
        }

        session.setStatus("RUNNING");
        session.setCreatedAt(LocalDateTime.now());
        session.setStartedAt(LocalDateTime.now());
        session.setTotalPagesCrawled(0);
        session.setTotalUrlsDiscovered(0);

        // Save session to PostgreSQL
        CrawlSessionEntity entity = convertToEntity(session);
        sessionRepository.save(entity);

        // Add to active sessions
        activeSessions.put(session.getId(), session);

        // Add seed URLs to frontier
        for (String seedUrl : session.getSeedUrls()) {
            urlFrontier.addUrl(seedUrl, 5, 0, session.getId());
        }

        log.info("Started crawl session: {} with {} seed URLs", session.getId(), session.getSeedUrls().size());

        return session;
    }

    /**
     * Pause a crawling session
     */
    @Transactional
    public CrawlSession pauseCrawlSession(String sessionId) {
        CrawlSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setStatus("PAUSED");

            // Update in PostgreSQL
            Optional<CrawlSessionEntity> entityOpt = sessionRepository.findById(sessionId);
            if (entityOpt.isPresent()) {
                CrawlSessionEntity entity = entityOpt.get();
                entity.setStatus("PAUSED");
                sessionRepository.save(entity);
            }

            log.info("Paused crawl session: {}", sessionId);
        } else {
            log.warn("Cannot pause session - not found: {}", sessionId);
        }
        return session;
    }

    /**
     * Resume a paused crawling session
     */
    @Transactional
    public CrawlSession resumeCrawlSession(String sessionId) {
        Optional<CrawlSessionEntity> entityOpt = sessionRepository.findById(sessionId);

        if (entityOpt.isPresent() && "PAUSED".equals(entityOpt.get().getStatus())) {
            CrawlSessionEntity entity = entityOpt.get();
            entity.setStatus("RUNNING");
            sessionRepository.save(entity);

            CrawlSession session = convertToModel(entity);
            activeSessions.put(sessionId, session);

            log.info("Resumed crawl session: {}", sessionId);
            return session;
        } else {
            log.warn("Cannot resume session - not found or not paused: {}", sessionId);
            return null;
        }
    }

    /**
     * Stop and finalize a crawling session
     */
    @Transactional
    public CrawlSession stopCrawlSession(String sessionId) {
        CrawlSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setStatus("COMPLETED");
            session.setCompletedAt(LocalDateTime.now());

            // Update in PostgreSQL
            Optional<CrawlSessionEntity> entityOpt = sessionRepository.findById(sessionId);
            if (entityOpt.isPresent()) {
                CrawlSessionEntity entity = entityOpt.get();
                entity.setStatus("COMPLETED");
                entity.setCompletedAt(LocalDateTime.now());
                sessionRepository.save(entity);
            }

            activeSessions.remove(sessionId);
            log.info("Stopped crawl session: {}", sessionId);
        } else {
            log.warn("Cannot stop session - not found: {}", sessionId);
        }
        return session;
    }

    /**
     * Get information about a crawling session
     */
    public Optional<CrawlSession> getCrawlSession(String sessionId) {
        // Try from active sessions first, then from database
        CrawlSession session = activeSessions.get(sessionId);
        if (session != null) {
            return Optional.of(session);
        }

        Optional<CrawlSessionEntity> entityOpt = sessionRepository.findById(sessionId);
        return entityOpt.map(this::convertToModel);
    }

    /**
     * Get all active crawling sessions
     */
    public List<CrawlSession> getActiveCrawlSessions() {
        return new ArrayList<>(activeSessions.values());
    }

    /**
     * Get all crawling sessions (including completed ones)
     */
    public List<CrawlSession> getAllCrawlSessions() {
        List<CrawlSessionEntity> entities = sessionRepository.findAll();
        return entities.stream().map(this::convertToModel).collect(Collectors.toList());
    }

    /**
     * Update crawl statistics for a session
     */
    @Transactional
    public void updateCrawlStats(String sessionId, int newPagesDiscovered, int pagesCrawled) {
        CrawlSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.setTotalUrlsDiscovered(session.getTotalUrlsDiscovered() + newPagesDiscovered);
            session.setTotalPagesCrawled(session.getTotalPagesCrawled() + pagesCrawled);

            // Update in PostgreSQL
            Optional<CrawlSessionEntity> entityOpt = sessionRepository.findById(sessionId);
            if (entityOpt.isPresent()) {
                CrawlSessionEntity entity = entityOpt.get();
                entity.setTotalUrlsDiscovered(entity.getTotalUrlsDiscovered() + newPagesDiscovered);
                entity.setTotalPagesCrawled(entity.getTotalPagesCrawled() + pagesCrawled);
                sessionRepository.save(entity);
            }
        }
    }

    /**
     * Scheduled task to distribute crawl tasks to workers
     */
    @Scheduled(fixedRate = 1000)
    public void distributeCrawlTasks() {
        // Only distribute if there are active sessions
        if (activeSessions.isEmpty()) {
            return;
        }

        // Get next job from frontier
        Optional<CrawlJob> nextJob = urlFrontier.getNextJob();

        if (nextJob.isPresent()) {
            CrawlJob job = nextJob.get();

            // Send job to workers via Kafka
            kafkaTemplate.send(CRAWL_TASK_TOPIC, job.getUrl(), job);

            log.debug("Distributed crawl task for URL: {}", job.getUrl());
        }
    }

    /**
     * Scheduled task to check and recover stalled jobs
     */
    @Scheduled(fixedRate = 60000)
    public void recoverStalledJobs() {
        urlFrontier.recoverStalledJobs();
    }

    /**
     * Convert a model object to an entity
     */
    private CrawlSessionEntity convertToEntity(CrawlSession model) {
        return CrawlSessionEntity.builder()
                .id(model.getId())
                .name(model.getName())
                .description(model.getDescription())
                .seedUrls(model.getSeedUrls())
                .maxDepth(model.getMaxDepth())
                .maxPagesPerDomain(model.getMaxPagesPerDomain())
                .respectRobotsTxt(model.isRespectRobotsTxt())
                .customHeaders(model.getCustomHeaders())
                .crawlDelay(model.getCrawlDelay())
                .status(model.getStatus())
                .createdAt(model.getCreatedAt())
                .startedAt(model.getStartedAt())
                .completedAt(model.getCompletedAt())
                .totalPagesCrawled(model.getTotalPagesCrawled())
                .totalUrlsDiscovered(model.getTotalUrlsDiscovered())
                .allowedDomains(model.getAllowedDomains())
                .urlPatterns(model.getUrlPatterns())
                .build();
    }

    /**
     * Convert an entity to a model object
     */
    private CrawlSession convertToModel(CrawlSessionEntity entity) {
        return CrawlSession.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .seedUrls(entity.getSeedUrls())
                .maxDepth(entity.getMaxDepth())
                .maxPagesPerDomain(entity.getMaxPagesPerDomain())
                .respectRobotsTxt(entity.isRespectRobotsTxt())
                .customHeaders(entity.getCustomHeaders())
                .crawlDelay(entity.getCrawlDelay())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .totalPagesCrawled(entity.getTotalPagesCrawled())
                .totalUrlsDiscovered(entity.getTotalUrlsDiscovered())
                .allowedDomains(entity.getAllowedDomains())
                .urlPatterns(entity.getUrlPatterns())
                .build();
    }
}
