@echo off
REM Distributed Web Crawler - Master Node Setup Script (Windows)
REM This script sets up the master node with all central services

echo Setting up Distributed Web Crawler - Master Node
echo ==================================================

REM Check if Docker is running
docker --version >nul 2>&1
if errorlevel 1 (
    echo Docker is not installed or not running. Please install Docker Desktop first.
    pause
    exit /b 1
)

REM Get the current machine's IP address
echo Detecting master node IP address...
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "ipv4"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set MASTER_IP=%%b
        goto :found_ip
    )
)
:found_ip
set MASTER_IP=%MASTER_IP: =%
echo.
echo ================================
echo MASTER NODE IP ADDRESS: %MASTER_IP%
echo ================================
echo.
echo IMPORTANT: Workers must use this IP address to connect!
echo Share this IP with all worker machines: %MASTER_IP%
echo.

REM Create necessary directories
echo Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config
if not exist data mkdir data

REM Update configuration files with actual IP
echo Updating configuration with detected IP: %MASTER_IP%
powershell -Command "(Get-Content docker-compose-services.yml) -replace '192.168.1.100', '%MASTER_IP%' | Set-Content docker-compose-services.yml"
powershell -Command "(Get-Content config\master-node.properties) -replace '192.168.1.100', '%MASTER_IP%' | Set-Content config\master-node.properties"

REM Start central services
echo Starting central services (MongoDB, Redis, Kafka)...
docker-compose -f docker-compose-services.yml up -d

REM Wait for services to be ready
echo Waiting for services to be ready...
timeout /t 30 /nobreak >nul

REM Check service health
echo Checking service health...
docker exec webcrawler-redis redis-cli ping
echo Kafka container status:
docker ps | findstr webcrawler-kafka

REM Build the web crawler application
echo Building web crawler application...
call mvn clean package -DskipTests

REM Start master node
echo Starting master node...
start "WebCrawler Master" java -jar -Dspring.config.location=config/master-node.properties target/webcrawler-1.0-SNAPSHOT.jar

echo Master node setup complete!
echo.
echo ================================================================
echo ACCESS POINTS:
echo   - Web Crawler UI: http://%MASTER_IP%:8080
echo   - Redis UI: http://%MASTER_IP%:8082
echo   - MongoDB UI: http://%MASTER_IP%:8083
echo.
echo WORKER NODE SETUP:
echo   Workers should connect using IP: %MASTER_IP%
echo   Run setup-worker.bat on each worker machine
echo   When prompted, enter this IP: %MASTER_IP%
echo ================================================================
echo.
pause
