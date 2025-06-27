package com.ouroboros.webcrawler.worker;

import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.CrawlController;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtConfig;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Worker node that manages crawler4j instances for distributed crawling
 */
@Service
@Slf4j
public class Crawler4jWorker {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private CrawledPageRepository crawledPageRepository;

    @Autowired
    private DistributedInstanceConfig distributedInstanceConfig;

    @Value("${webcrawler.user-agent}")
    private String userAgent;

    @Value("${webcrawler.max-depth:10}")
    private int maxDepth;

    @Value("${webcrawler.politeness.delay:500}")
    private int politenessDelay;

    @Value("${webcrawler.politeness.respect-robots-txt:true}")
    private boolean respectRobotsTxt;

    @Value("${webcrawler.crawler4j.thread-count:10}")
    private int numberOfCrawlers;

    @Value("${webcrawler.crawler4j.max-pages-per-domain:-1}")
    private int maxPagesPerDomain;

    @Value("${webcrawler.crawler4j.include-binary:false}")
    private boolean includeBinaryContentInCrawling;

    @Value("${webcrawler.crawler4j.storage-folder:#{null}}")
    private String storageFolder;

    private final Map<String, CrawlController> runningControllers = new ConcurrentHashMap<>();
    private final ExecutorService crawlExecutor = Executors.newCachedThreadPool();
    private Path tempStorageFolder;

    @PostConstruct
    public void init() throws Exception {
        // Create a temporary folder for crawler4j storage if one is not specified
        if (storageFolder == null || storageFolder.isEmpty()) {
            tempStorageFolder = Files.createTempDirectory("crawler4j-");
            storageFolder = tempStorageFolder.toString();
            log.info("Created temporary crawler4j storage folder: {}", storageFolder);
        }
    }

    @PreDestroy
    public void cleanup() {
        // Shutdown all running crawlers
        log.info("Shutting down all crawlers...");
        for (Map.Entry<String, CrawlController> entry : runningControllers.entrySet()) {
            try {
                log.info("Shutting down crawler for job: {}", entry.getKey());
                entry.getValue().shutdown();
            } catch (Exception e) {
                log.error("Error shutting down crawler: {}", e.getMessage(), e);
            }
        }

        // Shutdown executor
        crawlExecutor.shutdownNow();

        // Delete temp folder if we created one
        if (tempStorageFolder != null) {
            try {
                log.info("Deleting temporary crawler4j folder: {}", tempStorageFolder);
                Files.walk(tempStorageFolder)
                    .sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            log.warn("Failed to delete: {}", p, e);
                        }
                    });
            } catch (Exception e) {
                log.error("Error cleaning up temporary folder: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Process a crawl job received from Kafka
     */
    @KafkaListener(topics = "${webcrawler.kafka.topics.crawl-tasks}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void processCrawlJob(CrawlJob job) {
        log.info("Processing crawl job for URL: {}", job.getUrl());

        // Only process jobs assigned to this instance
        if (job.getProcessingInstance() != null &&
            !job.getProcessingInstance().equals(distributedInstanceConfig.instanceId())) {
            log.debug("Skipping job assigned to different instance: {}", job.getProcessingInstance());
            return;
        }

        try {
            // Create a specific folder for this job
            String jobFolder = storageFolder + "/" + job.getId();

            // Get session info to check if we should stay within the same domain
            boolean sameDomainOnly = false;
            try {
                sameDomainOnly = crawlerManager.getSession(job.getSessionId())
                    .map(session -> session.isSameDomainOnly())
                    .orElse(false);
            } catch (Exception e) {
                log.warn("Failed to get session config for {}, defaulting sameDomainOnly=false", job.getSessionId());
            }

            // Configure crawler4j
            CrawlConfig config = createCrawlConfig(job, jobFolder, sameDomainOnly);

            // Start the crawler
            startCrawler(job, config, sameDomainOnly);

        } catch (Exception e) {
            log.error("Error processing crawl job: {}", job.getUrl(), e);
            urlFrontier.markAsFailed(job, "Error: " + e.getMessage(), false);
        }
    }

    /**
     * Create a crawler4j configuration for the job
     */
    private CrawlConfig createCrawlConfig(CrawlJob job, String jobFolder, boolean sameDomainOnly) {
        CrawlConfig config = new CrawlConfig();

        // Set storage folder
        config.setCrawlStorageFolder(jobFolder);

        // Set politeness settings
        config.setPolitenessDelay(politenessDelay);
        config.setRespectNoFollow(true);
        config.setRespectNoIndex(true);
        config.setIncludeBinaryContentInCrawling(includeBinaryContentInCrawling);

        // Set user agent
        config.setUserAgentString(userAgent);

        // Set max depth
        config.setMaxDepthOfCrawling(job.getDepth() > 0 ? maxDepth - job.getDepth() : maxDepth);

        // Set max pages to crawl - only crawl 1 page if we're not following links
        config.setMaxPagesToFetch(sameDomainOnly ? maxPagesPerDomain : 1);

        // Performance tweaks
        config.setThreadShutdownDelaySeconds(3);
        config.setThreadMonitoringDelaySeconds(3);
        config.setCleanupDelaySeconds(3);

        return config;
    }

    /**
     * Start a crawler for a specific job
     */
    private void startCrawler(CrawlJob job, CrawlConfig config, boolean sameDomainOnly) throws Exception {
        // Setup crawler components
        PageFetcher pageFetcher = new PageFetcher(config);
        RobotstxtConfig robotstxtConfig = new RobotstxtConfig();
        robotstxtConfig.setEnabled(respectRobotsTxt);
        RobotstxtServer robotstxtServer = new RobotstxtServer(robotstxtConfig, pageFetcher);

        // Create a unique controller ID for this job
        String controllerId = job.getId() != null ? job.getId() : UUID.randomUUID().toString();

        // Create controller for this crawl
        CrawlController controller = new CrawlController(config, pageFetcher, robotstxtServer);
        controller.addSeed(job.getUrl());

        // Store controller for cleanup later
        runningControllers.put(controllerId, controller);

        // Start the crawl in a separate thread
        crawlExecutor.submit(() -> {
            try {
                // Create a crawler factory specific to this job
                CrawlController.WebCrawlerFactory<BasicCrawler> factory = () -> {
                    BasicCrawler crawler = new BasicCrawler();
                    crawler.initialize(job, crawledPageRepository, maxDepth, sameDomainOnly);
                    return crawler;
                };

                // Start the crawl
                log.info("Starting crawl for URL: {} with {} threads", job.getUrl(), numberOfCrawlers);
                controller.start(factory, numberOfCrawlers);

                // When the crawl is complete, mark the job as done
                log.info("Crawl completed for URL: {}", job.getUrl());
                urlFrontier.markAsCompleted(job);

                // Update crawler stats
                // TODO: Get actual count of new URLs discovered rather than constant 0
                crawlerManager.updateCrawlStats(job.getSessionId(), 0, 1);

                // Clean up the controller
                runningControllers.remove(controllerId);

            } catch (Exception e) {
                log.error("Error during crawl: {}", job.getUrl(), e);
                urlFrontier.markAsFailed(job, "Crawl error: " + e.getMessage(), false);
                runningControllers.remove(controllerId);
            }
        });
    }
}
