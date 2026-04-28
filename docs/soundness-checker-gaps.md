# Soundness checker — outstanding gaps

Inventory of gaps in `SoundnessChecker.kt` after the case-171 conservation
work landed (commits `ea07f53`, `7bd2eb7`). Surveyed by reading the
checker source, not speculation.

## In scope on this branch (`feat/soundness-checker-completeness`)

### (1) R8 deep conservation — declared but never implemented

The class doc and `SoundnessConfig.deepCheck` flag promise:

> R8 conservation (deep check) — committed_qty at root = Σ over leaves
> Skipped unless `SoundnessConfig.deepCheck = true`.

The flag is plumbed into `SoundnessReport.deepCheck` but **no R8 rule
exists in the file**. Setting `deepCheck=true` does nothing today.

What it should do: walk each demand's tree leaf-to-root accumulating
`qty × rate` along BOM edges, and assert the root's `committed_qty`
equals the resulting total at the demand's product unit. Catches
planner bugs that break leaf-to-root conservation in a way the per-WO
R4 check would miss (e.g. mid-tree rate-conversion drift, missing
intermediate WOs).

### (2) `committed_demands` ↔ `planning_pegging` cross-check

The two are produced by separate code paths in `runPlanning`. For each
demand, `committedRow.quantity` (in `committed_demands`) should match
the pegging tree root's `committed_qty` (in `planning_pegging`).
Today they could diverge silently — e.g. a demand reports
`committed=5` in `committed_demands` but the pegging shows root
`committed_qty=3`. Add a cross-demand rule (e.g.
`R0_committed_consistency`) that compares them within `config.tolerance`.

### (4) `walkPurchase` is a no-op

```kotlin
private fun walkPurchase(node: Map<String, Any?>, path: String) {
    // Purchase leaves are terminal — no per-row validation needed beyond
    // structural shape. Quantity is whatever plan() committed; pricing /
    // vendor selection is out of scope for soundness.
    @Suppress("UNUSED_VARIABLE")
    val pid = node["product_id"]?.toString()
}
```

A purchase leaf with `qty=-5`, missing `product_id`, or no
`location_id` would pass. At minimum: assert qty > 0 and pid/lid
present, mirroring the structural part of `walkSupply`. (Pricing
and vendor are out of scope, fine.)

## Out of scope on this branch — preserve as future work

### (3) R3 (timing) at the demand root isn't checked

R5_lead_time / R5_transit_time validate make/move WO timing, but no
rule asserts `commit_time ≤ request_due_time` at the demand root. A
demand fulfilled after its due date currently passes soundness. Would
be an `R3_demand_due_time` rule.

### (5) R7c tolerance is `1e-6` (the global config tolerance)

May be tight under FP drift. Σ 6 demands × 0.6667 fractional shares
can land at 4.0000001 vs WO qty=4. Want a small relative slack like
`max(1e-6, 1e-9 × produced)`. Not biting on case 171 yet but fragile.

### (6) Tree-selection fragility in `treeByDemand`

Does "last wins" on `planning_pegging` with the comment "matches the
frontend's lookup logic." If `consolidatedPegging` ordering ever
changes — e.g. a future engine emits per-demand trees first, then
consolidated — the checker would silently switch which tree it walks.
No explicit assertion that the picked tree is the canonical one.

### (7) Override conformance is unchecked

When `overrideIndex` configures `method_selection` or
`variant_selection` for a demand, the resulting WO should reflect
that choice. Today it's possible (in principle) for the planner to
ignore the override and still pass soundness. Would need to thread
override state into the checker and validate WO methods/variants
against it.
