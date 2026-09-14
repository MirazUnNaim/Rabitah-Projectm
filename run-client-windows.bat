@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul || (echo Java 21 is required. & exit /b 1)
where mvn >nul 2>nul || (echo Maven 3.9 or newer is required. & exit /b 1)
set "RABITAH_API_BASE_URL="
echo Finding the Rabitah server automatically...
call mvn -q -pl Rabitah-Frontend javafx:run
set "RABITAH_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %RABITAH_EXIT_CODE%
