# Task 4 Report: Post the notification, and wire the reader in

## Status: DONE

## What was implemented

1. **`phone/src/main/res/values/strings.xml`** — added three string entries inside `<resources>`, verbatim from the brief:
   - `channel_charge` = "Glass charged"
   - `charged_title` = "Glass is charged"
   - `charged_text` = "100%% — ready to go" (doubled `%%` preserved as required — a single `%` is read as a format specifier and fails the build)

2. **`phone/src/main/res/drawable/ic_glass_charged.xml`** (new file, new directory — `drawable/` did not previously exist in `phone/src/main/res/`) — vector drawable, white silhouette on transparent background, transcribed verbatim from the brief. Did not substitute any `android.R.drawable.stat_sys_battery*` platform drawable, per the constraint that those names are internal and would fail to compile.

3. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java`** (new file) — transcribed verbatim from the brief. Implements `LinkReader.Listener`, owns a `ChargeAlertPolicy`, creates the `glass_charge` notification channel (`IMPORTANCE_DEFAULT`, `setShowBadge(false)`) in the constructor, and on `onGlassState` posts/cancels notification ID 2 based on the policy's `SHOW`/`CANCEL`/`NONE` verdict.

4. **`phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java`** — four additive edits, exactly as specified in the brief (details below under "Exact lines changed").

## Exact lines changed in `LinkClientService.java`, and why

All edits are purely additive; no existing line was reordered, reformatted, or removed. Verified via `git diff` before committing — the diff shows only `+` lines plus the two one-word-token changes described below.

- **Import block (near top of file):** added `import android.os.Handler;`, `import android.os.Looper;`, `import java.io.InputStream;`, and `import dev.erinlkolp.glassnotify.wire.GlassState;`, each inserted alphabetically alongside their respective existing import groups. Necessary because step 4b needs `Handler`/`Looper`, and step 4d needs `InputStream` (for `getInputStream()`'s return type) and `GlassState` (the reader's callback payload type). The brief listed these as two separate pairs (4a and "two more imports" for 4d) but did not mandate a specific insertion point beyond "beside the existing ones" — I merged them into the existing alphabetical ordering, which changes nothing about behavior and keeps the file's existing style.

- **Two new fields**, inserted immediately after the existing `snapshotPending` field and its comment (before `public static void start(...)`):
  ```java
  /** Posts the charged alert. Touched only from the main thread. */
  private ChargeAlerter alerter;

  private final Handler main = new Handler(Looper.getMainLooper());
  ```
  Necessary: `alerter` is the single `ChargeAlerter` instance constructed once in `onCreate()` and invoked from the reader's callback; `main` is the `Handler` used to marshal the reader thread's `GlassState` delivery back onto the main thread, since `ChargeAlertPolicy` (via `ChargeAlerter`) is documented main-thread-only and not synchronized.

- **`onCreate()`:** inserted `alerter = new ChargeAlerter(this);` immediately after `createChannel();` and before `startForeground(...)`. Necessary so the charge-alert notification channel exists and the alerter is ready before any reader thread could possibly deliver a `GlassState` to it (the reader is only ever started later, inside `pump()`, so this ordering is safe with margin).

- **`pump()` signature:** changed `private void pump(BluetoothSocket connected) throws IOException` to `private void pump(final BluetoothSocket connected) throws IOException`. Necessary because the new anonymous `Runnable` inside `pump()` does not itself reference `connected`, but the brief's explicit instruction was to make this change regardless (matching the codebase's stated style of explicit `final` on captured parameters used by anonymous inner classes) — transcribed exactly as specified.

- **`pump()` body:** inserted a block of exactly the code given in the brief, between the closing of the `writeFrame(connected, MessageType.HELLO, ...)` statement and the existing `clearPendingSnapshot();` call. This block:
  - calls `connected.getInputStream()` once, outside any `Runnable`, so the checked `IOException` it can throw lands in `pump`'s existing `throws IOException` clause (matching every other failure path in `pump`, which all funnel into `connectLoop`'s existing retry/backoff handling);
  - starts a new, unjoined, unreferenced `Thread` named `"glassnotify-reader"` that runs a `LinkReader` against that input stream;
  - the `LinkReader.Listener.onGlassState` callback (which fires on the reader thread) does nothing itself except `main.post(...)` a `Runnable` that calls `alerter.onGlassState(state)` — this is the only place the reader thread's data crosses onto the main thread, and it never touches the socket, `wakeLock`, `backoff`, `connectedSocket`, or `connectingSocket`, preserving the single-writer invariant documented in the class comment;
  - logs `"reverse channel ended"` via the existing `TAG`/`Log.i` once `LinkReader.run()` returns (which per `LinkReader`'s own contract happens only when the stream dies, and never throws).

No other line in the file was touched. The forward path (HELLO write, snapshot writes, PING loop, backoff, reconnect logic in `connectLoop`) is unchanged, confirmed by re-reading the full `git diff` for this file before committing.

## Commands run and actual output

```
$ ./gradlew test assembleDebug
...
BUILD SUCCESSFUL in 2s
118 actionable tasks: 29 executed, 89 up-to-date
```

Test counts (one variant only per module, to avoid the debug+release double-count gotcha):

```
$ find wire -path "*/test-results/*" -name "TEST-*.xml" | xargs grep -o 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
58

$ find glass -path "*/test-results/testDebugUnitTest/*" -name "TEST-*.xml" | xargs grep -o 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
32

$ find phone -path "*/test-results/testDebugUnitTest/*" -name "TEST-*.xml" | xargs grep -o 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
42

$ find . -path "*/test-results/*" -name "TEST-*.xml" | xargs grep -l 'failures="[1-9]'
(no output — zero failures anywhere)
```

Results: wire 58, glass 32, phone 42 — exactly matching the expected totals in the brief. `glass` did not move off 32, so no out-of-scope investigation was triggered. Zero failures across all modules.

## Commit

```
$ git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java \
          phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkClientService.java \
          phone/src/main/res/drawable/ic_glass_charged.xml \
          phone/src/main/res/values/strings.xml
$ git commit -m "feat(phone): alert when Glass finishes charging ..." (exact message from brief's step 6, including the Co-Authored-By trailer)
[feat/glass-charge-alert e19f50c] feat(phone): alert when Glass finishes charging
 4 files changed, 135 insertions(+), 1 deletion(-)
 create mode 100644 phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlerter.java
 create mode 100644 phone/src/main/res/drawable/ic_glass_charged.xml
```

Commit SHA: `e19f50c3f9e0b2bf21c71b86ed9b554b0070a407`

## Deviations from the brief

None in substance. The only judgment call was import placement: the brief presented the four new imports as two separate pairs (step 4a's two, step 4d's two "alongside those from step 4a"), without dictating exact line position. I inserted all four into the file's existing alphabetically-sorted import block rather than clustering them elsewhere, since that is both consistent with "beside/alongside the existing ones" and with the file's pre-existing style. This has no effect on behavior or compilation.

## Anything that surprised me

- `phone/src/main/res/drawable/` did not exist before this task — had to create the directory. Not a concern, just noting it since the brief's file list only says "Create" for the drawable, not "create the directory."
- An untracked `data/` directory was already present in the repo at the start of this task (per initial `git status`), unrelated to this work — left untouched and not staged.
- Everything else matched expectations exactly: the `LinkReader`, `ChargeAlertPolicy`, and `GlassState` interfaces on disk matched the brief's stated signatures precisely, so no adaptation was needed.
