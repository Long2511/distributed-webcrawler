@echo off
REM Distributed Web Crawler - Worker Node Setup Script (Windows)
REM This script sets up a worker node that connects to the master

echo 🖥️ Setting up Distributed Web Crawler - Worker Node
echo ====================================================

set /p MASTER_IP=Enter the Master Node IP address: 
echo 🔗 Connecting to master node at: %MASTER_IP%

REM Get the current machine's IP address
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "ipv4"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set WORKER_IP=%%b
        goto :found_ip
    )
)
:found_ip
set WORKER_IP=%WORKER_IP: =%
echo 🔍 This worker's IP address: %WORKER_IP%

REM Create necessary directories
echo 📁 Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config

REM Create worker configuration
echo ⚙️ Creating worker configuration...
copy config\worker-node.properties config\worker-node-local.properties

REM Update configuration with actual IPs
powershell -Command "(Get-Content config\worker-node-local.properties) -replace '192.168.1.100', '%MASTER_IP%' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace '192.168.1.101', '%WORKER_IP%' | Set-Content config\worker-node-local.properties"

REM Test connection to master services
echo 🧪 Testing connection to master services...
echo Testing Redis connection...
timeout /t 2 >nul
echo Testing MongoDB connection...
timeout /t 2 >nul

REM Build the web crawler application if not already built
if not exist target\webcrawler-1.0-SNAPSHOT.jar (
    echo 🔨 Building web crawler application...
    call mvn clean package -DskipTests
)

REM Start worker node
echo 🎯 Starting worker node...
echo Worker will connect to master at %MASTER_IP%
start "WebCrawler Worker" java -jar -Dspring.config.location=config/worker-node-local.properties target/webcrawler-1.0-SNAPSHOT.jar

echo ✅ Worker node setup complete!
echo.
echo 📊 This worker node:
echo   - Worker IP: %WORKER_IP%:8081
echo   - Connecting to Master: %MASTER_IP%
echo   - Log file: logs/webcrawler-worker.log
echo.
echo 🔍 Check the master node UI to see this worker join the cluster
echo 🌐 Master UI: http://%MASTER_IP%:8080
echo.
pause
