package com.ouroboros.webcrawler.config;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import jakarta.annotation.PostConstruct;

@Configuration
@EnableMongoRepositories(basePackages = "com.ouroboros.webcrawler.repository")
@EnableMongoAuditing
public class MongoConfig {

    // Removing validation for now to fix compilation errors
    // We can add it back once Jakarta validation is working properly

    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void initIndexes() {
        // Create a TTL index for crawlUrls after 30 days
        mongoTemplate.indexOps("crawlUrls").ensureIndex(
            new Index().named("ttl_completed_urls")
                .on("updatedAt", org.springframework.data.domain.Sort.Direction.ASC)
                .expire(30 * 24 * 3600)
        );
    }
}
