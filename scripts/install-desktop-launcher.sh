#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}"
APPLICATIONS_DIR="${DATA_DIR}/applications"
ICONS_DIR="${DATA_DIR}/icons/hicolor/512x512/apps"
ICON_PATH="${ICONS_DIR}/com.rabitah.Rabitah.png"
LAUNCHER_PATH="${APPLICATIONS_DIR}/com.rabitah.Rabitah.desktop"

mkdir -p "${APPLICATIONS_DIR}" "${ICONS_DIR}"
install -m 0644 "${PROJECT_DIR}/Rabitah-Frontend/src/main/resources/com/rabitah/frontend/images/rabitah-app-icon-round-v1.png" "${ICON_PATH}"
sed -e "s|@PROJECT_DIR@|${PROJECT_DIR}|g" -e "s|@ICON_PATH@|${ICON_PATH}|g" \
    "${PROJECT_DIR}/desktop/rabitah.desktop.in" > "${LAUNCHER_PATH}"
chmod 644 "${LAUNCHER_PATH}"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${APPLICATIONS_DIR}" >/dev/null 2>&1 || true
fi

printf 'Rabitah launcher installed: %s\n' "${LAUNCHER_PATH}"
