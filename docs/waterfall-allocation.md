# Waterfall ("best-supply win") method allocation

## Status

Active. Replaces the proportional `split_mechanism` design (equal / score /
preference) that shipped briefly on `feat/method-selection-rectified` and was
reverted because of an exponential cost in BOM depth.

## Why this exists

`method_selection` originally had a binary `multiple` flag: `true` meant "use
all methods (equal split)", `false` meant "use the best one only." That
collapsed two questions — *how many methods can be used* and *how do we divide
demand across them* — into a single boolean.

The first attempt at rectifying this ("method-selection-rectified") split it
into two axes:

  - `max_methods: Int` — cap on how many methods one demand can use
  - `split_mechanism: enum` — how to divide demand across the top-N methods
    (`equal`, `score`, `preference`)

`split_mechanism` ran sub-tree simulations for each of the top-N methods at
every multi-candidate site at every BOM depth, branching factor `max_methods`
per level. For deep BOMs with many shared multi-method components (case-171:
~120 SUB_PCBA components with 2 candidate methods each), this is exponential
in BOM depth. Case-171 hung indefinitely at 0/208 demands under `max=2 +
supply consolidation` even after the recursion-narrowing fix at `7c5a5da` and
the `scoreVariant` config-threading fix in `feat/waterfall-allocation`. The
cost was inherent to the upfront-split algorithm, not a bug.

A different algorithm sidesteps the issue: **try the best-ranked method on
the full demand; whatever it can't fill, hand to the next; stop when the
demand is met or the cap is hit.** The user calls this "best supply win"; the
literature calls it waterfall allocation or sequential exhaustion. Slot 2
only fires when slot 1 hit capacity — there's no upfront combinatoric work,
and no recursion blow-up.

## Semantics

```
rank methods once at this call site:
    if mode == "preference":  sort by preference int (ascending)
    if mode == "elaborate":   sort by elaborate composite score (descending)

residual = demandNetQty
slots_used = 0
for m in ranked:
    if residual <= MIN_WATERFALL_RESIDUAL: break
    if slots_used >= max_methods: break
    slot = planMethodSlot(m, residual, ...)        # mutates inventory
    residual -= slot.committedQty                  # 0 if blocked
    accumulate slot.wos + slot.peggingNode
    slots_used += 1

emit demand commit_qty = demandNetQty - residual
```

