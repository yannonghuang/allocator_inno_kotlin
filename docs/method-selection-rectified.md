# Method selection — capped count + split mechanism

## Context

Today's `method_selection.multiple` is binary: `false` = pick one method
(by preference or elaborate score), `true` = use **every** available
method with **equal-split** of the demand. Two limitations:

- No way to say "use the top 2" when 4 methods exist.
- No alternative to equal-split, even though scoring already ranks
  methods by `commit_time / inventory_consumed / purchase` weights.

This work generalizes both axes:

1. **Cap (`top_n`)** — max methods to use, integer ≥ 1, or `null` =
   all available. UI default proposed to user: 2.
2. **Split mechanism** — `equal` (current) or `proportional` (new;
   weights by score from the existing elaborate scorer).

The pattern already exists for variants
([`variant_selection.topN`](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L51-L55)
+ equal-split inside `getPreferredVariants`). We mirror it for methods,
adding a proportional path the variants don't have today.

## Architecture

### Configuration shape

```kotlin
"method_selection": {
  // existing fields, unchanged
  "mode":         "preference" | "elaborate",
  "depth":        Int >= 1,                    // default 1
  "elaborate":    Bool,                        // legacy alias for mode=elaborate
  "score_weights": { commit_time, inventory_consumed, purchase },
  "depth_optimal": Bool,

  // NEW
  "multiple":         Bool,                    // existing; semantics relaxed (see below)
  "top_n":            Int? >= 1,               // null = all methods
  "split_mechanism":  "equal" | "proportional" // default "equal"
}
```

**Backwards compat for `multiple` and `top_n`** (resolution order in
`resolveMethodSelection`):

| stored config | effective `top_n` | rationale |
|---|---|---|
| `multiple: false`, `top_n: any` | `1` | back-compat: explicit single |
| `multiple: true`, `top_n: null` | `null` (all) | preserves old "use everything" |
| `multiple: true`, `top_n: 2` | `2` | new |
| `multiple: omitted`, `top_n: 2` | `2` | new runs from updated UI |
| nothing set | `1` (single method) | safest default |

`multiple` becomes a soft alias: `multiple = (top_n != 1)`. We keep
the field for now to avoid breaking saved configs and the existing
i18n surface; it can be deprecated in a follow-up.

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
1. cap = cfg.topN ?: methods.size; cap = min(cap, methods.size).
2. If cap == 1:
       Use the existing single-method branch (preserves preference vs. elaborate
       behavior end-to-end). Return [(chosen, demandQty)] OR fall through to a
       no-method blocked path the same way today's plan() does.
3. Score every method:
       - When mode == "elaborate":
             score_i = composite from scoreMethod(...) using current
             commit_time / inventory_consumed / purchase weights.
             Higher = better.
       - When mode == "preference":
             score_i = 1.0 / (preference_i + 1)   // lower preference int → higher score
             Stable monotone transform; preserves cascade ranking but yields
             positive weights for proportional.
