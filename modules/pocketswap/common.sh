#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xvsu

ps_tool() {
    ps_cmd="$1"
    shift
    if [ -x "/system/bin/$ps_cmd" ]; then
        "/system/bin/$ps_cmd" "$@"
    elif [ -n "$PS_BUSYBOX" ] && "$PS_BUSYBOX" --list | /system/bin/grep -qx "$ps_cmd"; then
        "$PS_BUSYBOX" "$ps_cmd" "$@"
    else
        command "$ps_cmd" "$@"
    fi
}

ps_find_busybox() {
    PS_BUSYBOX=
    for ps_bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
        if [ -x "$ps_bb" ]; then PS_BUSYBOX="$ps_bb"; break; fi
    done
}

ps_log() {
    ps_message="$*"
    printf '%s\n' "$ps_message"
    [ -d "$PS_STATE" ] || return 0
    if [ -f "$PS_STATE/runtime.log" ]; then
        ps_log_size=$(ps_tool stat -c %s "$PS_STATE/runtime.log" 2>/dev/null)
        if [ "${ps_log_size:-0}" -gt 65536 ]; then
            ps_tool mv -f "$PS_STATE/runtime.log" "$PS_STATE/runtime.previous.log"
        fi
    fi
    printf '%s %s\n' "$(ps_tool date '+%Y-%m-%d %H:%M:%S')" "$ps_message" >> "$PS_STATE/runtime.log"
}

ps_status() {
    ps_log "$1: $2"
    printf 'state=%s\nmessage=%s\n' "$1" "$2" > "$PS_STATE/status.tmp"
    ps_tool mv -f "$PS_STATE/status.tmp" "$PS_STATE/status.txt"
}

ps_uint() {
    case "$1" in ''|*[!0-9]*|0[0-9]*) return 1 ;; esac
    [ "${#1}" -le 5 ]
}

ps_pid() {
    case "$1" in ''|0|*[!0-9]*|0[0-9]*) return 1 ;; esac
    [ "${#1}" -le 10 ]
}

ps_load_config() {
    SIZE_MB=2048 CHUNK_MB=512 PRIORITY=1 MIN_FREE_MB=1024 ALLOW_UNENCRYPTED=0
    [ -f "$PS_STATE/config.conf" ] || return 0
    while IFS='=' read -r ps_key ps_value || [ -n "$ps_key" ]; do
        case "$ps_key" in ''|\#*) continue ;; esac
        ps_uint "$ps_value" || { ps_log "Invalid numeric setting: $ps_key"; return 1; }
        case "$ps_key" in
            SIZE_MB) SIZE_MB="$ps_value" ;;
            CHUNK_MB) CHUNK_MB="$ps_value" ;;
            PRIORITY) PRIORITY="$ps_value" ;;
            MIN_FREE_MB) MIN_FREE_MB="$ps_value" ;;
            ALLOW_UNENCRYPTED) ALLOW_UNENCRYPTED="$ps_value" ;;
            *) ps_log "Unknown setting: $ps_key"; return 1 ;;
        esac
    done < "$PS_STATE/config.conf"
    [ "$SIZE_MB" -ge 64 ] && [ "$SIZE_MB" -le 8192 ] &&
        [ "$CHUNK_MB" -ge 64 ] && [ "$CHUNK_MB" -le 512 ] &&
        [ $((SIZE_MB % CHUNK_MB)) -eq 0 ] &&
        [ "$PRIORITY" -le 32757 ] && [ "$MIN_FREE_MB" -ge 256 ] &&
        [ "$MIN_FREE_MB" -le 32768 ] && [ "$ALLOW_UNENCRYPTED" -le 1 ]
}

ps_enabled() {
    [ -d "$PS_MODDIR" ] && [ ! -e "$PS_MODDIR/disable" ] && [ ! -e "$PS_MODDIR/remove" ]
}

ps_root() {
    [ "$(ps_tool id -u)" = 0 ] || { ps_log 'Real UID 0 is required.'; return 1; }
    [ -r "$PS_SWAPS" ] || { ps_log 'Cannot read /proc/swaps: check kernel swap support and root permissions.'; return 1; }
    ps_caps=$(ps_tool awk '/^CapEff:/ {print $2}' "$PS_PROC/self/status" 2>/dev/null)
    case "$ps_caps" in ''|*[!0-9a-fA-F]*) ps_log 'Cannot read effective kernel capabilities.'; return 1 ;; esac
    [ $((0x$ps_caps & 2097152)) -ne 0 ] || {
        ps_log 'CAP_SYS_ADMIN is missing. UID 0 alone does not permit swapon.'; return 1;
    }
}

