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

## 3. Mental model — three phases, strictly separated

```
┌─ PLAN (once, outside the iteration loop) ─────────────────────────┐
│  Walk every demand's BOM symbolically with union-alt. For each    │
│  (demand, supply-bearing-node) pair, record:                      │
│    needsMatrix[D][S] = D.requestedQty × cumulativeRate(D → S)     │
│    summed over alt branches when a demand has multiple paths.     │
│                                                                   │
│  No inventory awareness. No work-order emission. No allocation.   │
│  Pure need collection.                                            │
└───────────────────────────────────────────────────────────────────┘
              │ matrix is fixed; only caps refine across iterations
              ▼
┌─ ITERATE ─────────────────────────────────────────────────────────┐
│                                                                   │
│  Phase 2 (ALLOCATE) — one allocation per supply:                  │
│    For each column S of the matrix:                               │
│      iter 1:  denom[D][S] = needsMatrix[D][S]                     │
│      iter k:  denom[D][S] = min(prev allocation, last actual draw)│
│      allocation[D][S] = applyPolicy(denom[*][S], supply[S].qty)   │
│                                                                   │
│    The same fair / proportional / priority_first machinery as     │
│    today, applied uniformly at supply scope.                      │
│                                                                   │
│  Phase 3 (COMMIT) — per-demand BOM walk:                          │
│    For each demand D:                                             │
│      Walk D's BOM. At every supply-bearing node S, draw up to     │
│      allocation[D][S]. Recurse for produced components.           │
│      Emit committed rows + pegging tree.                          │
│                                                                   │
│    For each produced component C with non-zero production needed: │
│      Emit one consolidated WO sized at Σ_D actual draws via C.    │
│                                                                   │
│  Converged iff actual draws == allocations for every (D, S).      │
│  Else feed actual draws into iter k+1's caps and loop.            │
└───────────────────────────────────────────────────────────────────┘
```

The architecture preserves the existing 3-phase shape (plan → consolidate → commit) but sharpens what each phase does. Each phase has one input domain and one output domain. No conflations.

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

## 5. Iteration

Three sources of refinement on iter 2+:

| Source | iter 2 effect |
|---|---|
| **Alt-divergence** | Demand allocated supply on alt path A but committed via A' → unused allocation on A's supplies. iter 2 caps that to actualDraw=0; supplies redistributed to other competitors |
| **Symbolic over-estimate** | `needsMatrix` ignored intermediate inventory; demand's actual draw at deep supply is less than allocated. iter 2 caps to actual draw |
| **Production cascade** | Iter 1 allocations at raw materials assumed cumulative-rate from symbolic need; iter 1's actual `toMake[C]` is smaller (intermediate inventory absorbed). iter 2 reconciles |

Convergence:

```
allocation_k[D][S] = min(allocation_{k-1}[D][S], actualDraw_{k-1}[D][S])
                     ── monotone clamp ──
```

Same `min(prev, current)` rule we proved out for the leaf-level cap loop. Each `(D, S)` cap is a non-increasing sequence bounded below by 0 → converges by the monotone-decreasing-bounded-sequence theorem.

Crucially, the **only** rebound source today (cross-group RM contention not governed by policy) is gone by construction. Expected iteration count drops dramatically — likely 2–3 iters typical, with 5–8 reserved for pathological alt-fanout cases. The current `MAX_PLANNING_ITERATIONS = 15` is comfortable headroom.

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

## 10. Implementation phases

