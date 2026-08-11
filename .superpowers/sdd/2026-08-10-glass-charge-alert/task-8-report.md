# Task 8 Report: Correct the documentation

Status: **DONE_WITH_CONCERNS** (one concern, resolved — see "Deviation from brief" below)

Commit: `f3329d579ea8bde2630b9162333be795635ce9b8` on branch `feat/glass-charge-alert`

## Files changed

`git diff --stat` for the commit:

```
 README.md                                          | 22 +++++++++++++++++-----
 .../specs/2026-08-04-glass-notifications-design.md | 12 +++++++++---
 2 files changed, 26 insertions(+), 8 deletions(-)
```

Exactly two files, both `.md`. No code, test, script, or build file touched.

## Passage-by-passage changes

### 1. README.md — §1 "Glass is read-only" bullet (was line 28)

Before:
```
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification — there is no reverse channel at all (see [section 5](#5-how-the-protocol-works)).
```

After:
```
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification. The one thing it does send back is its own battery state, so the phone can tell
  you when Glass has finished charging — see [section 5](#5-how-the-protocol-works).
```

Applied exactly as specified in the brief.

### 2. README.md — §5 heading and message list (was line 191)

- Heading changed: `### The three message types` → `### The four message types`
- Added a `GLASS_STATE` bullet immediately after the `PING` bullet:

```
- **`GLASS_STATE`** — Glass → phone, unsolicited. Glass's own battery level and whether it is plugged
  in. Sent when a connection opens and whenever the level or power state actually changes. This is the
  only message that travels this direction, and the phone acts on exactly one thing in it: reaching
  100% while on power, which raises a "Glass is charged" notification.
```

Applied exactly as specified in the brief.

### 3. README.md — reverse-channel paragraph (was line 205)

Before:
```
There is deliberately **no reverse channel and no acknowledgement**. Glass never sends anything back
to the phone.
```

After:
```
There is deliberately **no acknowledgement**. With full-state snapshots there is nothing to
acknowledge: a lost frame is superseded by the next one.

The reverse channel is exactly one message wide, and should stay that way. Glass volunteers its
battery state and nothing else — it never asks the phone for anything, never confirms receipt, and
never acts on a notification. Adding a second Glass → phone message is a real protocol change and
should be argued on its own merits, not waved through because a channel already exists.
```

Applied exactly as specified in the brief.

### 4. README.md — migration guide parenthetical (was line 356, now line ~366)

Before:
```
`LinkClientService` sets that status the moment TCP-level `connect()` succeeds, before any data is
exchanged, and the protocol has no reverse channel (spec §7.4) — so the phone genuinely cannot learn
it was rejected. Only Glass knows:
```

After:
```
`LinkClientService` sets that status the moment TCP-level `connect()` succeeds, before any data is
exchanged, and the reverse channel carries no such signal (spec §7.4 — it is one message wide and
carries battery state only) — so the phone genuinely cannot learn
it was rejected. Only Glass knows:
```

**Deviation from brief (see "Deviation" section below): I did not limit this edit to only the
parenthetical.** The brief's literal instruction was to change only `(spec §7.4)` to
`(spec §7.4 — the one reverse message carries battery state only)` and leave the rest of the
sentence — including the words "the protocol has no reverse channel" — untouched. I applied that
literal version first and it broke the corrected verification grep (see below), so I also reworded
the clause "the protocol has no reverse channel" → "the reverse channel carries no such signal" to
remove the stale absolute claim while preserving the sentence's argument (the phone still cannot
learn it was rejected, because `GLASS_STATE` carries only battery data). The rest of the paragraph,
and the conclusion "so the phone genuinely cannot learn it was rejected," is untouched. Line-wrap
column width (~100 chars) matches the surrounding paragraph's existing style.

### 5. docs/superpowers/specs/2026-08-04-glass-notifications-design.md — §7.4

Before:
```
### 7.4 No reverse channel

There is no `ACK` and no Glass → phone traffic. With full-state snapshots there is nothing to
acknowledge: a lost frame is superseded by the next snapshot.
```

After:
```
### 7.4 The reverse channel is one message wide

There is no `ACK`. With full-state snapshots there is nothing to acknowledge: a lost frame is
superseded by the next snapshot. There is no request/response either — neither side ever asks the
other for anything.

The single exception, added 2026-08-10, is `GLASS_STATE`: Glass volunteers its own battery level and
power state so the phone can raise an alert when charging completes. It is unsolicited, carries no
reply, and does not make Glass any less read-only with respect to notifications. See
`2026-08-10-glass-charge-alert-design.md` §5.4.
```

Applied exactly as specified in the brief. Section is rewritten, not deleted; the no-ACK /
no-request-response reasoning is preserved and the section now records the one exception.

### 6. README.md — §13 "Tuned values" table

Real table has 4 columns: `Constant | Value | Where | Status` (the brief's suggested row had only 3
cells and did not match this shape — see "Note on table shape" below). Added, following the last
existing row (`Backoff.MAX_MS`):

```
| `ChargeAlertPolicy.FULL_LEVEL` | `100` (100%) | `phone/src/main/java/dev/erinlkolp/glassnotify/phone/ChargeAlertPolicy.java` | Fixed. Battery level, while on power, at which the phone raises the "Glass is charged" notification. |
```

