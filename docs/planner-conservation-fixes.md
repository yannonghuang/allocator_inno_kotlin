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

### Root cause: integer rounding eats the bottleneck cap

The bottleneck arithmetic IS firing correctly with the 50% cap fix —
`capped` lands at `0.5` exactly. The problem is what happens next.

`buildWorkOrders` emits each lot as `roundQty(lotQty)` where
`roundQty(x) = Math.round(x).toDouble()`. Java's `Math.round(0.5)` is
**1** (ties round to +∞). So:

```
rawAchievable = effectiveQty(2) * demandNetQty(1) / neededQty(4) = 0.5
capped        = 0.5             (correct — bottleneck branch fired)
WO quantity   = roundQty(0.5) = 1   ← conservation violation here
```

The same rounding is applied at three sites:

- `buildWorkOrders` line 1449 (the actual WO row in the ledger)
- `buildWoNode` line 1485 (the pegging-tree WO node)
- `committedRow` lines 862-863 (the demand fulfillment row)

So a 50% bottleneck `capped=0.5` is invisibly upgraded to `qty=1` in
all three places. The R4 checker then sees parent_qty=1 with
child cqty=2 and rate=4 → 2 ≠ 1×4, violation reported.

Verified with a JVM round-trip: `Math.round(0.5)=1`,
`Math.round(0.49999999)=0`. Confirmed `2.0 * 1.0 / 4.0 = 0.5` exactly in
IEEE-754 (no float drift), so the rounding is what flips it.

### Instrumentation shipped (not committed)

`log.info("[R4-DEBUG single] ...")` at the single-method bottleneck
(line ~1320) and `[R4-DEBUG multi]` at the multi-method bottleneck
(line ~1085). Each line dumps demandId / pid@lid / demandNetQty /
rawAchievable / capped / **roundedWoQty** / per-child (need, eff).
Remove these logs once the fix lands.

### Empirical findings — case 171 / plan_run 378

Re-ran with the instrumentation. 1734 bottleneck firings on
`502-2588@1000`, **all from the single-method path** (zero
multi-method hits — `useMultipleMethods` is false here). The
violations split into two distinct mechanisms, both real:

**Mechanism A — `Math.round` rounds the bottleneck cap up.** The
bottleneck branch fires correctly, `capped` lands at a fractional
value, but `roundQty(capped)` upgrades it. Sample:

```
demand=20018654_10  pid=502-2588@1000  demandNet=29.95  rawAch=0.5
                    capped=0.5  roundedWoQty=1.0
   children: ... 320-0284@1000(need=119.81, eff=2.0) ...
```

Tolerance check arithmetic: `demandNet(29.95) - rawAch(0.5) = 29.45;
shortageTolerance(29.95) = 1.0; 29.45 < 1.0` is FALSE → bottleneck
branch fires → `capped=0.5`. Then `Math.round(0.5)=1`. WO emits at
qty=1, child 320-0284 delivered 2 — checker computes
`parent(1) × rate(4) = 4 ≠ 2`.

**Mechanism B — close-enough collapse swallows real shortage.** When
`demandNetQty` is fractional (a typical proportional consolidation
share), the close-enough collapse can absorb a 20–30% real shortage
because the absolute gap stays under tolerance. Sample:

```
demand=20018425_10  pid=502-2588@1000  demandNet=1.870  rawAch=1.5
                    capped=1.870  roundedWoQty=2.0
   children: ... 320-0284@1000(need=7.48, eff=6.0) ...
```

`demandNet(1.87) - rawAch(1.5) = 0.37; shortageTolerance(1.87) =
max(0.0187, min(1.0, 0.935)) = 0.935; 0.37 < 0.935` is TRUE → snap up
to `demandNet=1.870` → `Math.round(1.87)=2`. But the child only
delivered 6 of needed 7.48 (a 19.7% real shortage). Checker computes
`parent(2) × rate(4) = 8 ≠ 6`.

Mechanism B is the dominant pattern across the failing demands: the
absolute floor of 1.0 (and the 50% relative cap) is wide enough to
swallow ~20–30% relative shortages whenever `demandNetQty < 2`, which
is exactly the regime that fractional consolidation shares produce.

The 50% cap from commit `0a8944c` does *not* close mechanism B — it
only handles the boundary case where the gap equals the tolerance.

### Proposed fix

Replace the absolute/relative shortage tolerance with a tight
float-noise tolerance (so close-enough collapse still absorbs
genuine FP drift like `0.999999... vs 1.0`), AND floor in the partial
branch so integer rounding can't reverse the cap:

```kotlin
// single-method, line ~1317
val capped = if (rawAchievable >= demandNetQty - 1e-6) demandNetQty
             else floor(rawAchievable).coerceIn(0.0, demandNetQty)
```

```kotlin
// multi-method, line ~1082
val capped = if (rawAchievable >= methodQty - 1e-6) methodQty
             else floor(rawAchievable).coerceIn(0.0, methodQty)
```

