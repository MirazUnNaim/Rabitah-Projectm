#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

command -v java >/dev/null || { echo "Java 21 is required."; exit 1; }
if command -v mvn >/dev/null 2>&1; then
  MVN=mvn
elif [[ -x /opt/maven/bin/mvn ]]; then
  MVN=/opt/maven/bin/mvn
elif [[ -x /opt/apache-maven-3.9.16/bin/mvn ]]; then
  MVN=/opt/apache-maven-3.9.16/bin/mvn
elif [[ -x /tmp/apache-maven-3.9.11/bin/mvn ]]; then
  MVN=/tmp/apache-maven-3.9.11/bin/mvn
else
  echo "Maven 3.9+ is required."; exit 1
fi

# A second desktop-launcher click should create another client window, not try to bind a new backend to port 8080.
if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
  echo "Rabitah server is already running. Opening another window..."
  RABITAH_API_BASE_URL=http://127.0.0.1:8080/api/v1 "$MVN" -q -pl Rabitah-Frontend javafx:run
  exit 0
fi

command -v docker >/dev/null || { echo "Docker is required."; exit 1; }

export RABITAH_SYSTEM_ADMIN_PASSWORD="${RABITAH_SYSTEM_ADMIN_PASSWORD:-Rabitah123!}"
export RABITAH_DEMO_PASSWORD="${RABITAH_DEMO_PASSWORD:-Rabitah123!}"
export RABITAH_JWT_SECRET="${RABITAH_JWT_SECRET:-rabitah-local-development-secret-32chars}"

docker compose up -d postgres
"$MVN" -q -pl Rabitah-Backend spring-boot:run > /tmp/rabitah-backend.log 2>&1 &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT INT TERM

echo "Starting Rabitah..."
for _ in {1..60}; do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then break; fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "Backend failed. See /tmp/rabitah-backend.log"; exit 1
  fi
  sleep 1
done
curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null || { echo "Backend did not become ready."; exit 1; }

RABITAH_API_BASE_URL=http://127.0.0.1:8080/api/v1 "$MVN" -q -pl Rabitah-Frontend javafx:run
