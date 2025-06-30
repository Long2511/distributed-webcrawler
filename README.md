# Distributed Web Crawler System

A highly scalable distributed web crawler implementation with Docker-based deployment, multi-machine support, and a comprehensive web UI for monitoring and control.

## Overview

# How to run the worker node

```bash
mvn clean package -DskipTests

java -Dspring.config.location=config/worker-node-02.properties -jar target/webcrawler-1.0-SNAPSHOT.jar

java -Dserver.port=8888 -Dspring.config.additional-location=config/worker-node-02.properties -jar target/webcrawler-1.0-SNAPSHOT.jar

```

This project is a production-ready distributed web crawler system designed for horizontal scaling across multiple machines. The system uses a master-worker architecture where the master node hosts all central services (MongoDB, Redis, Kafka) and coordinates crawling tasks, while worker nodes connect remotely to perform distributed crawling.

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
  - Prioritized URL queue management
  - Configurable crawl policies
  - Domain-specific rate limiting
  - URL normalization and deduplication

- **Comprehensive Monitoring**
  - Real-time crawl session monitoring
  - Database web interfaces

- **Easy Management**
  - Web-based session control
  - Start/pause/resume/stop operations
  - Configuration through UI
  - Multi-session support

## Technology Stack

- **Core Platform**: Java 17, Spring Boot
- **Containerization**: Docker, Docker Compose
- **Message Queue**: Apache Kafka with Zookeeper
- **Data Storage**: MongoDB, Redis
- **Web Framework**: Spring MVC, Thymeleaf
- **Web Crawler**: JSoup for HTML parsing
- **Management UIs**: Redis Commander, Mongo Express

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

## Network Configuration

### Ports Used
- **8080**: Main web application
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

## Usage

### Creating and Managing Crawl Sessions

1. **Access the Web Dashboard**
   - Open http://localhost:8080 in your browser
   - Navigate to the main dashboard

2. **Start a New Crawl Session**
   - Click "New Session" or navigate to session management
   - Configure crawl parameters:
     - Session name and description
     - Seed URLs (starting points)
     - Maximum crawl depth
     - Domain restrictions
     - Rate limiting settings
   - Click "Start Crawling"

3. **Monitor Active Sessions**
   - View real-time crawl progress
   - Monitor URL queue status
   - Check worker activity and distribution
   - View crawled page statistics

4. **Session Control**
   - **Pause**: Temporarily stop crawling (can be resumed)
   - **Resume**: Continue a paused session
   - **Stop**: Permanently end the session
   - **View Details**: Examine session statistics and data

### Multi-Machine Operations

- **Worker Management**: View connected workers on the dashboard
- **Load Distribution**: Monitor task distribution across workers
- **Health Monitoring**: Check worker connectivity and performance
- **Scaling**: Add or remove workers dynamically

### Database Access

- **Redis Commander** (http://localhost:8082): 
  - View URL queues and crawl state
  - Monitor Redis performance
  - Clear data if needed

- **Mongo Express** (http://localhost:8083):
  - Browse crawled page data
  - Query session information
  - Export crawl results

## Configuration

### Master Node Configuration
Located in `config/master-node.properties`:
- Automatically updated by setup script with detected IP
- Central services connection settings
- Web UI port configuration

### Worker Node Configuration  
Located in `config/worker-node.properties`:
- Master node connection settings
- Worker-specific settings
- Updated by setup script with master IP

### Application Settings
Main configuration in `src/main/resources/application.properties`:
- Default application settings
- Overridden by node-specific config files


### Data Cleanup

```cmd
# Clear Redis data
docker exec webcrawler-redis redis-cli FLUSHALL

# Clear Kafka topics
docker exec webcrawler-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic webcrawler.tasks

# Reset all Docker volumes
docker-compose down -v
```


### Project Structure

```
distributed-webcrawler/
├── src/main/java/com/ouroboros/webcrawler/
│   ├── WebCrawlerApplication.java        # Main application entry
│   ├── config/                           # Configuration classes
│   ├── controller/                       # Web controllers
│   ├── entity/                          # Data entities
│   ├── frontier/                        # URL frontier management
│   ├── manager/                         # Crawler coordination
│   ├── worker/                          # Worker node implementation
│   └── ...
├── config/                              # Node-specific configurations
│   ├── master-node.properties
│   └── worker-node.properties
├── docker-compose.yml                   # Docker service definitions
├── setup-master.bat                     # Master node setup script
├── setup-worker.bat                     # Worker node setup script
└── ...
```


