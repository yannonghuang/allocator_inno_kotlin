# WO consolidation, traceability, and the Phase‑3 commitment aggregate

Architecture/algorithm decisions made on branch `feat/constraints`. Three
related areas: customer‑specific BOM constraints, cross‑demand work‑order
consolidation that stays traceable, and the bottom‑up commitment aggregate
that makes the plan conservation‑sound by construction.

---

## 1. Customer‑specific BOM‑alternative constraints

**Goal.** Pin which BOM alternative a *customer's* demand resolves to:
`config.constraints: [{customer, parent, location, child}]` (location ``/`*` =
any). Same config lifecycle as `purchasable_materials` (persists in
`plan_run.config`, round‑trips through UI + copilot).

**Decision — enforce at BOTH representation levels.** Case 173 has two
distinct ways a parent's alternatives are modelled, so the constraint is
applied in two places in `plan()` / `planMethodSlot`:

- **Multi‑method** (351 parents): each alternative child is a *separate make
  method* (distinct `bom_id`). Filtered at the **method** level in `plan()`
  via `makeMethodProducesChild()` before the override filter — drop make
  methods whose `bom_id` doesn't yield the constrained child; keep non‑make;
  fall back to all (with a warn) if none qualify.
- **alt_group** (127 parents): alternatives grouped by `alt_group` under one
  `bom_id`, chosen by `getPreferredVariants`. Filtered at the **variant**
  level in `planMethodSlot`'s make branch.

**Decision — precedence:** an explicit saved per‑WO `variant/method override`
wins; otherwise the constraint applies (the override is the more specific
manual decision).

**Load‑bearing fix.** Sub‑component constraints require the originating
demand's `customer_id` to flow down the BOM. Child `cDemand`s now carry
`customer_id`/`customer`, so a constraint on a parent that is itself a child
of the finished good matches.

---

## 2. Cross‑demand WO consolidation — and keeping it traceable

`consolidate_wos` (default ON when consolidation is enabled) merges work
orders sharing `(product, location, method, source, window)` into fewer,
larger orders — e.g. one PO per raw material instead of one per sub‑assembly
per demand. The hard part is not the merge; it's not losing the per‑demand
pegging (predecessor/successor, drill‑down, soundness).

**Decision — keep the per‑demand pegging intact; consolidate only the
work‑order *list*.** We do NOT mutate the pegging into a shared‑node DAG. The
per‑demand trees stay exactly as planned (so soundness, which walks them, and
drill‑down both keep working); the consolidated WO is a *summary* over them.

**Eligibility — identical sub‑pegging.** Two WOs may merge only when their
downstream pegging is structurally identical. `subtreeSignature()` computes a
canonical recipe/variant/source hash (ignoring quantities/timings, skipping
failed/0‑qty‑make subtrees), and it's part of the consolidation group key. So
two makes of the same product that chose *different* variants do not merge.

**Merged order fields** (`consolidateWorkOrdersByTiming`):
- quantity = sum; **start = min(start), end = max(end)** (the full span);
- `consolidation_split_details = [{demand_id, allocated_qty}]` per
  constituent demand — this is what restores **predecessor/successor**: the
  frontend's `woRowDemandIds` reads it, and the ↓/↑ relation graph is built
  from the intact per‑demand pegging, so a batch's edges are the **union**
  across its demands, for free.
- `consolidated_demand_ids` + `wo_window_start/end` — for the pegging
  endpoint to resolve a batch back to its per‑demand nodes.

**Drill‑down resolution** (`GET …/work-order-pegging`). A batched WO
(`demand_id=null`) forwards its `consolidated_demand_ids` + window; the
endpoint aggregates the matching per‑demand WO nodes (failed‑skipped,
window‑bounded) into one synthetic node whose quantity equals the batch and
whose children are the per‑demand contexts. `findAllWoNodes` skips
`failed=true` subtrees so phantom (rolled‑back) purchases never inflate it.

**Gotcha.** The API enrichment maps internal `consolidation_split_details` →
output `wo_consolidation_split_details`; emit the *internal* key from the
engine or the enrichment overwrites it.

---

## 3. The Phase‑3 commitment aggregate (the core algorithm)

### Mental model (quantity pass)

1. **Top‑down request decomposition** — from the demand, BOM‑explode and
   propagate quantity+timing *requests* down to raw materials.
2. **Inventory consolidation** — allocate shared inventory among competing
   demands per policy.
3. **Bottom‑up commitment aggregate** — by reverse BOM explosion, a parent
   commits the **least‑supplied child** (`min over children of
   child_commit / bom_rate`) for quantity and the **latest** child for
   timing. *Only commitment counts.*

Recursive form:

