# Method selection — capped count + split mechanism

## Context

Today's `method_selection.multiple` is binary: `false` = pick one method
(by preference or elaborate score), `true` = use **every** available
method with **equal-split** of the demand. Two limitations:

- No way to say "use the top 2" when 4 methods exist.
- No alternative to equal-split, even though scoring already ranks
  methods by `commit_time / inventory_consumed / purchase` weights.

This work generalizes both axes:

1. **Cap (`max_methods`)** — max methods to use, integer ≥ 1.
   Default **2** (both backend and UI seed the same value, kept in sync).
2. **Split mechanism** — three options:
   - `equal` — divide demand qty evenly (current behavior).
   - `score` — weight by the elaborate scorer's composite score.
     The scorer already normalizes its three components (commit_time
     / inventory_consumed / purchase) to `[0, 1]`, so raw scores
     are directly usable as proportional weights.
   - `preference` — weight by **rank**, not raw `preference` int.
     Raw preference values are arbitrary (we've seen negatives like
     `-7304` and wildly scattered ranges in real data); only the
     relative ordering matters. The mechanism normalizes to dense
     ranks `1, 2, 3, …`, then uses `weight_i = 1 / rank_i`. Ties
     in raw preference share the same rank (and therefore the same
     weight). See the algorithm details below.

The pattern partly mirrors variants
([`variant_selection.topN`](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L51-L55)
+ equal-split inside `getPreferredVariants`), but methods get richer
split semantics that variants don't have today.

## Architecture

### Configuration shape

```kotlin
"method_selection": {
  // existing fields, unchanged
  "mode":         "preference" | "elaborate",
  "depth":        Int >= 1,                                     // default 1
  "elaborate":    Bool,                                         // legacy alias for mode=elaborate
  "score_weights": { commit_time, inventory_consumed, purchase },
  "depth_optimal": Bool,

  // NEW
  "multiple":         Bool,                                     // @deprecated — see migration
  "max_methods":      Int >= 1,                                 // default 2
  "split_mechanism":  "equal" | "score" | "preference"          // default "equal"
}
```

**Backwards compat / migration for `multiple` and `max_methods`**
(resolution order in `resolveMethodSelection`):

| stored config | effective `max_methods` | rationale |
|---|---|---|
| `max_methods` set explicitly | use it (clamped to ≥ 1) | UI-driven new runs |
| `multiple: false`, no `max_methods` | `1` | back-compat: explicit single |
| `multiple: true`, no `max_methods` | `2` | **behavior change** for legacy "all" configs; documented |
| nothing set | `2` | new default; in sync with the UI |

The "behavior change" row is intentional and was the user's explicit
choice when defaults were aligned to 2. Saved plan runs in the DB are
immutable (their pegging trees and KPIs are persisted as-is), so this
only affects re-running an old config or starting a new plan from a
case whose stored config has `multiple: true` without `max_methods`.

`multiple` is **soft-deprecated**:
- TypeScript: tag with JSDoc `@deprecated use max_methods instead`.
- Backend: continue reading it for back-compat resolution above.
- New UI flows: write `max_methods` only; do not write `multiple`.
- A follow-up branch can remove `multiple` entirely once no live
  configs reference it.

### Scoring + selection algorithm

A new helper `selectAndSplitMethods` replaces the inlined block at
[PlanningEngine.kt:992-1016](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L992-L1016):

```kotlin
internal fun selectAndSplitMethods(
    methods: List<Map<String, Any?>>,
    demandNetQty: Double,
    cfg: MethodSelectionConfig,
    // ... + the args scoring already needs (inventory, data, reqDt, leadDays, path, depth)
): List<Pair<Map<String, Any?>, Double>>  // (method, allocated qty)
```

Pseudocode:

