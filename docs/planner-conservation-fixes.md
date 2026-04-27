# Planner conservation fixes — progress + outstanding work

## Context

Soundness check on case 171 plan_run 374 (supply engine, fair allocation,
elaborate methods, multi-variant) surfaced 4 R4_qty_propagation
violations + 6 R7b cross-demand over-consumption. All real planner
inconsistencies, not checker false positives.

## Root cause

The single-method bottleneck path in `plan()` uses `shortage_tolerance`
to absorb tiny rounding errors as "no bottleneck":

```kotlin
val capped = if (demandNetQty - rawAchievable < shortageTolerance(demandNetQty))
                 demandNetQty
             else rawAchievable.coerceIn(0.0, demandNetQty)
```

Default `shortageTolerance(qty) = max(absolute=1.0, relative=0.01 * qty)`.

Two failure modes:

1. **100% absorption** — for `demandNetQty < 1.0` (e.g., 0.985), the
   absolute floor of 1.0 absorbs ANY shortage, including 100%. The
   parent commits full demand qty while children deliver nothing.
   Surfaced by `international_others_F35_2024_08_VIRTUAL` for the make
   WO at 502-2178@1000 with rawAchievable=0.

2. **50% absorption** — for `demandNetQty = 1.0` and `rawAchievable=0.5`,
   the difference (0.5) is less than the absolute floor (1.0) — the
   tolerance treats a 50% bottleneck as noise. Surfaced by
   `20018741_20`/`20018860_10` at make WO 502-2588@1000 where child
   320-0284 delivers 2 of needed 4.

## Fixes shipped

**Commit `9e53e16` — conservation guard for 100% case.**

```kotlin
val capped = if (rawAchievable > 1e-9 &&
                 demandNetQty - rawAchievable < shortageTolerance(demandNetQty))
                 demandNetQty
             else rawAchievable.coerceIn(0.0, demandNetQty)
```

When `rawAchievable ≈ 0`, never absorb. A nothing-achievable case is
never noise. Applied identically in single-method (~line 1300) and
multi-method (~line 1077) paths.

Result on case 171: R4 violations dropped from 4 to 2.

**Commit `0a8944c` — cap tolerance to 50% of qty.**

```kotlin
fun shortageTolerance(qty: Double) =
    maxOf(shortageRel * qty, minOf(shortageAbs, qty * 0.5))
```

The absolute floor never exceeds 50% of qty. For small demands
(qty < 2 × abs), the cap dominates so > 50% shortage always triggers
the bottleneck path. For large demands, the absolute floor still
absorbs noise as before.

Expected to drop the remaining R4 violations from 50%-absorption
cases.

## Outstanding

After both fixes, run 377 still shows the same 4 R4 violations. The
tolerance check arithmetic shouldn't allow the 50%-short case through
(`demandNetQty(1) - rawAchievable(0.5) = 0.5; tolerance(1) = 0.5; 0.5 <
0.5 is FALSE`), yet the persisted tree shows the parent WO emitted at
full qty.

**Hypotheses to investigate**:

1. The persisted tree comes from a different code path (multi-method
   line 1077, or one of the variant-handling paths) that doesn't share
   the single-method bottleneck logic.
2. `effectiveQty` for the short child is computed differently than
   expected — e.g., includes a "partial" row that contributes when it
   shouldn't.
3. A second-pass replan under a different rate convention re-emits the
   tree with stale first-pass quantities.

**How to investigate**: add per-call instrumentation to the bottleneck
check in BOTH single-method and multi-method paths, dump
`(demandId, productId, lid, demandNetQty, rawAchievable, capped)` for
every invocation that would have committed at full qty when
`anyChildShort=true`. Re-run case 171 and grep for `502-2588@1000`
specifically.

## Cross-demand R7b

Six R7b violations remain (5 small fair-split rounding + 1 50%-over on
`500-6496_1000_4`). The 50%-over case is a separate bug in the
plan-run save path or supply-allocation accounting; investigate via:

```sql
SELECT demand_id, qty_consumed
FROM plan_supply_allocation
WHERE plan_run_id = <id> AND supply_id = '500-6496_1000_4'
ORDER BY qty_consumed DESC;
```

This is independent of the R4 work.
