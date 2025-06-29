# Distributed Web Crawler System

A highly scalable distributed web crawler implementation with Docker-based deployment, multi-machine support, and a comprehensive web UI for monitoring and control.

## Overview

This project is a production-ready distributed web crawler system designed for horizontal scaling across multiple machines. The system uses a master-worker architecture where the master node hosts all central services (MongoDB, Redis, Kafka) and coordinates crawling tasks, while worker nodes connect remotely to perform distributed crawling.

## 🚀 New Features (Latest Update)

### Priority-Based Crawling
- **Priority Levels**: HIGH, MEDIUM, LOW priority selection for crawl sessions
- **Smart Queue Management**: Priority-based URL frontier with Redis sorted sets
- **UI Priority Selection**: Choose priority when creating new crawl sessions

### Enhanced Session Management
- **Pause/Resume**: Pause running sessions and resume them later
- **Delete Sessions**: Permanently delete sessions and all associated data
- **Session Details**: Comprehensive session statistics and progress tracking
- **Auto-Resume**: Automatic session resumption when workers reconnect

### Fault Tolerance & Error Recovery
- **Circuit Breaker Pattern**: Domain-based circuit breakers to handle failing sites
- **Retry Logic**: Configurable retry attempts with exponential backoff
- **Error Classification**: Distinguish between transient and permanent failures
- **Health Monitoring**: Real-time health checks for all system components

### Scalability Features
- **Dynamic Worker Management**: Automatic worker registration and health monitoring
- **Load Balancing**: Intelligent workload distribution across available workers
- **Auto-Scaling**: Automatic scale up/down based on workload
- **Worker Timeout Handling**: Detect and handle unresponsive workers

### Enhanced UI
- **Modern Dashboard**: Bootstrap 5 with real-time metrics and charts
- **Session Management**: Comprehensive session control interface
- **Priority Visualization**: Color-coded priority levels and progress bars
- **Real-time Monitoring**: Live updates of crawl progress and system health

## Architecture

### Master-Worker Model
- **Master Node**: Hosts all central services and provides web UI for management
- **Worker Nodes**: Connect to master node and execute crawling tasks
- **Automatic IP Detection**: Setup scripts automatically detect and configure network addresses
- **Docker-based Services**: All infrastructure services run in containers for easy deployment

### Key Components
- **Central Services**: MongoDB (data storage), Redis (URL frontier), Kafka (task coordination)
- **Web Management UI**: Dashboard for session control, monitoring, and configuration
- **Database Web UIs**: Redis Commander (port 8082) and Mongo Express (port 8083)
- **Automated Setup**: Windows batch scripts for one-click deployment

## Features

- **True Distributed Architecture**
  - Multi-machine deployment support
  - Horizontal scaling with worker nodes
  - Centralized coordination and monitoring
  - Fault-tolerant task distribution

- **Production-Ready Deployment**
  - Docker Compose orchestration
  - Automated network configuration
  - Robust setup scripts with error handling
  - Data persistence with Docker volumes

- **Intelligent Crawling**
  - **Priority-based URL queue management**
  - **Configurable crawl policies with pause/resume**
  - **Domain-specific rate limiting with circuit breakers**
  - **URL normalization and deduplication**
  - **Retry logic with exponential backoff**

- **Comprehensive Monitoring**
  - Real-time crawl session monitoring
  - System health dashboards with fault tolerance metrics
  - Database web interfaces
  - Detailed crawl statistics and worker status

- **Enhanced Session Management**
  - **Start/pause/resume/stop operations**
  - **Session deletion with data cleanup**
  - **Priority-based session scheduling**
  - **Auto-resume on worker reconnection**
  - **Multi-session support with isolation**

- **Fault Tolerance & Scalability**
  - **Circuit breaker pattern for failing domains**
  - **Configurable retry attempts and delays**
  - **Dynamic worker management and health monitoring**
  - **Load balancing across available workers**
  - **Automatic scale up/down based on workload**

## Technology Stack

- **Core Platform**: Java 23, Spring Boot
- **Containerization**: Docker, Docker Compose
- **Message Queue**: Apache Kafka with Zookeeper
- **Data Storage**: MongoDB, Redis
- **Web Framework**: Spring MVC, Thymeleaf
- **Web Crawler**: JSoup for HTML parsing
- **Management UIs**: Redis Commander, Mongo Express
- **Fault Tolerance**: Custom circuit breaker implementation
- **Monitoring**: Spring Actuator with custom metrics

## Quick Start

### Prerequisites

- **Windows Environment**: Setup scripts are designed for Windows
- **Docker Desktop**: Required for running infrastructure services
- **Java 23 JDK**: For building and running the application
- **Maven 3.8+**: For project building
- **Network Access**: Open ports for multi-machine deployment

### Single-Machine Setup (Master Only)

1. **Clone the repository**
   ```cmd
   git clone <repository-url>
   cd distributed-webcrawler
   ```

2. **Run the master setup script**
   ```cmd
   setup-master.bat
   ```
   
   This script will:
   - Auto-detect your machine's IP address
   - Start all Docker services (MongoDB, Redis, Kafka, Zookeeper)
   - Configure networking for external worker access
   - Build and start the web crawler application
   - Clean up any previous Kafka/Zookeeper data conflicts

3. **Access the web interface**
   - Main Application: http://localhost:8080
   - Redis UI: http://localhost:8082
   - MongoDB UI: http://localhost:8083 (admin/pass)

### Multi-Machine Setup (Master + Workers)

#### On the Master Machine:

1. **Run master setup**
   ```cmd
   setup-master.bat
   ```
   
   Note the **Master IP address** displayed - you'll need this for worker setup.

#### On Each Worker Machine:

