# Supply-level consolidation — design

**Status:** Draft. Replaces the leaf-level consolidation engine (v2) with a supply-level allocator. Behind a feature flag during migration.

**Branch:** `feat/supply-level-consolidation`

**Author:** designed in collaboration, 2026-04-26.

---

## 1. Problem

The current planner (v2) consolidates demands at the **merged-leaf** level — the deepest produced component a demand symbolically reaches before hitting an inventory-bearing node. Demands at the same merged-leaf are bucketed into a `MergedGroup`, and the allocation policy (`fair` / `proportional` / `priority_first`) splits the group's production output across its members.

This works fine within a single group. It breaks when a supply is shared across **multiple** merged-leaves — typical for any deep raw material that participates in multiple BOM paths. Concretely, in case 162:

```
Supply 300-0355@1000  qty=19,831

  ├── F28 multi-demand group  (merged-leaf = 300-0355@1000)
  │   13 demands, fair-policy split, 3,038 produced
  │
  └── 28 other demands  (merged-leaves at various FG products)
      Their plan() walks descend through their FGs to consume
      300-0355 directly from real inventory. No allocation
      policy applies; ordering is FIFO via Phase 2 iteration order.

  Total consumed: 9,430. Two distinct competition mechanisms
  layered on the same supply:
    1. Within F28 group: explicit fair-policy split
    2. F28 group vs 28 others, and the 28 among themselves: implicit FIFO
```

Symptoms of this two-layer competition:

| Symptom | Diagnosis |
|---|---|
| Bistable convergence (case 162: 268k → 28k → 243k → 34k → 246k over 5 iters) | Cross-group RM contention not governed by policy — caps for one group free RM that another group then over-allocates against |
| `13 vs 41` UI inconsistency | The chip reports the multi-demand group's candidate count; the slide-in reports total consumers including passthroughs and main-loop |
| `splitInfo` semantics that need patching for every new edge case (passthrough vs main-loop vs multi-demand) | Pegging entries fragmented across three flag combinations because the model has three different ways a demand can consume a supply |
| `Phase 1 picks one alt deterministically` (now union-alt) | Picking the wrong alt was a way to lose consolidation entirely — symptom of consolidation being keyed at the wrong level |

The cap-loop iteration we already shipped (`fix(planning): monotone caps in v2 fixed-point to stop bistable rebound`) suppresses the worst symptoms but doesn't address the underlying mismatch. Each new edge case generates another patch.

## 2. Goal

Consolidate at the **supply** level. For every inventory-bearing node `(pid, lid)` — leaf, intermediate, or otherwise — every demand that transitively needs any quantity of it is in the same allocation pool, and the policy splits it across all of them uniformly.

Single layer of competition. Single allocation policy per supply. UI consistency by construction.

## 3. Mental model — three phases with compensation inside commit

```
┌─ PLAN (once, outside any iteration loop) ──────────────────────────┐
│  Walk every demand's BOM symbolically with union-alt. For each     │
│  (demand, supply-bearing-node) pair, record:                       │
│    needsMatrix[D][S] = D.requestedQty × cumulativeRate(D → S)      │
│    summed over alt branches when a demand has multiple paths.      │
│                                                                    │
│  No inventory awareness. No work-order emission. No allocation.    │
│  Pure need collection.                                             │
└────────────────────────────────────────────────────────────────────┘
              │  matrix is fixed; only allocations refine
              ▼
┌─ ALLOCATE (Phase 2) ───────────────────────────────────────────────┐
│  For each column S of needsMatrix:                                 │
│    allocation[D][S] = applyPolicy(needsMatrix[*][S], supply[S].qty)│
│                                                                    │
│  Same fair / proportional / priority_first machinery as today,     │
│  applied uniformly at supply scope. One pass per outer iteration.  │
└────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌─ COMMIT (Phase 3) — two sub-phases ────────────────────────────────┐
│                                                                    │
│  3a. Initial commit:                                               │
│      For each demand D:                                            │
│        walk D's BOM (with D's alt-pick rule)                       │
│        draw from each supply S up to allocation[D][S]              │
│      Emit committed rows + per-demand pegging tree.                │
│                                                                    │
│  3b. Compensation — runs after 3a stabilizes:                      │
│      For each supply S where actualDraw_total < allocation_total:  │
│        unused[S]  = allocation_total[S] − actualDraw_total[S]      │
│        candidates = demands at S whose actualDraw < allocation     │
│                     AND have unmet need at S after 3a              │
│        redistribute unused[S] across candidates by the policy      │
│        extend each candidate's draw at S accordingly               │
│                                                                    │
│      May iterate internally (each round only adds drawn qty,       │
│      bounded above by Σ allocation[*][S]; converges quickly).      │
│                                                                    │
│  After 3a + 3b: for each produced component C, emit one            │
│  consolidated WO sized to Σ_D actual qty drawn through C.          │
└────────────────────────────────────────────────────────────────────┘
              │
              ▼
   Outer iteration: only if 3b leaves residual at produced-component
   level that re-allocation could redistribute. Typical: 1 outer
   pass. Pathological: 2-3.
```

