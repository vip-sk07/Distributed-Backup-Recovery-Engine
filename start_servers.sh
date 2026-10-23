#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

PORT="${1:-8035}"
WEB_PORT="${2:-8080}"
STORE_DIR="${3:-./store}"

# Ensure store directory hierarchy exists
mkdir -p "$STORE_DIR/chunks" "$STORE_DIR/manifests" "$STORE_DIR/tmp"

# Clean up any stale instances
if [ -f .dashboard.pid ]; then
    kill "$(cat .dashboard.pid)" 2>/dev/null || true
    rm -f .dashboard.pid
fi
if [ -f .server.pid ]; then
    kill "$(cat .server.pid)" 2>/dev/null || true
    rm -f .server.pid
fi

echo "[STARTUP] Starting C POSIX Storage Backend on port ${PORT}..."
nohup ./server/server "${PORT}" "${STORE_DIR}" > server.log 2>&1 &
SERVER_PID=$!
echo $SERVER_PID > .server.pid
disown $SERVER_PID 2>/dev/null || true

# Wait up to 5 seconds for C server to bind port
READY=0
for i in {1..10}; do
    if (exec 3<>/dev/tcp/127.0.0.1/"${PORT}") 2>/dev/null; then
        READY=1
        exec 3<&- 2>/dev/null || true
        exec 3>&- 2>/dev/null || true
        break
    fi
    sleep 0.5
done

if [ $READY -eq 1 ]; then
    echo "[STARTUP] C Server listening on port ${PORT} (PID: ${SERVER_PID})."
else
    echo "[WARN] C Server started with PID ${SERVER_PID}, continuing..."
fi

echo "[STARTUP] Starting Java Web Dashboard on port ${WEB_PORT}..."
nohup /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
  --enable-native-access=ALL-UNNAMED \
  -cp "client/bin:client/lib/*" \
  com.backup.Phase4RestoreAndUiMain --daemon --port "${PORT}" --webPort "${WEB_PORT}" > dashboard.log 2>&1 &
DASHBOARD_PID=$!
echo $DASHBOARD_PID > .dashboard.pid
disown $DASHBOARD_PID 2>/dev/null || true

# Wait up to 10 seconds for Web Dashboard to initialize
DASHBOARD_READY=0
for i in {1..20}; do
    if curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${WEB_PORT}/api/stats" 2>/dev/null | grep -q "200"; then
        DASHBOARD_READY=1
        break
    fi
    sleep 0.5
done

if [ $DASHBOARD_READY -eq 1 ]; then
    echo "[STARTUP] Web Dashboard listening on http://127.0.0.1:${WEB_PORT} (PID: ${DASHBOARD_PID})."
else
    echo "[STARTUP] Web Dashboard process launched (PID: ${DASHBOARD_PID}). Check dashboard.log."
fi

echo "=========================================================="
echo "  Distributed Backup System is ACTIVE"
echo "  - C Backend Storage : 0.0.0.0:${PORT} (PID: ${SERVER_PID})"
echo "  - Web Dashboard UI  : http://127.0.0.1:${WEB_PORT} (PID: ${DASHBOARD_PID})"
echo "  - Server Logs       : ${DIR}/server.log"
echo "  - Dashboard Logs    : ${DIR}/dashboard.log"
echo "=========================================================="
