# Soundness checker — outstanding gaps

Inventory of gaps in `SoundnessChecker.kt` after the case-171 conservation
work landed. Surveyed by reading the checker source, not speculation.

## Status

All seven gaps catalogued in the original survey are addressed. Listed
in commit order; rule codes are what the checker emits today.

| # | gap | rule | commit |
|---|---|---|---|
| 1 | R8 deep conservation never implemented | `R8_deep_conservation` | `c384ce2` |
| 2 | `committed_demands` vs pegging silent divergence | `R0_committed_consistency` | `84f10eb` |
| 4 | `walkPurchase` was a structural no-op | `R7d_purchase_{pid,lid,qty}_*` | `63ab38a` |
| 3 | demand-root timing unchecked | *reverted — see "Principle" below* | `f3f35ee` → reverted |
| 5 | R7b/R7c hard-coded `1e-6` tolerance | (tolerance hardened) | `508af46` |
| 6 | tree-selection "last wins" silent fallback | `R0_pegging_duplicate` | `508af46` |
| 7 | method override conformance unchecked | `R9_method_override_violated` | `20619f8` |

## Principle: what is and isn't a soundness violation

The soundness checker validates the plan's *internal* consistency — no
phantom WOs, conservation holds at every edge and across the chain, no
over-consumption of supplies, identifiers are well-formed. It does not
validate whether the planner achieved every customer's wish.

Two outcomes are explicitly **acceptable** and not flagged:

- `committed_qty < requested_qty` (partial fulfillment) — the planner
  did its best with available supply. Surfaces via
  `committed_demands.shortage` and the `partial` commit_reason.
- `commit_time > request_due_time` (late delivery) — supply timing can
  force this. Surfaces via the demand row's `commit_time` for the user
  to act on.

Both are real signals worth reporting, but they belong in the planning
KPIs / shortage view, not the soundness verdict. R3 was added (`f3f35ee`)
and reverted on this principle — the late-delivery count was correct
information surfaced via the wrong channel.

UI: deep-check toggle hoisted to the run-history panel header (`86a5f97`)
so the user can opt into R8 before clicking "check soundness."

## Notable findings surfaced

**R0_pegging_duplicate** flagged 8 demands on run 386 with multiple
entries in `planning_pegging`. Investigation showed this was a checker
false positive — the leaf-legacy consolidator legitimately emits a
second `passthrough: true` entry per consuming demand for the WO-pegging
endpoint. Resolved on `fix/planner-soundness-followups` (`758ac52`):
filter passthrough/consolidated entries before grouping. R0 still fires
on TRUE duplicates (two canonical-tagged trees per demand_id), which
would indicate a real engine bug.

## Held as future work

### Variant-override conformance (sub-item of (7))

R9 covers `method_selection` overrides. `variant_selection` overrides
force a specific `alt_group`, but the WO doesn't directly carry alt_group
— the check would need to look up each child's alt_group via BOM at
walk time and assert that the children all come from the forced group.

**Lower priority**: variant-override is currently disabled on the UI,
so the planner doesn't receive these from users in normal operation.
Worth implementing if and when that toggle is re-enabled.