The architecture preserves the existing 3-phase shape (plan → consolidate → commit) but sharpens what each phase does. Each phase has one input domain and one output domain. No conflations.

**Crucially, commit (Phase 3) has two sub-phases:** an initial commit (each demand walks once and draws within its allocations), then a *compensation* sub-phase that redistributes any allocation a demand left unused — typically because that demand picked an alt path that bypassed the supply, or because intermediate inventory absorbed need before the supply was reached. Compensation runs after initial commit stabilizes, in a single pass (or a tiny finite inner loop). It allows competitors who were capped during initial commit to draw additional quantity from the freed-up capacity, by the same allocation policy.

This means the outer Phase 2 → Phase 3 iteration loop, which today is doing heavy lifting to chase cross-group equilibrium, becomes vestigial. Compensation handles within-iter rebalancing in one shot. Outer iteration is reserved for the rare case where compensation reveals that a *produced component's* WO needs to be re-sized, requiring a fresh Phase 2 allocation round at sub-supplies.

## 4. What changes in the model

### 4.1 Inventory at every level is "just a supply"

Today's code treats `merged-leaves` as special, because it's the level where allocation policy fires. Under supply-level consolidation, *every* inventory-bearing node — leaf, intermediate, or sub-assembly — is a column in `needsMatrix` and a participant in Phase 2 allocation.

Consequences:

- Phase 1's BOM walk no longer **stops** at the first inventory-bearing node. It keeps walking through the whole BOM, recording rate-converted needs at every node along the way (including past inventory-bearing nodes — they're allocated, not stoppers).
- The `merged-leaf` concept is gone. There is no preferred level.
- A demand's contribution to supply `S` doesn't depend on whether `S` is "deep" or "shallow"; just on the cumulative rate from `D` to `S`.

### 4.2 Allocation is a column-wise operation

Phase 2 walks the columns of `needsMatrix`. For each supply `S`:

```
totalNeed[S]        = Σ_D needsMatrix[D][S]   on iter 1
                    = Σ_D denom[D][S]         on iter k
allocation[*][S]    = applyPolicy(denom[*][S], supply[S].qty)
```

`applyPolicy` reuses the existing `splitFair` / `splitProportional` / `splitPriorityFirst` implementations unchanged — they already operate on a `Map<demandId, qty>` shape. The migration is the input plumbing, not the policy.

The result is a per-`(D, S)` cap matrix. Σ over `D` ≤ `supply[S].qty` always. Some `D` may be capped below their full need under scarcity; some may have full need allocated under abundance.

### 4.3 Commit uses supply-level caps

Phase 3 walks each demand `D`'s BOM. The existing `consumeFromInventory(..., budgetCap=)` mechanism already enforces a per-`(component, demand)` draw cap; we just feed it from the supply-level allocation map instead of the leaf-level budget map.

```kotlin
val cap = allocation[D]?.get(componentKey) ?: Double.POSITIVE_INFINITY
val consumed = consumeFromInventory(inventory, pid, lid, need, demandId, budgetCap = cap)
```