ps_choose_base() {
    PS_CRYPT=$(ps_tool getprop ro.crypto.type)
    case "$PS_CRYPT" in
        file)
            PS_BASE="$PS_CE_BASE"
            if [ -f "$PS_STATE/adopt-legacy" ]; then PS_BASE="$PS_LEGACY_BASE"; fi
            ;;
        block) PS_BASE="$PS_STATE/fde-swap" ;;
        *)
            [ "$ALLOW_UNENCRYPTED" = 1 ] || {
                ps_log 'Storage encryption type unknown or absent; directory opt-in is required (ALLOW_UNENCRYPTED=0).'; return 1;
            }
            PS_BASE="$PS_STATE/plain-swap"
            ;;
    esac
}

ps_unlocked() {
    if [ "$PS_CRYPT" = file ]; then
        [ "$(ps_tool getprop sys.user.0.ce_available)" = true ] && [ -d "${PS_CE_BASE%/*}" ]
    else
        [ "$(ps_tool getprop sys.boot_completed)" = 1 ]
    fi
}

ps_active() {
    ps_tool awk -v wanted="$1" '$1 == wanted {found=1} END {exit !found}' "$PS_SWAPS"
}

ps_used_kb() {
    ps_tool awk -v wanted="$1" '$1 == wanted {print $4; found=1} END {if (!found) print 0}' "$PS_SWAPS"
}

ps_priority() {
    ps_tool awk -v wanted="$1" '$1 == wanted {print $5}' "$PS_SWAPS"
}

ps_pick_priority() {
    PS_ZRAM_MIN=$(ps_tool awk '$1 ~ /\/zram[0-9]+$/ {if (!seen || $5<min) min=$5; seen=1} END {if (seen) print min}' "$PS_SWAPS")
    PS_PRIORITY_MODE=explicit PS_EFFECTIVE_PRIORITY="$PRIORITY"
    if [ -n "$PS_ZRAM_MIN" ]; then
        if [ "$PS_ZRAM_MIN" -le 0 ]; then
            PS_PRIORITY_MODE=automatic
        elif [ "$PRIORITY" -ge "$PS_ZRAM_MIN" ]; then
            PS_EFFECTIVE_PRIORITY=$((PS_ZRAM_MIN - 1))
        fi
    fi
}

ps_enable_file() {
    if ! ps_active "$1"; then
        if [ "$PS_PRIORITY_MODE" = automatic ]; then
            ps_tool swapon "$1" 2>> "$PS_STATE/runtime.log" || return 1
        else
            ps_tool swapon -p "$PS_EFFECTIVE_PRIORITY" "$1" 2>> "$PS_STATE/runtime.log" || return 1
        fi
    fi
    ps_actual_priority=$(ps_priority "$1")
    [ -n "$ps_actual_priority" ] || return 1
    if [ -n "$PS_ZRAM_MIN" ] && [ "$ps_actual_priority" -ge "$PS_ZRAM_MIN" ]; then
        ps_log "Swap priority is not below zRAM: $1. Keeping active data intact; restart after fixing priorities."
        return 1
    fi
}

ps_lock() {
    PS_LOCK="$PS_STATE/run.lock"
    if ! ps_tool mkdir "$PS_LOCK" 2>/dev/null; then
        ps_old_pid=$(ps_tool cat "$PS_LOCK/pid" 2>/dev/null)
        ps_old_boot=$(ps_tool cat "$PS_LOCK/boot" 2>/dev/null)
        ps_boot=$(ps_tool cat "$PS_PROC/sys/kernel/random/boot_id" 2>/dev/null)
        if [ -n "$ps_boot" ] && [ "$ps_old_boot" = "$ps_boot" ] &&
            ps_pid "$ps_old_pid" && kill -0 "$ps_old_pid" 2>/dev/null; then
            ps_log 'Another PocketSwap operation is running.'; return 1
        fi
        # An incomplete lock may belong to a process that is still creating it.
        if [ -z "$ps_old_boot" ] || ! ps_pid "$ps_old_pid"; then
            ps_log 'Incomplete lock; refusing concurrent work. Reboot then remove run.lock if needed.'; return 1
        fi
        ps_tool rm -f "$PS_LOCK/pid" "$PS_LOCK/boot"
        ps_tool rmdir "$PS_LOCK" 2>/dev/null || return 1
        ps_tool mkdir "$PS_LOCK" || return 1
    fi
    printf '%s\n' "$$" > "$PS_LOCK/pid"
    ps_tool cat "$PS_PROC/sys/kernel/random/boot_id" > "$PS_LOCK/boot" 2>/dev/null
}

