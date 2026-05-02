#!/system/bin/sh
MODDIR=${0%/*}

KT_PATH="/sys/devices/platform/10010000.kp/keycodetype"
if [ "$(cat $KT_PATH 2>/dev/null)" != "2" ]; then
    echo 2 > "$KT_PATH"
fi

"$MODDIR/one35uinputd" "$MODDIR" >> "$MODDIR/one35uinputd.log" 2>&1 &
