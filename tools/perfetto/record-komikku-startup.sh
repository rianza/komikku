#!/usr/bin/env bash
set -euo pipefail

ADB_BIN=${ADB_BIN:-adb}
PACKAGE_NAME=${PACKAGE_NAME:-app.komikkurnz.beta}
ACTIVITY_NAME=${ACTIVITY_NAME:-eu.kanade.tachiyomi.ui.main.MainActivity}
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
CONFIG_FILE=${CONFIG_FILE:-"$SCRIPT_DIR/komikku-startup.pbtx"}
OUTPUT_FILE=${1:-"komikku-startup-$(date +%Y%m%d-%H%M%S).pftrace"}
TIMING_FILE="${OUTPUT_FILE}.timing.txt"
REMOTE_CONFIG=/data/local/tmp/komikku-startup.pbtx
REMOTE_TRACE=/data/misc/perfetto-traces/komikku-startup.pftrace

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "ADB command not found: $ADB_BIN" >&2
  echo "For Termux, run with ADB_BIN=termux-adb" >&2
  exit 1
fi

if [ ! -f "$CONFIG_FILE" ]; then
  echo "Perfetto config not found: $CONFIG_FILE" >&2
  exit 1
fi

"$ADB_BIN" push "$CONFIG_FILE" "$REMOTE_CONFIG" >/dev/null
"$ADB_BIN" shell rm -f "$REMOTE_TRACE"
"$ADB_BIN" shell am force-stop "$PACKAGE_NAME"
sleep 2

"$ADB_BIN" shell "cat '$REMOTE_CONFIG' | perfetto --background --txt -c - -o '$REMOTE_TRACE'" >/dev/null
sleep 1

{
  echo "package=$PACKAGE_NAME"
  echo "activity=$ACTIVITY_NAME"
  echo "started_at=$(date --iso-8601=seconds)"
  "$ADB_BIN" shell am start -W "$PACKAGE_NAME/$ACTIVITY_NAME"
} | tee "$TIMING_FILE"

# The config records for 12 seconds. Leave time for Perfetto to flush the trace.
sleep 14
"$ADB_BIN" pull "$REMOTE_TRACE" "$OUTPUT_FILE" >/dev/null
"$ADB_BIN" shell rm -f "$REMOTE_CONFIG" "$REMOTE_TRACE"

printf 'Trace: %s\nTiming: %s\n' "$OUTPUT_FILE" "$TIMING_FILE"