1. **Clone the repository**
   ```cmd
   git clone <repository-url>
   cd distributed-webcrawler
   ```

2. **Run worker setup**
   ```cmd
   setup-worker.bat
   ```
   
   When prompted, enter the **Master IP address** from step 1.

3. **Verify connection**
   Check the master dashboard to see connected workers.

## Usage

### Creating and Managing Crawl Sessions

1. **Access the Web Dashboard**
   - Open http://localhost:8080 in your browser
   - Navigate to the main dashboard or sessions page

2. **Start a New Crawl Session**
   - Click "New Session" or use the Quick Crawl form
   - Configure crawl parameters:
     - **Session name and description**
     - **Seed URLs** (starting points)
     - **Priority level** (HIGH, MEDIUM, LOW)
     - **Maximum crawl depth**
     - **Maximum pages to crawl**
     - **Domain restrictions**
     - **Rate limiting settings**
   - Click "Start Crawling"

3. **Monitor Active Sessions**
   - View real-time crawl progress with progress bars
   - Monitor URL queue status and worker activity
   - Check fault tolerance metrics and circuit breaker status
   - View scalability metrics and worker distribution

4. **Session Control**
   - **Pause**: Temporarily stop crawling (can be resumed)
   - **Resume**: Continue a paused session
   - **Stop**: Permanently end the session
   - **Delete**: Remove session and all associated data

### Priority-Based Crawling

- **HIGH Priority**: Crawled first, useful for important or time-sensitive content
- **MEDIUM Priority**: Standard crawling priority (default)
- **LOW Priority**: Background crawling for less critical content

### Fault Tolerance Features

- **Automatic Retries**: Failed URLs are automatically retried with exponential backoff
- **Circuit Breakers**: Domains with repeated failures are temporarily blocked
- **Health Monitoring**: Real-time monitoring of system components
- **Error Recovery**: Automatic recovery from transient failures

### Scalability Features

- **Dynamic Workers**: Add or remove worker nodes without stopping the system
- **Load Balancing**: Work is automatically distributed across available workers
- **Auto-Scaling**: System automatically adjusts based on workload
- **Worker Health**: Automatic detection and handling of unresponsive workers

## Configuration

### Fault Tolerance Settings

```properties
# Retry configuration
webcrawler.fault-tolerance.max-retries=3
webcrawler.fault-tolerance.retry-delay-ms=5000

# Circuit breaker configuration
webcrawler.fault-tolerance.circuit-breaker.threshold=5
webcrawler.fault-tolerance.circuit-breaker.timeout-ms=30000
```

### Scalability Settings

```properties
# Worker management
webcrawler.scalability.worker-timeout-seconds=120
webcrawler.scalability.max-workers-per-session=10
webcrawler.scalability.load-balancing.enabled=true
```

### Crawler Settings

```properties
# Basic crawler settings
webcrawler.max-depth=10
webcrawler.politeness.delay=500
webcrawler.politeness.respect-robots-txt=true
webcrawler.batch.size=20

# Priority settings
webcrawler.frontier.adaptive-allocation=true
webcrawler.frontier.batch-size=20
```

## Network Configuration

### Ports Used
- **8080**: Main web application
- **8081**: Worker node application
- **8082**: Redis Commander web UI
- **8083**: Mongo Express web UI
- **27017**: MongoDB database
- **6379**: Redis server
- **19092**: Kafka external access (for workers)
- **9092**: Kafka internal access (Docker containers)
- **2181**: Zookeeper

### Firewall Requirements
For multi-machine deployment, ensure these ports are accessible:
- 8080, 27017, 6379, 19092 (master → workers)
- All ports above should be open on the master machine

## Docker Services

The system runs the following containerized services:

- **webcrawler-mongodb**: Data storage and session persistence
- **webcrawler-redis**: URL frontier and distributed coordination  
- **webcrawler-kafka**: Task distribution and worker coordination
- **webcrawler-zookeeper**: Kafka cluster coordination
- **webcrawler-redis-ui**: Redis management interface
- **webcrawler-mongo-ui**: MongoDB management interface

### Service Management

```cmd
# View running services
docker-compose ps

# Stop all services
docker-compose down

# Restart services
docker-compose up -d

# View service logs
docker-compose logs -f [service-name]
```

## Monitoring and Troubleshooting

### Health Checks

- **System Health**: `/api/monitor/health`
- **Worker Status**: `/api/monitor/workers`
- **Session Stats**: `/api/monitor/sessions`
- **Fault Tolerance**: `/api/monitor/fault-tolerance`
- **Scalability**: `/api/monitor/scalability`

### Common Issues

1. **Worker Connection Issues**
   - Check firewall settings
   - Verify master IP address
   - Check network connectivity

2. **Crawl Failures**
   - Check circuit breaker status
   - Review retry configuration
   - Monitor error logs

3. **Performance Issues**
   - Adjust batch sizes
   - Check worker count
   - Monitor resource usage

## API Endpoints

### Session Management
- `POST /api/crawler/sessions` - Create new session
- `GET /api/sessions` - List all sessions
- `GET /api/sessions/{id}` - Get session details
- `POST /api/sessions/{id}/pause` - Pause session
- `POST /api/sessions/{id}/resume` - Resume session
- `POST /api/sessions/{id}/stop` - Stop session
- `DELETE /api/sessions/{id}` - Delete session

### Monitoring
- `GET /api/monitor/metrics` - System metrics
- `GET /api/monitor/health` - Health status
- `GET /api/monitor/workers` - Worker status
- `GET /api/monitor/fault-tolerance` - Fault tolerance stats
- `GET /api/monitor/scalability` - Scalability stats

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review the logs in the `logs/` directory
3. Check the health endpoints
4. Create an issue in the repository