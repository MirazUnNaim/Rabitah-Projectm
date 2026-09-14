@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul || (echo Java 21 is required. & exit /b 1)
where mvn >nul 2>nul || (echo Maven 3.9 or newer is required. & exit /b 1)
where docker >nul 2>nul || (echo Docker Desktop is required. & exit /b 1)

REM A second click should open another JavaFX window instead of starting a second backend on port 8080.
powershell -NoProfile -Command "try { Invoke-RestMethod http://127.0.0.1:8080/actuator/health | Out-Null; exit 0 } catch { exit 1 }"
if errorlevel 1 goto :start_backend
echo Rabitah server is already running. Opening another window...
set "RABITAH_API_BASE_URL=http://127.0.0.1:8080/api/v1"
call mvn -q -pl Rabitah-Frontend javafx:run
set "RABITAH_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %RABITAH_EXIT_CODE%

:start_backend
if not defined RABITAH_SYSTEM_ADMIN_PASSWORD set "RABITAH_SYSTEM_ADMIN_PASSWORD=Rabitah123!"
if not defined RABITAH_DEMO_PASSWORD set "RABITAH_DEMO_PASSWORD=Rabitah123!"
if not defined RABITAH_JWT_SECRET set "RABITAH_JWT_SECRET=rabitah-local-development-secret-32chars"

docker compose up -d postgres || exit /b 1
start "Rabitah Backend" /min cmd /c "mvn -q -pl Rabitah-Backend spring-boot:run"
echo Starting Rabitah. Please wait...
powershell -NoProfile -Command "$ok=$false; 1..60 | ForEach-Object { try { Invoke-RestMethod http://127.0.0.1:8080/actuator/health | Out-Null; $ok=$true; break } catch { Start-Sleep 1 } }; if(-not $ok){exit 1}" || (echo Backend did not become ready. & exit /b 1)
set "RABITAH_API_BASE_URL=http://127.0.0.1:8080/api/v1"
call mvn -q -pl Rabitah-Frontend javafx:run
set "RABITAH_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %RABITAH_EXIT_CODE%
