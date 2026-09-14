#!/usr/bin/env bash
# macOS Finder opens .command files in Terminal. The JavaFX client and Docker Compose setup are shared with Linux.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${PROJECT_DIR}/run-linux.sh"
