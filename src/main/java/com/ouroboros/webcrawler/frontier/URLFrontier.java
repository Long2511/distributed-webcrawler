package com.ouroboros.webcrawler.frontier;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * URL Frontier implementation using PostgreSQL
 * Handles prioritization and distribution of URLs to be crawled
 */
@Component
@Slf4j
public class URLFrontier {

    @Autowired
    private CrawlUrlRepository crawlUrlRepository;

    /**
     * Add a URL to the frontier with a specified priority
     * Higher number means higher priority
     */
    @Transactional
    public void addUrl(String url, int priority, int depth, String sessionId) {
        if (isUrlVisited(url)) {
            log.debug("URL already visited or queued: {}", url);
            return;
        }

        CrawlUrl crawlUrl = CrawlUrl.builder()
                .url(url)
                .priority(priority)
                .depth(depth)
                .status("QUEUED")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .retryCount(0)
                .sessionId(sessionId)
                .build();

        crawlUrlRepository.save(crawlUrl);
        log.debug("Added URL to frontier: {} with priority {}", url, priority);
    }

    /**
     * Get the next URL to crawl based on priority
     */
    @Transactional
    public Optional<CrawlJob> getNextJob() {
        // Try to get from highest priority (5) down to lowest (1)
        for (int priority = 5; priority >= 1; priority--) {
            Optional<CrawlUrl> crawlUrl = crawlUrlRepository.findNextUrlToCrawl(priority);

            if (crawlUrl.isPresent()) {
                CrawlUrl url = crawlUrl.get();

                // Mark as processing
                url.setStatus("PROCESSING");
                url.setUpdatedAt(LocalDateTime.now());
                crawlUrlRepository.save(url);

                CrawlJob job = CrawlJob.builder()
                        .id(url.getId().toString())
                        .url(url.getUrl())
                        .priority(url.getPriority())
                        .depth(url.getDepth())
                        .jobStatus("PROCESSING")
                        .createdAt(url.getCreatedAt())
                        .updatedAt(url.getUpdatedAt())
                        .retryCount(url.getRetryCount())
                        .build();

                log.debug("Retrieved job from frontier: {}", url.getUrl());
                return Optional.of(job);
            }
        }

        return Optional.empty();
    }

    /**
     * Mark a URL as completed
     */
    @Transactional
    public void markAsCompleted(CrawlJob job) {
        Optional<CrawlUrl> optionalCrawlUrl = crawlUrlRepository.findById(Long.parseLong(job.getId()));

        if (optionalCrawlUrl.isPresent()) {
            CrawlUrl crawlUrl = optionalCrawlUrl.get();
            crawlUrl.setStatus("COMPLETED");
            crawlUrl.setUpdatedAt(LocalDateTime.now());
            crawlUrlRepository.save(crawlUrl);

            log.debug("Marked job as completed: {}", job.getUrl());
        } else {
            log.warn("Could not find CrawlUrl for job id: {}", job.getId());
        }
    }

    /**
     * Mark a URL as failed and potentially requeue
     */
    @Transactional
    public void markAsFailed(CrawlJob job, String errorMessage, boolean requeue) {
        Optional<CrawlUrl> optionalCrawlUrl = crawlUrlRepository.findById(Long.parseLong(job.getId()));

        if (!optionalCrawlUrl.isPresent()) {
            log.warn("Could not find CrawlUrl for job id: {}", job.getId());
            return;
        }

        CrawlUrl crawlUrl = optionalCrawlUrl.get();
        crawlUrl.setErrorMessage(errorMessage);
        crawlUrl.setUpdatedAt(LocalDateTime.now());
        crawlUrl.setRetryCount(crawlUrl.getRetryCount() + 1);

        if (requeue && crawlUrl.getRetryCount() < 3) {
            // Requeue with lower priority
            int newPriority = Math.max(1, crawlUrl.getPriority() - 1);
            crawlUrl.setPriority(newPriority);
            crawlUrl.setStatus("QUEUED");

            crawlUrlRepository.save(crawlUrl);
            log.debug("Requeued failed job: {} with new priority {}", job.getUrl(), newPriority);
        } else {
            crawlUrl.setStatus("FAILED");
            crawlUrlRepository.save(crawlUrl);
            log.debug("Job failed permanently: {}", job.getUrl());
        }
    }

    /**
     * Check if a URL has already been visited or is in the queue
     */
    public boolean isUrlVisited(String url) {
        return crawlUrlRepository.existsByUrl(url);
    }

    /**
     * Get stats about the frontier
     */
    public FrontierStats getStats() {
        FrontierStats stats = new FrontierStats();

        // Count URLs by priority and status
        for (int priority = 1; priority <= 5; priority++) {
            final int p = priority;
            long count = crawlUrlRepository.count((Specification<CrawlUrl>) (root, query, cb) -> {
                Predicate statusPredicate = cb.equal(root.get("status"), "QUEUED");
                Predicate priorityPredicate = cb.equal(root.get("priority"), p);
                return cb.and(statusPredicate, priorityPredicate);
            });
            stats.getQueueSizes().put(priority, count);
        }

        // Count processing and visited URLs
        long processingCount = crawlUrlRepository.countByStatus("PROCESSING");
        long visitedCount = crawlUrlRepository.countByStatus("COMPLETED");

        stats.setProcessingCount(processingCount);
        stats.setVisitedCount(visitedCount);

        return stats;
    }

    /**
     * Clean up stalled jobs (in processing state for too long)
     */
    @Transactional
    public void recoverStalledJobs() {
        LocalDateTime cutoffTime = LocalDateTime.now().minus(Duration.ofMinutes(10));

        crawlUrlRepository.findStalledJobs(cutoffTime).forEach(job -> {
            job.setStatus("QUEUED");
            job.setRetryCount(job.getRetryCount() + 1);
            job.setUpdatedAt(LocalDateTime.now());

            if (job.getRetryCount() >= 3) {
                job.setStatus("FAILED");
                job.setErrorMessage("Max retries exceeded");
            }

            crawlUrlRepository.save(job);
        });
    }
}
