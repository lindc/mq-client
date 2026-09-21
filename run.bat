@echo off
setlocal
rem Start the MQ Client desktop app (Windows).
cd /d "%~dp0"

rem Prefer a locally installed Gradle (GRADLE_HOME, then PATH) so we skip the
rem wrapper's distribution download. Falls back to gradlew.bat when none is found.
set "GRADLE_CMD="
if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" set "GRADLE_CMD=%GRADLE_HOME%\bin\gradle.bat"
if not defined GRADLE_CMD for %%G in (gradle.bat) do if not "%%~$PATH:G"=="" set "GRADLE_CMD=%%~$PATH:G"

if defined GRADLE_CMD (
  echo Using local Gradle: %GRADLE_CMD%
  call "%GRADLE_CMD%" :app-ui:run %*
) else (
  call gradlew.bat :app-ui:run %*
)

set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