ps_unlock_lock() {
    [ -n "$PS_LOCK" ] || return 0
    [ "$(ps_tool cat "$PS_LOCK/pid" 2>/dev/null)" = "$$" ] || return 0
    ps_tool rm -f "$PS_LOCK/pid" "$PS_LOCK/boot"
    ps_tool rmdir "$PS_LOCK" 2>/dev/null
}

ps_owned() {
    [ ! -L "$PS_BASE" ] && [ -f "$PS_BASE/.pocketswap-owner" ] &&
        [ ! -L "$PS_BASE/.pocketswap-owner" ] &&
        [ "$(ps_tool stat -c %u "$PS_BASE")" = 0 ] &&
        [ "$(ps_tool stat -c %u "$PS_BASE/.pocketswap-owner")" = 0 ] &&
        [ "$(ps_tool cat "$PS_BASE/.pocketswap-owner")" = pocketswap-v1 ]
}

ps_prepare_base() {
    [ ! -L "$PS_BASE" ] || return 1
    [ ! -L "$PS_BASE/.pocketswap-owner" ] || return 1
    if [ -d "$PS_BASE" ] && ! ps_owned; then
        if [ "$PS_BASE" = "$PS_LEGACY_BASE" ] && [ -f "$PS_STATE/adopt-legacy" ]; then
            for ps_part in 0 1 2 3; do
                ps_file="$PS_BASE/fallback$ps_part.swap"
                [ ! -L "$ps_file" ] && [ -f "$ps_file" ] &&
                    [ "$(ps_tool stat -c %u "$ps_file")" = 0 ] &&
                    [ "$(ps_tool stat -c %s "$ps_file")" = 536870912 ] || return 1
            done
        elif [ -n "$(ps_tool ls -A "$PS_BASE" 2>/dev/null)" ]; then
            ps_log 'Swap directory contains unowned files. Refusing to adopt it.'; return 1
        fi
    fi
    ps_tool mkdir -p "$PS_BASE" || return 1
    ps_tool chown 0:0 "$PS_BASE" || return 1
    ps_tool chmod 700 "$PS_BASE" || return 1
    printf '%s\n' pocketswap-v1 > "$PS_BASE/.pocketswap-owner" || return 1
    ps_tool chmod 600 "$PS_BASE/.pocketswap-owner"
}

ps_has_space() {
    ps_free_kb=$(ps_tool df -Pk "$PS_BASE" | ps_tool awk 'NR>1 {free=$4} END {print free}')
    ps_uint "$ps_free_kb" || {
        # df values may exceed the five-digit limit used for configuration.
        case "$ps_free_kb" in ''|*[!0-9]*) return 1 ;; esac
    }
    [ "$ps_free_kb" -ge $((($1 + MIN_FREE_MB) * 1024)) ]
}

ps_remove_inactive() {
    ps_active "$1" && { ps_log "Retained active swap file: $1"; return 1; }
    ps_tool rm -f "$1"
}

ps_probe() {
    ps_probe_file="$PS_BASE/.probe.swap"
    [ ! -L "$ps_probe_file" ] || return 1
    ps_remove_inactive "$ps_probe_file" || return 1
    ps_has_space 4 || { ps_log 'Insufficient free storage for the compatibility probe.'; return 1; }
    ps_tool fallocate -l 4194304 "$ps_probe_file" 2>> "$PS_STATE/runtime.log" &&
        ps_tool chmod 600 "$ps_probe_file" &&
        ps_tool mkswap "$ps_probe_file" 2>> "$PS_STATE/runtime.log" && ps_enable_file "$ps_probe_file"
    ps_probe_result=$?
    if ps_active "$ps_probe_file"; then
        [ "$(ps_used_kb "$ps_probe_file")" = 0 ] && ps_tool swapoff "$ps_probe_file" 2>> "$PS_STATE/runtime.log" || {
            ps_log 'Probe now holds pages; retaining it. Reboot then run purge.'; return 1;
        }
    fi
    ps_remove_inactive "$ps_probe_file" || return 1
    [ "$ps_probe_result" -eq 0 ] || {
        ps_log 'Kernel/filesystem/SELinux rejected fast-allocated swap. No dd fallback or SELinux changes are applied.'
        return 1
    }
}

