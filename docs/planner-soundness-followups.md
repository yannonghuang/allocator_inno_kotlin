# Planner-side bugs surfaced by the soundness checker

The soundness completeness work (`feat/soundness-checker-completeness`,
merged into main as `a09df09`) added rules that immediately caught real
planner bugs the prior checker silently allowed. Those bugs are planner
fixes, not checker work — tracked here for follow-up on this branch
(`fix/planner-soundness-followups`).

## (1) Leaf-engine "duplicate" pegging entries — RESOLVED (checker fix)

**Initial finding**: `R0_pegging_duplicate` flagged 8 demands on
case 171 plan_run 386 (leaf-legacy) with multiple `planning_pegging`
entries.

**Root cause** (after investigation): not a planner bug. The
consolidator at [ConsolidationEngine.kt:606-620](../backend-kotlin/src/main/kotlin/com/allocator/services/ConsolidationEngine.kt#L606-L620)
intentionally emits a second entry per consuming-demand tagged with
`passthrough: true`, holding the supply-allocation pegging at a
deeper (pid, lid) level. The WO-pegging endpoint and frontend rely
on these to attribute consumed supplies to specific demands. They
are not alternate views of the demand's canonical tree.

The two entries differ as expected:
- `passthrough: true` entry: rooted at the *deeper supply* (e.g.
  `260-0385@2000`), `quantity` = consumed qty, has
  `per_demand_allocations`.
- canonical entry: rooted at the *demand's product* (e.g.
  `F37__888@VIRTUAL`), `quantity` = full demand qty, no extra tags.

Verified: every demand with a passthrough entry also has a canonical
entry, so filtering passthrough/consolidated entries is safe.

**Fix**: in `SoundnessChecker.checkRunSoundness`, skip entries with
`passthrough == true` or `consolidated == true` when grouping
`peggingByDemand`. R0_pegging_duplicate then fires only on TRUE
duplicates — two or more canonical-tagged entries for the same
demand_id, which would indicate a real engine emission bug.

**Verification**: case 171 plan_run 386 now `overall_sound: true`
(was: false with 8 R0_pegging_duplicate). Run 385 unchanged.

## (Hold) Variant-override conformance

R9 covers `method_selection`. `variant_selection` is structurally more
involved — the WO doesn't carry alt_group directly, so the check would
need BOM lookup at each child to infer which variant a WO used. Not a
*planner* bug; a *checker* gap. Park here in case it shifts in scope.
