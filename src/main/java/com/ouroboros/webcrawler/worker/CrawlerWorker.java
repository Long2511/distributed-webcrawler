package com.ouroboros.webcrawler.worker;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;

import java.util.List;

public interface CrawlerWorker {

    CrawledPageEntity crawl(CrawlUrl crawlUrl);

    List<String> extractLinks(String html, String baseUrl);

    boolean isValidUrl(String url);

    void shutdown();
}