```
Plan(demand) -> supply:
    achievable_quantity = request_quantity
    achievable_time     = request_time - lead_time
    for (child, bom_rate) in BOM(parent):
        child = Plan(demand(request_quantity*bom_rate, request_time-lead_time, child))
        achievable_quantity = min(achievable_quantity, child.commit_quantity / bom_rate)
        achievable_time      = max(achievable_time,     child.commit_time)
    return supply(achievable_quantity, achievable_time)
```

### Why a post‑plan pass at all

`plan()` does this top‑down and commits **greedily**. Cross‑demand inventory
contention can later zero a child a parent already committed against, leaving
a make committed **above** what its children actually supply — the
R4/R8 conservation breaks. A make's plan‑time view can't catch this: the
child was valid when the make planned it.

So the **commitment is re‑derived as a genuine bottom‑up aggregate over the
final pegging** — phase 3 done explicitly, not frozen during the top‑down
recursion.

### `reconcile(node, target)` — design

Ask the parent's `target` down, let each subtree report what it can actually
supply, take the AND‑min up ("least dominates"), and **re‑trim** the
over‑supplied siblings to that figure so the whole subtree is
conservation‑consistent: `make.quantity = committed = min(child / rate)`.

Key decisions:

- **Trim‑only, single‑pass.** It only reduces existing committed flows (no
  inventory re‑planning), so it's a no‑op for an already‑consistent tree and
  cannot oscillate. *An earlier re‑cap **loop** version fed back on rounding
  slack and crawled (2632→2630→2600…); rejected.*
- **Re‑trim by proportional `scaleSubtree`, not a second `reconcile`.** A
  second reconcile per child is O(2^depth); proportional scaling is
  O(subtree).
- **`quantity == committed_qty` invariant on internal nodes.** Otherwise the
  bottleneck child (scaled by factor 1) keeps a stale request `quantity >
  committed` and fails the per‑node check. The root's original request is
  restored by the caller for requested‑vs‑committed display.
- **EXACT arithmetic — no `roundQty`.** Rounding to the nearest integer
  rounded a leaf consuming a fractional supply (564.595) *up past it*
  (R7a/R7b), and rounded a parent and child *separately* so they disagreed by
  1 (R4_move). The pegging already holds fractional qtys; keep them exact.
- **No slack band.** A `if rawSupply < want−0.5` band left `parent = want`
  while the child committed `want−ε`. With exact single‑pass math a
  consistent tree returns `rawSupply == want` anyway, so no band is needed.
- **A move is 1:1.** Ask the source for `want` (rate 1), not
  `want × (source.quantity/curQty)` — the stored `source.quantity` can drift
  ≈1 above `curQty` upstream, and a rate ≠ 1 then never trims the
  over‑supplying source.

### Hook placement

Run reconcile on `finalTimings.peggingTrees` — the **final** trees, *after*
the timing fix. Hooking earlier (on `allPegging`, before `fixTimingFromPegging`)
is worse: the timing fix rebuilds trees from the unreconciled work orders and
feeds on inconsistent input → *more* violations. The work‑order list and
`planning_pegging` both flatten from the reconciled trees.

### `committed_demands` consistency (R0)

`committed_demands.quantity` must equal the pegging root's `committed_qty`
(R0_committed_consistency). Sync it **exactly** to the reconciled root (no
`roundQty`, any delta). Two details to match the checker
(`SoundnessChecker` ~240‑312):

- It compares `committedQtyById` (sum of **non‑hard‑failure** rows) to
  `treeByDemand = peggingByDemand.mapValues { it.last() }` — the **last** tree
  per demand. So the sync **overwrites** with the last entry's committed (a
  demand can have several trees, e.g. 818_F28 = 64 + 599.334, and R0 only
  checks the last); single non‑failure row → set exactly, multi‑row → scale.
- Hard‑failure rows (`no_methods` / `child_failed:` / `cycle_stopped` /
  `depth_limit`) are skipped and R0 is bypassed when `tableQty` is null — so
  `Negative_Inventory_*` pseudo‑demands are not R0‑checked.

### Plan‑time companion (still useful)

`planMethodSlot` also blocks a make outright when a re‑planned AND child
materially under‑delivers (off its authoritative `committed_qty`, > max(0.5,
2%)). This is the plan‑time application of "least dominates": blocking the
unbuildable make frees the waterfall to find a working method, so it actually
*improves* fill (e.g. 20018812_40: 23→39) instead of emitting phantom output.
The Phase‑3 aggregate is the catch‑all for what plan‑time can't see.

---

## Result (case 173, consolidation on, max_methods=2)

All deep‑check soundness rules clean — `R0 = R4 = R4_move = R7d = R7a = R7b =
R8 = 0` (208/208 demands sound). Fill ≈ 98.65%; the small drop from earlier
runs is the honest trim of phantom over‑commit slivers. 337 backend tests
green; ~150 s runtime.

See also: [planner-conservation-fixes.md](planner-conservation-fixes.md),
[soundness-checker-gaps.md](soundness-checker-gaps.md),
[supply-level-consolidation.md](supply-level-consolidation.md).
