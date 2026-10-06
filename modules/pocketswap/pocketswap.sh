#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
PS_MODDIR=${0%/*}
. "$PS_MODDIR/common.sh"
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH
PS_STATE=/data/adb/pocketswap
PS_CE_BASE=/data/misc_ce/0/pocketswap
PS_LEGACY_BASE=/data/misc_ce/0/locationspoofer_swap
PS_PROC=/proc
PS_SWAPS=/proc/swaps
ps_find_busybox

if [ "$1" = status ]; then
    printf 'PocketSwap 1.0.0\nuid=%s\nce_available=%s\nroot_manager=%s\n' "$(ps_tool id -u)" "$(ps_tool getprop sys.user.0.ce_available)" "${KSU:+KernelSU}${APATCH:+APatch}${MAGISK_VER:+Magisk}"
    [ ! -f "$PS_STATE/status.txt" ] || ps_tool cat "$PS_STATE/status.txt"
    ps_tool cat "$PS_SWAPS"
    exit 0
fi
ps_root || exit 1
[ ! -L "$PS_STATE" ] || exit 1
ps_tool mkdir -p "$PS_STATE" || exit 1
ps_tool chown 0:0 "$PS_STATE" || exit 1
ps_tool chmod 700 "$PS_STATE" || exit 1
# Save cleanup before locking: startup may still be waiting for an unlock.
if [ "$1" = uninstall ]; then
    [ ! -L "$PS_STATE/recovery" ] || exit 1
    ps_tool mkdir -p "$PS_STATE/recovery" || exit 1
    ps_tool cp "$PS_MODDIR/common.sh" "$PS_STATE/recovery/common.sh" || exit 1
    ps_tool cp "$PS_MODDIR/pocketswap.sh" "$PS_STATE/recovery/pocketswap.sh" || exit 1
    ps_tool chmod 700 "$PS_STATE/recovery" "$PS_STATE/recovery/pocketswap.sh"
fi
ps_load_config || { ps_status error 'Invalid config.conf; no changes applied.'; exit 1; }
ps_choose_base || { ps_status error 'No permitted encrypted storage.'; exit 1; }
ps_lock || exit 1
trap ps_unlock_lock EXIT
trap 'exit 130' INT TERM
case "$1" in
    start) ps_start "$2" ;;
    stop) ps_cleanup_files stop ;;
    purge) ps_cleanup_files purge ;;
    uninstall)
        ps_cleanup_files purge
        ;;
    *) printf 'Usage: pocketswap.sh {status|start [--wait]|stop|purge}\n'; exit 2 ;;
esac