| Phase | Deliverable | Files | LOC est | Risk |
|---|---|---|---|---|
| **A** | `needsMatrix` builder (pure function over `ResolutionGraph` + BOM) | `services/SupplyDemandMatrix.kt` + tests | 300 | Low — symbolic computation, well-tested |
| **B** | Per-supply allocator (adapts existing `splitFair`/`splitProportional`/`splitPriorityFirst` to the new input shape) | `services/SupplyAllocator.kt` + tests | 200 | Low |
| **C** | Per-demand commit with supply-level caps | `services/PlanningEngine.kt` modifications | 150 | Low — `budgetCap` plumbing exists |
| **D** | Produced-component WO synthesizer | `services/PlanningEngine.kt` modifications | 400 | Medium — sizing toMake[C] across demands is subtle |
| **E** | `runV2Supply` orchestrator (replaces `runV2Iterated` when `engine="supply"`) | `services/PlanningEngine.kt` modifications | 250 | Medium |
| **F** | Frontend: chip + slide-in show single supply-level allocation | `frontend/lib/api.ts`, `_CaseSectionPage.tsx` | 200 | Low |
| **G** | Migration / config flag / soak test | Various | n/a | Medium |

Total: ~1300 LOC backend + ~200 frontend. Estimated 2 weeks of focused work + 1 week soak.

## 11. Open questions

### Q1: How aggressive is matrix sparsity?

For a case with ~2,000 demands and ~10,000 supplies (case 162-scale), a dense matrix is `2,000 × 10,000 = 20M cells`. Most cells will be zero (most supplies aren't reachable from most demands). Need a sparse representation: `Map<demandId, Map<supplyKey, qty>>` is the obvious starting shape — same as the current `allocation` field on `ConsolidationResult`.

Phase 2 transposes implicitly when iterating columns, which on a sparse map requires an inverted index `Map<supplyKey, Map<demandId, qty>>`. Build both at Phase 1 end; pay the O(N) memory once.

### Q2: Initial allocation when iter 1's matrix is over-estimated

Iter 1's `needsMatrix` is symbolic — typically much larger than realisable need. For an under-supplied raw material, this means iter 1 distributes the supply across many demands that won't actually use it, leaving real consumers under-allocated.

Two responses:
- **Accept the over-distribution and let iter 2 redistribute via the monotone clamp.** Simple, cheap. Probably the right default.
- **Pre-deflate iter 1's matrix using inventory at intermediate levels.** Requires running a quick BOM-walk simulation per demand (with shared inventory accounting), which has its own ordering problem. More accurate iter 1, more code.

I'd start with the first option and measure. If iter count exceeds ~5 on representative cases, consider deflation.

### Q3: WO emission for a produced component drawn by demands with different alt-branches below

A produced component `C` may be a node on multiple alt paths. Each demand reaches `C` via its chosen alt (in commit), and `C`'s sub-tree below differs per alt. The single consolidated WO at `C` produces qty = `Σ_D actualDraw[D][C_synthetic]`. The WO's own BOM walk consumes raw materials within their supply allocations.

If `C`'s sub-BOMs differ structurally per alt, the consolidated WO's BOM is taken from the union of sub-trees — same as the union-alt logic at the resolution-graph level, applied recursively at production. Consumes raw materials at union-alt-determined leaves; iter 2+ reconciles unused alt branches.

This is the trickiest piece. Worth a separate spike before committing to the implementation.

### Q4: How do supply overrides interact?

Today's `supply_split` override lets a user manually allocate qty across demands at a given supply. Under supply-level allocation this is exactly what Phase 2 already does — overrides become explicit override entries that bypass `applyPolicy` for a specific supply. Cleaner mapping than today's "override the consolidated WO's split". Should simplify the override UI.

## 12. Next steps

1. **Discuss this draft.** Iterate on the design before writing code. Open questions in §11 are the focal points.
2. **Spike Q3 (WO emission across alt-branches)** in isolation — write a small fixture and walk through it manually. The cleanliness of the rest of the design depends on this not being a hidden complexity.
3. **Implement Phase A (matrix builder).** Land it on `feat/supply-level-consolidation` along with unit tests. Reviewable in isolation.
4. **Implement Phase B (allocator).** Reuses existing policy code; should be small.
5. **Implement Phases C–E.** The orchestrator is the pivot point — once `runV2Supply` works on a single-demand fixture, layer up complexity.
6. **Frontend (Phase F).** Should be a simplification, not an expansion.
7. **Validate (Phase G).** Side-by-side on case 162 + representative cases. If the supply engine is clean and the leaf engine is the one with patches, the migration writes itself.
