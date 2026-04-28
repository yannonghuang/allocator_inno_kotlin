# Planner-side bugs surfaced by the soundness checker

The soundness completeness work (`feat/soundness-checker-completeness`,
merged into main as `a09df09`) added rules that immediately caught real
planner bugs the prior checker silently allowed. Those bugs are planner
fixes, not checker work — tracked here for follow-up on this branch
(`fix/planner-soundness-followups`).

## (1) Leaf-engine duplicate pegging entries

**Detected by**: `R0_pegging_duplicate` (cross-demand violation).

**Reproduction**: case 171, plan_run 386, `engine: "leaf-legacy"`.

```bash
curl -s -X POST http://localhost:8000/cases/171/plan-runs/386/check-soundness \
  -H 'Content-Type: application/json' -d '{}' \
  | jq '.cross_demand_violations | map(select(.rule == "R0_pegging_duplicate"))'
```

8 demands have multiple entries in `planning_pegging`; checker has to
fall back to "last wins" to pick a canonical tree:

| demand_id | n entries |
|---|---|
| `Negative_Inventory_280-1049-29_2000_-1991` | 2 |
| `Negative_Inventory_280-1049-31_2000_-2829` | 2 |
| `Negative_Inventory_280-1049-33_2000_-1703` | 2 |
| `Negative_Inventory_280-1312-27_2000_-7291` | 2 |
| `Negative_Inventory_280-1312-29_2000_-8609` | 2 |
| `Negative_Inventory_280-1312-33_2000_-7123` | 2 |
| `888_F37_2024_07_VIRTUAL` | 2 |
| `818_F28_2024_07_VIRTUAL` | 2 |

The supply engine (`run 385`, `engine: "supply"`) is clean — 0
duplicates. The bug is leaf-legacy-specific.

**Likely cause**: the consolidator path
([ConsolidationEngine.kt](../backend-kotlin/src/main/kotlin/com/allocator/services/ConsolidationEngine.kt))
emits `consolidatedPegging` entries with `demand_id=null` (intended) but
also has another path that emits per-demand entries that overlap with
the per-demand pegging from `legacyCommit`. Or: the same demand's tree
is appended twice during the iteration loop in `runV2Iterated`.

**Investigation steps**:

1. Identify what `planning_pegging` entries each duplicate demand has —
   are they identical, or do they differ in `committed_qty` / tree
   structure?
2. Trace which code path appends each. Likely candidates:
   - `runV2Iterated`'s outer loop appending per-iteration trees.
   - `runConsolidation` emitting both null-demand and per-demand entries.
   - `legacyCommit` re-emitting trees that the consolidator already
     queued.
3. Pick which one is the canonical tree (the latest, the one with
   `committed_qty > 0`?), suppress the duplicate, and verify
   `R0_pegging_duplicate` count drops to 0.

**Why it matters**: today the checker's "last wins" tree selection
mostly hides the issue, but the discarded tree could carry a different
verdict — different supply leaves, different WO qtys — that's silently
ignored. A user inspecting the pegging tree in the UI sees only one of
the two.

The 6 `Negative_Inventory_*` placeholders are planner-injected synthetic
demands (for negative starting inventory). The 2 real demands
(`888_F37_2024_07`, `818_F28_2024_07`) are user-facing — those are the
priority targets.

## (Hold) Variant-override conformance

R9 covers `method_selection`. `variant_selection` is structurally more
involved — the WO doesn't carry alt_group directly, so the check would
need BOM lookup at each child to infer which variant a WO used. Not a
*planner* bug; a *checker* gap. Park here in case it shifts in scope.
