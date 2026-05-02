#!/system/bin/sh
MODDIR=${0%/*}
INGEST="/sdcard/one35uinputd.json"
ACTIVE="$MODDIR/one35uinputd.json"
DEFAULT="$MODDIR/default.json"
PID_FILE="$MODDIR/one35uinputd.pid"

if [ -f "$INGEST" ]; then
    cp "$INGEST" "$ACTIVE"
    rm "$INGEST"
    echo "one35uinputd: config ingested from sdcard"
else
    cp "$DEFAULT" "$ACTIVE"
    echo "one35uinputd: reset to default config"
fi

# Restart daemon
DAEMON_PID=$(cat "$PID_FILE" 2>/dev/null)
if [ -n "$DAEMON_PID" ]; then
    kill "$DAEMON_PID" 2>/dev/null
    sleep 0.5
fi
"$MODDIR/one35uinputd" "$MODDIR" >> "$MODDIR/one35uinputd.log" 2>&1 &
echo "one35uinputd: daemon restarted"
