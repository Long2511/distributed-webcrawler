package com.ouroboros.webcrawler.worker;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Worker node that performs the actual crawling of web pages
 */
@Service
@Slf4j
public class CrawlerWorker {

    @Autowired
    private URLFrontier urlFrontier;

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private CrawledPageRepository crawledPageRepository;

    @Value("${webcrawler.user-agent}")
    private String userAgent;

    @Value("${webcrawler.max-depth:10}")
    private int maxDepth;

    @Value("${webcrawler.politeness.delay:500}")
    private int politenessDelay;

    @Value("${webcrawler.politeness.respect-robots-txt:true}")
    private boolean respectRobotsTxt;

    @Value("${webcrawler.politeness.adaptive-delay:true}")
    private boolean adaptiveDelay;

    @Value("${webcrawler.politeness.max-delay:10000}")
    private int maxPolitenessDelay;

    // Cache for robots.txt rules
    private final Map<String, RobotsTxtRules> robotsCache = new HashMap<>();

    // Map to track last access time per domain
    private final Map<String, Long> lastAccessTimes = new ConcurrentHashMap<>();

    // Map to track custom delays per domain (based on adaptive delays)
    private final Map<String, Integer> domainDelays = new ConcurrentHashMap<>();

    // Map to track response times per domain for adaptive delays
    private final Map<String, List<Long>> responseTimesHistory = new ConcurrentHashMap<>();

