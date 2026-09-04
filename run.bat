@echo off
rem Start the MQ Client desktop app (Windows).
cd /d "%~dp0"
call gradlew.bat :app-ui:run %*