ps_prepare_file() {
    ps_file="$1" ps_bytes="$2"
    [ ! -L "$ps_file" ] && [ ! -L "$ps_file.tmp" ] || return 1
    if [ -f "$ps_file" ]; then
        [ "$(ps_tool stat -c %u "$ps_file")" = 0 ] &&
            [ "$(ps_tool stat -c %s "$ps_file")" = "$ps_bytes" ] || {
                ps_log "Existing file size/owner differs: $ps_file. Stop and purge before resizing."; return 1;
            }
        ps_tool chmod 600 "$ps_file" || return 1
        return 0
    fi
    ps_has_space "$CHUNK_MB" || { ps_log 'Storage reserve would be breached.'; return 1; }
    ps_remove_inactive "$ps_file.tmp" || return 1
    ps_tool fallocate -l "$ps_bytes" "$ps_file.tmp" 2>> "$PS_STATE/runtime.log" &&
        ps_tool chmod 600 "$ps_file.tmp" && ps_tool mkswap "$ps_file.tmp" 2>> "$PS_STATE/runtime.log" &&
        ps_tool mv "$ps_file.tmp" "$ps_file" || {
            ps_remove_inactive "$ps_file.tmp"
            return 1
        }
}

ps_start() {
    ps_enabled || { ps_status disabled 'Module disabled or removed.'; return 1; }
    while ! ps_unlocked; do
        ps_status waiting_unlock 'Waiting for user 0 encrypted storage.'
        [ "$1" = --wait ] || return 2
        ps_tool sleep 5
        ps_enabled || return 1
    done
    ps_prepare_base || { ps_status error 'Unsafe or inaccessible storage directory.'; return 1; }
    ps_pick_priority
    ps_log "Storage=$PS_BASE filesystem=$(ps_tool stat -f -c %T "$PS_BASE" 2>/dev/null) priority=$PS_PRIORITY_MODE/$PS_EFFECTIVE_PRIORITY"
    # No probe allocation is needed when reusing verified swap files.
    if [ ! -f "$PS_BASE/fallback0.swap" ]; then
        ps_probe || { ps_status error 'Swap compatibility probe failed; see runtime.log.'; return 1; }
    fi
    ps_total=$((SIZE_MB / CHUNK_MB)) ps_index=0
    ps_extra="$ps_total"
    while [ "$ps_extra" -lt 128 ]; do
        if [ -e "$PS_BASE/fallback$ps_extra.swap" ]; then
            ps_status error 'Existing chunk count differs. Disable, reboot, unlock and purge before resizing.'
            return 1
        fi
        ps_extra=$((ps_extra + 1))
    done
    while [ "$ps_index" -lt "$ps_total" ]; do
        ps_enabled || { ps_status partial 'Disabled during startup; existing active swaps retained until reboot.'; return 1; }
        ps_prepare_file "$PS_BASE/fallback$ps_index.swap" "$((CHUNK_MB * 1048576))" &&
            ps_enable_file "$PS_BASE/fallback$ps_index.swap" || {
                ps_status partial "Failed at chunk $ps_index; successful chunks retained. See runtime.log."; return 1;
            }
        ps_log "Ready chunk $ps_index / $ps_total"
        ps_index=$((ps_index + 1))
    done
    ps_status active "$SIZE_MB MiB disk swap configured; zRAM is preserved."
}

ps_cleanup_files() {
    ps_unlocked || { ps_status pending_cleanup 'Unlock user 0, then run the saved cleanup script.'; return 1; }
    ps_owned || { ps_status error 'Owner marker missing; no files will be removed.'; return 1; }
    ps_failed=0 ps_index=0
    while [ "$ps_index" -lt 128 ]; do
        ps_file="$PS_BASE/fallback$ps_index.swap"
        if ps_active "$ps_file"; then
            if [ "$(ps_used_kb "$ps_file")" != 0 ]; then
                ps_log "Active pages in $ps_file. Reboot before cleanup; avoiding a memory surge."
                ps_failed=1
            elif ! ps_tool swapoff "$ps_file" 2>> "$PS_STATE/runtime.log"; then
                ps_failed=1
            fi
        fi
        if [ "$1" = purge ]; then
            ps_remove_inactive "$ps_file" || ps_failed=1
            ps_remove_inactive "$ps_file.tmp" || ps_failed=1
        fi
        ps_index=$((ps_index + 1))
    done
    ps_file="$PS_BASE/.probe.swap"
    if ps_active "$ps_file"; then
        [ "$(ps_used_kb "$ps_file")" = 0 ] && ps_tool swapoff "$ps_file" 2>> "$PS_STATE/runtime.log" || ps_failed=1
    fi
    [ "$1" != purge ] || ps_remove_inactive "$ps_file" || ps_failed=1
    [ "$ps_failed" -eq 0 ] || { ps_status pending_cleanup 'Active swap retained. Disable/remove, reboot, unlock, then run purge.'; return 1; }
    if [ "$1" = purge ]; then
        ps_tool rm -f "$PS_BASE/.pocketswap-owner"
        ps_tool rmdir "$PS_BASE" 2>/dev/null || true
    fi
    ps_status stopped 'Managed disk swap is inactive. Other swap devices are unchanged.'
}
