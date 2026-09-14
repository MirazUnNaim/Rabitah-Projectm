#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if command -v mvn >/dev/null 2>&1; then MVN=mvn
elif [[ -x /opt/maven/bin/mvn ]]; then MVN=/opt/maven/bin/mvn
elif [[ -x /opt/apache-maven-3.9.16/bin/mvn ]]; then MVN=/opt/apache-maven-3.9.16/bin/mvn
elif [[ -x /tmp/apache-maven-3.9.11/bin/mvn ]]; then MVN=/tmp/apache-maven-3.9.11/bin/mvn
else echo "Maven 3.9+ is required."; exit 1; fi
unset RABITAH_API_BASE_URL
echo "Finding the Rabitah server automatically..."
"$MVN" -q -pl Rabitah-Frontend javafx:run
