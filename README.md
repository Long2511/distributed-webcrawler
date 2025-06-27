# Distributed Web Crawler System

A highly scalable and resilient distributed web crawler implementation with a user interface for control and monitoring.

## Overview

This project is a complete implementation of a distributed web crawler system designed for high performance, scalability, and resilience. The system can distribute crawling tasks across multiple worker nodes, efficiently manage crawl priorities, and handle failures gracefully.

![Distributed Web Crawler Architecture](https://via.placeholder.com/800x400?text=Distributed+Web+Crawler+Architecture)

## Features

- **Distributed Architecture**
  - Horizontal scaling with worker nodes
  - Centralized coordination via Crawler Manager
  - Distributed URL frontier using Redis
  - Message-based communication with Kafka

- **Intelligent Crawling**
  - Prioritized URL queue
  - Politeness policies (respects robots.txt)
  - Configurable crawl depth and scope
  - Domain-specific rate limiting
  - URL normalization and deduplication

- **Resilience Features**
  - Automatic retry for transient failures
  - Circuit breaker patterns for external dependencies
  - Recovery of stalled crawl tasks
  - Graceful degradation under load

- **Data Storage**
  - Structured data in MongoDB
  - Efficient URL registry with Redis Bloom filter
  - Configurable content extraction and filtering

- **Web UI Dashboard**
  - Real-time crawl monitoring
  - Session management (start/pause/resume/stop)
  - Detailed statistics and visualizations
  - Configuration management

## Technology Stack

- **Core Platform**: Java 23, Spring Boot
- **Distributed Processing**: Akka Actor model
- **Web Crawler**: JSoup
- **Message Queue**: Apache Kafka
- **Data Storage**: MongoDB, Redis
- **Web Interface**: Thymeleaf, Bootstrap
- **API**: RESTful endpoints

## System Components

### Core Components

- **Crawler Manager**: Central coordination service
- **URL Frontier**: Prioritized, distributed URL queue
- **Worker Nodes**: Distributed crawling execution
- **Content Processor**: HTML parsing and data extraction

### Storage Components

- **Metadata Store**: MongoDB for page metadata and crawl info
- **URL Registry**: Redis for tracking visited URLs
- **Message Queue**: Kafka for distributed task management

### Web Interface

- **Dashboard**: System overview and real-time monitoring
- **Session Management**: Create and control crawl sessions
- **Status Pages**: Detailed session information and statistics

## Getting Started

### Prerequisites

- Java 23 JDK
- Maven 3.8+
- MongoDB 5.0+
- Redis 6.2+
- Apache Kafka 3.6+

### Installation

1. Clone the repository
   ```
   git clone https://github.com/yourusername/webcrawler.git
   cd webcrawler
   ```

2. Configure the application
   Edit `src/main/resources/application.properties` to set up your MongoDB, Redis, and Kafka connections.

3. Build the application
   ```
   mvn clean package
   ```

4. Run the application
   ```
   java -jar target/webcrawler-1.0-SNAPSHOT.jar
   ```

### Docker Deployment

For containerized deployment, use the provided Docker Compose file:

```
docker-compose up -d
```

This will start the web crawler along with MongoDB, Redis, and Kafka in separate containers.

## Usage

### Starting a Crawl Session

1. Access the web interface at `http://localhost:8080`
2. Navigate to "Sessions" > "New Crawl Session"
3. Enter a name for the session
4. Add seed URLs (starting points for the crawler)
5. Configure crawl parameters:
   - Max depth
   - Pages per domain
   - Crawl delay
   - Allowed domains
6. Click "Start Crawling"

### Monitoring and Control

- View active sessions on the dashboard
- Check detailed statistics for each session
- Pause/resume/stop sessions as needed
- Monitor system resource usage


## Scaling

The system is designed to scale horizontally:

1. **Add Worker Nodes**: Deploy additional worker instances that connect to the same Kafka topics
2. **Increase Kafka Partitions**: Scale the message queue for higher throughput
3. **Shard MongoDB**: For very large crawl data storage
4. **Redis Cluster**: For high-volume URL frontier management

## Architecture Details

### URL Frontier

The URL Frontier manages the queue of URLs to be crawled. It implements:

- Priority-based queuing (5 priority levels)
- Domain-based sharding for politeness
- Efficient visit tracking with Redis sets
- Automatic cleanup of stalled jobs

### Crawler Worker

Workers perform the actual crawling tasks:

- Connect to web pages
- Parse HTML and extract links
- Normalize and filter URLs
- Handle errors with exponential backoff
- Report results to the manager

### User Interface

The web dashboard provides:

- Real-time monitoring of crawler activity
- Visualization of crawl progress
- Configuration interface for new crawl sessions
- Detailed reports on crawled content

## Contributing

Contributions to the project are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- The architecture is inspired by best practices in distributed systems design
- Uses proven technologies for reliability and performance
- Designed for educational and production use cases
