#!/sbin/sh

# Preserve active config across module updates
OLD_CONFIG="/data/adb/modules/$MODID/one35uinputd.json"
[ -f "$OLD_CONFIG" ] && cp "$OLD_CONFIG" "$MODPATH/one35uinputd.json"

set_perm "$MODPATH/one35uinputd" root root 0755
set_perm "$MODPATH/service.sh"   root root 0755
set_perm "$MODPATH/action.sh"    root root 0755