```
1. cap = min(cfg.maxMethods, methods.size).
2. If cap == 1:
       Use the existing single-method branch (preserves preference vs. elaborate
       behavior end-to-end). Return [(chosen, demandQty)].

3. Rank + score the methods (semantics differ by split_mechanism):

       split_mechanism == "score":
           score_i = composite from scoreMethod(...) using
           commit_time / inventory_consumed / purchase weights, in [0, 1].
           Higher = better. (Always computed, even under mode=preference,
           because the user explicitly asked for score-weighted split.)

       split_mechanism == "preference":
           Sort by raw preference int ascending (lower = better).
           Assign DENSE ranks 1, 2, 3, ... — equal raw values share the
           same rank, next distinct value advances by 1 (e.g.
           raw [0, 0, 5, 100] → ranks [1, 1, 2, 3]).
           score_i = 1.0 / rank_i.
           Raw values can be negative or arbitrary; only relative position
           matters. Ties in raw preference get equal share by design.

       split_mechanism == "equal":
           For ranking only (when |methods| > cap): use the cfg.mode-
           appropriate score (elaborate → composite; preference → dense
           rank). Weights are not consumed for the split (step 6).

4. Filter to feasible (drop hard-failed candidates per the existing scorer).
   If filtering leaves 0 methods, fall back to the original list (matches
   the current variant-selection fallback at getPreferredVariants line 768).

5. Sort by score desc; take top `cap`.

6. Allocate qtyPerMethod across the top-`cap` methods:

       "equal":
           Existing logic — base = qty/cap, rem = qty%cap; first `rem`
           slots get base+1 for integer demand. Fractional demand:
           qty/cap each.

       "score" or "preference":
           Σ scores → weight_i = score_i / Σ.
           Integer demand: largest-remainder rounding so
           Σ qtyPerMethod == demandNetQty exactly.
           Fractional demand: qty * weight_i.

           Degenerate cases — fall back to equal split:
             - Σ scores ≤ ε
             - max(score) - min(score) < ε  (all equal — this is also
               how the preference path handles all-equal raw values:
               every method ends up at dense rank 1, so all weights
               equal 1, and the equal-split fallback fires)
             - score_weights map empty AND mechanism == "score"
               (no signal to weight on)

7. Return zipped list.
```

The downstream multi-method loop at
[PlanningEngine.kt:1020-1132](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L1020-L1132)
becomes a pure consumer: iterate `selectAndSplitMethods(...)`,
preserving the existing per-method first-pass / second-pass /
bottleneck logic (which the conservation work just stabilized).

### What does *not* change

- `getPreferredMethodElaborate` / `getPreferredMethodCascade` /
  `getPreferredMethod` — still drive the `topN == 1` path verbatim.
- The OR-aggregation in `computeRawAchievable` — orthogonal, untouched.
- Per-method bottleneck arithmetic (floor, move-clamp, etc.) — untouched.
- `runOptimalDepthPlanning` — sees the same per-iter `methodCfg`,
  no changes.

## Implementation

### Backend

| file | change |
|---|---|
| [services/PlanningEngine.kt:41-48](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L41-L48) | `MethodSelectionConfig` — add `maxMethods: Int` (default 2) and `splitMechanism: SplitMechanism` enum (`EQUAL` / `SCORE` / `PREFERENCE`). |
| `services/PlanningEngine.kt:77-102` | `resolveMethodSelection` — parse `max_methods` (clamp ≥ 1; default 2; non-numeric → 2). Parse `split_mechanism` case-insensitive; default `EQUAL`; unknown → `EQUAL` with a warn log. Apply legacy resolution: when `max_methods` absent, derive from `multiple` (false→1, true→2 for new default sync, missing→2). |
| `services/PlanningEngine.kt` (new top-level) | `selectAndSplitMethods` helper as above. Reuse the existing `scoreMethod` / `normalizeScoreWeights` plumbing. |
| `services/PlanningEngine.kt:992-1016` | Replace the inlined equal-split block with a call to `selectAndSplitMethods`. The `for ((idx, m) in methods.withIndex())` loop becomes `for ((idx, slot) in selected.withIndex()) { val (m, methodQty) = slot; ... }`. |
| `services/PlanningEngine.kt:~985` | `useMultipleMethods` becomes `min(methodCfg.maxMethods, methods.size) > 1`. |

### Frontend types

