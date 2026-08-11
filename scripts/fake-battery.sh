#!/usr/bin/env bash
# Injects a synthetic battery state into the Glass app, so the phone's charge
# alert can be exercised without waiting out a real charge.
#
#   scripts/fake-battery.sh 100 true    # full, on the charger  -> alert
#   scripts/fake-battery.sh 100 false   # unplugged             -> alert clears
#   scripts/fake-battery.sh 64 true     # charging              -> nothing
set -euo pipefail

SERIAL="${GLASS_SERIAL:-0123456789ABCDEF}"
ACTION=dev.erinlkolp.glassnotify.DEBUG_BATTERY

# `adb shell` joins its arguments with spaces and hands the result to the
# DEVICE's shell, where our local quoting no longer exists. See the same note
# in fake-notify.sh for what goes wrong without this.
remote() {
  local quoted=""
  local arg
  for arg in "$@"; do
    quoted="$quoted '$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")'"
  done
  adb -s "$SERIAL" shell "$quoted"
}

LEVEL="${1:-100}"
PLUGGED="${2:-true}"

remote am broadcast -a "$ACTION" \
  --ei level "$LEVEL" \
  --ez plugged "$PLUGGED"
