# SDD ledger — plan: docs/superpowers/plans/2026-08-04-glass-notifications.md

Branch: feat/glass-notifications
Branch base: 5cf87bb (merge-base with main for the final review)
Started: 2026-08-04

Note: this workspace is deliberately NOT git-ignored, matching the gesture
launcher's convention of retaining the ledger and per-task reports in git.
It is not deleted at the end of the run.

## Pre-flight scan

Two conflicts found and resolved in the plan before execution (commit 5cf87bb):

- Task 10 originally told the implementer to write a call to `LinkClientService`
  (which Task 11 creates) and comment it out. That would have produced
  commented-out code a reviewer should flag. Task 10 now simply does not
  reference it; Task 11 adds the call. Every module compiles standalone at the
  end of every task.
- Task 12's `SetupActivity.requestBatteryExemption` carried an
  `SDK_INT < M` guard that is unreachable at minSdk 26. Removed.

No conflicts found between tasks and the Global Constraints.

## Progress

Task 1: complete (commits 5cf87bb..c1046ac, review clean)
Task 1: reviewer Minor "no .gitignore for build/" — resolved by controller, false
  positive: .gitignore:3 already has build/, committed before the branch so it
  fell outside the diff the reviewer saw.
Task 1: reviewer ⚠️ "cannot verify wrapper works end-to-end" — resolved: the
  suite ran and reported 11/11, which requires a working wrapper.
Task 2: complete (commits c1046ac..d542044, review clean)
Task 2: minor (deferred): FrameCodec.write silently truncates a type outside 0-255
  to its low 8 bits; only MessageType constants are passed today.
Task 2: minor (deferred): no positive-boundary test for a frame exactly
  MAX_FRAME_BYTES long (only the just-over rejection). Inherited from the brief.
Task 2: minor (deferred): FrameCodec wraps the stream in Data*Stream per call
  rather than taking a pre-wrapped one. Correct as written; noted for callers.
Task 3: complete (commits d542044..a498282, review clean) — wire module done, 36 tests
Task 3: minor (deferred): the worst-case-size test maxes text but not title, so it
  understates the true worst case by ~1.3KB across 20 items. Margin is large;
  outcome unchanged. Inherited from the brief.
Task 3: minor (deferred): codecs do not close their Data*Stream wrappers. Harmless
  over ByteArray streams; matches the brief.
Task 4: complete (commits a498282..9593754, review clean) — glass module assembles
Task 4: reviewer ⚠️ "cannot verify settings/AGP/Gradle/useAndroidX from diff" —
  resolved by controller: settings.gradle.kts includes :glass, gradle.properties
  has android.useAndroidX=false, root build declares AGP 8.7.0, wrapper is 8.9.
Task 4: minor (deferred): the cursor's in-range invariant is maintained jointly by
  setSize/next/previous rather than centrally; worth a comment if a fourth
  mutating method is ever added.
