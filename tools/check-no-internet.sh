#!/usr/bin/env bash
# Fails if the Jarvis "Kale" APK requests any network permission, and checks that the gate APK
# is the only one that does. Reads the permissions from the final APKs with aapt2, so it also
# catches permissions merged in from libraries.
#
# Usage: tools/check-no-internet.sh <jarvis.apk> [<jarvis-kapi.apk>]
set -euo pipefail

KALE_APK="${1:?usage: $0 <jarvis.apk> [<jarvis-kapi.apk>]}"
GATE_APK="${2:-}"

AAPT2="${AAPT2:-}"
if [[ -z "$AAPT2" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  AAPT2="$(ls -1 "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
fi
[[ -x "$AAPT2" ]] || { echo "aapt2 not found (set AAPT2 or ANDROID_HOME)"; exit 2; }

perms() { "$AAPT2" dump permissions "$1" | sed -n "s/.*name='\([^']*\)'.*/\1/p" | sort -u; }

FORBIDDEN=(
  android.permission.INTERNET
  android.permission.ACCESS_NETWORK_STATE
  android.permission.ACCESS_WIFI_STATE
  android.permission.CHANGE_NETWORK_STATE
  android.permission.CHANGE_WIFI_STATE
  android.permission.NEARBY_WIFI_DEVICES
  android.permission.BLUETOOTH_CONNECT
  android.permission.QUERY_ALL_PACKAGES
)

echo "== Kale permissions ($KALE_APK)"
KALE_PERMS="$(perms "$KALE_APK")"
echo "$KALE_PERMS"
fail=0
for p in "${FORBIDDEN[@]}"; do
  if grep -qx "$p" <<<"$KALE_PERMS"; then
    echo "::error::Kale APK requests forbidden permission $p"
    fail=1
  fi
done

# Conversations and memory must never leave through Android's cloud backup either. Libraries can
# declare allowBackup=true in their manifest; the merged value in the final APK is what counts.
if "$AAPT2" dump xmltree --file AndroidManifest.xml "$KALE_APK" | grep -q "android:allowBackup([^)]*)=true"; then
  echo "::error::Kale APK allows backup (android:allowBackup=true)"
  fail=1
fi

if [[ -n "$GATE_APK" ]]; then
  echo "== Gate permissions ($GATE_APK)"
  GATE_PERMS="$(perms "$GATE_APK")"
  echo "$GATE_PERMS"
  grep -qx android.permission.INTERNET <<<"$GATE_PERMS" || { echo "::error::Gate APK must request INTERNET"; fail=1; }
  # The gate must not ask for anything beyond what downloading needs.
  while read -r p; do
    case "$p" in
      android.permission.INTERNET|android.permission.FOREGROUND_SERVICE|android.permission.FOREGROUND_SERVICE_DATA_SYNC|android.permission.POST_NOTIFICATIONS|io.github.ahmetdurmus1218.jarvis.permission.GATE|io.github.ahmetdurmus1218.jarvis.gate.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) ;;
      "") ;;
      *) echo "::error::Gate APK requests unexpected permission $p"; fail=1 ;;
    esac
  done <<<"$GATE_PERMS"
fi

if [[ $fail -ne 0 ]]; then exit 1; fi
echo "OK: Kale has no network permission."
