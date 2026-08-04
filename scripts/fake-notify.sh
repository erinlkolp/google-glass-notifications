#!/usr/bin/env bash
# Injects a synthetic notification into the Glass app, so the UI can be
# exercised without the phone. See spec section 12.4.
#
#   scripts/fake-notify.sh "Signal" "Jordan Reyes" "are you still good for 7pm?" INTERRUPT
#   scripts/fake-notify.sh --clear
set -euo pipefail

SERIAL="${GLASS_SERIAL:-0123456789ABCDEF}"
ACTION=dev.erinlkolp.glassnotify.DEBUG_INJECT

if [[ "${1:-}" == "--clear" ]]; then
  adb -s "$SERIAL" shell am broadcast -a "$ACTION" --ez clear true
  exit 0
fi

APP="${1:-Signal}"
TITLE="${2:-Jordan Reyes}"
TEXT="${3:-are you still good for 7pm?}"
TIER="${4:-QUEUE}"

adb -s "$SERIAL" shell am broadcast -a "$ACTION" \
  --es app "$APP" \
  --es title "$TITLE" \
  --es text "$TEXT" \
  --es tier "$TIER"
