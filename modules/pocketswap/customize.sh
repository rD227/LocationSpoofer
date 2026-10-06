# SPDX-License-Identifier: GPL-3.0-only
# Sourced by the root manager installer; never exit this script directly.
[ "$BOOTMODE" = true ] || abort '! Install from Magisk/KernelSU/APatch, not recovery.'
[ "$(id -u)" = 0 ] || abort '! Real root permissions are required.'
[ "${API:-0}" -ge 26 ] || abort '! Android 8 or newer is required.'
[ -r /proc/swaps ] || abort '! Kernel swap support or /proc access is missing.'

PS_INSTALL_STATE=/data/adb/pocketswap
[ ! -L "$PS_INSTALL_STATE" ] || abort '! Unsafe state directory.'
mkdir -p "$PS_INSTALL_STATE" || abort '! Cannot create root state directory.'
chmod 700 "$PS_INSTALL_STATE"
[ ! -L "$PS_INSTALL_STATE/config.conf" ] || abort '! Unsafe config symlink.'
[ -f "$PS_INSTALL_STATE/config.conf" ] || cp "$MODPATH/config.example" "$PS_INSTALL_STATE/config.conf"
chmod 600 "$PS_INSTALL_STATE/config.conf"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for ps_script in "$MODPATH"/*.sh; do set_perm "$ps_script" 0 0 0755; done
touch "$MODPATH/skip_mount"

# Only archive the exact boot script created in this conversation. Unknown scripts stay untouched.
ps_legacy=/data/adb/service.d/locationspoofer_disk_swap.sh
if [ -f "$ps_legacy" ]; then
    ps_hash=$(sha256sum "$ps_legacy" 2>/dev/null)
    ps_hash=${ps_hash%% *}
    if [ "$ps_hash" = 63610119f00454c9a2fe46f8f6e228b793391f54a144ce0726134f513a1ca7c7 ]; then
        cp "$ps_legacy" "$PS_INSTALL_STATE/legacy-service.backup" || abort '! Cannot back up old startup script.'
        touch "$PS_INSTALL_STATE/adopt-legacy"
        mv "$ps_legacy" "$PS_INSTALL_STATE/legacy-service.disabled" || abort '! Cannot archive old startup script.'
        ui_print '- Adopting the existing four swap files; old startup script archived.'
    else
        ui_print '! Unknown legacy startup script detected; it was not changed.'
        ui_print '! Avoid running another swap module at the same time.'
    fi
fi
ui_print '- Script-only module: Magisk / KernelSU / APatch; no system mounts or Zygisk needed.'
ui_print '- Default: 2048 MiB in four fast-allocated files, lower priority than zRAM.'
ui_print '- Storage is prepared after the first unlock. Open Action to see status.'
ui_print '- Configure: /data/adb/pocketswap/config.conf'
ui_print '- Disabling takes effect after reboot. Uninstall retains any swap file still holding pages.'
