#!/bin/bash

# Distributed Web Crawler - Master Node Setup Script
# This script sets up the master node with all central services

echo "🚀 Setting up Distributed Web Crawler - Master Node"
echo "=================================================="

# Check if Docker and Docker Compose are installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker first."
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo "❌ Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

# Get the current machine's IP address
IP_ADDRESS=$(hostname -I | awk '{print $1}')
echo "🔍 Detected IP address: $IP_ADDRESS"

# Create necessary directories
echo "📁 Creating directories..."
mkdir -p logs config data

# Update Kafka configuration with actual IP
echo "⚙️ Updating Kafka configuration with IP: $IP_ADDRESS"
sed -i "s/192.168.1.100/$IP_ADDRESS/g" docker-compose-services.yml
sed -i "s/192.168.1.100/$IP_ADDRESS/g" config/master-node.properties

# Start central services
echo "🐳 Starting central services (MongoDB, Redis, Kafka)..."
docker-compose -f docker-compose-services.yml up -d

# Wait for services to be ready
echo "⏳ Waiting for services to be ready..."
sleep 30

# Check service health
echo "🏥 Checking service health..."
echo "MongoDB: $(docker exec webcrawler-mongodb mongosh --eval 'db.runCommand("ping")' --quiet)"
echo "Redis: $(docker exec webcrawler-redis redis-cli ping)"
echo "Kafka: $(docker exec webcrawler-kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | wc -l) topics available"

# Build the web crawler application
echo "🔨 Building web crawler application..."
mvn clean package -DskipTests

# Start master node
echo "🎯 Starting master node..."
java -jar -Dspring.config.location=config/master-node.properties target/webcrawler-1.0-SNAPSHOT.jar &

echo "✅ Master node setup complete!"
echo ""
echo "📊 Access points:"
echo "  - Web Crawler UI: http://$IP_ADDRESS:8080"
echo "  - Redis UI: http://$IP_ADDRESS:8082"
echo "  - MongoDB UI: http://$IP_ADDRESS:8083"
echo ""
echo "🔗 For worker nodes to connect, use IP: $IP_ADDRESS"
echo "📋 Share this IP with other machines that will join as workers"