### Locked-in design choices

  - **No re-ranking between iterations.** The order is frozen at the start
    of each demand. Re-ranking the residual against remaining methods is
    expensive (it'd require re-running the elaborate scorer per slot) and
    rarely changes the order in practice. May be added later as opt-in
    (`method_selection.rerank_residual: true`); not in v1.
  - **Inventory carries forward** between iterations. Slot N+1 sees slot N's
    actual consumption; no snapshot/restore between slots. This is correct:
    method A drew real supplies, method B should see reality. The
    snapshot/restore is internal to a single slot's first-pass / second-pass
    bottleneck logic.
  - **Hard-fail of slot N is non-fatal.** A method that committed 0 doesn't
    doom the demand — the loop continues to slot N+1. Matches the cascade
    fallback semantics that single-method users already rely on. The
    blocked WO placeholder (zero qty) is still emitted in pegging so the UI
    shows which method was attempted.
  - **`MIN_WATERFALL_RESIDUAL = 0.5`** (hard-coded in v1). Prevents lot-size
    or bottleneck rounding from leaving 0.x-unit residuals that burn a slot
    on a trivial second WO. Configurable later via
    `method_selection.min_residual` if real cases need it.

### `max_methods` semantics

  - `max_methods = 1` ≡ pre-waterfall single-method behavior: no fallback to
    a second method. The single-method path runs `getPreferredMethodCascade`
    or `getPreferredMethodElaborate` (depending on `mode`) and commits one
    WO. Cascade still does its feasibility-probe fallback (cf. cascade
    semantics below) — that's a separate mechanism layered inside the chosen
    method's selection, not a multi-WO emission.
  - `max_methods >= 2` enables waterfall: up to that many ranked methods may
    be tried in sequence.
  - `max_methods` is clamped against `methods.size` per call site, so a
    single-method demand with `max=4` simply runs one slot.

### Gating

Waterfall fires only when **all four** conditions hold:

  1. `methodCfg.maxMethods > 1`
  2. `shouldElaborateAtDepth(depth, methodCfg.depth)` — by default this means
     the root demand only (`depth = 1` in the form). Setting `depth = 2`
     would also enable waterfall one level down.
  3. `effectiveMethods.size > 1` — the post-override candidate set has more
     than one method.
  4. (Implicit) the call is reached via `plan()`'s normal flow, not the
     simulation path inside `scoreMethodsForElaborate` (which forces
     `max_methods=1` in its `simConfig` to avoid recursion blow-up).

This gating is the load-bearing piece. Below the gate, single-method
preference cascade only — no further waterfall expansion. Cost is
`max_methods × cost(plan one demand at depth−1)`, *not*
`max_methods^depth`.

## API

`MethodSelectionConfig`:

```kotlin
internal data class MethodSelectionConfig(
    val mode: String,        // "preference" | "elaborate"  — ranking source
    val depth: Int,          // ≥ 1                         — gate level
    val multiple: Boolean,                                  //   legacy parse only
    val maxMethods: Int,     // ≥ 1                         — waterfall cap
    val scoreWeights: Map<String, Any?>?,
    val maxBomDepth: Int = 3,  // 1..10                     — make-fallback admission cap
)
```

The config schema accepted on input (JSON):

```json
{
  "method_selection": {
    "mode": "preference",
    "depth": 1,
    "max_methods": 2,
    "max_bom_depth": 3,
    "elaborate": false,
    "score_weights": { "commit_time": 1.0, "inventory_consumed": 0.0, "purchase": 0.0 }
  }
}
```

Legacy keys still parsed but ignored on input: `multiple`, `split_mechanism`,
`depth_optimal`. The frontend strips them before re-submitting saved configs
(defense in depth). Saved `plan_run.config` rows are immutable audit data —
the backend never re-executes them, so historical legacy rows display
unchanged in the run-history UI; the
[`ConfigRetirementMigration`](../backend-kotlin/src/main/kotlin/com/allocator/services/ConfigRetirementMigration.kt)
scrubs `depth_optimal`/`scope` from saved configs at startup so KB
signatures stay comparable.

## Reactive fallback within a single method slot

The waterfall described above is the *outer* loop — slot N+1 fires only when
slot N hit capacity. There's also an *inner* fallback that fires reactively
when a single method blocks. It's distinct from the outer waterfall (which
operates on the demand's ranked method list); the inner fallback decides
what to do with **the chosen method's own structurally-equivalent
alternatives** when its primary attempt returns 0.

Two flavours of inner fallback are admitted at the single-method path
([`PlanningEngine.kt::plan()`](../backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt)
around line 2050):

### 1. Move-to-move fallback

When the primary method is a `move` and blocks, scan `effectiveMethods` for
*other* moves of the same product that differ only by `from_location_id`.
Tied-preference move sources are common (e.g. `move 1000→VIRTUAL` and
`move 2000→VIRTUAL` both ranked at preference 1). When the @1000 chain dies
downstream but the @2000 chain has feasible inventory, this admits the
@2000 source as a same-method fallback.

Cost is bounded — downstream BOM is identical for both moves; only the
starting location changes.

### 2. Make-fallback gated by `max_bom_depth`

When the primary method is non-make (typically a blocked `move` or
`purchase`), real `make` alternatives may be admitted as fallback —
provided the BOM under that make is structurally feasible to plan. Without
the gate this is dangerous: a make whose recipe recurses through 8 levels
of intermediates might collapse on a deep no-inventory subtree, burning
recursion cost for nothing.

The gate is the **`maxMakeDepth` feasibility cache**, computed lazily per
`(product_id, location_id)` and bounded by `method_selection.max_bom_depth`
(UI label "Max BOM depth", default 3, range 1–10):

  - `maxMakeDepth(pid, lid)` returns the minimum real-make-recursion depth
    needed to source `(pid, lid)` via *any* structurally-feasible path
    (moves traverse free, makes count). The recursion descends through
    candidate methods at each level and picks the cheapest.
  - A make alternative whose precomputed depth exceeds
    `methodCfg.maxBomDepth` is dropped from the fallback list — no plan()
    call, no inventory mutation, no waste.
  - Memoization: the cache is owned by the planning entry (one cache per
    `runV2Iterated` invocation), so per-demand walks share cost. Without
    memoization, a 200-demand case re-walks the same intermediate
    feasibility tree thousands of times.

Special-case: `VirtualProduct_*` targets are exempt from the cap — by data
construction they have no other method type than make, so the depth gate
would always block the only feasible source.

### Why the cap exists at all

Empirically 3 covers typical real BOMs (e.g. `260-0385.make →
280-1786.make → leaf supply`, depth 2) while leaving headroom. Bumping
higher trades recovery potential for recursion cost — most blocked makes
are blocked because of leaf-level supply gaps that no amount of recursion
will fix. Setting `max_bom_depth = 1` disables the make-fallback
entirely (no make-as-fallback admitted), useful for benchmarking the
"pure waterfall" baseline.

### Cached structural failure

A separate memo (`structuralFailedMakes`) records `(pid, lid)` pairs
whose make-fallback was *attempted* and hard-blocked on a structural
cascade (e.g. `no_methods` at every leaf). Future demands skip these
without re-attempting. The memo distinguishes structural failure from
capacity-driven failure (the latter is runtime-dependent and might
succeed for a different demand under different inventory pressure).

## Where to look in the code

| Responsibility | Location |
|---|---|
| `MethodSelectionConfig` definition | `backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt` (around line 41) |
| `parseMaxMethods` parser + DEFAULT_MAX_METHODS | same file (around line 100) |
| `parseMaxBomDepth` parser + `DEFAULT_MAX_BOM_DEPTH = 3` | same file (around line 50, 96) |
| `MIN_WATERFALL_RESIDUAL` constant | same file (around line 87) |
| `maxMakeDepth` feasibility helper | same file (around line 632) |
| `planMethodSlot` helper (extracted per-method body) | same file (around line 1145) |
| Waterfall loop + gating | `plan()` body, after `effectiveMethods` is computed |
| Single-method path + reactive move/make fallback | `plan()` body, after the waterfall block (around line 2050) |
| Form: max-methods + max-bom-depth inputs | `frontend/app/cases/[id]/_CaseSectionPage.tsx` |
| Outgoing config normalization | `frontend/lib/api.ts` `normalizeMethodSelection` |
| Persistence | `backend-kotlin/src/main/kotlin/com/allocator/api/Allocate.kt` (around line 1820) |
| Tests | `backend-kotlin/src/test/kotlin/com/allocator/WaterfallAllocationTest.kt`, `MakeFallbackTest.kt` |

## Trade-offs vs proportional split

  - **Conservation/fairness across methods**: waterfall lopsides production
    toward the best-ranked method. If the goal of multi-method is *supplier
    diversification* or *capacity smoothing across plants*, waterfall doesn't
    deliver that — the second method only fires when the first hit capacity.
    Proportional split was the right tool for that, but its cost was
    prohibitive on real BOMs. If diversification becomes a hard requirement,
    the right answer is probably a constraint *on the planner's input* (cap
    method A's capacity at X) rather than re-introducing proportional split.
  - **"Best" is fixed mid-iteration**: if method A's commit drops the
    elaborate score for the residual (e.g., method B becomes more
    attractive at smaller qty), waterfall doesn't notice — it uses the
    initial ranking. Re-ranking would catch this; v1 doesn't.
  - **Boundary instability**: a method's commit can fluctuate by a few units
    from lot-size rounding, flipping the residual from 0 to 0.5 and
    triggering method B for a trivial remainder. The `MIN_WATERFALL_RESIDUAL
    = 0.5` threshold absorbs this; raise via `method_selection.min_residual`
    if 0.5 isn't tight enough.

## Empirical findings (case-171, 2026-04-28)

The first end-to-end sweep on case-171 (208 demands, deep BOMs sharing
~120 SUB_PCBA components, supply-engine consolidation on, purchase
disabled) revealed several non-obvious patterns. Documenting here so
future tuning has a baseline.

| run | max | mode | fill % | Gini | median | starv % | on-time | WOs | wall |
|-----|-----|------|--------|------|--------|---------|---------|-----|------|
| 419 | 1   | pref     | 13.37 | 0.4445 | 0.675  | 20.19 | 197/208 | —   | ~4 min |
| 420 | 2   | pref     | **15.20** | **0.4165** | **0.7387** | **19.23** | 177 | 322 | ~2 min |
| 421 | 4   | pref     | 14.99 | 0.4272 | 0.7321 | 20.67 | 176 | 300 | ~3 min |
| 422 | 2   | elab     | 14.60 | 0.4931 | 0.467  | 22.60 | 181 | 340 | ~5.7 min |
| 423 | 4   | elab     | 15.12 | 0.4228 | 0.7279 | 20.67 | 178 | 356 | ~5.9 min |

Lessons:

  1. **`max=2, mode=preference` is the sweet spot for this case.** Best
     Gini and median, lowest starvation, fastest wall-time among
     waterfall configs. +13.7% fill vs `max=1` baseline.
  2. **Diminishing/inverted returns past `max=2` under preference.**
     Every fairness metric is slightly *worse* at `max=4` than `max=2`.
     Hypothesis: more methods per demand means slot 3/4 grabs lower-
     preference supplies that another demand might have used more
     efficiently. Per-demand local optimum, global pessimum.
  3. **Elaborate ranking is per-demand-greedy and can hurt shared-supply
     fairness.** At `max=2`, elab-ON has Gini **0.4931** vs preference's
     **0.4165** — a noticeable regression. Why: elaborate's composite
     score (commit_time / inventory / purchase) is local to each demand;
     it picks fastest-commit methods that may use shared capacity another
     demand desperately needed. Preference int is global (user-set
     priorities common to all demands), so it incidentally coordinates
     them.
  4. **The elaborate-fairness regression evaporates as `max_methods`
     approaches `methods.size`.** At `max=4`, elab-ON's Gini is 0.4228 —
     comparable to preference's 0.4272. When the cap saturates the
     candidate pool (most products have 2-3 candidates), ranking ceases
     to matter because all methods are used anyway.
  5. **Elaborate has a fixed wall-time tax** (~3-4 min for per-demand
     subtree simulation), roughly independent of `max_methods`.
     Preference is much cheaper because no scoring simulation runs.
  6. **on-time drops by ~20 demands across every waterfall config.**
     That's the lead-time penalty of fallback methods (slot N+1 typically
     has longer transit/lead than slot N), not really tunable here. If
     on-time is paramount, stay at `max=1`.