Optionally drop `shortageTolerance` / `shortageRel` / `shortageAbs`
config plumbing — the `1e-6` is a hard-coded float-noise epsilon and
isn't a tuning knob anyone should reach for. (Keep the config keys
for one release if any caller sets them, but make them no-ops with a
deprecation log.)

Why floor: the bottleneck cap is the largest parent qty whose
BOM-implied child requirements are all ≤ what each child actually
committed (`P × child_rate ≤ child_actual` for every child).
`Math.round(0.5)=1` reverses that guarantee (`1×4=4 > 2`); `floor(0.5)
=0` preserves it (`0×4=0 ≤ 2`). For larger raw values like 1.5,
`floor=1` still satisfies conservation since children delivered
`1.5×rate ≥ 1×rate`.

Why drop the absolute/relative tolerance: it was implicitly absorbing
"real but small" shortages as noise. Float noise after the few
multiplies in the `effectiveQty * demandNetQty / neededQty`
arithmetic is < 1e-9 in practice; the 1.0-absolute floor was always
papering over a conservation hole. Lot quantization at child leaves
*does* exist but it's already absorbed by the soundness checker's own
R4 tolerance (`max(1.0, 10% of expected)`, per
`SoundnessChecker.kt`).

Effect on the failing case 171 WOs:

- 502-2588 mechanism-A demands (e.g. 20018654_10, 20018656_120,
  20018741_20): `rawAch ∈ {0.25, 0.5, 0.75}` → `floor=0` → existing
  `capped <= 1e-9` blocked-WO branch fires → qty=0 with
  `child_failed:320-0284@1000`. Conservation holds.
- 502-2588 mechanism-B demands (e.g. 20018425_10, 20018425_20,
  20018427_10): `rawAch=1.5, demandNet≈1.87` → `floor=1` → emits qty=1
  WO. Children deliver `1.5×rate`, parent uses `1×rate`. Conservation
  holds.
- 502-2178 (international_others demand): `rawAch=0` already → already
  hits the 100%-absorption guard from commit `9e53e16`, unchanged.

**Tradeoffs to flag**:

1. Demands that previously reported "qty=1 fully fulfilled, no shortage
   flag" because of a 20% absorbed shortage will now report
   "qty=floor partial". The actual production plan didn't change; the
   pegging tree's parent qty just stops over-stating itself. Frontend
   /UX consumers reading `commit_reason="partial"` may see more
   demands flagged partial than before — but each one is a *real*
   partial that was previously hidden.
2. A bottleneck `rawAch=0.6` (no FP-noise snap, `floor=0`) now emits
   qty=0 instead of qty=1 with a shortage flag. The demand reports
   100% failed instead of 100% over-committed. This is the correct
   conservation behavior; the existing blocked-WO branch already
   handles this format for full-zero bottlenecks, so the schema is
   unchanged — just the trigger threshold (was `rawAch≈0`; now
   `rawAch < 1`).

### Verification — final run 385

| run | sound | unsound | rule breakdown |
|---|---|---|---|
| 378 (50% cap only)  | 204/208 | 4 R4 + 6 R7b | 3 × R4_propagation (502-2588, 502-2515), 1 × R4_move_conservation, 6 × R7b |
| 380 (floor only) | 203/208 | 5 R4 + 6 R7b | 2 × R4_propagation OR (intl_others), 3 × R4_move_conservation, 6 × R7b |
| 382 (+ OR sum) | 205/208 | 3 R4 + 6 R7b | 3 × R4_move_conservation, 6 × R7b |
| 384 (+ move clamp) | 208/208 | 0 R4 + 6 R7b | 6 × R7b cross-demand overconsumption |
| **385 (+ supply leaf unround)** | **208/208** | **0** | **overall_sound: true** |

Backend test suite: 243 tests, 0 failures across all four fixes.

### Verification curl recipe

```bash
JOB=$(curl -s -X POST http://localhost:8000/cases/171/plan \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --argjson cfg "$(cat /tmp/run370_config.json)" \
        '{async: true, config: $cfg}')" | jq -r .job_id)

until [[ "$(curl -s http://localhost:8000/cases/171/plan/status/$JOB \
            | jq -r .status)" != "running" ]]; do sleep 5; done
RUN=$(curl -s http://localhost:8000/cases/171/plan/status/$JOB | jq -r .plan_run_id)

curl -s -X POST http://localhost:8000/cases/171/plan-runs/$RUN/save \
  -H 'Content-Type: application/json' -d '{"mode":"new"}'

curl -s -X POST http://localhost:8000/cases/171/plan-runs/$RUN/check-soundness \
  -H 'Content-Type: application/json' -d '{}' \
  | jq '{overall_sound, sound_count, demand_count, cross: (.cross_demand_violations | length)}'
```

## Fix 2: OR-relation Σ=0 regression (additive vs min)

