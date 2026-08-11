## Task 8: Correct the documentation

**Files:**
- Modify: `README.md` (lines 28, 191, 205, and section 13)
- Modify: `docs/superpowers/specs/2026-08-04-glass-notifications-design.md` (§7.4)

Four statements in the docs now say the opposite of what the code does. Read each one in place before editing — the line numbers are from commit `ec70ecc` and will have shifted.

- [ ] **Step 1: Fix README §1**

At `README.md:28`, the bullet currently reads:

```markdown
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification — there is no reverse channel at all (see [section 5](#5-how-the-protocol-works)).
```

Replace with:

```markdown
- **Glass is read-only.** It displays and scrolls a queue. It never dismisses, replies to, or acts
  on a notification. The one thing it does send back is its own battery state, so the phone can tell
  you when Glass has finished charging — see [section 5](#5-how-the-protocol-works).
```

- [ ] **Step 2: Fix README §5**

Change the heading at `README.md:191` from `### The three message types` to `### The four message types`, and add an entry after the `PING` description:

```markdown
**`GLASS_STATE`** — Glass → phone, unsolicited. Glass's own battery level and whether it is plugged
in. Sent when a connection opens and whenever the level or power state actually changes. This is the
only message that travels this direction, and the phone acts on exactly one thing in it: reaching
100% while on power, which raises a "Glass is charged" notification.
```

- [ ] **Step 3: Fix the reverse-channel paragraph**

At `README.md:205`, replace the paragraph beginning "There is deliberately **no reverse channel and no acknowledgement**" with:

```markdown
There is deliberately **no acknowledgement**. With full-state snapshots there is nothing to
acknowledge: a lost frame is superseded by the next one.

The reverse channel is exactly one message wide, and should stay that way. Glass volunteers its
battery state and nothing else — it never asks the phone for anything, never confirms receipt, and
never acts on a notification. Adding a second Glass → phone message is a real protocol change and
should be argued on its own merits, not waved through because a channel already exists.
```

- [ ] **Step 4: Fix the spec cross-reference**

At `README.md:356` the migration guide says the protocol "has no reverse channel (spec §7.4) — so the phone genuinely cannot learn". Read the surrounding sentence in place. It is about the phone being unable to discover something from Glass during setup, which is still true — the reverse channel carries battery state only. Change just the parenthetical to `(spec §7.4 — the one reverse message carries battery state only)` and leave the surrounding argument intact.

- [ ] **Step 5: Rewrite parent spec §7.4**

In `docs/superpowers/specs/2026-08-04-glass-notifications-design.md`, replace section 7.4:

```markdown
### 7.4 The reverse channel is one message wide

There is no `ACK`. With full-state snapshots there is nothing to acknowledge: a lost frame is
superseded by the next snapshot. There is no request/response either — neither side ever asks the
other for anything.

The single exception, added 2026-08-10, is `GLASS_STATE`: Glass volunteers its own battery level and
power state so the phone can raise an alert when charging completes. It is unsolicited, carries no
reply, and does not make Glass any less read-only with respect to notifications. See
`2026-08-10-glass-charge-alert-design.md` §5.4.
```

- [ ] **Step 6: Add the tuned value**

In README section 13 ("Tuned values"), add a row to the existing table matching its format:

```markdown
| Full-charge threshold | 100% while on power | `ChargeAlertPolicy.FULL_LEVEL` |
```

- [ ] **Step 7: Verify no stale claims remain**

Run: `grep -rn "no reverse channel\|three message types\|never sends anything back" README.md docs/`
Expected: no matches outside `2026-08-10-glass-charge-alert-design.md`, where §5.4 quotes the old wording deliberately while explaining what replaced it.

- [ ] **Step 8: Commit**

```bash
git add README.md docs/superpowers/specs/2026-08-04-glass-notifications-design.md
git commit -m "docs: record the reverse channel

Four places said Glass never sends anything back. Corrected, and the
parent spec's 7.4 is rewritten rather than deleted - its reasoning about
acknowledgements and deltas still holds, and the boundary it draws is
worth keeping now that there is exactly one exception to it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

