@echo off
rem NT-Bank: stop the stand with a double click (Windows).
rem Runs "docker compose down": containers are removed, data stays in Docker volumes.
rem Keep this file in plain ASCII with CRLF line endings.

setlocal EnableExtensions
title NT-Bank: stop
rem System tools go first: Git for Windows brings its own find.exe
set "PATH=%SystemRoot%\System32;%PATH%"

pushd "%~dp0" || goto :no_folder
if not exist "docker-compose.yml" goto :no_compose_file

docker info >nul 2>nul
if errorlevel 1 goto :docker_not_running

echo.
echo Stopping the stand: docker compose down
docker compose down
if errorlevel 1 goto :down_failed

echo.
echo The stand is stopped. Your data is kept: accounts, transfers, Grafana and InfluxDB history.
echo To start it again, run start.bat
echo To delete all the data as well, run: docker compose down -v
echo Docker Desktop keeps running, you can close it from the system tray.
echo.
popd
pause
exit /b 0

:no_folder
echo.
echo Cannot open the project folder: "%~dp0"
pause
exit /b 1

:no_compose_file
echo.
echo docker-compose.yml was not found next to stop.bat.
echo Keep stop.bat in the root folder of the project.
goto :fail

:docker_not_running
echo.
echo Docker is not running, so the stand is already stopped.
echo To start it again, run start.bat
echo.
popd
pause
exit /b 0

:down_failed
echo.
echo "docker compose down" failed, see the messages above.
goto :fail

:fail
echo.
popd
pause
exit /b 1
