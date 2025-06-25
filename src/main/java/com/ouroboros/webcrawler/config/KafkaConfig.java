package com.ouroboros.webcrawler.config;

import com.ouroboros.webcrawler.model.CrawlJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${webcrawler.kafka.topics.crawl-tasks}")
    private String crawlTasksTopic;

    @Value("${webcrawler.kafka.topics.partition-count}")
    private int partitionCount;

    @Value("${webcrawler.kafka.topics.replication-factor}")
    private short replicationFactor;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(configs);
    }

    @Bean
    public NewTopic crawlTasksTopic() {
        return new NewTopic(crawlTasksTopic, partitionCount, replicationFactor);
    }

    @Bean
    public ProducerFactory<String, CrawlJob> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, CrawlJob> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, CrawlJob> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // Add a unique consumer ID for each instance
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, groupId + "-" + UUID.randomUUID().toString().substring(0, 8));

        // Enable auto-commit to allow sharing work
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");

        // Configure deserializers
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CrawlJob.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ouroboros.webcrawler.model");

        // Add these settings for better load balancing across consumers
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10); // Process smaller batches
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 minutes
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000); // 30 seconds
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000); // 10 seconds

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CrawlJob> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CrawlJob> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // Configure concurrency for this instance (each instance can run multiple threads)
        factory.setConcurrency(3);

        // Set manual acknowledgment mode for better control over message processing
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Create an exponential backoff configuration
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000);

        // Set up error handler with retry capability
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            // This is the recovery callback that gets called when retries are exhausted
            log.error("Error processing Kafka message after retries exhausted: {}", exception.getMessage());
        }, backOff);

        // Only retry specific exceptions (optional)
        errorHandler.addRetryableExceptions(
            org.springframework.dao.DataAccessException.class,
            java.net.ConnectException.class,
            org.springframework.kafka.KafkaException.class,
            java.io.IOException.class
        );

        // Set max number of attempts (3 retries total)
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.warn("Failed to process message, retry attempt {}: {}", deliveryAttempt, ex.getMessage());
        });

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
