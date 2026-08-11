# Task 3 report: ChargeAlertPolicy

## What was implemented

- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java` — pure-JVM
  decision logic (no `android.*` imports) that converts a repeated `GlassState` stream from
  Glass into at most one `SHOW` per charge and one `CANCEL` per unplug, using a single
  package-private `boolean shown` field. `Action` is a nested enum (`SHOW`, `CANCEL`, `NONE`).
  `FULL_LEVEL` is a package-private `static final int` = `100`. Not thread-safe, main-thread
  only, as documented in the class Javadoc (which references `ChargeAlerter`, the Task 4
  caller that does not exist yet — this is expected per the brief).
- `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java` — 9 JUnit
  tests covering: completing a charge, not repeating while still on the charger, not
  re-alerting on reconnect while still full, cancelling on unplug, re-arming after unplug,
  not cancelling when nothing was shown, not alerting when found full but already unplugged,
  cancelling only once per unplug, and a dismissed notification not reappearing by itself.

Both files were transcribed verbatim from
`.superpowers/sdd/2026-08-10-glass-charge-alert/task-3-brief.md` with no changes, renames,
reformatting, or additions.

## TDD sequence and actual output

1. **Wrote the failing test** (`ChargeAlertPolicyTest.java`, `ChargeAlertPolicy.java` not yet
   created).

2. **Ran and confirmed the expected failure:**

   Command: `./gradlew :phone:testReleaseUnitTest --tests '*ChargeAlertPolicyTest*'`

   Result: `BUILD FAILED` — `Execution failed for task
   ':phone:compileReleaseUnitTestJavaWithJavac'` with 29 `error: package ChargeAlertPolicy
   does not exist` / `cannot find symbol` compiler errors, e.g.:
   ```
   error: package ChargeAlertPolicy does not exist
           assertEquals(ChargeAlertPolicy.Action.SHOW, charging(100));
   ```
   This matches the brief's expected failure exactly (compilation error, class does not
   exist).

3. **Wrote `ChargeAlertPolicy.java`** verbatim from the brief.

4. **Ran and confirmed it passes:**

   Command: `./gradlew :phone:testReleaseUnitTest --tests '*ChargeAlertPolicyTest*'`

   Result: `BUILD SUCCESSFUL in 959ms`. The generated report
   `phone/build/test-results/testReleaseUnitTest/TEST-dev.erinlkolp.glassnotify.phone.ChargeAlertPolicyTest.xml`
   shows:
   ```
   tests="9" skipped="0" failures="0" errors="0"
   ```
   9/9 pass, matching the brief's expectation.

5. **Full suite:**

   Command: `./gradlew test`

   Result: `BUILD SUCCESSFUL in 1s` (mostly `UP-TO-DATE`; new work was
   `:phone:testDebugUnitTest` and `:phone:testReleaseUnitTest`).

   Counted one variant per module (per the task's counting-tests gotcha — Android modules
   emit both `testDebugUnitTest` and `testReleaseUnitTest`, so only `testReleaseUnitTest` was
   summed for `glass` and `phone`; `wire` has a single plain `test` variant):

   ```
   wire (test) count:              58
   glass (testReleaseUnitTest):    32
   phone (testReleaseUnitTest):    42
   ```

   This matches the expected post-task totals (wire 58, glass 32, phone 42) exactly.
   `grep`-ing `failures="..." errors="..."` across all counted `TEST-*.xml` files in those
   three result directories returned only `failures="0" errors="0"` — zero failures/errors
   anywhere in the counted set.

## Commit

```
git add phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java \
        phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicyTest.java
git commit -m "feat(phone): decide when a full charge is worth an alert ..."
```

Commit SHA: **`89dae9c923d2e11ba01ddcc77a5363b61bf743cd`**

`git status` before committing showed only an unrelated untracked `data/` directory (present
at session start, not touched by this task) in addition to the two new files, so nothing
extraneous was staged.

## Surprises / deviations

- None. The brief's source was transcribed exactly as given — no renames, reformatting, or
  additions. The one thing worth flagging (not a deviation, just a note): the class Javadoc
  references `{@link ChargeAlerter}`, a class that does not exist on this branch yet (it's
  Task 4's caller). This did not cause any compile or javadoc-lint failure in this build
  configuration, and the brief explicitly states nothing calls this policy yet, so it was
  left as written.
- No build files were touched, and `LinkClientService`, `LinkReader`, and everything in
  `wire` were left untouched, per the constraints.