**Default recommendation**: `mode=preference, max_methods=2`. Switch to
elaborate only when delivery time matters more than fairness (e.g.,
expedited orders), and consider `max_methods >= methods.size` if you
do — the elab-ON Gini hit fades when the cap is broad.

## Migration

Saved plan_run configs containing `split_mechanism` are not migrated. They
remain as-is in the DB (immutable audit). New runs persist no
`split_mechanism` key (`Allocate.kt` was updated to drop it from emission).
The backend's `resolveMethodSelection` ignores any `split_mechanism` key on
input — there's a regression test in `PlanningEngineSelectionConfigTest`:

```
test("split_mechanism is silently ignored on input (legacy field)")
```

UI dropdown shows only "Max methods" now. Old browser sessions that cached a
config with `split_mechanism: "equal"` will have it stripped on first
submit by `frontend/lib/api.ts` `normalizeMethodSelection`.

## Verification

Backend unit tests:

```
cd backend-kotlin && ./gradlew test --console=plain
```

Targeted waterfall coverage:

```
./gradlew test --tests "*WaterfallAllocationTest*" --info
```

End-to-end on case-171 (the case that surfaced the original bug):

```
make build
docker compose --env-file .env.dev -f docker-compose.yml -f docker-compose.dev.yml up -d --no-deps allocator-backend allocator-frontend
```

Then in the browser:

  - **`max=1` baseline**: should match the pre-waterfall `max=1` baseline run
    417 (Gini 0.4445, p10 0.0, median 0.675, starvation 20.19%, fill 13.37%)
    within FP noise. Wall-time ~90s.
  - **`max=2` waterfall**: completes in well under 10× the baseline
    wall-time (no exponential blowup). KPIs likely show a Gini drop and a
    fill-rate increase, since waterfall picks up residual on demands where
    method 1 hit capacity.

DB sanity:

```
SELECT id, config->'method_selection' FROM plan_run
WHERE created_at > '2026-04-28 20:00:00'
ORDER BY id DESC;
```

Confirm new runs persist no `split_mechanism` key.

## Origin

Designed during the case-171 verification session on 2026-04-28 after the
proportional split mechanism was found to hang on real BOMs. User decision
quote: *"the right mental model in dealing with multiple options is: exhaust
the best first, resort to lesser one only if the demand is not met. this is
different from proportional allocation to all. this could potentially avoid
explosion."* That framing is verbatim the design.
