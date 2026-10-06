#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Tests installer behavior against a temporary path, never real /data/adb.
SOURCE=${1:-modules/pocketswap}
TEST_ROOT=$(mktemp -d /tmp/pocketswap-install.XXXXXXXX) || exit 1
trap 'case "$TEST_ROOT" in /tmp/pocketswap-install.*) rm -rf -- "$TEST_ROOT" ;; esac' EXIT
mkdir -p "$TEST_ROOT/data/adb/service.d" "$TEST_ROOT/module"
cp "$SOURCE/config.example" "$TEST_ROOT/module/config.example"
sed "s|/data/adb|$TEST_ROOT/data/adb|g" "$SOURCE/customize.sh" > "$TEST_ROOT/customize.sh"
MODPATH="$TEST_ROOT/module" BOOTMODE=true API=30
MOCK_HASH=unknown
MOCK_UID=0
COUNT=0
id() { printf '%s\n' "$MOCK_UID"; }
sha256sum() { printf '%s  %s\n' "$MOCK_HASH" "$1"; }
ui_print() { printf '%s\n' "$*"; }
abort() { printf '%s\n' "$*" >&2; exit 1; }
set_perm_recursive() { return 0; }
set_perm() { return 0; }
install_mock() { ( . "$TEST_ROOT/customize.sh" ); }
assert() {
    label="$1"; shift
    if "$@"; then COUNT=$((COUNT + 1)); printf 'PASS %s\n' "$label";
    else printf 'FAIL %s\n' "$label" >&2; exit 1; fi
}
reject() { if "$@"; then return 1; else return 0; fi; }
# /proc/swaps is only read, and must be available for this test host.
BOOTMODE=false
assert 'recovery installation rejected' reject install_mock
BOOTMODE=true MOCK_UID=2000
assert 'installer requires actual root' reject install_mock
MOCK_UID=0 API=25
assert 'old Android installer rejected' reject install_mock
API=30
printf 'unknown script\n' > "$TEST_ROOT/data/adb/service.d/locationspoofer_disk_swap.sh"
assert 'unknown legacy does not prevent install' install_mock
assert 'unknown legacy script unchanged' test -f "$TEST_ROOT/data/adb/service.d/locationspoofer_disk_swap.sh"
assert 'unknown legacy not adopted' test ! -e "$TEST_ROOT/data/adb/pocketswap/adopt-legacy"
assert 'default config installed' cmp "$SOURCE/config.example" "$TEST_ROOT/data/adb/pocketswap/config.conf"
printf 'SIZE_MB=1024\n' > "$TEST_ROOT/data/adb/pocketswap/config.conf"
MOCK_HASH=63610119f00454c9a2fe46f8f6e228b793391f54a144ce0726134f513a1ca7c7
assert 'known legacy adopted' install_mock
assert 'known service removed from global boot folder' test ! -e "$TEST_ROOT/data/adb/service.d/locationspoofer_disk_swap.sh"
assert 'legacy backup exists' test -f "$TEST_ROOT/data/adb/pocketswap/legacy-service.backup"
assert 'legacy disabled archive exists' test -f "$TEST_ROOT/data/adb/pocketswap/legacy-service.disabled"
assert 'adoption marker exists' test -f "$TEST_ROOT/data/adb/pocketswap/adopt-legacy"
assert 'config preserved during update' grep -qx SIZE_MB=1024 "$TEST_ROOT/data/adb/pocketswap/config.conf"
rm "$TEST_ROOT/data/adb/pocketswap/config.conf"
ln -s "$TEST_ROOT/module/config.example" "$TEST_ROOT/data/adb/pocketswap/config.conf"
assert 'config symlink rejected' reject install_mock
printf 'PASS: %s installer checks; real device paths unchanged.\n' "$COUNT"
