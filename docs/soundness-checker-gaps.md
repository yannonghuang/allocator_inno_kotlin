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
| 3 | demand-root timing unchecked | `R3_demand_late` | `f3f35ee` |
| 5 | R7b/R7c hard-coded `1e-6` tolerance | (tolerance hardened) | `508af46` |
| 6 | tree-selection "last wins" silent fallback | `R0_pegging_duplicate` | `508af46` |
| 7 | method override conformance unchecked | `R9_method_override_violated` | `20619f8` |

UI: deep-check toggle hoisted to the run-history panel header (`86a5f97`)
so the user can opt into R8 before clicking "check soundness."

## Notable findings surfaced

These are *real* issues that the new rules caught the moment they ran on
case 171, separate from the conservation work:

- **R3** flagged 13 late deliveries on run 385 (supply engine) and 10 on
  run 386 (leaf-legacy) — demands committed after their `request_due_time`.
  No prior rule asserted the temporal contract.
- **R0_pegging_duplicate** flagged 8 demands on run 386 with multiple
  entries in `planning_pegging` — 6 synthetic `Negative_Inventory_*`
  placeholders + 2 user demands. Indicates a leaf-engine emission bug;
  the supply engine is clean. Tracked as planner work.

## Held as future work

### Variant-override conformance (sub-item of (7))

R9 covers `method_selection` overrides. `variant_selection` overrides
force a specific `alt_group`, but the WO doesn't directly carry alt_group
— the check would need to look up each child's alt_group via BOM at
walk time and assert that the children all come from the forced group.
Not done.

### Planner bug filed by R0_pegging_duplicate

The leaf-engine duplicate-pegging emission is a planner-side fix, not a
checker fix. The checker now surfaces it; the next planner-debug session
should investigate the consolidator path that emits both per-demand and
synthetic entries under the same demand_id.
