# SDD ledger — plan: docs/superpowers/plans/2026-08-10-glass-charge-alert.md

Branch: feat/glass-charge-alert
Baseline: 102 tests green (wire 45, glass 32, phone 25) at ec70ecc
Pre-flight: plan scanned; fixed two "two forms shown" ambiguities (Task 4 step 4d, Task 6 step 1) before dispatch. No task/constraint conflicts found.

Task 1: complete (commits 9595fa1..7bcb306, review clean — spec met, quality approved, no findings)
Task 2: minor (deferred): LinkReaderTest.stopsOnAnUnknownProtocolVersion comment says "length 2" but the bytes correctly encode length 4. Originates in the plan text (my error), transcribed faithfully. Comment-only, no functional effect.
Task 2: complete (commits 7bcb306..9895425, review clean — spec met, quality approved, 1 deferred minor)
Task 3: minor (deferred): three ChargeAlertPolicyTest cases drive an identical input sequence (onPower/100/shown=true -> NONE); distinct narratives, no distinct coverage. Plan-prescribed, not an implementer choice.
Task 3: minor (deferred): no test pins "on power, level<100, shown already true" (mid-charge dip after alerting). Same NONE fallthrough already covered on each operand separately.
Task 3: complete (commits 9895425..89dae9c, review clean — spec met, quality approved, 2 deferred minors)
Task 4: minor (deferred): onDestroy neither clears `alerter` nor calls main.removeCallbacksAndMessages(null); a GLASS_STATE decoded just before teardown can post ID 2 after the service dies. No crash or leak (application context). Arguably still wanted by the wearer.
Task 4: minor (deferred): ChargeAlerter implements LinkReader.Listener directly, whose contract says "called on the reader thread", while ChargeAlerter is main-thread-only. Current wiring hops via Handler correctly, but the shape invites a future caller to skip the hop.
Task 4: minor (deferred): the added `final` on pump(BluetoothSocket connected) is unused — the anonymous Runnable captures `reverse`, not `connected`. Plan-mandated for style.
Task 4: complete (commits 89dae9c..e19f50c, review clean — spec met, quality approved, single-writer invariant verified intact, 3 deferred minors)
Task 5: complete (commits e19f50c..e2a4364, review clean — spec met, quality approved, no findings)
Task 6: minor (deferred) [MOST WORTH FINAL-REVIEW TRIAGE]: LinkServerService publishes `stateWriter` AFTER writerThread.start(), so a battery change landing in that window is dropped. Controller note: self-healing — the next reconnect re-seeds the writer from batteryWatcher.latest(), and the design's alert-on-reconnect-if-still-plugged rule then fires. Swapping the two lines is free if the final review wants it.
Task 6: minor (deferred): the 500ms writer join runs before acceptLoop closes the socket, so a genuinely wedged writer always burns the full timeout on the accept thread. Tidiness only; documented as such.
Task 6: minor (deferred): StateWriterTest deadline uses System.currentTimeMillis() (NTP-steppable) rather than nanoTime(). Low-probability flake source.
Task 6: minor (deferred): StateWriterTest has no @After calling writer.stop(); a failing awaitBytes leaks a parked non-daemon thread. Failure runs only.
Task 6: minor (deferred): no test covers the coalescing property the StateWriter class doc headlines — sendsEachOfferedState awaits between offers, which specifically prevents coalescing. Gap originates in the plan.
Task 6: minor (deferred): task-6-report.md claims coalescing is verified by "two sequential offers each awaited separately". It is not — see the previous line. Report inaccuracy, not a code defect.
Task 6: complete (commits e2a4364..52a9faa, review clean — spec met, quality approved, single-writer verified by grep, 6 deferred minors)
Task 7: complete (commits 52a9faa..5fde0c3, review clean — spec met, quality approved, no findings)
Task 8: minor (deferred): README migration sentence wraps mid-clause in the markdown source ("cannot learn / it was rejected"). Rendered output unaffected.
Task 8: deviation (accepted): brief said "change ONLY the parenthetical" at README:356, but the surrounding clause itself asserted "the protocol has no reverse channel" — itself a stale claim. Implementer reworded it minimally; reviewer confirmed the original argument survives.
Task 8: deviation (accepted): brief assumed a 3-column table in README s13; the real table has 4 (Constant|Value|Where|Status). Implementer followed the real table.
Task 8: complete (commits 5fde0c3..f3329d5, review clean — spec met, quality approved, 1 deferred minor, 2 accepted deviations)

