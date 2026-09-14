#!/usr/bin/env bash
# Opens a client-only Rabitah window and discovers a campus server on the local network.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${PROJECT_DIR}/run-client-linux.sh"