Verified against source: `ChargeAlertPolicy.java:50` — `static final int FULL_LEVEL = 100;` — value
and constant name match the brief.

## Deviation from brief — why, and what I'd flag for review

The top-level task instructions doubly emphasized "Change ONLY the parenthetical [at line 356]...
Do not rewrite the surrounding paragraph," while also requiring (also doubly emphasized) that the
corrected case-insensitive grep show zero matches in `README.md`. These two constraints are in direct
tension: the original sentence contains the literal substring "no reverse channel" *outside* the
parenthetical ("the protocol **has no reverse channel** (spec §7.4)"), so satisfying "change only the
parenthetical" necessarily leaves a grep match in `README.md`, violating "any match in README.md...
means you missed one."

I resolved this in favor of the verification gate (treating it as the harder, more explicit
constraint, and because leaving an unqualified "has no reverse channel" claim in the README while
having just corrected the identical claim in three other places seemed like the actual defect the
task is asking to fix) by rewording the clause itself, not just the parenthetical, while keeping the
change minimal and the paragraph's argument/conclusion intact. This is worth a second look — if the
literal "change only the parenthetical" instruction was intentional and the grep hit was expected to
be accepted here, my edit went further than asked.

## Note on table shape

The brief's proposed row for §13 had 3 cells (`Full-charge threshold | 100% while on power |
ChargeAlertPolicy.FULL_LEVEL`), but the actual table has 4 columns (`Constant | Value | Where |
Status`), matching every other row in the table (e.g. `Backoff.MAX_MS`, `SnapshotBus.DEBOUNCE_MS`).
I followed the real table's shape: put the constant name in the `Constant` column (`ChargeAlertPolicy.FULL_LEVEL`),
`100 (100%)` in `Value`, the source file path in `Where`, and a `Status` cell describing what the
constant does, consistent with the "Fixed." / "Starting value, tune on hardware." convention used by
neighboring rows.

## Verification

Case-insensitive grep (corrected per the task instructions, since the brief's suggested
`grep -rn` is case-sensitive and would miss the spec heading's capital N):

```
$ grep -rni "no reverse channel\|three message types\|never sends anything back" README.md docs/
docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md:137:This supersedes parent spec §7.4, which stated there is no reverse channel at all. Most of that
docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md:313:| `README.md:28` | "there is no reverse channel at all" |
docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md:314:| `README.md:191` | "The three message types" |
docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md:315:| `README.md:205` | "no reverse channel and no acknowledgement… Glass never sends anything back" |
docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md:316:| Parent spec §7.4 | "No reverse channel" |
docs/superpowers/plans/2026-08-10-glass-charge-alert.md:2115:  on a notification — there is no reverse channel at all (see [section 5](#5-how-the-protocol-works)).
docs/superpowers/plans/2026-08-10-glass-charge-alert.md:2128:Change the heading at `README.md:191` from `### The three message types` to `### The four message types`, and add an entry after the `PING` description:
docs/superpowers/plans/2026-08-10-glass-charge-alert.md:2139:At `README.md:205`, replace the paragraph beginning "There is deliberately **no reverse channel and no acknowledgement**" with:
docs/superpowers/plans/2026-08-10-glass-charge-alert.md:2153:At `README.md:356` the migration guide says the protocol "has no reverse channel (spec §7.4) — so the phone genuinely cannot learn". Read the surrounding sentence in place. It is about the phone being unable to discover something from Glass during setup, which is still true — the reverse channel carries battery state only. Change just the parenthetical to `(spec §7.4 — the one reverse message carries battery state only)` and leave the surrounding argument intact.
docs/superpowers/plans/2026-08-10-glass-charge-alert.md:2182:Run: `grep -rn "no reverse channel\|three message types\|never sends anything back" README.md docs/`
docs/superpowers/plans/2026-08-10-glass-charge-alert.md:2191:Four places said Glass never sends anything back. Corrected, and the
```

All matches fall inside `docs/superpowers/specs/2026-08-10-glass-charge-alert-design.md` (which
deliberately quotes the old wording while explaining what replaced it) and
`docs/superpowers/plans/2026-08-10-glass-charge-alert.md` (the implementation plan, which
legitimately quotes the old text and this very brief). **Zero matches in `README.md` or in
`docs/superpowers/specs/2026-08-04-glass-notifications-design.md`.**

### Build/test verification

```
$ ./gradlew test
BUILD SUCCESSFUL in 977ms
86 actionable tasks: 86 up-to-date
```

Per-module test counts, one variant only (`testDebugUnitTest`, avoiding the debug+release
double-count):

| Module | Test count | Failures |
|---|---|---|
| wire | 58 | 0 |
| glass | 47 | 0 |
| phone | 42 | 0 |

Matches the expected wire 58 / glass 47 / phone 42, zero failures.

## Commit

```
commit f3329d579ea8bde2630b9162333be795635ce9b8
Author: Erin L. Kolp <erinlkolpfoss@gmail.com>

    docs: record the reverse channel

    Four places said Glass never sends anything back. Corrected, and the
    parent spec's 7.4 is rewritten rather than deleted - its reasoning about
    acknowledgements and deltas still holds, and the boundary it draws is
    worth keeping now that there is exactly one exception to it.

    Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

Message matches the brief's Step 8 exactly. `git diff --stat` for the commit shows exactly two
files, both `.md` (see top of this report).
