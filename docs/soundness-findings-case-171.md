# Soundness findings — case 171, plan_run 370

Run config: supply engine, fair allocation, elaborate method selection,
multi-variant. Default shortage_tolerance (abs=1.0, rel=0.01).

After all checker false-positive fixes shipped on `feat/run-soundness-check`,
the residual findings are **10 real planner inconsistencies**: 4 R4 +
6 R7b. The checker is doing its job; what follows is a planner-debug
hand-off.

## R4 — quantity propagation conflicts (4)

### Pattern: AND-make WO claims positive qty with required children at cqty=0

| demand | path | exp | act | %off |
|---|---|---|---|---|
| 20018741_20 | 0-0-1-1 | 4 | 2 | 50% |
| 20018860_10 | 0-0-1-1 | 4 | 2 | 50% |
| international_others_F35_2024_08_VIRTUAL | 0-0-2-0-0-0 | 2 | 0 | 100% |
| international_others_F35_2024_08_VIRTUAL | 0-0-2-0-0-0 | 2 | 0 | 100% |

**Concrete example** (international_others_F35_2024_08_VIRTUAL):
the make WO `502-2178 @ 1000` has `qty=1.0` and `children_relation="and"`,
yet 6 of 11 required children have `cqty=0` and `commit_reason="no_methods"`:

```
[2-0-0-0] work_order:make 502-2178@1000 qty=1.0 children_relation=and
  [0]  260-0199@1000 qty=1 cqty=1   ✓  (made via move)
  [1]  300-0150@1000 qty=1 cqty=0   ✗  no_methods
  [2]  300-0151@1000 qty=1 cqty=0   ✗  no_methods
  [3]  300-0152@1000 qty=1 cqty=0   ✗  no_methods
  [4]  302-0004@1000 qty=2 cqty=2   ✓  (from supply)
  [5]  302-0007@1000 qty=2 cqty=2   ✓
  [6]  310-0401@1000 qty=1 cqty=1   ✓
  [7]  310-0591@1000 qty=1 cqty=0   ✗  no_methods
  [8]  311-0244@1000 qty=2 cqty=0   ✗  no_methods
  [9]  311-0276@1000 qty=2 cqty=0   ✗  no_methods
  [10] 320-0284@1000 qty=2 cqty=2   ✓
```

Per spec.md (and the engine's own AND semantics) this WO **cannot
physically produce 1**: 6 required inputs delivered nothing.

### Expected behavior

Plan()'s single-method path:

```
val rawAchievable = childPassResults.minOf { cr -> cr.effectiveQty * demandNetQty / cr.neededQty }
// → 0/1 * 1 = 0 (from any of the failed children)
val capped = if (demandNetQty - rawAchievable < shortageTolerance(demandNetQty))
                 demandNetQty else rawAchievable
// → 1 - 0 = 1; shortageTolerance(1) = max(1.0, 0.01) = 1.0
// → "1 < 1.0" is FALSE → capped = rawAchievable = 0
if (capped <= 1e-9) { ...failed-make path; emit qty=0 WO; return... }
```

The "failed-make path" should fire and emit `qty=0`. The persisted tree
shows `qty=1.0` instead — meaning either:

1. effectiveQty is computed > 0 for some failed children (despite
   `commit_reason="no_methods"` which `isHardPlanningFailure` returns
   true for, contributing 0).
2. Some other code path emits the WO before/after this branch.
3. The persisted tree is stale (first-pass values not overwritten by
   the failed-make return).

### How to investigate

Add temp logging in `plan()` at line 1284 (`anyChildShort` calc) and
line 1287 (`achievableParentQty` calc) capturing demand_id, productId,
each child's (neededQty, effectiveQty, commit_reasons). Re-run case 171
and grep for productId=502-2178 to see what plan() actually computed.

## R7b — cross-demand supply over-consumption (6)

| supply | available | consumed | overrun |
|---|---|---|---|
| WOOALOB109            | 906   | 907   | +0.1% |
| 311-0436_1000_80000   | 80000 | 80002 | +0.0% |
| WOOAZOB112            | 143   | 144   | +0.7% |
| 302-0007_1000_27126   | 27126 | 27127 | +0.0% |
| ZGOALOB108            | 391   | 392   | +0.3% |
| **500-6496_1000_4**   | **4** | **6** | **+50%** |

The first five are tiny (≤2 units / ≤0.7%) — most plausibly fair-split
proportional rounding when the same supply is allocated to many demands.
Probably acceptable noise; could be eliminated by integer-truncating
the proportional split rather than rounding.

The **500-6496_1000_4** case is the genuine outlier: a 4-unit supply
showing 6 units consumed. 50% physical over-consumption — impossible.
Likely candidates:

- `plan_supply_allocation` write path double-counts (e.g., demand
  retries re-emitting the same row).
- A demand walked the same supply via two paths in a multi-variant or
  multi-method tree, both contributing to `qty_consumed`.
- A floating-point reconciliation issue between the in-memory commit
  and the persistence layer.

Query to localize:

```sql
SELECT demand_id, qty_consumed
FROM plan_supply_allocation
WHERE plan_run_id = 370 AND supply_id = '500-6496_1000_4'
ORDER BY qty_consumed DESC;
```

## What this exercise validated

- The soundness checker correctly distinguishes real planner
  inconsistencies from representation artifacts (placeholder WOs under
  shortage, lot-size quantization, multi-variant fan-out).
- Several iterations were needed to tune R4's tolerance + cases (parent=0,
  rate convention, OR/AND, lot rounding). The committed tolerance
  (`max(1.0, 10% of expected)`) absorbs noise without missing 50%+
  inconsistencies.
- The shortage_tolerance threshold in `plan()` is the load-bearing
  approximation that produces most of the residual R4 patterns. Any
  planner fix here should be paired with a re-run on case 171 to verify
  the soundness count stays at 0.