The bottleneck cap was `min over children of (eff/need × demand)`,
which is correct for AND (every child required) but wrong for OR
(multi-variant alternatives). For OR, each variant contributes
*additively* — `Σ over variants of variant_capacity` — matching the
soundness checker's R4 OR rule.

Two issues were tangled in the OR case:

1. The arithmetic itself: `min` over OR variants caps the parent at
   the worst variant's capacity (e.g. one variant delivers 67, two
   deliver 2 → parent capped at 2). Wrong; should be sum.
2. The second pass: a uniform `scale = capped/demand` rescale
   re-plans every variant at the same fraction, which is incoherent
   for OR (succeeding variants get shrunk to match failing ones).

Fix: a small helper `computeRawAchievable` that branches on
`woChildrenRelation`. AND uses the classic min-bottleneck. OR uses
`Σ (min(eff,need)/need × demand/N)` and the per-method/per-demand
flow skips the second pass — first-pass per-variant commits already
represent independent attempts.

Applied at both [PlanningEngine.kt:1336](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L1336)
(single-method) and the multi-method slot bottleneck.

Result on case 171 plan_run 382: the two `intl_others_F35_2024_07/08`
OR violations resolved.

## Fix 3: move-conservation off-by-one

Move WOs failed R4_qty_conservation_move (e.g. parent=18,
child_committed=17). Root cause: the bottleneck path's first-pass
`effectiveQty` is computed from rounded `s["quantity"]` values
returned by the recursive child plan(). For an inner take of 17.6
units against a budget cap, the recursive plan() returns a row with
`quantity=roundQty(17.6)=18`, so first pass thinks `effective=18`.

`capped = floor(18) = 18`. Second pass re-plans the source-side
child at `qty=18`, which actually takes 17.6 from the (restored)
inventory + budget; the demand pegging node rounds that to
`committed_qty=roundQty(17.6)=18`, BUT the residual 0.4 falls into
the deeper make-method path that fails, which forces the demand node
to use `committedQty = taken (=17.6)` (rounding to 17) for the
blocked-WO branch. The move WO retains qty=18, child shows 17 — gap
of 1 unit.

Fix: after the second pass, for move methods, clamp
`achievableParentQty` to the source-side child's actual
`committed_qty`. Move WOs always have exactly one source-side child,
and conservation requires `parent == child_committed`, so the clamp
is principled:

```kotlin
if (m["type"] == "move") {
    val childCommit = childPeggingNodes.firstOrNull()?.let {
        (it["committed_qty"] as? Number)?.toDouble()
    }
    if (childCommit != null && childCommit < achievableParentQty - 1e-6) {
        achievableParentQty = floor(childCommit).coerceAtLeast(0.0)
    }
}
```

Applied in both [single-method](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L1418)
and multi-method paths.

Result on case 171 plan_run 384: all three R4_qty_conservation_move
violations resolved → 208/208 demands sound.

## Fix 4: R7b supply over-consumption (fractional fair-split)

Six R7b cross-demand violations remained, with `500-6496_1000_4` the
worst at 50% over (4 supply, 6 reported consumed).

Root cause: the supply allocator splits a supply fairly across N
candidate demands. For 4 units / 6 demands, each gets a 0.667 budget
cap. Each demand's `consumeFromInventory` correctly takes 0.667 (the
budget cap), but the resulting supply leaf in the pegging tree was
emitted with `quantity = roundQty(0.667) = 1`. R7b sums the rounded
leaves: `6 × 1 = 6 > supply.qty=4`. The other 5 violations are the
same pattern at smaller scale (≤2-unit overruns from 0.5/0.7/etc.
fractional shares).

Fix: don't round the supply leaf qty. Keep the unrounded
`bucket.qty` from `consumeFromInventory`. Both
`extractSupplyAllocations` and the R7b checker sum these qtys, so
accuracy matters more than display niceness.

```kotlin
// PlanningEngine.kt:947-955
peggingChildren.add(mapOf(
    "type" to "supply", ...
    "quantity" to bucket.qty,   // was: roundQty(bucket.qty)
    ...
))
```

Result on case 171 plan_run 385: 0 R7b violations → **overall_sound: true**.

## Steps taken

1. ✓ Instrumented both bottleneck paths, captured smoking gun on case 171.
2. ✓ Fix 1: floor + 1e-6 tight-noise tolerance in single-method and multi-method bottleneck branches.
3. ✓ Removed dead `shortageTolerance` config plumbing (no longer referenced).
4. ✓ Fix 2: OR-relation additive sum + skip-second-pass via `computeRawAchievable` helper.
5. ✓ Fix 3: move-conservation clamp — parent_qty := floor(child.committed_qty).
6. ✓ Fix 4: stop rounding supply leaf quantity in the pegging tree.
7. ✓ Stripped instrumentation logs.
8. ✓ Backend test suite passes (243 tests).
9. ✓ Final case-171 plan_run 385: **overall_sound: true** (208/208 demands, 0 cross-demand).
