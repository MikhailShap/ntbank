@echo off
rem NT-Bank: start the whole stand with a double click (Windows).
rem Starts Docker Desktop if needed, runs "docker compose up -d", waits until the bank
rem is healthy and opens the bank and Grafana in a browser.
rem Keep this file in plain ASCII with CRLF line endings: cmd.exe reads it in the console
rem code page, and goto/call labels are unreliable with LF-only line endings.

setlocal EnableExtensions
title NT-Bank: start
rem System tools go first: Git for Windows brings its own find.exe
set "PATH=%SystemRoot%\System32;%PATH%"

set "BANK_URL=http://localhost:8080"
set "GRAFANA_URL=http://localhost:3000"
set "BANK_CONTAINER=ntbank-app"
set "DOCKER_TIMEOUT=180"
set "HEALTH_TIMEOUT=300"
set "GRAFANA_TIMEOUT=30"

pushd "%~dp0" || goto :no_folder
if not exist "docker-compose.yml" goto :no_compose_file

rem ---------------------------------------------------------------- 1. Docker
echo.
echo [1/4] Checking Docker...
call :find_docker_desktop
where docker >nul 2>nul
if errorlevel 1 if defined DOCKER_DESKTOP call :add_cli_to_path
where docker >nul 2>nul
if errorlevel 1 goto :no_docker_desktop

docker info >nul 2>nul
if not errorlevel 1 goto :docker_ready

tasklist /fi "imagename eq Docker Desktop.exe" /nh 2>nul | find /i "Docker Desktop.exe" >nul
if not errorlevel 1 goto :docker_launched
if not defined DOCKER_DESKTOP goto :no_docker_desktop
echo       Starting Docker Desktop: "%DOCKER_DESKTOP%"
start "" "%DOCKER_DESKTOP%"

:docker_launched
echo       Waiting for Docker, up to %DOCKER_TIMEOUT% seconds...
call :now STARTED
:wait_docker
docker info >nul 2>nul
if not errorlevel 1 goto :docker_ready
call :elapsed STARTED PASSED
if %PASSED% GEQ %DOCKER_TIMEOUT% goto :docker_timeout
echo       %PASSED%s: Docker is not ready yet
call :sleep 3
goto :wait_docker

:docker_ready
echo       Docker is running.

rem ---------------------------------------------------------------- 2. Containers
echo.
echo [2/4] Starting the containers: docker compose up -d
echo       The very first start builds the images and takes 5-10 minutes.
docker compose up -d
if errorlevel 1 goto :compose_failed

rem ---------------------------------------------------------------- 3. Health
echo.
echo [3/4] Waiting for the bank, up to %HEALTH_TIMEOUT% seconds...
call :now STARTED
:wait_bank
set "STATE=not found"
set "HEALTH=unknown"
for /f "tokens=1,2" %%a in ('docker inspect -f "{{.State.Status}} {{.State.Health.Status}}" %BANK_CONTAINER% 2^>nul') do (
    set "STATE=%%a"
    set "HEALTH=%%b"
)
if "%HEALTH%"=="healthy" goto :bank_ready
call :elapsed STARTED PASSED
if %PASSED% GEQ %HEALTH_TIMEOUT% goto :bank_timeout
echo       %PASSED%s: container %STATE%, health %HEALTH%
call :sleep 5
goto :wait_bank

:bank_ready
echo       The bank is healthy.

rem ---------------------------------------------------------------- 4. Browser
echo.
echo [4/4] Opening the browser...
call :wait_grafana
call :open_browser

echo.
echo ================================================================
echo  NT-Bank is running
echo ================================================================
echo  Bank        %BANK_URL%
echo  Test SMS    %BANK_URL%/dev/sms
echo  Defects     %BANK_URL%/admin/chaos
echo  Swagger     %BANK_URL%/swagger-ui
echo  Grafana     %GRAFANA_URL%
echo  Prometheus  http://localhost:9090
echo.
echo  Clients     user0001 ... user1000, password Passw0rd!
echo  Admin       admin / admin
echo  Grafana     view without a login, edit as admin / admin
echo  JMeter CSV  test-data\users.csv
echo.
echo  To stop the stand, run stop.bat
echo ================================================================
echo.
popd
pause
exit /b 0

rem ---------------------------------------------------------------- Errors

:no_folder
echo.
echo Cannot open the project folder: "%~dp0"
pause
exit /b 1

:no_compose_file
echo.
echo docker-compose.yml was not found next to start.bat.
echo Keep start.bat in the root folder of the project.
goto :fail

:no_docker_desktop
echo.
echo Docker Desktop was not found on this computer.
echo Start Docker Desktop by hand, wait until it is running, then run start.bat again.
echo If it is not installed: https://www.docker.com/products/docker-desktop/
goto :fail

:docker_timeout
echo.
echo Docker did not start within %DOCKER_TIMEOUT% seconds.
echo Open Docker Desktop, check that it is running and shows no prompts or errors,
echo then run start.bat again.
goto :fail

:compose_failed
echo.
echo "docker compose up -d" failed, see the messages above.
echo Usual reasons: a port is already in use, or the lite profile is still running.
echo Run stop.bat and try again.
goto :fail

