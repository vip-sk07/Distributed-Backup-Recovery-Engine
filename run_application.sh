#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

PORT="${1:-8035}"
WEB_PORT="${2:-8080}"
STORE_DIR="${3:-./store}"

# Ensure store directory hierarchy exists
mkdir -p "$STORE_DIR/chunks" "$STORE_DIR/manifests" "$STORE_DIR/tmp"

# Kill any existing stale processes listening on the ports
fuser -k "${PORT}/tcp" 2>/dev/null || true
fuser -k "${WEB_PORT}/tcp" 2>/dev/null || true
sleep 0.5

echo "=========================================================="
echo "  Starting Distributed Backup, Sync & Recovery System    "
echo "  - C POSIX Storage Server: port ${PORT}                 "
echo "  - Web Dashboard UI      : http://127.0.0.1:${WEB_PORT} "
echo "=========================================================="

# Trap termination to kill child C server process
cleanup() {
    echo ""
    echo "[RUNNER] Terminating background C storage server..."
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
    fi
    exit 0
}
trap cleanup SIGTERM SIGINT EXIT

# Start C backend server in background
./server/server "${PORT}" "${STORE_DIR}" &
SERVER_PID=$!

# Wait for C server to bind port
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
    echo "[RUNNER] C Storage Backend is listening on 0.0.0.0:${PORT} (PID: ${SERVER_PID})"
else
    echo "[RUNNER] Warning: Port ${PORT} check timed out, continuing..."
fi

# Detect Java 25 or fallback
JAVA_CMD="java"
if [ -x "/usr/lib/jvm/java-25-openjdk-amd64/bin/java" ]; then
    JAVA_CMD="/usr/lib/jvm/java-25-openjdk-amd64/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

echo "[RUNNER] Launching Java Web Dashboard using ${JAVA_CMD}..."
exec "$JAVA_CMD" --enable-native-access=ALL-UNNAMED \
    -cp "client/bin:client/lib/*" \
    com.backup.Phase4RestoreAndUiMain --daemon --port "${PORT}" --webPort "${WEB_PORT}"