[`frontend/lib/api.ts:300-309`](frontend/lib/api.ts#L300-L309) —
extend `method_selection`:

```ts
method_selection?: {
  mode?: 'preference' | 'elaborate';
  depth?: number;
  elaborate?: boolean;
  /** @deprecated use max_methods instead */
  multiple?: boolean;
  max_methods?: number;                                  // NEW; default 2
  split_mechanism?: 'equal' | 'score' | 'preference';    // NEW; default 'equal'
  score_weights?: { commit_time?: number; inventory_consumed?: number; purchase?: number };
  depth_optimal?: boolean;
}
```

### Frontend UI

The current copilot-driven controls at
[`_CaseSectionPage.tsx:908-1030`](frontend/app/cases/[id]/_CaseSectionPage.tsx#L908-L1030)
include presets like "equal split methods". Plan:

1. **Two new controls** in the planning-config drawer (find the
   existing form section that owns `multiple`):
   - **Max methods** — a small number picker (1, 2, 3, 4). Use the
     same select-style component as other planning-config fields.
     Sends `max_methods: 1|2|3|4`. UI default `2` (in sync with the
     backend default).
   - **Split mechanism** — radio with three options:
     - `Equal` (current behavior; recommended when methods are
       roughly comparable).
     - `By score` (uses elaborate scorer's composite weights;
       requires non-trivial `score_weights` to differentiate).
     - `By preference` (uses BOM `preference` int directly).
2. **Back-compat for old saved configs**: when loading a config that
   has `multiple` but not `max_methods`:
   - `multiple: false` → render `Max methods = 1`.
   - `multiple: true`  → render `Max methods = 2` (the new default).
   On save, write `max_methods` and **drop `multiple`** so the config
   migrates forward as users edit it.
3. **Copilot shortcuts** — replace the single "equal split methods"
   preset with three:
   - "Top 2 methods, equal split" (the new baseline suggestion)
   - "Top N methods, score-weighted"
   - "Top N methods, preference-weighted"
   Update natural-language detection to recognize "top 2 methods",
   "score-weighted split", "preference-weighted split".
4. **i18n**: new keys under `planning.copilot` and the planning-config
   form's namespace, en + zh:
   - `methodMaxCount`, `methodMaxCountTooltip`,
     `methodSplitMechanism`, `methodSplitMechanismTooltip`,
     `methodSplitEqual`, `methodSplitScore`, `methodSplitPreference`,
     plus tooltip strings explaining each.

### i18n keys to add (en + zh)

```
"methodMaxCount":              "Max methods"
"methodMaxCountTooltip":       "Cap on how many methods can be used per demand. 1 = single best; 2-4 = blend the best few."
"methodSplitMechanism":        "Split"
"methodSplitMechanismTooltip": "How to divide a demand's quantity across the selected methods."
"methodSplitEqual":            "Equal"
"methodSplitEqualTooltip":     "Demand divided evenly across the selected methods."
"methodSplitScore":            "By score"
"methodSplitScoreTooltip":     "Demand weighted by the elaborate scorer (commit_time / inventory / purchase). Falls back to equal when all scores are equal or score weights are not set."
"methodSplitPreference":       "By preference"
"methodSplitPreferenceTooltip":"Demand weighted by BOM preference (lower preference int → larger share). Falls back to equal when all preferences are equal."
```

## Tests

### Backend unit tests

Add to
[`backend-kotlin/src/test/kotlin/com/allocator/PlanningEngineSelectionConfigTest.kt`](backend-kotlin/src/test/kotlin/com/allocator/PlanningEngineSelectionConfigTest.kt):

1. `max_methods` parsing: missing → 2; `0`/`-1`/`abc` → 2 (clamped to
   default); `1.7` → 1; `3` → 3.
2. `split_mechanism` parsing: missing → `EQUAL`; `"equal"` /
   `"EQUAL"` / `"score"` / `"preference"` → respective enums;
   `"foo"` → `EQUAL` (with warn log).
3. Legacy resolution priority: `max_methods` set explicitly always
   wins over `multiple` (so `multiple: false, max_methods: 3` →
   effective 3).
4. Legacy `multiple: false` (no `max_methods`) → effective 1.
5. Legacy `multiple: true` (no `max_methods`) → effective 2.
6. Both fields absent → effective 2.

New file
`backend-kotlin/src/test/kotlin/com/allocator/SelectAndSplitMethodsTest.kt`:

1. **max_methods cap**: 4 methods, cap=2 → returns 2 highest-scored.
2. **max_methods exceeds count**: cap=10, 3 methods → returns all 3.
3. **max_methods=1**: returns the single best, full demand.
4. **Equal split, integer demand=10, n=3** → `[4, 3, 3]`
   (largest-remainder; matches existing variants logic).
5. **Equal split, fractional demand=10.5, n=3** → `[3.5, 3.5, 3.5]`.
6. **Score-weighted, scores [3, 1], integer demand=8** → `[6, 2]`.
7. **Score-weighted, scores [3, 1], fractional demand=10.0** →
   `[7.5, 2.5]`.
8. **Preference-weighted, prefs [1, 2, 3], integer demand=11** —
   weights 1/2, 1/3, 1/4 normalized → roughly `[6, 3, 2]` after
   largest-remainder.
9. **Score fallback (all-zero scores)** → equal split.
10. **Score fallback (all equal scores)** → equal split.
11. **Score fallback (empty score_weights map)** → equal split.
12. **Preference fallback (all equal preferences)** → equal split.
13. **Top-K with score sort**: scores [5, 1, 3, 2], cap=2 →
    selected indices match the top-2 scores `[5, 3]` and qty
    weights derive from those.

### Backend integration tests

On case 171 (curl harness, like the prior conservation work):

1. **Baseline**: `max_methods=1` — soundness should match the existing
   run-385 baseline.
2. **Top-2 equal**: `max_methods=2, split_mechanism=equal` — soundness
   still `overall_sound=true`; KPI `manufacturing.order_count`
   should change relative to baseline.
3. **Top-2 by score**: `max_methods=2, split_mechanism=score,
   mode=elaborate, weights={commit_time:1.0, inventory:0, purchase:0}` —
   soundness still `overall_sound=true`; per-method qtys should differ
   from equal-split (inspect via `result.work_orders`).
4. **Top-2 by preference**: `max_methods=2, split_mechanism=preference` —
   soundness still `overall_sound=true`; per-method qtys should reflect
   `1/(pref+1)` weighting.

A small bonus: when running 2-4 above, the new **Fairness KPI** card
provides a quick distributional check — score-weighted vs preference-
weighted vs equal split should produce visibly different Gini /
starvation values.

### Frontend tests

- TypeScript compile clean.
- Manual smoke: open case 171, set Max=2 + Score, run plan, verify
  the request body contains `max_methods: 2, split_mechanism:
  "score"`. Verify the saved config round-trips through the
  run-history panel and that loading a legacy `multiple: true` run
  renders Max=2 (and re-saves as `max_methods: 2` only).

## Design decisions (resolved)

User-confirmed answers to the open questions in the original draft:

1. **Default**: backend and UI both default to `max_methods = 2`.
   Documented as a deliberate behavior change for legacy configs that
   had `multiple: true` without an explicit cap (previously "all
   methods"; now top 2).
2. **Split mechanism options**: three values — `equal`, `score`,
   `preference`. Independent of `mode` (proportional split is allowed
   in preference mode).
3. **Field name**: `max_methods` (self-documenting) instead of
   `top_n`.
4. **`multiple`**: soft-deprecated. TS gets `@deprecated`. Backend
   continues to read it for back-compat. New UI flows write
   `max_methods` only and stop emitting `multiple`.

## Verification (post-implementation)

Run the full backend test suite:

```bash
cd backend-kotlin && ./gradlew test    # expect 249 + ~13 new tests, 0 failures
```

End-to-end on case 171:

```bash
# Top-2, score-weighted, under elaborate mode
JOB=$(curl -s -X POST http://localhost:8000/cases/171/plan \
  -H 'Content-Type: application/json' \
  -d '{"async": true, "config": {
        "method_selection": {
          "mode": "elaborate", "depth": 2,
          "max_methods": 2,
          "split_mechanism": "score",
          "score_weights": {"commit_time": 1.0}
        },
        "consolidation": {"enabled": true, "engine": "supply", "allocation_mode": "fair"}
      }}' | jq -r .job_id)
# poll, save, check soundness, inspect work_orders + plan_kpis.fairness.
```

Manual UI test: pick the new controls, run a plan, verify the
Fairness card and the existing soundness check still report sensible
values. The three split mechanisms should produce visibly different
Gini / starvation values on the same case, which gives the user an
immediate sanity check that the new mechanism is wired correctly.
