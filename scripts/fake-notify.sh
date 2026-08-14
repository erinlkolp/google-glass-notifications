#!/usr/bin/env bash
# Injects a synthetic notification into the Glass app, so the UI can be
# exercised without the phone. See spec section 12.4.
#
#   scripts/fake-notify.sh "Signal" "Jordan Reyes" "are you still good for 7pm?" INTERRUPT
#   scripts/fake-notify.sh "Slack" "#eng" "deploy finished" INTERRUPT_CHIRP
#   scripts/fake-notify.sh --clear
set -euo pipefail

SERIAL="${GLASS_SERIAL:-0123456789ABCDEF}"
ACTION=dev.erinlkolp.glassnotify.DEBUG_INJECT

# `adb shell` joins its arguments with spaces and hands the result to the
# DEVICE's shell, where our local quoting no longer exists. Passing
# `--es title "Jordan Reyes"` therefore arrives as two words: `am` takes
# `Jordan` as the extra and treats the stray `Reyes` as a PACKAGE FILTER,
# so the broadcast silently matches no receiver and nothing happens. The
# only symptom is `pkg=Reyes` in am's echo, which is easy to read past.
#
# So quote every token a second time, for the remote shell. Embedded single
# quotes are closed, escaped and reopened, which is the one case a naive
# wrapper gets wrong -- and app labels like "Bob's Phone" hit it.
remote() {
  local quoted=""
  local arg
  for arg in "$@"; do
    quoted="$quoted '$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")'"
  done
  adb -s "$SERIAL" shell "$quoted"
}

if [[ "${1:-}" == "--clear" ]]; then
  remote am broadcast -a "$ACTION" --ez clear true
  exit 0
fi

APP="${1:-Signal}"
TITLE="${2:-Jordan Reyes}"
TEXT="${3:-are you still good for 7pm?}"
TIER="${4:-QUEUE}"

remote am broadcast -a "$ACTION" \
  --es app "$APP" \
  --es title "$TITLE" \
  --es text "$TEXT" \
  --es tier "$TIER"
