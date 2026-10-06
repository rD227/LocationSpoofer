#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xvsu
# All swap operations and root identity are mocked. Never calls real swapon/swapoff.
SOURCE=${1:-modules/pocketswap/common.sh}
. "$SOURCE" || exit 1
TEST_ROOT=$(mktemp -d /tmp/pocketswap-test.XXXXXXXX) || exit 1
trap 'case "$TEST_ROOT" in /tmp/pocketswap-test.*) rm -rf -- "$TEST_ROOT" ;; esac' EXIT
PATH=/usr/bin:/bin:/system/bin
PS_STATE="$TEST_ROOT/state" PS_MODDIR="$TEST_ROOT/module"
PS_CE_BASE="$TEST_ROOT/ce/swap" PS_LEGACY_BASE="$TEST_ROOT/ce/legacy"
PS_PROC="$TEST_ROOT/proc" PS_SWAPS="$TEST_ROOT/swaps"
mkdir -p "$PS_STATE" "$PS_MODDIR" "$TEST_ROOT/ce" "$PS_PROC/self" "$PS_PROC/sys/kernel/random"
printf 'test-boot\n' > "$PS_PROC/sys/kernel/random/boot_id"
printf 'CapEff:\t0000000000200000\n' > "$PS_PROC/self/status"
MOCK_UID=0 MOCK_UNLOCK=true MOCK_CRYPT=file MOCK_FREE=9000000 MOCK_SWAPON_FAIL=0
MOCK_SWAPOFF_CALLS=0 MOCK_MKSWAP_CALLS=0 MOCK_DISABLE_SLEEP=0
COUNT=0

ps_tool() {
    case "$1" in
        id) printf '%s\n' "$MOCK_UID" ;;
        getprop)
            case "$2" in
                ro.crypto.type) printf '%s\n' "$MOCK_CRYPT" ;;
                sys.user.0.ce_available) printf '%s\n' "$MOCK_UNLOCK" ;;
                sys.boot_completed) printf '1\n' ;;
            esac ;;
        stat)
            if [ "$2" = -c ] && [ "$3" = %u ]; then printf '0\n'; else command "$@"; fi ;;
        df) printf 'Filesystem 1024-blocks Used Available Capacity Mounted\nmock 10000000 0 %s 0%% /mock\n' "$MOCK_FREE" ;;
        chown) return 0 ;;
        fallocate) truncate -s "$3" "$4" ;;
        mkswap) MOCK_MKSWAP_CALLS=$((MOCK_MKSWAP_CALLS + 1)); return 0 ;;
        swapon)
            [ "$MOCK_SWAPON_FAIL" = 0 ] || return 1
            if [ "$2" = -p ]; then mock_priority="$3"; mock_path="$4"; else mock_priority=-3; mock_path="$2"; fi
            printf '%s file 65532 0 %s\n' "$mock_path" "$mock_priority" >> "$PS_SWAPS" ;;
        swapoff)
            MOCK_SWAPOFF_CALLS=$((MOCK_SWAPOFF_CALLS + 1))
            awk -v wanted="$2" '$1 != wanted' "$PS_SWAPS" > "$PS_SWAPS.tmp"
            mv "$PS_SWAPS.tmp" "$PS_SWAPS" ;;
        sleep) [ "$MOCK_DISABLE_SLEEP" != 1 ] || touch "$PS_MODDIR/disable" ;;
        *) command "$@" ;;
    esac
}
assert() {
    label="$1"; shift
    if "$@"; then COUNT=$((COUNT + 1)); printf 'PASS %s\n' "$label";
    else printf 'FAIL %s\n' "$label" >&2; exit 1; fi
}
reject() { if "$@"; then return 1; else return 0; fi; }
reset_swaps() { printf 'Filename Type Size Used Priority\n/dev/block/zram0 partition 1572860 0 32758\n' > "$PS_SWAPS"; }
reset_swaps
assert 'root with CAP_SYS_ADMIN' ps_root
MOCK_UID=2000
assert 'non-root rejected' reject ps_root
MOCK_UID=0
printf 'CapEff:\t0000000000000000\n' > "$PS_PROC/self/status"
assert 'UID 0 without capability rejected' reject ps_root
printf 'CapEff:\t0000000000200000\n' > "$PS_PROC/self/status"