Task 5: reviewer spec ❌ — 2 Important findings, both traced to a self-contradictory
  Interfaces block in the plan (controller's authoring error), not the code.
  Escalated to Erin as a plan conflict per the skill. Ruling: CODE GOVERNS.
  Plan corrected in commit below; no code change, no fix round needed.
Task 5: complete (commits 9593754..98483f5, review resolved by plan correction)
Task 5: reviewer ⚠️ "cannot verify test results" — resolved by controller running
  :wire:test :glass:testDebugUnitTest :glass:assembleDebug — all green.
Task 5: minor (deferred): PeerPin discards commit()'s boolean; a Log.w on false
  would make a silent persistence failure visible, matching SnapshotStore.persist.
Task 5: minor (deferred): persist() writes in place rather than temp-then-rename;
  a kill mid-write truncates the cache. Verified to degrade safely, not crash.
Task 5: minor (deferred): closeQuietly fully-qualifies java.io.Closeable instead
  of importing it. Stylistic.
Task 5: note: implementer's report overstated two file line counts. Cosmetic.
Task 6: reviewer spec ✅ but task quality "Needs fixes" — 1 Important:
  QueueActivity sized its QueueCursor only in onResume(), so a snapshot arriving
  from the service thread while foregrounded left the cursor stale and
  items.get(index) could go out of bounds. Deterministic crash, not a race.
  Labeled plan-mandated (verbatim from the plan's Step 7), but the fix SERVES the
  plan rather than contradicting it — design §12.3 explicitly requires this exact
  shrink-under-the-reader case to clamp rather than throw, and QueueCursor already
  implements the clamp. Controller ruling: fix, no human gate needed, since no
  part of the plan defends the behaviour.
Task 6: fix round 1/5 dispatched to the original implementer (a793e654c4404081c).
Task 6: minor (deferred): SwipeDetector.latest is written by begin()/move() and
  never read — dead state from an earlier path-tracking design.
Task 6: minor (deferred): no SwipeDetector test sits in the band where the ratio
  and raw dx/dy formulas diverge (e.g. dx=60,dy=55), so a regression reverting the
  documented "ratio, not raw" decision would pass. Inherited from the brief.
Task 6: deferred to Task 8: the activity does not live-update when a snapshot
  arrives while foregrounded — it only re-renders on navigation or onResume.
  Real, but out of scope here; needs a notify path from LinkServerService.
Task 6: fix round 1/5 (1 addressed, 0 open; commits 4787a6b..b49edc8)
Task 6: complete (commits 21080d7..b49edc8, review clean)
Task 6: note: the new QueueCursorTest case overlaps the existing clamp test — the
  cursor was always correct; the bug was the activity never calling setSize. It
  stands as a regression anchor. QueueActivity's half is verified by compilation
  and inspection only (no Robolectric, deliberately) — confirm at bring-up.
Task 7: complete (commits d97f9ab..6f96c2f, review clean)
Task 7: minor (deferred): no test distinguishes "newest among genuinely-new items"
  from "overall-newest, then checked for newness". Reviewer traced the code by hand
  and confirmed it is correct; this is a coverage gap, not a bug.
Task 7: minor (deferred): InterruptOverlay.show() does not null-guard its item;
  CardRenderer.interruptCard would NPE outside the try/catch. Callers gate on
  selectInterrupt() returning non-null, so low risk.
Task 7: note: implementer's report undercounted file line counts again. Cosmetic.
Task 8: implementer DONE_WITH_CONCERNS — had to add buildFeatures{buildConfig=true}
  to glass/build.gradle.kts, absent from the plan. Accepted: AGP 8.x stopped
  generating BuildConfig by default, so the plan's own BuildConfig.DEBUG reference
  could not compile. Minimal and correct. Plan needs the same fix.
Task 8: reviewer spec ✅ but quality "Needs fixes" — 3 Important, all plan-mandated
  and all lifecycle, none defended anywhere in the plan, so fixed without a human
  gate (same reasoning as Task 6):
  (1) the connected BluetoothSocket was a local, never a field, so onDestroy could
      not unblock an actively-serving accept thread — it could outlive the service
      and keep popping overlays until the phone disconnected;
  (2) serverSocket was not safely published between the accept thread and the main
      thread, so even the no-connection cleanup path could silently fail;
  (3) DebugInjectReceiver built a throwaway InterruptOverlay per broadcast, so two
      injections inside the 5s window stacked two uncoordinated windows.
Task 8: reviewer ⚠️ x2 resolved by controller — RECEIVE_BOOT_COMPLETED is declared
  (manifest:8) and version_mismatch exists (strings.xml:6).
Task 8: fix round 1/5 dispatched to the original implementer (aa1ff0ed98363d356).
Task 8: minor (deferred): DebugInjectReceiver is exported=true on an unprotected
  action, so any co-installed app could trigger fake interrupts in a debug build.
Task 8: minor (deferred): repeated accept() failures have no backoff (only
  listenUsingRfcomm failures sleep), so a hardware edge case could hot-loop.
Task 8: minor (deferred): one fully-qualified NotificationItem reference instead
  of an import. Cosmetic.
Task 8: fix round 1/5 (3 addressed, 0 open; commits 6fe87fd..4ad1065)
Task 8: complete (commits 6f96c2f..4ad1065, review clean) — GLASS APP DONE
Task 8: plan's Task 8 listing corrected to match the shipped lifecycle fixes, so
  a re-run cannot rebuild the same three bugs.
Task 8: lifecycle fixes verified by compilation and inspection only. Confirm at
  bring-up that stopping the service mid-connection actually tears the socket down.
Task 9: complete (commits 451292b..474deb5, review clean)
Task 9: reviewer ⚠️ "AGP/Gradle versions not in diff" — resolved: AGP 8.7.0 in the
  root build, wrapper is gradle-8.9-bin.
Task 9: reviewer ⚠️ "confirm AllowRule is wired in later" — resolved: it is NOT.
  Nothing references it anywhere. Dead code the controller put in the plan.
  Plan corrected; Task 10 deletes the shipped file.
Task 9: minor (deferred): no test at the exact truncation boundary
  (value.length()==maxChars). Code is correct (<=), just not test-pinned.
Task 9: minor (deferred): substring truncation can split a UTF-16 surrogate pair
  if text ends on an emoji at the cut point — corrupts rather than throws.
Task 10: reviewer verdict Approved but with 1 Important, so the loop triggers:
  SnapshotBus.pending is a plain boolean mutated from publish() and deliver(),
  and the check-then-act is not atomic even if it were volatile. Latent today
  (only the main thread publishes) but undocumented and unenforced, and Task 11
  wires a second component in next. Plan-mandated; nothing defends it; fixed.
Task 10: fix round 1/5 dispatched to the original implementer (a1a52632188fcea5f).
Task 10: minor (deferred): NotifyListenerService.republish() rebuilds AllowlistStore
  and re-decodes SharedPreferences on every callback, ahead of the bus's own
  coalescing. Wasted work, not a correctness issue.
Task 10: AllowRule.java deleted as authorised; repo-wide grep confirms no dangling
  references.
Task 10: fix round 1/5 (1 addressed, 0 open; commits be30cb5..174070e)
Task 10: complete (commits d26adc4..174070e, review clean)
Task 11: reviewer spec ✅ but quality "Needs fixes" — 1 CRITICAL + 2 Important, all
  plan-mandated, none defended, fixed without a human gate:
  (CRITICAL) pump()'s HELLO/PING writes run on the worker thread while
      onSnapshot()->send() writes SNAPSHOT from the bus callback thread, and
      socketLock guards only the field READ, not the FrameCodec.write itself. A
      notification arriving during the 10s PING splices two frames on the same
      stream; Glass's framing desyncs and this end has no read side to ever
      notice. Ordinary operation, not adversarial timing. Best catch of the run.
  (Important) the in-flight socket is not published before connect(), so
      onDestroy() cannot abort a blocking connect; when it returns the thread
      sends HELLO plus a snapshot before pump()'s running check is consulted —
      a destroyed service writing live data to Glass.
  (Important) waitFor() is a bare wait() with no wake-requested flag, so a
      wake() landing during the status() Binder IPC window is dropped and the
      phone waits out the full 60s backoff — deliberate behaviour #2 silently
      not working.
Task 11: reviewer ⚠️ x2 resolved by controller — all six phone strings exist, and
  the manifest declares only install-time BLUETOOTH/BLUETOOTH_ADMIN (no
  BLUETOOTH_CONNECT, correct for targetSdk 28). Build output is warning-free.
Task 11: fix round 1/5 dispatched to the original implementer (a05bd9d520f0b6093).
Task 11: minor (deferred): the `worker` Thread field is assigned but never read,
  joined or interrupted — no way to confirm the thread actually terminated.
Task 11: minor (deferred): findBondedGlass fully-qualifies java.util.Locale.US
  while AclReceiver imports Locale — inconsistent between two files doing the
  same thing.
Task 11: minor (deferred): AclReceiver uses the single-arg getParcelableExtra,
  deprecated since API 33. Harmless at targetSdk 28.
Task 11: fix round 1/5 (3 addressed, 0 open; commits 0bd3f62..567d7f9)
Task 11: complete (commits 7e7c963..567d7f9, review clean)
Task 11: re-reviewer out-of-scope note "worker may linger 60s past destroy" —
  checked by controller and it is a FALSE POSITIVE: onDestroy does
  synchronized(wakeLock){notifyAll()}, so a parked waitFor wakes and the
  running check ends the loop. The separate unused-worker-field minor stands.
Task 11: re-reviewer noted a narrow benign residual — if closeSocket lands during
  the status() call after the running check, pump throws on the closed stream
  rather than transmitting. No live bytes reach a destroyed service.
Task 11: plan's LinkClientService listing corrected to match the shipped fixes.
Task 12: reviewer Approved with 1 Important, labeled plan-mandated: build output
  not pristine. Controller measured before escalating — TWO warning classes, not
  one: (a) "source/target value 8 is obsolete", a pure artifact of the deliberate
  Java 8 constraint the Glass device requires, and (b) the getDefaultAdapter
  deprecation, which is 7 call sites across 3 files in BOTH modules — including
  LinkServerService and LinkClientService, the two files where the frame-splicing
  race and accept-thread leak were fixed over two prior rounds.
  ESCALATED to Erin as a plan conflict per the skill. RULING: suppress (a) with one
  gradle.properties line; leave (b) alone rather than churn just-stabilised
  concurrency code for an informational note. (b) is carried to the final review as
  a known accepted item.
Task 12: fix round 1/5 dispatched to the original implementer (a38faf428ffb02d2b).
Task 12: minor (deferred): AllowlistActivity.getView re-decodes the rules map per
  row bind, and cycle() reads it again on top of put/remove's own read. Bounded by
  rule-set size, not app count, so not a scaling hazard — but hoisting one read
  would remove the churn.
Task 12: minor (deferred): inline fully-qualified AdapterView.OnItemClickListener
  instead of an import. Verbatim from the brief.
Task 12: minor (deferred): SetupActivity.refresh() calls hasNotificationAccess()
  twice per refresh. Trivially cheap.
Task 12: fix round 1/5 (0 addressed, 1 open — commits 8f11b37..5c23207). The named
  AGP property removed AGP's own "Java compiler version 21..." diagnostic but NOT
  javac's raw "[options] source/target value 8 is obsolete" lines, which come from
  javac directly — confirmed by their appearing for :wire, a plain java-library
  AGP never touches. Implementer diagnosed this correctly and stopped rather than
  guessing at further flags, as instructed.
Task 12: fix round 2/5 dispatched — add -Xlint:-options to all three modules,
  which is the remedy javac names in its own warning text. Trade-off noted: it
  silences the whole [options] category, accepted because the source/target level
  is fixed for the life of the project.
Task 12: fix round 2/5 (1 addressed, 0 open; commits 5c23207..fc13b96)
Task 12: complete (commits ee7741a..fc13b96, review clean)
Task 12: ACCEPTED RESIDUE per Erin's ruling — "Note: Some input files use or
  override a deprecated API" (2 occurrences) from BluetoothAdapter.getDefaultAdapter
  at 7 call sites. Deliberately not fixed. Carry to the final review as known.

=== ALL 13 IMPLEMENTATION TASKS COMPLETE ===
Totals: wire 36 tests, glass 31, phone 22. Both APKs build. Build output clean
except the one accepted deprecation note.

=== WHOLE-BRANCH REVIEW (opus, a4a618e..fc13b96, 27 commits) ===
Verdict: Ready to merge WITH FIXES. Protocol traced coherent end to end for all
three message types; framing coverage matches spec 12.1; the eight prior fixes
hold up individually. But two Criticals show they are not coherent as a SET:
 C1 send() can write to a socket still inside connect(). Fix #7 published the
    socket before connect() so onDestroy could abort it; fix #6 made send() write
    through that same field. A notification arriving during the ~10s connect
    either aborts the phone's own connection (IOException -> closeSocket on the
    socket connectLoop is blocked in) or throws unchecked out of a Handler
    runnable on the MAIN THREAD -> process crash.
 C2 Blocking RFCOMM writes run on the main thread. SnapshotBus.handler is the
    main looper, so onSnapshot -> send -> writeFrame does a blocking write on the
    UI thread; writeLock is also held across the worker's blocking PING write, so
    the main thread can block on lock acquisition alone. ANR when Glass goes out
    of range, and an ANR kill takes NotifyListenerService down with it.
 I3 key and appLabel are never truncated -> 64KB frame ceiling reachable ->
    ProtocolException -> closeSocket -> reconnect -> re-send identical snapshot
    -> permanent unrecoverable loop. The Task 3 deferral spotted the small
    sibling of this and stopped short.
 I4 backoff.reset() fires on socket connect, not on a healthy session, so Glass's
    accept-then-reject paths (unpinned MAC, version mismatch) produce indefinite
    full-duty-cycle churn; exponential backoff never engages where it is for.
 I5 Glass accept() failure has no backoff -> hot loop on the smallest battery.
 I6 QueueActivity still never live-updates. THE LEDGER DEFERRED THIS TO TASK 8
    AND TASK 8 SHIPPED WITHOUT IT AND NOBODY REOPENED IT. Staleness marker never
    appears while the queue is open, which is the entire guard against showing
    hours-old notifications as current.
 I7 Spec 11's "watch ACTION_STATE_CHANGED and idle" implemented on neither side
    (both poll). Spec question, not a code question.
 I8 Version mismatch is a ~3.5s Toast, not a state. Spec 7.1 says STATE, and
    CardRenderer.messageCard exists for exactly this. The PLAN downgraded the
    spec. Plan/spec divergence.
Triage of the 25 deferred minors: 2 must resolve (I5, I6), 2 obsolete, 3 worth
doing opportunistically (PeerPin commit() return - the security control fails
OPEN silently; SwipeDetector ratio-band test; MAX_FRAME_BYTES boundary test),
18 fine to carry. Reviewer explicitly declined to inflate the list.
Process finding: "deferred to Task N" needs a check at Task N's close.

=== FINAL FIX WAVE (opus, 11 commits fc13b96..a623441) ===
All 11 whole-branch findings ADDRESSED. Tests 89 -> 99. Single-writer restructure
verified independently by the re-reviewer, which reconstructed the happens-before
argument rather than accepting the report: exactly one FrameCodec.write call site,
onSnapshot does no I/O under any monitor, no snapshot can be lost (publish-then-
post ordering plus clear-before-read means the worst case is a redundant re-send),
no missed wakeup, PING's IOException still propagates. Socket split into
connectingSocket (teardown-only) and connectedSocket (sole writable).
Re-reviewer also independently recomputed the worst-case snapshot as 9150 bytes
ASCII, confirming the 8KB->16KB re-baseline was honest, not a loosened test.

=== SCOPED RE-REVIEW (opus) — ONE NEW IMPORTANT DEFECT INTRODUCED ===
OPEN (Important): onSnapshot's notifyAll() now signals the SAME wakeLock monitor
  that waitForWake parks on, and waitForWake has no deadline loop — it cannot
  distinguish timeout from notify. So with Glass out of range and the backoff at
  60s, EVERY notification post or removal wakes the worker and triggers another
  connect attempt. Notification traffic sets the reconnect cadence; the
  exponential backoff is defeated. Same duty-cycle failure class as IMPORTANT 4,
  moved from the reset path to the wait path. Newly introduced by the wave — before
  it, onSnapshot never touched wakeLock. The fix report explicitly claims this
  cannot happen; the re-reviewer traced the code and showed it can.
  Minimal fix: a deadline loop in waitForWake, or a separate monitor for
  snapshotPending. ~5 lines.
OPEN (Low, parked): unguarded first encode() in SnapshotCodec.encodeWithinFrame
  (unreachable today — SnapshotBuilder caps every field); wakeRequested not
  consumed inside a session (bounded at one extra fast retry); pre-existing
  teardown window where a connect started after onDestroy sampled the socket
  fields is not aborted (correctness unaffected, liveness only); QueueActivity
  re-renders unconditionally every 5s.
Out-of-scope: AclReceiver still calls backoff.reset() on every ACL_CONNECTED, so
  IMPORTANT 4's escalation will sawtooth rather than climb monotonically; and
  AclReceiver can resurrect the link service after notification access is revoked.
Per the skill there is NO second fix wave — load-bearing residuals surface to the
human. ESCALATING the backoff-collapse defect to Erin.

=== REGRESSION FIX (Erin-directed, 2 commits 2cdf2be..58bbec4) ===
All 3 addressed, no new breakage. Tests 99 -> 102.
- waitForWake now has a deadline loop on SystemClock.elapsedRealtime, so a
  spurious notify from onSnapshot re-parks with the remaining time instead of
  collapsing the wait. A real wake() still exits immediately without consulting
  the deadline, including one landing before the wait is entered.
- awaitWork now consumes wakeRequested, so the flag means one thing.
- encodeWithinFrame's first attempt is inside the degradation loop.
Re-reviewer verified wait(0) is structurally unreachable and the loop cannot spin.
Re-reviewer's fair counter to the "untestable" claim: injecting a LongSupplier
clock (defaulting to SystemClock::elapsedRealtime) and relaxing visibility would
let a plain JUnit test drive the real loop with real threads and no test
framework. Legitimate to skip for a targeted regression fix, but it is an
AVOIDABLE limitation, not an unavoidable one. Carried as a minor.

=== ALL CODE COMPLETE — 102 tests, both APKs build ===
