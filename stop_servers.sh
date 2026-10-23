#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

PORT="${1:-8035}"
WEB_PORT="${2:-8080}"

echo "[STOP] Stopping Distributed Backup System..."

if [ -f .dashboard.pid ]; then
    D_PID=$(cat .dashboard.pid 2>/dev/null || true)
    if [ -n "$D_PID" ] && kill -0 "$D_PID" 2>/dev/null; then
        echo "[STOP] Terminating Web Dashboard (PID: $D_PID)..."
        kill "$D_PID" 2>/dev/null || true
    fi
    rm -f .dashboard.pid
fi

if [ -f .server.pid ]; then
    S_PID=$(cat .server.pid 2>/dev/null || true)
    if [ -n "$S_PID" ] && kill -0 "$S_PID" 2>/dev/null; then
        echo "[STOP] Terminating C Server (PID: $S_PID)..."
        kill "$S_PID" 2>/dev/null || true
    fi
    rm -f .server.pid
fi

# Fallback check on ports
fuser -k "${WEB_PORT}/tcp" 2>/dev/null || true
fuser -k "${PORT}/tcp" 2>/dev/null || true

echo "[STOP] System stopped successfully."
