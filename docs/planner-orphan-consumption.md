# Orphan inventory consumption — discovery, fix, and new R7d check

## Status

Fixed on `fix/under-consumption`. Planner side (commit `7aa8013`) restores
inventory + budget on the AND-bottleneck blocked path. Checker side
(commit `2cac3b2`) adds R7d as a named rule that catches the pattern in
legacy runs. Regression coverage: `PlanningEngineOrphanConsumptionTest`
(commit `58667b3`) — 3 cases including a multi-level subtree restore.

## Discovery

Surfaced during a 2026-04-30 conversation about cross-demand soundness
checks while validating the planning agent. The user noticed that the
checker only enforces an *upper* bound (Σ leaf consumption ≤ supply.qty)
and asked whether under-consumption — where a planner records leaf
consumption that doesn't actually flow up to a parent's emitted output —
is also possible.

Tracing through `planMethodSlot`'s AND-bottleneck branch confirmed: yes,
and the engine actively creates the pattern. The first pass mutates
the global `inventory` list at every BOM depth; if a sibling AND-required
child returns `effectiveQty=0`, `computeRawAchievable` returns 0,
`capped` floors to 0, and the function returns from the *blocked* branch
without restoring inventory or budget. The non-blocked second-pass
branch a few lines below does restore — symmetrical, but only one half
was wired.

## Reproduction (pre-fix)

Minimal config from `PlanningEngineOrphanConsumptionTest`:

- FG made via BOM_FG with two AND-required children: A (alt_group=null)
  and B (alt_group=null).
- A has supply qty=100, B has supply qty=0.
- Plan a demand for 100 of FG.

Pre-fix observation:

- FG committed: 0 (B is the bottleneck).
- A's supply post-plan: **qty=0** (orphan — A's first-pass take of 100
  persisted in the live state).
- The placeholder WO under FG's pegging had A's first-pass tree attached,
  showing 100 consumed of supply A even though FG emitted 0.

Subsequent demands for the same A see depleted inventory and starve.
The orphan steals from later demands.

## Root cause

[`planMethodSlot`](../backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt) at PlanningEngine.kt:988-1014 — the
*blocked* branch of the bottleneck handler. Snapshots `inventorySnap` and
`budgetSnap` are taken at function entry (lines 944-945). The non-blocked
second-pass branch (lines 1024-1031) restores from those snapshots
before re-planning at scaled qty. The blocked branch *also* needs the
restore but didn't have it.

Why this matters at all depths: `inventory` is a single global list
passed by reference through every recursive `plan()` call. The first
pass at the *outer* `planMethodSlot` triggers recursion that descends
the entire BOM, mutating `inventory` at every level — raw materials,
intermediates, deeper sub-makes' second-pass commits. A snapshot
captured at the outer level captures all of it; restoring at the outer
level reverts all of it. Conversely, *not* restoring at the outer level
strands all of it.

## Fix (planner)

Symmetrical 4-line restore plus a small pegging change:

```kotlin
if (capped <= 1e-9) {
    inventory.clear()
    inventory.addAll(inventorySnap)
    if (budget != null && budgetSnap != null) {
        budget.clear()
        budget.putAll(budgetSnap)
    }
    // Build placeholder WO with NO failed-child pegging — those trees
    // showed first-pass takes that no longer exist post-restore.
    val blockedWoNode = buildWoNode(..., emptyList<Map<String, Any?>>(), ...)
    return MethodSlotResult(achievableQty = 0.0, ..., blockedReason = reason)
}
```

Two coupled changes:

1. **Restore inventory + budget**. Symmetric to the second-pass branch.
2. **Drop the failed-child pegging**. Once inventory is restored, the
   first-pass child trees no longer reflect reality — keeping them
   would re-create the per-demand R4 violation we just spent the
   restore eliminating. The bottleneck product@location is preserved
   in `method_choice_explanation` ("blocked: deep child X@Y has no
   supply"), so debug context isn't lost.

The restore is **subtree-wide by construction**: a single restore at
the outer level reverts mutations at every depth below it, since
`inventory` is one global list. The fix auto-applies at any recursion
depth where `planMethodSlot`'s blocked branch fires (since it's shared
code). The multi-level test case in
`PlanningEngineOrphanConsumptionTest` proves this concretely with a
2-level BOM (FG → A → RM): RM's deep raw material is restored even
though A's sub-make committed it during *its own* second pass before
the outer FG slot blocked.

## Fix (checker) — new rule R7d

`SoundnessChecker.kt` adds **R7d_orphan_leaf_under_blocked_wo**:

> For any `work_order` node with `quantity ≤ ε`, the sum of
> `supply` + `purchase` leaf qty in the WO's subtree must also be `≤ ε`.

Catches the orphan pattern by name. Structurally implied by R4 (qty
propagation), but R4's message is generic ("child qty mismatch") — R7d
names the pattern so operators see "orphan inventory consumption" and
know to look at planner conservation, not pegging-tree validity.

Runs on every check. No deep-check flag — the walk is O(tree size).

The walker stops descending into nested non-zero WOs (those have their
own R7d frame and own R4 propagation check; descending would
double-count). It *does* descend into nested zero-qty WOs — orphans can
chain along a zero-qty path.

## Verification

Backend unit tests:

```
cd backend-kotlin
./gradlew test --tests "*OrphanConsumption*"  # 3 regression cases
./gradlew test --tests "*SoundnessChecker*"    # R7d case + existing R-rules
./gradlew test                                  # full suite, 269 passing
```

End-to-end smoke (post-merge):

```
make build
docker compose --env-file .env.dev -f docker-compose.yml -f docker-compose.dev.yml up -d --no-deps allocator-backend
```

Re-run case-171 with `max=1` and `max=2` baselines and compare against
the existing plan_runs 419 (max=1, fill 13.37%) and 420 (max=2, fill
15.20%):

- If pre-fix runs were stranding inventory in orphan paths, post-fix
  runs should commit *more* (higher fill_rate_pct, higher
  total_committed) — raw materials previously orphaned now flow to
  subsequent demands.
- If equal, no orphan ever fired on case-171's BOM. Fix is still
  correct, just not exercised by this case.

DB sanity — re-check legacy runs against R7d:

```sql
-- Legacy pre-fix runs may light up with R7d violations.
SELECT id, soundness_status,
       jsonb_path_query_array(soundness_report::jsonb,
         '$.demands[*].violations[?(@.rule=="R7d_orphan_leaf_under_blocked_wo")]')
FROM plan_run
WHERE case_id = 171
  AND soundness_status IN ('sound', 'unsound');
```

Re-running the soundness check on those rows after this branch lands
will populate R7d in the report where the planner left orphans. Going
forward, new runs from the post-fix planner should never produce
R7d violations.

## What's not addressed in this branch

- **R8 (deep conservation)** still runs only when `deep_check=true`.
  We made deep-check the default, so going forward this isn't a gap;
  legacy runs without deep-check on are now retroactively covered by
  R7d for the specific orphan pattern (other R8-only violations remain
  uncaught).
- **OR-relation second-pass branch**: out of scope. OR variants don't
  run a uniform second pass and don't have the same snapshot-restore
  shape; their first-pass takes are designed to persist as independent
  partial attempts. No bug there.
- **Recommended config changes for the agent**: deferred. The agent's
  knowledge primer (`agent-knowledge.md`) gets a one-line failure-mode
  entry pointing at R7d so it can cite the rule when explaining
  starved demands; deeper exposition lives in this doc.