4. Filter to feasible (drop hard-failed candidates per the existing scorer).
5. Sort by score desc; take top `cap`.
6. Allocate qtyPerMethod:
       - "equal":         existing logic (integer-aware: base = qty/n,
                          rem = qty%n; first `rem` slots get base+1).
       - "proportional":  Σ scores → weight_i = score_i / Σ.
                          For integer demands: largest-remainder rounding so
                          Σ qtyPerMethod = demandNetQty exactly.
                          For fractional: qty * weight_i.
       - Degenerate cases (Σ scores ≤ ε, or all scores equal):
                          fall back to equal split.
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
| [services/PlanningEngine.kt:41-48](backend-kotlin/src/main/kotlin/com/allocator/services/PlanningEngine.kt#L41-L48) | `MethodSelectionConfig` — add `topN: Int?`, `splitMechanism: SplitMechanism` enum (`EQUAL` / `PROPORTIONAL`). |
| `services/PlanningEngine.kt:77-102` | `resolveMethodSelection` — parse `top_n` (clamp ≥ 1; null when 0/negative/missing/non-numeric) and `split_mechanism` (case-insensitive; default `EQUAL`; unknown → `EQUAL` with a warn log). |
| `services/PlanningEngine.kt` (new top-level) | `selectAndSplitMethods` helper as above. Reuse the existing `scoreMethod` / `normalizeScoreWeights` plumbing. |
| `services/PlanningEngine.kt:992-1016` | Replace the inlined equal-split block with a call to `selectAndSplitMethods`. The `for ((idx, m) in methods.withIndex())` loop becomes `for ((idx, slot) in selected.withIndex()) { val (m, methodQty) = slot; ... }`. |
| `services/PlanningEngine.kt:~985` | `useMultipleMethods` becomes `methodCfg.effectiveTopN(methods.size) > 1`. |

### Frontend types

[`frontend/lib/api.ts:300-309`](frontend/lib/api.ts#L300-L309) —
extend `method_selection`:

```ts
method_selection?: {
  mode?: 'preference' | 'elaborate';
  depth?: number;
  elaborate?: boolean;
  multiple?: boolean;                            // kept for back-compat
  top_n?: number | null;                         // NEW
  split_mechanism?: 'equal' | 'proportional';    // NEW
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
   - **Max methods** — a small number picker (1, 2, 3, 4, All). Use
     the same select-style component as other planning-config fields.
     Sends `top_n: 1|2|3|4|null`. Default in the UI: `2`.
   - **Split mechanism** — radio with two options:
     - `Equal` (current behavior; recommended when methods are
       roughly comparable).
     - `Proportional` (weights by elaborate score; only meaningful
       when score weights are non-trivial, so the UI nudges users
       toward elaborate mode if they pick proportional with
       preference mode).
2. **Back-compat for old saved configs**: when loading a config with
   `multiple: false`, render `Max methods = 1`. With `multiple: true`
   and no `top_n`, render `Max methods = All`.
3. **Copilot shortcuts** — add two more presets:
   - "Top 2 methods, equal split" (likely the new default suggestion)
   - "Top N methods, proportional"
   And update the natural-language detection in the existing copilot
   block to recognize phrases like "top 2 methods" and "proportional
   split".
4. **i18n**: new keys under `planning.copilot` and the planning-config
   form's namespace, en + zh:
   - `methodMaxCount`, `methodSplitMechanism`, `methodSplitEqual`,
     `methodSplitProportional`, plus tooltips explaining each.

### i18n keys to add (en + zh)

```
"methodMaxCount":             "Max methods"
"methodMaxCountTooltip":      "Cap on how many methods can be used per demand. 1 = single best; 2-4 = blend the best few; All = unbounded."
"methodSplitMechanism":       "Split"
"methodSplitMechanismTooltip":"How to divide a demand's quantity across the selected methods."
"methodSplitEqual":           "Equal"
"methodSplitProportional":    "Proportional to score"
"methodSplitProportionalNote":"Uses the elaborate scorer's weights; falls back to equal when all scores are zero or the score weights are not configured."
```

## Tests

### Backend unit tests

Add to
[`backend-kotlin/src/test/kotlin/com/allocator/PlanningEngineSelectionConfigTest.kt`](backend-kotlin/src/test/kotlin/com/allocator/PlanningEngineSelectionConfigTest.kt):

1. `top_n` parsing: `null` (default) → null; `0`/`-1`/`abc` → null;
   `1.7` → 1; `3` → 3.
2. `split_mechanism` parsing: missing → `EQUAL`; `"equal"` /
   `"EQUAL"` / `"proportional"` → respective enums; `"foo"` → `EQUAL`
   (with warn log; assert via captured logger).
3. Legacy resolution: `multiple: false` always wins (effective
   `top_n` = 1, even if `top_n: 5` is also set).
4. `multiple: true, top_n: null` → effective `top_n` = methods.size.

New file
`backend-kotlin/src/test/kotlin/com/allocator/SelectAndSplitMethodsTest.kt`:

1. **topN cap**: 4 methods, topN=2 → returns 2 highest-scored.
2. **topN exceeds count**: topN=10, 3 methods → returns all 3.
3. **topN=1**: returns the single best, full demand.
4. **Equal split, integer demand=10, n=3** → `[4, 3, 3]`
   (largest-remainder; matches existing variants logic).
5. **Equal split, fractional demand=10.5, n=3** → `[3.5, 3.5, 3.5]`.
6. **Proportional, scores [3, 1], integer demand=8** → `[6, 2]`.
7. **Proportional, scores [3, 1], fractional demand=10.0** →
   `[7.5, 2.5]`.
8. **Proportional fallback (equal scores)** → equal split.
9. **Proportional fallback (all-zero scores)** → equal split.
10. **Preference-mode scoring with topN=2**: methods with preferences
    `[1, 2, 3]` → top 2 are pref=1 and pref=2.

### Backend integration tests

On case 171 (curl harness, like the prior conservation work):

1. **Baseline**: `top_n=1`, equal — soundness should match the
   existing run-385 baseline.
2. **Top-2 equal**: `top_n=2, split=equal` — soundness still
   `overall_sound=true`; KPI `manufacturing.order_count` should
   change (more or fewer make WOs depending on demand structure).
3. **Top-2 proportional**: `top_n=2, split=proportional, mode=elaborate,
   weights={commit_time:1.0, inventory:0, purchase:0}` — soundness
   still `overall_sound=true`; verify the per-method qtys differ from
   equal-split when scoring is non-uniform (inspect a sample WO via
   `result.work_orders`).

### Frontend tests

- TypeScript compile clean.
- Manual smoke: open case 171, set Max=2 + Proportional, run plan,
  verify the request body contains `top_n: 2,
  split_mechanism: "proportional"`. Verify the saved config round-trips
  through the run-history panel.

## Open design questions

These are the points worth confirming before implementation:

1. **Default for new runs (UI)**: The user said "default to 2 for example".
   Confirm: the *backend* default stays `null` (= preserves old
   behavior for legacy configs), but the *UI* seeds `top_n = 2` for
   newly-created configs. Is that the right split?

2. **Proportional in preference mode**: Should `split_mechanism =
   proportional` work when `mode = preference` (using
   `score = 1/(preference+1)`), or should the UI gate it to
   `mode = elaborate` only?

3. **Field name**: `top_n` (parallels variants) vs `max_methods`
   (more self-documenting). Plan uses `top_n` for parity.

4. **Soft-deprecate `multiple`** in TS as `@deprecated use top_n
   instead`, or keep it active? The plan keeps it active for
   back-compat round-tripping.

## Verification (post-implementation)

Run the full backend test suite:

```bash
cd backend-kotlin && ./gradlew test    # expect 249 + N new tests, 0 failures
```

End-to-end on case 171:

```bash
# Top-2 proportional under elaborate mode
JOB=$(curl -s -X POST http://localhost:8000/cases/171/plan \
  -H 'Content-Type: application/json' \
  -d '{"async": true, "config": {
        "method_selection": {
          "mode": "elaborate", "depth": 2, "top_n": 2,
          "split_mechanism": "proportional",
          "score_weights": {"commit_time": 1.0}
        },
        "consolidation": {"enabled": true, "engine": "supply", "allocation_mode": "fair"}
      }}' | jq -r .job_id)
# poll, save, check soundness, inspect work_orders.
```

Manual UI test: pick the new controls, run a plan, verify the
Fairness card and the existing soundness check still report sensible
values (top-N selection should generally improve fairness Gini when
proportional weights spread demand wider).
