package com.ouroboros.webcrawler.model;

import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CrawlResult {
    private CrawledPageEntity crawledPage;
    private List<String> extractedLinks;
}
