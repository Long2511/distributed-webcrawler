package com.ouroboros.webcrawler.worker;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.model.CrawlJob;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import edu.uci.ics.crawler4j.crawler.Page;
import edu.uci.ics.crawler4j.crawler.WebCrawler;
import edu.uci.ics.crawler4j.parser.HtmlParseData;
import edu.uci.ics.crawler4j.url.WebURL;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Basic web crawler implementation using crawler4j
 */
@Slf4j
public class BasicCrawler extends WebCrawler {

    private final static Pattern FILTERS = Pattern.compile(".*(\\.(css|js|gif|jpg|png|mp3|mp4|zip|gz|pdf))$");

    private CrawlJob crawlJob;
    private CrawledPageRepository crawledPageRepository;
    private int maxDepth;
    private boolean sameDomainOnly;
    private String baseDomain;
    private Set<String> visitedUrls = new HashSet<>();

    /**
     * Initialize crawler with job parameters
     */
    public void initialize(CrawlJob job, CrawledPageRepository repository, int maxDepth, boolean sameDomainOnly) {
        this.crawlJob = job;
        this.crawledPageRepository = repository;
        this.maxDepth = maxDepth;
        this.sameDomainOnly = sameDomainOnly;

        if (job != null && job.getUrl() != null) {
            try {
                java.net.URL url = new java.net.URL(job.getUrl());
                this.baseDomain = url.getHost();
                log.debug("Base domain for job {}: {}", job.getId(), this.baseDomain);
            } catch (Exception e) {
                log.error("Failed to parse base domain from URL: {}", job.getUrl(), e);
            }
        }
    }

    /**
     * This method receives two parameters. The first parameter is the page
     * in which we have discovered this new url and the second parameter is
     * the new url. You should implement this function to specify whether
     * the given url should be crawled or not (based on your crawling logic).
     */
    @Override
    public boolean shouldVisit(Page referringPage, WebURL url) {
        String href = url.getURL().toLowerCase();

        // Skip URLs we've already seen
        if (visitedUrls.contains(href)) {
            return false;
        }

        // Skip binary/media files
        if (FILTERS.matcher(href).matches()) {
            return false;
        }

        // Stay within the same domain if required
        if (sameDomainOnly && baseDomain != null) {
            String domain = url.getDomain();
            return domain != null && domain.equals(baseDomain);
        }

        // Check depth against max depth
        if (url.getDepth() > maxDepth) {
            return false;
        }

        return true;
    }

    /**
     * This function is called when a page is fetched and ready
     * to be processed by your program.
     */
    @Override
    public void visit(Page page) {
        String url = page.getWebURL().getURL();
        visitedUrls.add(url);

        log.debug("Visiting: {}", url);

        if (page.getParseData() instanceof HtmlParseData) {
            HtmlParseData htmlParseData = (HtmlParseData) page.getParseData();
            String text = htmlParseData.getText();
            String html = htmlParseData.getHtml();
            Set<WebURL> links = htmlParseData.getOutgoingUrls();
            String title = htmlParseData.getTitle();

            // Extract metadata from HTML
            Map<String, String> metaInfo = extractMetaTags(html);

            // Convert to our domain model
            CrawledPageEntity.CrawledPageEntityBuilder pageBuilder = CrawledPageEntity.builder()
                    .url(url)
                    .title(title)
                    .content(text)
                    .html(html)
                    .statusCode(page.getStatusCode())
                    .contentType(page.getContentType())
                    .crawlTimestamp(LocalDateTime.now())
                    .crawlDepth(page.getWebURL().getDepth())
                    .domain(page.getWebURL().getDomain())
                    .metaInfo(metaInfo)
                    .parsed(true)
                    .sessionId(crawlJob != null ? crawlJob.getSessionId() : null);

            // Extract outgoing links
            Set<String> outgoingLinks = new HashSet<>();
            for (WebURL link : links) {
                outgoingLinks.add(link.getURL());
            }
            pageBuilder.outgoingLinks(outgoingLinks);

            // Set content length
            if (page.getContentData() != null) {
                pageBuilder.contentLength(page.getContentData().length);
            } else {
                pageBuilder.contentLength(0);
            }

            CrawledPageEntity crawledPage = pageBuilder.build();

            // Save to database
            try {
                crawledPageRepository.save(crawledPage);
                log.debug("Saved page: {} with {} outgoing links", url, outgoingLinks.size());
            } catch (Exception e) {
                log.error("Failed to save crawled page: {}", url, e);
            }
        }
    }

    /**
     * Extract meta tags from HTML
     */
    private Map<String, String> extractMetaTags(String html) {
        Map<String, String> metaTags = new HashMap<>();

        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            doc.select("meta").forEach(meta -> {
                String name = meta.attr("name");
                if (StringUtils.hasText(name)) {
                    metaTags.put(name, meta.attr("content"));
                } else {
                    // Try property for OpenGraph tags
                    name = meta.attr("property");
                    if (StringUtils.hasText(name)) {
                        metaTags.put(name, meta.attr("content"));
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Error extracting meta tags: {}", e.getMessage());
        }

        return metaTags;
    }
}
