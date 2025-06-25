package com.ouroboros.webcrawler.metrics;

import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import com.ouroboros.webcrawler.repository.CrawlUrlRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exports crawler metrics for monitoring systems like Prometheus
 */
@Component
@Slf4j
public class CrawlerMetrics {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlUrlRepository crawlUrlRepository;

    @Autowired
    private CrawledPageRepository crawledPageRepository;

    @Autowired
    private CrawlSessionRepository crawlSessionRepository;

    @Autowired
    private DistributedInstanceConfig distributedInstanceConfig;

    // Track crawl rate metrics
    private final AtomicInteger crawlRate = new AtomicInteger(0);
    private final Map<String, AtomicInteger> domainCrawlRates = new HashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing crawler metrics for monitoring");

        // URL Frontier metrics
        Gauge.builder("webcrawler.urls.queued", () -> urlFrontier.getStats().getQueuedUrls())
            .description("Number of URLs queued for crawling")
            .register(meterRegistry);

        Gauge.builder("webcrawler.urls.processing", () -> urlFrontier.getStats().getProcessingUrls())
            .description("Number of URLs currently being processed")
            .register(meterRegistry);

        Gauge.builder("webcrawler.urls.completed", () -> urlFrontier.getStats().getCompletedUrls())
            .description("Number of URLs successfully crawled")
            .register(meterRegistry);

        Gauge.builder("webcrawler.urls.failed", () -> urlFrontier.getStats().getFailedUrls())
            .description("Number of URLs that failed during crawl")
            .register(meterRegistry);

        Gauge.builder("webcrawler.urls.retry", () -> urlFrontier.getStats().getRetryUrls())
            .description("Number of URLs queued for retry")
            .register(meterRegistry);

        // Session metrics
        Gauge.builder("webcrawler.sessions.active", () -> crawlSessionRepository.countByStatus("RUNNING"))
            .description("Number of active crawl sessions")
            .register(meterRegistry);

        Gauge.builder("webcrawler.sessions.completed", () -> crawlSessionRepository.countByStatus("COMPLETED"))
            .description("Number of completed crawl sessions")
            .register(meterRegistry);

        // Crawled page metrics
        Gauge.builder("webcrawler.pages.crawled", crawledPageRepository::count)
            .description("Total number of pages crawled")
            .register(meterRegistry);

        // Distributed instance metrics
        Gauge.builder("webcrawler.instances.active", distributedInstanceConfig::getActiveInstanceCount)
            .description("Number of active crawler instances")
            .register(meterRegistry);

        Gauge.builder("webcrawler.instances.queued", () -> distributedInstanceConfig.getQueuedInstances().size())
            .description("Number of instances in the processing queue")
            .register(meterRegistry);

        // Crawl rate metric (updated programmatically)
        Gauge.builder("webcrawler.crawl.rate", crawlRate::get)
            .description("Current crawl rate (pages per minute)")
            .register(meterRegistry);
    }

    /**
     * Update the crawl rate metric
     *
     * @param pagesPerMinute Pages crawled per minute
     */
    public void updateCrawlRate(int pagesPerMinute) {
        crawlRate.set(pagesPerMinute);
    }

    /**
     * Update domain-specific crawl rate
     *
     * @param domain The domain name
     * @param pagesPerMinute Pages crawled per minute for this domain
     */
    public void updateDomainCrawlRate(String domain, int pagesPerMinute) {
        domainCrawlRates.computeIfAbsent(domain, d -> {
            // Create a new metric for this domain if it doesn't exist
            AtomicInteger rate = new AtomicInteger(0);
            Gauge.builder("webcrawler.domain.crawl.rate", rate::get)
                .tag("domain", domain)
                .description("Domain crawl rate (pages per minute)")
                .register(meterRegistry);
            return rate;
        }).set(pagesPerMinute);
    }
}
