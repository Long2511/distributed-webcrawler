package com.ouroboros.webcrawler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB configuration for the distributed web crawler
 * Enables MongoDB repositories and sets up database connection
 */
@Configuration
@EnableMongoRepositories(basePackages = "com.ouroboros.webcrawler.repository")
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Override
    protected String getDatabaseName() {
        return "webcrawler";
    }
}
