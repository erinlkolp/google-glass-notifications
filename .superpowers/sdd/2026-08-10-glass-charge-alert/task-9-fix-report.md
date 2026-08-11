# Task 9: Hardware verification — charge-alert double-percent-sign fix

## Defect

Observed on the LG V30 (real device): the "Glass is charged" notification body rendered as
`100%% — ready to go`, with two literal percent signs.

## Root cause (verified)

`phone/src/main/res/values/strings.xml` held:

```xml
<string name="charged_text">100%% — ready to go</string>
```

`ChargeAlerter.build()` (`phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java:55`)
retrieves it with the single-argument `context.getString(R.string.charged_text)`. That overload
never calls `String.format` and therefore never collapses `%%` to `%` — it returns the stored
string verbatim, so both percent characters reach the notification. Escape collapsing only
happens via the varargs `getString(int, Object...)` overload, which is not used here. The `%%`
had been added specifically to dodge aapt's format-string validation on a lone `%`, but that
validation and the retrieval method were working against each other.

## Fix chosen

**Preferred fix, and the one that shipped**: mark the string non-format and go back to a single `%`.

```xml
<string name="charged_text" formatted="false">100% — ready to go</string>
```

`formatted="false"` tells aapt this string is not a format string, so a lone `%` is legal at
compile time, and `getString(int)` returns it unchanged at runtime — which is exactly the code
path `ChargeAlerter` already uses. `ChargeAlerter.java` was left unmodified, as intended.

**Why the fallbacks were not needed**: `./gradlew assembleDebug` succeeded on the first try with
`formatted="false"` in place — the resource compiler on this toolchain accepts it without
complaint. There was no need to fall back to `getString(int, Object[0])` or to reword the string
away from a percent sign.

## Build

```
./gradlew assembleDebug
...
BUILD SUCCESSFUL in 1s
65 actionable tasks: 7 executed, 58 up-to-date
```

## Runtime verification (the part that actually matters)

Devices: Glass serial `0123456789ABCDEF`, phone (LG V30) serial `VS9967edd915b`, both attached
over adb and confirmed present via `adb devices -l`.

1. Installed the rebuilt APK:
   `adb -s VS9967edd915b install -r phone/build/outputs/apk/debug/phone-debug.apk` → `Success`.
2. Waited 20s for the phone to reconnect to Glass.
3. Triggered a **real** battery broadcast on Glass (not the debug injection path, which bypasses
   the battery watcher):
   ```
   adb -s 0123456789ABCDEF shell dumpsys battery set ac 1
   adb -s 0123456789ABCDEF shell dumpsys battery set level 95
   (wait 5s)
   adb -s 0123456789ABCDEF shell dumpsys battery set level 100
   (wait 5s)
   ```
4. Waited an additional 15s, then read back the actual system-held notification text:
   ```
   adb -s VS9967edd915b shell dumpsys notification --noredact \
     | grep -A40 "glassnotify.phone|2|null" \
     | grep -i "android.text\|android.title"
   ```

### Exact dumpsys output (proof)

```
          android.title=String (Glass is charged)
          android.text=String (100% — ready to go)
```

This is the live system notification record on the V30, not a log line from the app — it shows a
single `%`, confirming the fix works end to end, not just at compile time.

## Test suite

`./gradlew test` ran clean. Counting the debug-variant results only (per the doubling gotcha —
Android modules emit both `testDebugUnitTest` and `testReleaseUnitTest`, and summing both
directories double-counts):

| Module | Test count | Failures | Errors |
|--------|-----------|----------|--------|
| wire   | 58        | 0        | 0      |
| glass  | 47        | 0        | 0      |
| phone  | 42        | 0        | 0      |

All three match the expected counts (wire 58, glass 47, phone 42) with zero failures and zero
errors across all `TEST-*.xml` suite files in each module's debug-variant results directory.

## Documentation corrections

- `docs/superpowers/plans/2026-08-10-glass-charge-alert.md`, Task 4 Step 1: updated the code
  block to `formatted="false"` + single `%`, and replaced the false claim ("the doubled `%%` is
  required ... treated as a format specifier and fails the build") with an explanation of what is
  actually true: a lone `%` needs `formatted="false"` because `ChargeAlerter` reads the string
  with the single-argument `getString(int)`, which never runs `String.format` and so never
  collapses `%%`; doubling the sign was therefore the wrong fix regardless of what aapt requires
  at compile time.
- `docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md` section 7.3 ("The
  notification", line 254) already read `Content: title "Glass is charged", text "100% — ready to
  go"` — a single `%`, consistent with the shipped fix. No change was needed there.

## Cleanup

Ran `adb -s 0123456789ABCDEF shell dumpsys battery reset` after verification. Confirmed Glass is
reporting live values again, not the sticky override:

```
Current Battery Service state:
  AC powered: false
  USB powered: true
  Wireless powered: false
  status: 2
  health: 1
  present: true
  level: 96
  scale: 100
  voltage: 4349
  temperature: 320
  technology: Li-ion
```

`AC powered: false` / `level: 96` (not the injected `ac 1` / `level 100`) confirms the override
was cleared and the service is back to reading the real battery.

## Constraints respected

- `Protocol.VERSION` untouched.
- `glass` module and `wire` module untouched (only `phone/src/main/res/values/strings.xml` and
  the plan doc changed).
- No threading or link code touched — string/resource fix plus documentation only.
- `ChargeAlerter.java` left unmodified, as the preferred fix allowed.

## Commit

`f676a442a1c810aaf9da2a33c86710cca3ed52af` on branch `feat/glass-charge-alert`:

```
fix(phone): stop doubling the percent sign in the charge alert

`charged_text` stored `100%% — ready to go` to dodge aapt's format-string
validation on a lone `%`. But ChargeAlerter.build() reads it with the
single-argument context.getString(int), which never calls String.format
and so never collapses `%%` back to `%` — it renders both characters
verbatim. Confirmed on the V30: the notification body read
"100%% — ready to go".

Mark the string formatted="false" instead, which tells aapt a bare `%`
is not a format specifier, and go back to a single `%`. Verified via a
real Glass battery broadcast (dumpsys battery set ac/level) and
dumpsys notification --noredact on the phone: android.text now reads
"100% — ready to go".

Corrected the plan doc's Task 4 Step 1, which asserted the doubled
`%%` "is required" and gave the wrong reason.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

Files changed: `phone/src/main/res/values/strings.xml`,
`docs/superpowers/plans/2026-08-10-glass-charge-alert.md` (2 files, 3 insertions, 3 deletions).
