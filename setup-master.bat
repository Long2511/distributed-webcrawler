@echo off
REM Distributed Web Crawler - Master Node Setup Script (Windows)
REM This script sets up the master node with all central services

echo 🚀 Setting up Distributed Web Crawler - Master Node
echo ==================================================

REM Check if Docker is running
docker --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Docker is not installed or not running. Please install Docker Desktop first.
    pause
    exit /b 1
)

REM Get the current machine's IP address
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "ipv4"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set IP_ADDRESS=%%b
        goto :found_ip
    )
)
:found_ip
set IP_ADDRESS=%IP_ADDRESS: =%
echo 🔍 Detected IP address: %IP_ADDRESS%

REM Create necessary directories
echo 📁 Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config
if not exist data mkdir data

REM Update configuration files with actual IP
echo ⚙️ Updating configuration with IP: %IP_ADDRESS%
powershell -Command "(Get-Content docker-compose-services.yml) -replace '192.168.1.100', '%IP_ADDRESS%' | Set-Content docker-compose-services.yml"
powershell -Command "(Get-Content config\master-node.properties) -replace '192.168.1.100', '%IP_ADDRESS%' | Set-Content config\master-node.properties"

REM Start central services
echo 🐳 Starting central services (MongoDB, Redis, Kafka)...
docker-compose -f docker-compose-services.yml up -d

REM Wait for services to be ready
echo ⏳ Waiting for services to be ready...
timeout /t 30 /nobreak >nul

REM Check service health
echo 🏥 Checking service health...
docker exec webcrawler-redis redis-cli ping
echo Kafka container status:
docker ps | findstr webcrawler-kafka

REM Build the web crawler application
echo 🔨 Building web crawler application...
call mvn clean package -DskipTests

REM Start master node
echo 🎯 Starting master node...
start "WebCrawler Master" java -jar -Dspring.config.location=config/master-node.properties target/webcrawler-1.0-SNAPSHOT.jar

echo ✅ Master node setup complete!
echo.
echo 📊 Access points:
echo   - Web Crawler UI: http://%IP_ADDRESS%:8080
echo   - Redis UI: http://%IP_ADDRESS%:8082
echo   - MongoDB UI: http://%IP_ADDRESS%:8083
echo.
echo 🔗 For worker nodes to connect, use IP: %IP_ADDRESS%
echo 📋 Share this IP with other machines that will join as workers
echo.
pause