:bank_timeout
echo.
echo The bank did not become healthy within %HEALTH_TIMEOUT% seconds.
echo Last status: container %STATE%, health %HEALTH%.
echo To see what is going on, run this in the project folder:
echo     docker compose logs bank-app
goto :fail

:fail
echo.
popd
pause
exit /b 1

rem ---------------------------------------------------------------- Docker Desktop lookup

:find_docker_desktop
set "DOCKER_DESKTOP="
set "UNINSTALL=SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Docker Desktop"
call :try_registry "HKCU\%UNINSTALL%" InstallLocation
call :try_registry "HKLM\%UNINSTALL%" InstallLocation
call :try_registry "HKLM\SOFTWARE\Docker Inc.\Docker\1.0" AppPath
call :try_registry "HKCU\SOFTWARE\Docker Inc.\Docker\1.0" AppPath
call :try_folder "%ProgramFiles%\Docker\Docker"
call :try_folder "%ProgramW6432%\Docker\Docker"
call :try_folder "%LOCALAPPDATA%\Programs\DockerDesktop"
call :try_folder "%LOCALAPPDATA%\Programs\Docker\Docker"
call :try_folder "%LOCALAPPDATA%\Docker\Docker"
rem docker.exe in PATH lives in <install folder>\resources\bin
for /f "delims=" %%i in ('where docker 2^>nul') do call :try_folder "%%~dpi..\.."
exit /b 0

:try_registry
if defined DOCKER_DESKTOP exit /b 0
call :reg_value "%~1" "%~2" FOUND_FOLDER
if defined FOUND_FOLDER call :try_folder "%FOUND_FOLDER%"
exit /b 0

:try_folder
if defined DOCKER_DESKTOP exit /b 0
if exist "%~f1\Docker Desktop.exe" set "DOCKER_DESKTOP=%~f1\Docker Desktop.exe"
exit /b 0

:add_cli_to_path
for %%i in ("%DOCKER_DESKTOP%") do set "PATH=%%~dpiresources\bin;%PATH%"
exit /b 0

rem ---------------------------------------------------------------- Browser

:wait_grafana
call :now STARTED
:wait_grafana_loop
curl --noproxy "*" -s -o nul -m 2 "%GRAFANA_URL%/api/health"
if not errorlevel 1 exit /b 0
call :elapsed STARTED PASSED
if %PASSED% GEQ %GRAFANA_TIMEOUT% exit /b 0
call :sleep 2
goto :wait_grafana_loop

:open_browser
set "ASSOCIATIONS=HKCU\Software\Microsoft\Windows\Shell\Associations\UrlAssociations\http"
call :reg_value "%ASSOCIATIONS%\UserChoiceLatest\ProgId" ProgId PROG_ID
if not defined PROG_ID call :reg_value "%ASSOCIATIONS%\UserChoice" ProgId PROG_ID
echo(%PROG_ID%| find /i "firefox" >nul
if errorlevel 1 goto :open_default_browser

rem Firefox may be set to the JMeter recorder proxy: without the recorder pages do not open
call :find_edge
if not defined EDGE_EXE goto :no_browser
echo       The default browser is Firefox, using Microsoft Edge instead.
start "" "%EDGE_EXE%" "%BANK_URL%" "%GRAFANA_URL%"
exit /b 0

:open_default_browser
start "" "%BANK_URL%"
call :sleep 2
start "" "%GRAFANA_URL%"
exit /b 0

:no_browser
echo       The default browser is Firefox and Microsoft Edge was not found.
echo       Open the addresses below in any browser without the JMeter proxy.
exit /b 0

:find_edge
set "EDGE_EXE="
call :reg_value "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe" Path EDGE_FOLDER
if defined EDGE_FOLDER if exist "%EDGE_FOLDER%\msedge.exe" set "EDGE_EXE=%EDGE_FOLDER%\msedge.exe"
if defined EDGE_EXE exit /b 0
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" set "EDGE_EXE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if defined EDGE_EXE exit /b 0
if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" set "EDGE_EXE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
exit /b 0

rem ---------------------------------------------------------------- Helpers

rem Reads a registry value: call :reg_value "key" name VARIABLE
:reg_value
set "%~3="
for /f "tokens=1,2,*" %%a in ('reg query "%~1" /v "%~2" 2^>nul') do if /i "%%a"=="%~2" set "%~3=%%c"
exit /b 0

rem Seconds since midnight: call :now VARIABLE. "100" + value, then modulo 100, keeps "08" and "8" valid.
:now
for /f "tokens=1-3 delims=:.," %%a in ("%time: =0%") do set /a "%~1=(100%%a%%100)*3600+(100%%b%%100)*60+(100%%c%%100)"
exit /b 0

rem Seconds passed since a mark from :now, also across midnight: call :elapsed MARK VARIABLE
:elapsed
call :now NOW_SECONDS
set /a "%~2=(NOW_SECONDS-%~1+86400)%%86400"
exit /b 0

rem "timeout" refuses to work with redirected input, ping waits in any case
:sleep
set /a "PINGS=%~1+1"
ping -n %PINGS% 127.0.0.1 >nul
exit /b 0