assert 'default configuration' ps_load_config
assert 'default 2GiB in four files' test "$((SIZE_MB / CHUNK_MB))" -eq 4
printf 'SIZE_MB=$(touch /tmp/never-execute)\n' > "$PS_STATE/config.conf"
assert 'config shell injection rejected' reject ps_load_config
printf 'SIZE_MB=100\nCHUNK_MB=64\n' > "$PS_STATE/config.conf"
assert 'indivisible chunks rejected' reject ps_load_config
printf 'CHUNK_MB=0\n' > "$PS_STATE/config.conf"
assert 'zero chunk rejected without division' reject ps_load_config
printf 'UNKNOWN=1\n' > "$PS_STATE/config.conf"
assert 'unknown configuration rejected' reject ps_load_config
printf 'SIZE_MB=128\nCHUNK_MB=64\nPRIORITY=32757\n' > "$PS_STATE/config.conf"
assert 'valid custom configuration' ps_load_config
assert 'FBE base chosen' ps_choose_base
assert 'FBE storage path' test "$PS_BASE" = "$PS_CE_BASE"
MOCK_UNLOCK=false
assert 'locked CE does not start' reject ps_start
assert 'locked CE did not create files' test ! -e "$PS_BASE"
MOCK_DISABLE_SLEEP=1
assert 'waiting start observes disable' reject ps_start --wait
MOCK_DISABLE_SLEEP=0 MOCK_UNLOCK=true
rm "$PS_MODDIR/disable"
MOCK_CRYPT=unknown
assert 'unknown encryption rejected by default' reject ps_choose_base
ALLOW_UNENCRYPTED=1
assert 'explicit unencrypted opt-in' ps_choose_base
MOCK_CRYPT=file ALLOW_UNENCRYPTED=0
ps_choose_base
ps_pick_priority
assert 'configured priority preserved below zRAM' test "$PS_EFFECTIVE_PRIORITY" -eq 32757
PRIORITY=32758
ps_pick_priority
assert 'zRAM priority takes precedence' test "$PS_EFFECTIVE_PRIORITY" -eq 32757
printf 'Filename Type Size Used Priority\n/dev/block/zram0 partition 1024 0 -2\n' > "$PS_SWAPS"
ps_pick_priority
assert 'negative zRAM uses automatic priority' test "$PS_PRIORITY_MODE" = automatic
assert 'automatic priority verified below zRAM' ps_enable_file "$TEST_ROOT/mock.swap"
printf '%s file 1024 0 10\n' "$TEST_ROOT/high.swap" >> "$PS_SWAPS"
assert 'bad existing priority rejected' reject ps_enable_file "$TEST_ROOT/high.swap"
reset_swaps
PRIORITY=1
mkdir -p "$PS_BASE"
touch "$PS_BASE/unrelated"
assert 'unowned populated directory rejected' reject ps_prepare_base
rm "$PS_BASE/unrelated"
rmdir "$PS_BASE"
ln -s "$PS_STATE" "$PS_BASE"
assert 'symlink base rejected' reject ps_prepare_base
rm "$PS_BASE"
assert 'owned directory created' ps_prepare_base
assert 'owner marker recognized' ps_owned
ps_pick_priority
MOCK_FREE=100
assert 'low free storage rejects probe' reject ps_probe
MOCK_FREE=9000000 MOCK_SWAPON_FAIL=1
assert 'kernel rejection aborts probe' reject ps_probe
assert 'failed probe removed when inactive' test ! -e "$PS_BASE/.probe.swap"
MOCK_SWAPON_FAIL=0
assert 'small compatibility probe' ps_probe
assert 'probe cleaned up' test ! -e "$PS_BASE/.probe.swap"
assert 'two chunk startup' ps_start
assert 'first chunk enabled' ps_active "$PS_BASE/fallback0.swap"
assert 'second chunk enabled' ps_active "$PS_BASE/fallback1.swap"
BEFORE_MKSWAP="$MOCK_MKSWAP_CALLS"
assert 'idempotent startup' ps_start
assert 'existing swaps not reformatted' test "$MOCK_MKSWAP_CALLS" = "$BEFORE_MKSWAP"
SIZE_MB=64
assert 'online shrink rejected' reject ps_start
SIZE_MB=128
awk -v wanted="$PS_BASE/fallback0.swap" 'BEGIN {OFS=" "} $1 == wanted {$4=4096} {print}' "$PS_SWAPS" > "$PS_SWAPS.tmp"
mv "$PS_SWAPS.tmp" "$PS_SWAPS"
assert 'cleanup refuses to pull active pages into RAM' reject ps_cleanup_files purge
assert 'active file retained' test -f "$PS_BASE/fallback0.swap"
assert 'active swap still enabled' ps_active "$PS_BASE/fallback0.swap"
assert 'idle chunk removed' test ! -e "$PS_BASE/fallback1.swap"
assert 'zRAM remains active' ps_active /dev/block/zram0
reset_swaps
assert 'post reboot purge' ps_cleanup_files purge
assert 'dedicated empty directory removed' test ! -d "$PS_BASE"
assert 'missing owner prevents deletion' reject ps_cleanup_files purge
assert 'first operation locks' ps_lock
assert 'live operation prevents duplicate lock' reject ps_lock
ps_unlock_lock
assert 'lock released' test ! -d "$PS_STATE/run.lock"
mkdir "$PS_STATE/run.lock"
printf '123456\n' > "$PS_STATE/run.lock/pid"
printf 'previous-boot\n' > "$PS_STATE/run.lock/boot"
assert 'stale previous boot lock recovered' ps_lock
ps_unlock_lock
assert 'large PID accepted' ps_pid 123456
printf 'PASS: %s checks; no real swap operations performed.\n' "$COUNT"
