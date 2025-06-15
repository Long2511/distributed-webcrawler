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

    /**
     * Process a crawl job received from Kafka
     */
    @KafkaListener(topics = "webcrawler.tasks", groupId = "webcrawler-workers")
    @Transactional
    public void processCrawlJob(CrawlJob job) {
        log.info("Processing crawl job for URL: {}", job.getUrl());

        try {
            // Check if max depth reached
            if (job.getDepth() > 10) { // Should be configurable
                log.debug("Skipping URL due to max depth: {}", job.getUrl());
                urlFrontier.markAsCompleted(job);
                return;
            }

            // Connect and fetch the page
            Connection.Response response = Jsoup.connect(job.getUrl())
                    .userAgent("Ouroboros Web Crawler/1.0")
                    .timeout(10000)
                    .followRedirects(true)
                    .execute();

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
            String domain = extractDomain(job.getUrl());

            // Extract all links
            Set<String> links = extractLinks(document, job.getUrl());
            int newLinks = 0;

            // Add extracted links to frontier
            for (String link : links) {
                if (!urlFrontier.isUrlVisited(link)) {
                    urlFrontier.addUrl(link, calculatePriority(link), job.getDepth() + 1, null);
                    newLinks++;
                }
            }

            // Create crawled page record
            CrawledPageEntity crawledPage = CrawledPageEntity.builder()
                    .url(job.getUrl())
                    .title(title)
                    .content(document.text()) // Store plain text content
                    .outgoingLinks(links)
                    .statusCode(response.statusCode())
                    .contentType(contentType)
                    .contentLength(response.bodyAsBytes().length)
                    .crawlTimestamp(LocalDateTime.now())
                    .crawlDepth(job.getDepth())
                    .domain(domain)
                    .parsed(true)
                    .build();

            // Save to PostgreSQL
            crawledPageRepository.save(crawledPage);

            // Mark job as completed
            urlFrontier.markAsCompleted(job);

            // Update stats
            crawlerManager.updateCrawlStats(job.getId(), newLinks, 1);

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
     * Extract and normalize links from a document
     */
    private Set<String> extractLinks(Document document, String baseUrl) {
        return document.select("a[href]").stream()
                .map(element -> element.attr("abs:href"))
                .filter(this::isValidUrl)
                .map(this::normalizeUrl)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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
     * Calculate priority for a URL
     */
    private int calculatePriority(String url) {
        // Very basic prioritization - can be expanded
        if (url.contains("news") || url.contains("article")) {
            return 4;
        } else if (url.contains("about") || url.contains("contact")) {
            return 3;
        } else {
            return 2;
        }
    }

    /**
     * Determine if a failed request should be retried based on status code
     */
    private boolean shouldRetry(int statusCode) {
        // Retry transient errors (5xx)
        return statusCode >= 500 && statusCode < 600;
    }
}