    /**
     * Process a crawl job received from Kafka
     */
    @KafkaListener(topics = "${webcrawler.kafka.topics.crawl-tasks}", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void processCrawlJob(CrawlJob job) {
        log.info("Processing crawl job for URL: {}", job.getUrl());

        try {
            // Check if max depth reached
            if (job.getDepth() > maxDepth) {
                log.debug("Skipping URL due to max depth: {}", job.getUrl());
                urlFrontier.markAsCompleted(job);
                return;
            }

            String domain = extractDomain(job.getUrl());

            // Respect robots.txt if enabled
            if (respectRobotsTxt && !isAllowedByRobots(job.getUrl())) {
                log.debug("Skipping URL disallowed by robots.txt: {}", job.getUrl());
                urlFrontier.markAsCompleted(job);
                return;
            }

            // Apply politeness delay with domain specific rules
            applyPolitenessDelay(domain);

            long startTime = System.currentTimeMillis();

            // Connect and fetch the page
            Connection.Response response = Jsoup.connect(job.getUrl())
                    .userAgent(userAgent)
                    .timeout(10000)
                    .followRedirects(true)
                    .execute();

            long responseTime = System.currentTimeMillis() - startTime;

            // Update response time history for this domain for adaptive delays
            updateResponseTimeHistory(domain, responseTime);

            Document document = response.parse();
            String contentType = response.contentType();

            // Only process HTML content
            if (contentType == null || !contentType.contains("text/html")) {
                log.debug("Skipping non-HTML content: {} - {}", job.getUrl(), contentType);
                urlFrontier.markAsCompleted(job);
                return;
            }

            // Extract page info
            String title = document.title();

            // Extract all links
            Set<String> links = extractLinks(document, job.getUrl(), job.getSessionId());
            int newLinks = 0;

            // Add extracted links to frontier
            for (String link : links) {
                if (!urlFrontier.isUrlVisited(link)) {
                    urlFrontier.addUrl(link, calculatePriority(link), job.getDepth() + 1, job.getSessionId());
                    newLinks++;
                }
            }

            // Create crawled page record
            CrawledPageEntity.CrawledPageEntityBuilder pageBuilder = CrawledPageEntity.builder()
                    .url(job.getUrl())
                    .title(title)
                    .content(document.text()) // Store plain text content
                    .outgoingLinks(links)
                    .statusCode(response.statusCode())
                    .contentType(contentType)
                    .crawlTimestamp(LocalDateTime.now())
                    .crawlDepth(job.getDepth())
                    .domain(domain)
                    .parsed(true)
                    .sessionId(job.getSessionId());

            // Safely get content length
            try {
                byte[] bodyBytes = response.bodyAsBytes();
                if (bodyBytes != null) {
                    pageBuilder.contentLength(bodyBytes.length);
                } else {
                    pageBuilder.contentLength(0);
                    log.warn("Response body was null for URL: {}", job.getUrl());
                }
            } catch (Exception e) {
                pageBuilder.contentLength(0);
                log.warn("Could not determine content length for URL: {} - {}", job.getUrl(), e.getMessage());
            }

            CrawledPageEntity crawledPage = pageBuilder.build();

            // Extract metadata
            Map<String, String> metaInfo = new HashMap<>();
            document.select("meta").forEach(meta -> {
                String name = meta.attr("name");
                if (!name.isEmpty()) {
                    metaInfo.put(name, meta.attr("content"));
                }
            });
            crawledPage.setMetaInfo(metaInfo);

            // Save to MongoDB
            crawledPageRepository.save(crawledPage);

            // Mark job as completed
            urlFrontier.markAsCompleted(job);

            // Update stats
            crawlerManager.updateCrawlStats(job.getSessionId(), newLinks, 1);

            log.info("Successfully crawled URL: {}, found {} links", job.getUrl(), links.size());

        } catch (HttpStatusException e) {
            log.warn("HTTP error crawling URL: {} - Status: {}", job.getUrl(), e.getStatusCode());
            urlFrontier.markAsFailed(job, "HTTP error: " + e.getStatusCode(), shouldRetry(e.getStatusCode()));
        } catch (IOException e) {
            log.error("IO error crawling URL: {}", job.getUrl(), e);
            urlFrontier.markAsFailed(job, "IO error: " + e.getMessage(), true);
        } catch (Exception e) {
            log.error("Unexpected error crawling URL: {}", job.getUrl(), e);
            urlFrontier.markAsFailed(job, "Error: " + e.getMessage(), false);
        }
    }

    /**
     * Apply politeness delay for a domain
     */
    private void applyPolitenessDelay(String domain) {

        if (politenessDelay > 0) {
            try {
                // Get the last access time for this domain
                Long lastAccessTime = lastAccessTimes.get(domain);

                // Calculate adaptive delay if enabled
                if (adaptiveDelay && lastAccessTime != null) {
                    long currentTime = System.currentTimeMillis();
                    long timeSinceLastAccess = currentTime - lastAccessTime;

                    // Use the minimum of the configured politeness delay and the adaptive delay
                    int adaptivePolitenessDelay = (int)Math.min(politenessDelay, maxPolitenessDelay - timeSinceLastAccess);

                    // Ensure we don't go below the base politeness delay
                    adaptivePolitenessDelay = Math.max(adaptivePolitenessDelay, politenessDelay);

                    log.debug("Applying adaptive politeness delay of {} ms for domain: {}", adaptivePolitenessDelay, domain);
                    TimeUnit.MILLISECONDS.sleep(adaptivePolitenessDelay);
                } else {
                    log.debug("Applying fixed politeness delay of {} ms for domain: {}", politenessDelay, domain);
                    TimeUnit.MILLISECONDS.sleep(politenessDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Check if URL is allowed by robots.txt
     */
    private boolean isAllowedByRobots(String url) {
        try {
            URL parsedUrl = new URL(url);
            String domain = parsedUrl.getHost();
            String protocol = parsedUrl.getProtocol();

            RobotsTxtRules rules = robotsCache.computeIfAbsent(domain, d -> {
                try {
                    String robotsUrl = protocol + "://" + d + "/robots.txt";
                    return RobotsTxtRules.parse(robotsUrl, userAgent);
                } catch (Exception e) {
                    log.warn("Error fetching robots.txt for {}: {}", d, e.getMessage());
                    return new RobotsTxtRules(); // Empty rules = allow all
                }
            });

            return rules.isAllowed(url);
        } catch (MalformedURLException e) {
            return true; // If we can't parse the URL, just allow it
        }
    }

    /**
     * Extract and normalize links from a document
     */
    private Set<String> extractLinks(Document document, String baseUrl, String sessionId) {
        // Get the session to check the sameDomainOnly setting
        boolean sameDomainOnly = false;
        try {
            sameDomainOnly = crawlerManager.getSession(sessionId)
                .map(session -> session.isSameDomainOnly())
                .orElse(false);
        } catch (Exception e) {
            log.warn("Failed to get session config for {}, defaulting sameDomainOnly=false", sessionId);
        }

        // Extract domain of current page
        final String baseDomain = extractDomain(baseUrl);

        // Create a final copy of the sameDomainOnly flag for use in lambda
        final boolean finalSameDomainOnly = sameDomainOnly;

        return document.select("a[href]").stream()
                .map(element -> element.attr("abs:href"))
                .filter(this::isValidUrl)
                // Apply same domain filter if enabled
                .filter(url -> !finalSameDomainOnly || isSameDomain(url, baseDomain))
                .map(this::normalizeUrl)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Check if URL has the same domain as the base domain
     */
    private boolean isSameDomain(String url, String baseDomain) {
        if (baseDomain == null || url == null) {
            return false;
        }

        String domain = extractDomain(url);
        return baseDomain.equals(domain);
    }

    /**
     * Extract domain from URL
     */
    private String extractDomain(String urlString) {
        try {
            URL url = new URL(urlString);
            return url.getHost();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /**
     * Check if URL is valid for crawling
     */
    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > 2048) {
            return false;
        }

        // Exclude non-http/https URLs, fragments, etc.
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equals("http") || scheme.equals("https"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Normalize URL (remove fragments, default ports, etc.)
     */
    private String normalizeUrl(String url) {
        try {
            URL parsedUrl = new URL(url);
            URI uri = new URI(
                    parsedUrl.getProtocol(),
                    parsedUrl.getUserInfo(),
                    parsedUrl.getHost(),
                    parsedUrl.getPort(),
                    parsedUrl.getPath(),
                    parsedUrl.getQuery(),
                    null  // No fragment
            );

            String normalized = uri.toString();

            // Remove trailing slash if it's just the path
            if (normalized.endsWith("/") && normalized.indexOf('/', 8) == normalized.length() - 1) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }

            return normalized;
        } catch (MalformedURLException | URISyntaxException e) {
            log.debug("Failed to normalize URL: {}", url);
            return null;
        }
    }

    /**
     * Calculate priority for a URL based on various heuristics
     * Lower number = higher priority
     */
    private int calculatePriority(String url) {
        // Simple priority calculation based on URL length and structure
        int priority = 100; // Default priority

        // Shorter URLs tend to be more important
        priority -= Math.min(50, 2000 / Math.max(1, url.length()));

        // Prefer URLs with fewer path segments
        long pathSegments = url.chars().filter(ch -> ch == '/').count() - 2;
        priority += Math.min(30, (int)pathSegments * 10);

        // Penalize URLs with query parameters
        if (url.contains("?")) {
            priority += 20;
        }

        return Math.max(1, Math.min(priority, 999)); // Keep priority between 1-999
    }

    /**
     * Determine if a failed URL should be retried based on status code
     */
    private boolean shouldRetry(int statusCode) {
        // Don't retry client errors (except 429 Too Many Requests)
        if (statusCode >= 400 && statusCode < 500) {
            return statusCode == 429;
        }

        // Retry server errors
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Update the response time history for a domain
     */
    private void updateResponseTimeHistory(String domain, long responseTime) {
        responseTimesHistory.computeIfAbsent(domain, d -> new ArrayList<>()).add(responseTime);

        // Keep only the latest N response times (e.g., 10)
        List<Long> times = responseTimesHistory.get(domain);
        if (times.size() > 10) {
            times.remove(0);
        }

        // Calculate the average response time
        long avgResponseTime = (long)times.stream().mapToLong(Long::longValue).average().orElse(0.0);

        // Adjust the domain delay based on the average response time
        int adjustedDelay = (int)Math.min(Math.max(avgResponseTime * 2, politenessDelay), maxPolitenessDelay);
        domainDelays.put(domain, adjustedDelay);
    }

    /**
     * Simple robots.txt rules implementation
     */
    private static class RobotsTxtRules {
        private final List<String> disallowedPaths = new ArrayList<>();

        public static RobotsTxtRules parse(String robotsUrl, String userAgent) {
            RobotsTxtRules rules = new RobotsTxtRules();

            try {
                Document doc = Jsoup.connect(robotsUrl)
                        .userAgent(userAgent)
                        .timeout(5000)
                        .get();

                String content = doc.body().text();
                boolean foundUserAgent = false;
                boolean foundAll = false;

                for (String line : content.split("\n")) {
                    line = line.trim();

                    if (line.startsWith("User-agent:")) {
                        String agent = line.substring("User-agent:".length()).trim();
                        foundUserAgent = agent.equals(userAgent) || agent.equals("*");
                        foundAll = agent.equals("*");
                    }

                    if (foundUserAgent && line.startsWith("Disallow:")) {
                        String path = line.substring("Disallow:".length()).trim();
                        if (!path.isEmpty()) {
                            rules.disallowedPaths.add(path);
                        }
                    }

                    if (foundUserAgent && line.startsWith("User-agent:") &&
                        !line.substring("User-agent:".length()).trim().equals(userAgent) &&
                        !foundAll) {
                        break;
                    }
                }
            } catch (Exception e) {
                // If there's an error, we assume everything is allowed
            }

            return rules;
        }

        public boolean isAllowed(String url) {
            try {
                URL parsedUrl = new URL(url);
                String path = parsedUrl.getPath();

                for (String disallowed : disallowedPaths) {
                    if (path.startsWith(disallowed)) {
                        return false;
                    }
                }

                return true;
            } catch (MalformedURLException e) {
                return true; // If we can't parse the URL, just allow it
            }
        }
    }
}
