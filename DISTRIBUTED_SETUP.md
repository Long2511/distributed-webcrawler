# Distributed Web Crawler - Multi-Machine Setup Guide

## 🏗️ Architecture Overview

This distributed web crawler uses a master-worker architecture:

- **Master Node**: Hosts central services (MongoDB, Redis, Kafka) + Web UI + Crawls
- **Worker Nodes**: Connect to master and only crawl (no central services)
- **Coordination**: Via Redis (URL frontier) and Kafka (task distribution)

## 🚀 Quick Setup

### Master Node Setup (Machine 1)

1. **Run the setup script:**
   ```bash
   # Windows
   setup-master.bat
   
   # Linux/Mac
   chmod +x setup-master.sh
   ./setup-master.sh
   ```

2. **Note the IP address** displayed - you'll need this for worker nodes

3. **Access the Web UI** at `http://YOUR_IP:8080`

### Worker Node Setup (Machine 2, 3, 4, ...)

1. **Copy the project** to each worker machine
2. **Run the worker setup script:**
   ```bash
   # Windows
   setup-worker.bat
   
   # Linux/Mac (create similar script)
   ```
3. **Enter the master IP** when prompted
4. **Verify connection** in the master Web UI

## 📋 Manual Setup Instructions

### Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Docker Compose (for master node only)
- Network connectivity between machines

### Master Node Manual Setup

1. **Start Central Services:**
   ```bash
   docker-compose -f docker-compose-services.yml up -d
   ```

2. **Update Configuration:**
   Edit `config/master-node.properties`:
   ```properties
   webcrawler.instance.advertised-host=YOUR_ACTUAL_IP
   ```

3. **Build and Run:**
   ```bash
   mvn clean package -DskipTests
   java -jar -Dspring.config.location=config/master-node.properties target/webcrawler-1.0-SNAPSHOT.jar
   ```

### Worker Node Manual Setup

1. **Update Configuration:**
   Copy `config/worker-node.properties` and update:
   ```properties
   spring.redis.host=MASTER_IP
   spring.kafka.bootstrap-servers=MASTER_IP:9092
   spring.data.mongodb.uri=mongodb://MASTER_IP:27017/webcrawler
   webcrawler.instance.advertised-host=THIS_WORKER_IP
   ```

2. **Build and Run:**
   ```bash
   mvn clean package -DskipTests
   java -jar -Dspring.config.location=config/worker-node-local.properties target/webcrawler-1.0-SNAPSHOT.jar
   ```

## 🌐 Network Configuration

### Firewall Ports to Open

**Master Node:**
- `8080` - Web UI
- `27017` - MongoDB
- `6379` - Redis
- `9092` - Kafka
- `8082` - Redis UI (optional)
- `8083` - MongoDB UI (optional)

**Worker Nodes:**
- `8081` - Worker health endpoint

### IP Address Configuration

**Critical:** Each machine must use its actual network-accessible IP address, not localhost or 127.0.0.1.

Find your IP:
```bash
# Windows
ipconfig | findstr IPv4

# Linux
hostname -I

# Mac
ifconfig | grep inet
```

## 📊 Monitoring the Cluster

### Web UI Dashboard
Access at `http://MASTER_IP:8080` to see:
- Active crawl sessions
- Connected worker nodes
- Crawl statistics
- URL frontier status

### Service UIs
- **Redis UI**: `http://MASTER_IP:8082` (admin/pass)
- **MongoDB UI**: `http://MASTER_IP:8083` (admin/pass)

### Health Checks
- Master: `http://MASTER_IP:8080/actuator/health`
- Workers: `http://WORKER_IP:8081/actuator/health`

## 🔧 Advanced Configuration

### Load Balancing
The system automatically distributes URLs among connected workers based on:
- Machine capacity
- Current load
- Network latency

### Scaling
- **Add workers**: Just run setup-worker.bat on new machines
- **Remove workers**: Stop the Java process (graceful shutdown)
- **High availability**: Run multiple master nodes (advanced setup)

### Performance Tuning

**For high-throughput crawling:**
```properties
# Master node
webcrawler.frontier.batch-size=50
webcrawler.kafka.topics.partition-count=20

# Worker nodes
webcrawler.batch.size=30
webcrawler.worker.max-concurrent-crawls=10
webcrawler.politeness.delay=200
```

## 🛠️ Troubleshooting

### Worker Can't Connect
1. Check firewall settings
2. Verify IP addresses in config
3. Test network connectivity: `telnet MASTER_IP 6379`
4. Check logs: `tail -f logs/webcrawler-worker.log`

### Performance Issues
1. Monitor Redis memory usage
2. Check Kafka consumer lag
3. Adjust batch sizes
4. Scale vertically (more RAM/CPU) or horizontally (more workers)

### Data Consistency
- All data is stored in MongoDB on master
- Workers are stateless
- URL frontier state is in Redis

## 🎯 Example Multi-Machine Setup

```
Machine 1 (Master): 192.168.1.100
├── MongoDB (port 27017)
├── Redis (port 6379)  
├── Kafka (port 9092)
├── Web UI (port 8080)
└── Crawler Worker

Machine 2 (Worker): 192.168.1.101
└── Crawler Worker (port 8081)

Machine 3 (Worker): 192.168.1.102
└── Crawler Worker (port 8081)

Machine 4 (Worker): 192.168.1.103
└── Crawler Worker (port 8081)
```

All workers connect to master's services and receive URL assignments automatically.

## 📈 Expected Performance

With this setup, you should achieve:
- **Single machine**: ~100-500 URLs/minute
- **4-machine cluster**: ~400-2000 URLs/minute
- **Linear scaling** with additional workers (respecting politeness delays)

The actual performance depends on:
- Target website response times
- Network bandwidth
- Politeness delay settings
- Hardware specifications
