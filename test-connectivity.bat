@echo off
REM Distributed Web Crawler - Connectivity Test Script
REM Run this on worker nodes to verify they can connect to master services

echo =======================================================
echo Distributed Web Crawler - Connectivity Test
echo =======================================================
echo.
echo 📋 Enter the master node IP address to test connectivity.
echo.

set /p MASTER_IP=🔗 Enter Master Node IP address: 
echo.
echo Testing connectivity to master node: %MASTER_IP%
echo.

echo [1/4] Testing Kafka (port 9092)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 9092 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo ✓ Kafka connection successful
) else (
    echo ✗ Kafka connection failed
    set HAS_ISSUES=1
)

echo [2/4] Testing MongoDB (port 27017)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 27017 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo ✓ MongoDB connection successful
) else (
    echo ✗ MongoDB connection failed  
    set HAS_ISSUES=1
)

echo [3/4] Testing Redis (port 6379)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 6379 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo ✓ Redis connection successful
) else (
    echo ✗ Redis connection failed
    set HAS_ISSUES=1
)

echo [4/4] Testing Web UI (port 8080)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 8080 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo ✓ Web UI connection successful
) else (
    echo ✗ Web UI connection failed (may be OK if master not started yet)
)

echo.
echo =======================================================

if defined HAS_ISSUES (
    echo ❌ CONNECTIVITY ISSUES DETECTED
    echo.
    echo Please check:
    echo 1. Master node services are running
    echo 2. Firewall allows connections on these ports
    echo 3. Network connectivity between machines
    echo 4. Master IP address is correct: %MASTER_IP%
    echo.
    echo See TROUBLESHOOTING.md for detailed guidance.
) else (
    echo ✅ ALL SERVICES ACCESSIBLE
    echo.
    echo You should be able to start the worker node now:
    echo java -jar target/webcrawler-1.0-SNAPSHOT.jar --spring.config.location=config/worker-node.properties
)

echo.
pause