That's the entire commit-side plumbing change.

### 4.4 Work-order emission

For each produced (`make`-method) component `C`:

```
toMake[C] = Σ_D actualDraw[D][C_synthetic] − inventoryAt(C)
```

Where `C_synthetic` is the synthetic supply Phase 3 emits at `C` (still keyed the same way as today). The WO is a single consolidated work order for `toMake[C]`. The per-demand `consolidation_split_details` are derived from `actualDraw[*][C_synthetic]`.

The WO's own BOM consumes from raw materials, *also constrained by the per-supply allocation*. Recursion bottoms out when all consumed supplies are within their caps.

## 5. Iteration: compensation first, outer loop as fallback

The bulk of refinement happens **inside Phase 3** via the compensation sub-phase (3b in §3's diagram). Outer Phase 2/3 iteration becomes a fallback for cases compensation can't fix in place.

### 5.1 What compensation handles

After Phase 3a's initial commit, every supply `S` has a measured `actualDraw[D][S]` for each demand. If `actualDraw[D][S] < allocation[D][S]`, demand `D` left unused capacity at `S`. Reasons:

| Source of unused allocation | Why it happens |
|---|---|
| **Alt-divergence** | `D` was allocated on supplies along alt path A, but its commit walked alt path A' instead. Allocations on A's supplies sit untouched |
| **Intermediate-inventory absorption** | `D`'s upstream BOM nodes had inventory; the BOM walk shortcut before reaching deeper supplies. Those deeper allocations are unused |
| **Demand quantity less than modeled** | Rare, but possible for upstream rounding |
| **Hard failure upstream** | `D`'s walk hit an unsatisfiable raw-material need and stopped before reaching `S` |

Compensation 3b redistributes this unused capacity to *other* demands at `S` who were capped during 3a — using the same allocation policy. It can iterate internally (a tiny inner loop bounded by `Σ allocation[*][S]`) until no more capacity can be redistributed.

### 5.2 What compensation can't handle — outer iteration

One refinement source crosses the Phase 2 / Phase 3 boundary and so can't be resolved inside compensation: **production cascade**.

When demand `D` ends up consuming less of produced component `C` than allocation predicted (for any of the reasons above), `toMake[C]` shrinks. The WO at `C` was sized to a smaller production quantity, and the raw materials it consumes are correspondingly smaller. The raw-material allocations on iter 1 were sized for the predicted (larger) `toMake[C]`. Now those raw-material allocations have residual that compensation at the raw-material level *would* handle — but only if the WO at `C` was already correctly sized.

So if iter 1's `toMake[C]` was over-estimated, iter 2 needs:

1. Re-run Phase 2: re-allocate raw materials with the revised `toMake[C]` as the new total need at `C`'s sub-supplies.
2. Re-run Phase 3 (3a + 3b): re-commit with new caps; compensation redistributes within the new caps.

Convergence is by the same monotone clamp:

```
allocation_k[D][S] = min(allocation_{k-1}[D][S], actualDraw_{k-1}[D][S])
                     ── monotone clamp at outer-iter scope ──
```

Each `(D, S)` cap forms a non-increasing sequence bounded below by 0 → outer iteration converges in finitely many passes.

### 5.3 Expected iter counts

| Case | Outer iters | Compensation passes per outer iter |
|---|---|---|
| Simple BOM, no alts, no shared deep RM | 1 | 0 (or 1 trivial) |
| Diamond BOM, shared raw materials | 1 | 1–2 (handles alt-divergence + intermediate-inventory) |
| Multi-level production cascade with intermediate inventory | 2–3 | 1–2 |
| Pathological: deep BOM + heavy alt-fanout + tight RM | 5–8 | 2–4 |

Ceiling stays at `MAX_PLANNING_ITERATIONS = 15` for safety. In practice we expect dramatic reduction from today's typical 5–8 iters — most cases land in 1–2 outer passes because compensation handles within-iter rebalancing inline.

The **only** rebound source today (cross-group RM contention not governed by policy) is gone by construction.

## 6. What disappears

| Concept | Why it goes |
|---|---|
| `mergeGroups`, `MergedGroup`, `MergedMember`, `withMemberCaps` | No leaf-level grouping; allocation is per-supply, not per-group |
| `ConsolidationGroup`, `runConsolidation` | The synthetic-demand-per-group machinery is replaced by per-supply allocation + per-demand commit |
| `consolidated_<demandId>_<pid>` synthetic tagged buckets | No more pre-emission of consolidated supply at leaf level. Supply caps enforce sharing directly |
| Pegging entries with `consolidated: true` / `passthrough: true` flags | Every demand's pegging tree is structurally identical. The "kind of entry" distinction goes away |
| `splitInfo` per consolidated WO | Replaced with per-supply `allocation` map exposed on the supply view |
| Cross-group cap loop (`runV2Iterated`'s monotone clamps to suppress bistable rebound) | Cross-group competition no longer exists; remaining iteration is supply-level refinement (much simpler) |

## 7. What stays

| Concept | Role |
|---|---|
| `buildResolutionGraph` + union-alt | Now walks **all** nodes (no inventory shortcut), feeds `needsMatrix` |
| `BomAncestry`, `cumulativeRate` | Unchanged; used to compute matrix entries |
| `plan()` recursive walker with `budgetCap` | Reused in Phase 3 with supply-level caps |
| Allocation policy implementations | Same code, applied at supply level |
| Per-demand committed rows + pegging trees | Same shape; now sourced from supply-level allocations |
| Frontend supply view + slide-in | Simplified — `splitInfos` becomes a single per-supply allocation; chip and slide-in agree by construction |

## 8. Frontend implications

After migration:

- **Supply table chip:** `<peggedDemandCount>d shared · <consumedQty>/<initialQty> · <policy>` — accurate by construction (the chip's number IS the allocation column's denominator)
- **Supply explanation slide-in:** one allocation table, no Path column needed (every demand reached the supply through allocation; "direct" is no longer a meaningful category)
- **Per-demand pegging:** unchanged from the user's view; tree structure is the same
- **Override dialog:** still meaningful — manual allocation override at the supply level is exactly what the override dialog already lets you do

The current frontend's `splitInfos`, `demandPath`, multi-section CONSOLIDATION SPLIT plumbing all collapse to a single `allocation: Record<demandId, qty>` map per supply.

## 9. Migration plan

### Feature flag

```yaml
consolidation:
  engine: "supply"  # new
  # or "leaf-legacy" (current v2 behavior, kept for rollback)
```

`engine` defaults to `leaf-legacy` until the new engine has soaked.

### Side-by-side validation

| Scenario | Expected behavior |
|---|---|
| No consolidation (current `enabled: false`) | Both engines produce identical output — no consolidation involved |
| Single demand, no shared supplies | Identical — degenerate case for both engines |
| Multi-demand at one merged-leaf, no shared deep supplies | Outputs match within rounding; supply engine produces simpler pegging trees |
| Case 162 (the bistable scenario) | Supply engine: 1–2 iters, 0 residual, impacted=0. Leaf engine: 8 iters, 0 residual after monotone-cap fix |
| Diamond BOMs with shared raw materials | Supply engine: clean per-supply allocation. Leaf engine: convergence depends on cap loop |

### Acceptance criteria

1. All existing tests pass under both engines (with engine-specific assertions for cases where outputs differ structurally).
2. The case-162 reproducer asserts `iter ≤ 3` and `over-production = 0` under the supply engine.
3. New unit tests cover: matrix shape, per-supply allocation under each policy, alt-branch convergence, production cascade.
4. End-to-end on a sample case (162 + a few representative real cases): supply engine produces ≤ leaf engine's WO count and equal-or-better committed-qty totals.

### Cutover

Once side-by-side validation shows the supply engine is at least as good on a representative set of cases:

1. Flip default `engine` to `"supply"`.
2. Leaf engine kept reachable via flag for one release cycle.
3. After one release with no rollbacks, delete the leaf engine.

## 10. Implementation phases — **COMPLETE**

| Phase | Status | Commit | Notes |
|---|---|---|---|
| **A** `needsMatrix` builder | ✅ | `a9f4dc1` | 11 tests, ~300 LOC. Symbolic walker continues past inventory-bearing nodes (key difference from leaf engine's `walkResolution`). |
| **B** Per-supply allocator | ✅ | `898cf17` | 22 tests, ~200 LOC. Extracted policy primitive `allocate()` with parity to leaf engine's `splitFair`/`splitProportional`/`splitPriorityFirst`. |
| **C** Per-demand initial commit (3a) | ✅ | `e0f8265` | 6 tests, ~150 LOC. Reuses `plan()`'s existing `budget` parameter; computes `actualDraws` for the compensation pass. |
| **D** Compensation sub-phase (3b) | ✅ | `51b647b` | 8 tests, ~250 LOC. Pure analytical step — no `plan()` calls inside `compensate()`. |
| **E** WO synthesis | ✅ | `abb91d4` | 8 tests, ~300 LOC. Output shape matches leaf engine's `consolidated:true` exactly so frontend chip + slide-in work without changes. |
| **F** Orchestrator + feature flag | ✅ | `a7251b1` | 8 tests, ~400 LOC. `consolidation.engine: "supply"` activates the pipeline; default stays `"leaf-legacy"`. |
| **G** Frontend reads supply-level allocations | ✅ | `6afa27b` | New `supply_level_allocations` field threaded through; chip + Path column populate from it under the supply engine. |
| **H** Comparison harness | ✅ | `9353593` | 7 scenarios in `SupplyVsLeafEquivalenceTest`. Both engines produce equivalent committed qty + supply consumption on the patterns case 162 exercises. |
| **Bug fix** budget snapshot | ✅ | `6f13b53` | Discovered in Phase H: `plan()`'s two-pass make-flow snapshotted inventory but not budget; caused empty supply-leaf children under shortage at deep RM. ~30 LOC fix. |

Total: ~1450 LOC backend + ~200 frontend. **222 tests, 0 failures.** Branch: `feat/supply-level-consolidation`.

## 10a. Migration status

The supply engine is feature-complete and validated against the leaf engine on the synthetic equivalence harness. Default is unchanged (`leaf-legacy`). Migration steps remaining:

1. **Real-data validation on case 162**: toggle `consolidation.engine = "supply"` in the case config, run Analyze impact, verify the `supply iter` log lines show convergence in 1-3 iters with no `(max)` warning. Compare WO counts and committed-qty totals against the leaf engine.

2. **Soak period**: leave default at `leaf-legacy`. Opt-in callers (case-by-case basis, set via the planning config) exercise the new engine to surface production-shape edge cases. Recommend at least one full release cycle of soak.

3. **Default flip**: change `ConsolidationConfig.engine`'s default from `"leaf-legacy"` to `"supply"` once soak validation passes. Keep the leaf engine reachable via `engine: "leaf-legacy"` for one further release as rollback insurance.

4. **Leaf engine retirement**: after a full release with default flipped and no rollbacks, delete `runV2Iterated` and `mergeGroups` / `MergedGroup` infrastructure. Estimated removal: ~800 LOC.

Phase D (compensation) is the new central piece relative to today's planner. It's the place where alt-divergence and intermediate-inventory absorption are reconciled in-place, eliminating most of today's outer-iteration churn. Worth a careful test plan: synthetic fixtures covering each unused-allocation source (alt-divergence, intermediate absorption, hard failure upstream, demand-qty rounding) plus a combined diamond-BOM stress case.

## 11. Open questions

### Q1: How aggressive is matrix sparsity?

**Resolved.** Sparse representation: `needsMatrix: Map<demandId, Map<supplyKey, qty>>` (same shape as today's `allocation` field on `ConsolidationResult`). Phase 2 needs to iterate columns, so build an inverted index `byColumn: Map<supplyKey, Map<demandId, qty>>` once at Phase 1 end — O(N) memory paid once, O(1) column lookup forever.

For case-162-scale (~2,000 demands × ~10,000 supplies), most cells are zero (only the cells along each demand's BOM paths). Sparse maps stay cheap.

### Q2: Initial allocation when iter 1's matrix is over-estimated

**Resolved.** Compensation (Phase 3b) redistributes unused allocation in-place after each initial commit. Demands that pick alt paths or whose intermediate inventory absorbed need leave allocations on the table; compensation lets capped competitors draw the freed-up capacity by the same policy. No outer iteration needed for these cases — compensation handles them within the same pass.

Outer iteration is reserved for production-cascade refinement (when a produced component's `toMake[C]` shrinks relative to iter 1's prediction, requiring re-allocation at sub-supplies). See §5.

The earlier suggestion to "pre-deflate iter 1's matrix using inventory at intermediate levels" is moot under this design — Phase 1 stays purely symbolic (need collection, no inventory awareness), and the compensation sub-phase + outer monotone clamp drive convergence.

### Q3: WO emission for a produced component drawn by demands with different alt-branches below

**Resolved.** This question conflated two kinds of "alt" that operate at different scopes and don't interact:

| Scope | Source | Resolution |
|---|---|---|
| **Demand-side alts** | A demand's BOM has OR-branches near the top (e.g., `A → B \| B'`). Different demands may reach the same component via different branches | Phase 1 enumerates both via union-alt; Phase 3 picks one per demand at commit; iter 2+ caps the unpicked-alt allocations to zero via the monotone clamp |
| **Supply-side alts** | A produced component's own recipe has OR-branches in its sub-BOM (e.g., `C → X \| Y`) | Resolved entirely by C's own plan() invocation when the WO is synthesized — preference scoring, inventory check, cascade probe — same logic that already makes alt-picks for non-consolidated demands |

The principle: **supply-side manufacture is independent of which demands triggered production.** Demands contribute quantity to a produced component; the component's recipe (including alt sub-tree picks) is its own decision, driven by `plan()` and the operator-configured rules.

Concretely: WO synthesis at `C` is

```
plan(syntheticDemand{ product_id=C.pid, location_id=C.lid, quantity=totalNeed[C] }, ...)
```

`totalNeed[C] = Σ_D actualDraw[D][C_synthetic]` over the demands that walked through `C`. `plan()` chooses C's alt sub-tree based on its own logic (preferences, sub-supply availability) without reference to which demands contributed. The downstream raw materials it consumes are constrained by their own supply-level allocations.

No spike needed.

### Q4: How do supply overrides interact?

**Resolved.** With clean separation between demand (need formulation) and supply (manufacture / allocation), overrides are straightforward: a `supply_split` override is just a manual allocation at Phase 2 that bypasses `applyPolicy` for a specific supply. The override entry replaces the policy result for that one column of the matrix; everything else flows through unchanged.

Today's override has to navigate the consolidated WO's split-detail structure and the demand-tag tracking around synthetic buckets. Under supply-level allocation, the override directly addresses what the user wants to manipulate — the per-`(demand, supply)` cap. The override UI gets simpler: one allocation table per supply, one row per demand, qty fields the user can set freely (sum-validated against `supply.qty`).

The same UI primitive can be reused as the supply-level analogue of today's per-WO override.

## 12. Next steps

Design is settled — all four open questions in §11 resolved.

1. **Implement Phase A (matrix builder).** Land it on `feat/supply-level-consolidation` along with unit tests. Reviewable in isolation. ~300 LOC.
2. **Implement Phase B (allocator).** Reuses existing policy code; small.
3. **Implement Phases C–F.** Phase D (compensation) is the central new piece — most attention goes there. Phase F (orchestrator) is the pivot: once `runV2Supply` works on a single-demand fixture, layer up complexity.
4. **Frontend (Phase G).** Should be a simplification, not an expansion.
5. **Validate (Phase H).** Side-by-side on case 162 + representative cases under the feature flag. If the supply engine is clean and the leaf engine is the one with patches, the migration writes itself.