--- Task 9 hardware verification (controller-run, both devices on adb) ---
Task 9: PASS check 2 — 100% on power alerts. Confirmed on-device by Erin visually, twice.
Task 9: PASS check 3 — no nagging. posttimeElapsedMs frozen at 535985085 across 3 identical repeats; control (unplug+replug) moved it to 536036592, proving the instrument detects re-posts.
Task 9: PASS check 4 — real unplug broadcast cancels the notification.
Task 9: PASS check 5 — re-arms; next full charge alerts again.
Task 9: PASS check 6 — seed-on-connect. From a clean baseline with Glass genuinely at 100% on power, a phone reconnect delivered the state and posted. Proves StateWriter seeds from batteryWatcher.latest() on connect.
Task 9: PASS check 8 — REAL path via `dumpsys battery set level`: 95/97/99 on AC silent, 100 alerts. Genuine BatteryWatcher -> StateWriter -> LinkReader -> policy chain, not the debug shortcut.
Task 9: KEY HARDWARE FINDING — Glass reports `status: 2` (BATTERY_STATUS_CHARGING) at level 100, never 5 (FULL). Had the design triggered on BATTERY_STATUS_FULL the alert would NEVER have fired on this ROM. Spec section 4's level-based decision is now validated on hardware.
Task 9: DEFECT (real, user-visible, being fixed) — notification renders literal "100%%". getString(int) does not process format escapes; %% collapses only via String.format/getString(int, args). My plan asserted the doubling was required and was half-wrong.
Task 9: minor (deferred) — DebugBatteryReceiver bypasses BatteryWatcher, so fake injections never update batteryWatcher.latest(). A reconnect during fake-driven testing therefore seeds nothing. Debug-tooling limitation, not a product defect, but it cost real debugging time and is worth a comment in the script/receiver.
Task 9: OUTSTANDING — check 1 (a real notification reaching the prism) not verified by me; needs Erin. Check 7 (charge completed while down, unplugged before reconnect) attempted 3x, preconditions invalid each time; retry on the fixed build.
Task 9: DEFECT FIXED — commit f676a44. `formatted="false"` + single `%`. Proven at runtime: android.text=String (100% — ready to go). Plan doc Task 4 Step 1 rationale corrected. Battery override reset afterwards.
Task 9: PASS check 7 (on retry with verified preconditions) — Glass at 100% FULLY unplugged (AC false, USB false via `dumpsys battery set usb 0`), phone reconnects: NO alert. Positive control on the SAME link (plug back in -> POSTED) proves it was not a dead-link false negative. Earlier 3 attempts were invalid: `dumpsys battery unplug` leaves USB powered true while adb-tethered.
Task 9: Glass left in real reporting state (dumpsys battery reset; USB powered, 97%, status 2). Synthetic alert cleared and re-armed, so a genuine 100% will alert naturally.
Task 9: OUTSTANDING FOR ERIN — check 1, a real allowlisted notification travelling phone->Glass to the prism. Not verifiable by me; adb cannot synthesise a third-party notification on API 28. Glass-local rendering IS confirmed (fake-notify -> INTERRUPT overlay fired).
Task 9: complete (8 of 9 checks passed on hardware, 1 defect found and fixed, check 1 handed to Erin)

--- Final whole-branch review (opus) ---
Final: verdict merge-ready WITH CAVEATS. Invariants 1-5 verified against current files: Protocol.VERSION=1; only two FrameCodec.write call sites in production (LinkClientService:382, StateWriter:96); stalled reverse write cannot block main or the prism; no android imports/logging in LinkReader/StateWriter; acceptLoop/dispatch/applySnapshot/connectLoop/awaitWork/writeFrame have ZERO diff hunks.
Final: CONTROLLER RULING OVERTURNED — I earlier parked the Task 6 publish-after-start race as "self-healing on reconnect". That was WRONG. Reviewer's scenario: Glass at 99% on power, phone reconnects, writer seeded (99,true), battery ticks to 100 inside the publish window, onBatteryState sees stateWriter==null and drops it; the tuple is now stable so BatteryWatcher's debounce means no further broadcast ever fires. No alert for the whole session; recovery needs an actual link drop, possibly hours. Elevated to must-fix.
Final: entering ONE fix wave for 5 findings, then one scoped re-review.
Final fix wave: commit 2af4766. Scoped re-review: ALL FIVE findings ADDRESSED, no constraint violated, full exit-path walk of serve() clean (no NPE, no thread leak on any path).
Final: parked — if Thread.start() throws (OOM on a reconnect storm), stateWriter is now published and stays non-null while the accept thread dies. Ruling: low severity, strictly cheaper than the race it replaced; the accept thread was already lost on that path pre-fix, and offer() never blocks so the main thread is unaffected. Not load-bearing.
Final: parked — ChargeAlerter comment collision (new setAutoCancel rationale glued to the pre-existing "Belt and braces" comment for setOnlyAlertOnce). Cosmetic.
Final: parked — LinkServerService logs "reverse channel ended" even on a session that never had one; the "serving without it" line disambiguates. Trivial.
Final: VERDICT ready to merge.
