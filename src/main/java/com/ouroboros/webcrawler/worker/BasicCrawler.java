package com.ouroboros.webcrawler.worker;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class BasicCrawler implements CrawlerWorker {

    @Value("${webcrawler.user-agent:Ouroboros Web Crawler/1.0}")
    private String userAgent;

    @Value("${webcrawler.politeness.delay:500}")
    private int politenessDelay;

    @Value("${webcrawler.politeness.respect-robots-txt:true}")
    private boolean respectRobotsTxt;

    private static final Pattern VALID_URL_PATTERN = Pattern.compile(
        "^https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$"
    );

    private final Map<String, RobotsTxtRules> robotsCache = new HashMap<>();

    @Override
    public CrawledPageEntity crawl(CrawlUrl crawlUrl) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("Crawling URL: {}", crawlUrl.getUrl());
            
            // Check robots.txt if enabled
            if (respectRobotsTxt && !isAllowedByRobots(crawlUrl.getUrl())) {
                log.debug("URL blocked by robots.txt: {}", crawlUrl.getUrl());
                return CrawledPageEntity.builder()
                    .url(crawlUrl.getUrl())
                    .sessionId(crawlUrl.getSessionId())
                    .statusCode(403)
                    .errorMessage("Blocked by robots.txt")
                    .crawlTime(LocalDateTime.now())
                    .crawlDurationMs(System.currentTimeMillis() - startTime)
                    .depth(crawlUrl.getDepth())
                    .parentUrl(crawlUrl.getParentUrl())
                    .crawlerInstanceId(crawlUrl.getAssignedTo())
                    .build();
            }

            // Apply politeness delay
            if (politenessDelay > 0) {
                Thread.sleep(politenessDelay);
            }

            // Fetch the page
            Document doc = Jsoup.connect(crawlUrl.getUrl())
                .userAgent(userAgent)
                .timeout(30000)
                .followRedirects(true)
                .get();

            // Extract content
            String title = doc.title();
            String content = doc.text();
            String rawHtml = doc.html();
            
            long crawlDuration = System.currentTimeMillis() - startTime;
            
            return CrawledPageEntity.builder()
                .url(crawlUrl.getUrl())
                .title(title)
                .content(content)
                .rawHtml(rawHtml)
                .sessionId(crawlUrl.getSessionId())
                .statusCode(200)
                .contentType("text/html")
                .contentLength(content.length())
                .crawlTime(LocalDateTime.now())
                .crawlDurationMs(crawlDuration)
                .depth(crawlUrl.getDepth())
                .parentUrl(crawlUrl.getParentUrl())
                .crawlerInstanceId(crawlUrl.getAssignedTo())
                .build();
                
        } catch (IOException e) {
            log.error("Error crawling URL: {}", crawlUrl.getUrl(), e);
            return CrawledPageEntity.builder()
                .url(crawlUrl.getUrl())
                .sessionId(crawlUrl.getSessionId())
                .statusCode(0)
                .errorMessage(e.getMessage())
                .crawlTime(LocalDateTime.now())
                .crawlDurationMs(System.currentTimeMillis() - startTime)
                .depth(crawlUrl.getDepth())
                .parentUrl(crawlUrl.getParentUrl())
                .crawlerInstanceId(crawlUrl.getAssignedTo())
                .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Crawling interrupted for URL: {}", crawlUrl.getUrl(), e);
            return CrawledPageEntity.builder()
                .url(crawlUrl.getUrl())
                .sessionId(crawlUrl.getSessionId())
                .statusCode(0)
                .errorMessage("Interrupted")
                .crawlTime(LocalDateTime.now())
                .crawlDurationMs(System.currentTimeMillis() - startTime)
                .depth(crawlUrl.getDepth())
                .parentUrl(crawlUrl.getParentUrl())
                .crawlerInstanceId(crawlUrl.getAssignedTo())
                .build();
        }
    }

    @Override
    public List<String> extractLinks(String html, String baseUrl) {
        List<String> links = new ArrayList<>();
        
        try {
            log.debug("Starting link extraction from baseUrl: {}", baseUrl);
            Document doc = Jsoup.parse(html, baseUrl);
            Elements linkElements = doc.select("a[href]");
            
            log.debug("Found {} anchor elements in HTML", linkElements.size());
            
            for (Element link : linkElements) {
                String href = link.attr("abs:href");
                log.debug("Processing link: {} -> {}", link.attr("href"), href);
                
                if (isValidUrl(href)) {
                    links.add(href);
                    log.debug("Added valid link: {}", href);
                } else {
                    log.debug("Rejected invalid link: {}", href);
                }
            }
            
            log.debug("Link extraction completed. Valid links found: {}", links.size());
            
        } catch (Exception e) {
            log.error("Error extracting links from {}", baseUrl, e);
        }
        
        return links;
    }

    @Override
    public boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        
        return VALID_URL_PATTERN.matcher(url).matches();
    }

    private boolean isAllowedByRobots(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            
            RobotsTxtRules rules = robotsCache.get(host);
            if (rules == null) {
                rules = fetchRobotsTxt(host);
                robotsCache.put(host, rules);
            }
            
            return rules.isAllowed(path, userAgent);
            
        } catch (Exception e) {
            log.debug("Error checking robots.txt for {}, allowing by default", url);
            return true;
        }
    }

    private RobotsTxtRules fetchRobotsTxt(String host) {
        try {
            String robotsUrl = "http://" + host + "/robots.txt";
            Document robotsDoc = Jsoup.connect(robotsUrl)
                .userAgent(userAgent)
                .timeout(5000)
                .get();
            
            return new RobotsTxtRules(robotsDoc.text());
            
        } catch (Exception e) {
            log.debug("Could not fetch robots.txt for {}, allowing all", host);
            return new RobotsTxtRules("");
        }
    }

    @Override
    public void shutdown() {
        log.info("BasicCrawler shutting down");
        robotsCache.clear();
    }

    static class RobotsTxtRules {
        private final List<String> disallowedPaths = new ArrayList<>();
        private final List<String> allowedPaths = new ArrayList<>();

        public RobotsTxtRules(String robotsTxt) {
            parseRobotsTxt(robotsTxt);
        }

        private void parseRobotsTxt(String robotsTxt) {
            if (robotsTxt == null || robotsTxt.trim().isEmpty()) {
                return;
            }

            String[] lines = robotsTxt.split("\n");
            boolean relevantUserAgent = false;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.toLowerCase().startsWith("user-agent:")) {
                    String agent = line.substring(11).trim();
                    relevantUserAgent = agent.equals("*") || 
                        agent.toLowerCase().contains("crawler") ||
                        agent.toLowerCase().contains("bot");
                } else if (relevantUserAgent) {
                    if (line.toLowerCase().startsWith("disallow:")) {
                        String path = line.substring(9).trim();
                        if (!path.isEmpty()) {
                            disallowedPaths.add(path);
                        }
                    } else if (line.toLowerCase().startsWith("allow:")) {
                        String path = line.substring(6).trim();
                        if (!path.isEmpty()) {
                            allowedPaths.add(path);
                        }
                    }
                }
            }
        }

        public boolean isAllowed(String path, String userAgent) {
            if (path == null) path = "/";

            // Check allow rules first
            for (String allowedPath : allowedPaths) {
                if (path.startsWith(allowedPath)) {
                    return true;
                }
            }

            // Check disallow rules
            for (String disallowedPath : disallowedPaths) {
                if (path.startsWith(disallowedPath)) {
                    return false;
                }
            }

            return true;
        }
    }
}
