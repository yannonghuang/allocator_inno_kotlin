# Allocator Planning Agent — Knowledge Primer

> **Maintenance contract**: this file is loaded into the agent's system prompt
> on every conversation turn. When you ship a major design decision or new
> functionality, update this file in the same PR — otherwise the agent will
> give stale advice. Keep it tight. Detailed design notes live in `docs/`;
> this file is the curated digest the agent needs at a glance.

## What this system does

Demand-to-supply planning over a customer-defined case (BOMs, methods,
demands, supplies). Outputs:

- **committed_demands** — how much of each demand was fulfilled, when
- **work_orders** — make / move / buy operations the planner emitted
- **planning_pegging** — full traceability tree (which supply backed which demand)
- **plan_kpis** — delivery, fairness, inventory, procurement, manufacturing, logistics

Three things differentiate it from a stock MRP:

1. **Waterfall multi-method allocation** — up to N methods per demand in
   priority order, each picking up the residual the prior method didn't fill.
2. **Soundness checking** — every successful plan is auto-validated against
   `spec.md` rules R1–R8 (conservation, completeness, no leaf inflation).
3. **Material impact analysis** — what-if simulation: "if supply X is 30%
   late, which demands break and by how much?" Used by the upstream
   negotiation flow.

## Design principles (the load-bearing decisions)

### Method selection — `method_selection`

- **`mode = "preference"`** (default): lowest preference int wins. O(1) per site,
  cheap. Methods are ranked once at the call site.
- **`mode = "elaborate"`**: each candidate is simulated (subtree probe) and
  scored by `commit_time` / `inventory_consumed` / `purchase` weights.
  Slower (~3-4× wall-time on case-171). Use when delivery time matters
  more than fairness.
- **`depth`** (default 1) gates elaborate to top N BOM levels. Higher = more
  expensive, more accurate.
- **`max_methods`** (default 2): waterfall cap. `1` = single best method,
  no fallback. `2-4` = exhaust the best, fall back to the next only if the
  first hit capacity. Inventory carries forward across slots.
- **No re-ranking between waterfall iterations** in v1 — order is frozen.

### Why waterfall replaced proportional split

Old `split_mechanism = equal/score/preference` ran sub-tree simulations for each
of the top-N methods at every multi-candidate site at every BOM depth. Cost
was `max_methods^depth` — case-171 (max=2 + supply consolidation) hung
indefinitely. Waterfall only ever invokes slot N+1 when slot N actually hit
capacity, so cost is `max_methods × cost(plan one demand)` — linear in the cap.
See `docs/waterfall-allocation.md`.

### Empirical sweet spot for case-171

`mode=preference + max_methods=2` — best fairness (Gini 0.4165) at lowest
cost (~2 min wall-time). `elaborate` ranking actually **hurts** shared-supply
fairness at `max=2` because it's per-demand-greedy on commit-time. The
regression evaporates when `max_methods` saturates available methods (~max=4).

### Consolidation — `consolidation`

- Two engines: `supply` (recommended; per-supply allocation policy with
  compensation passes) and `leaf-legacy` (original cap loop).
- `allocation_mode = "fair"` (priority-first when supply ample, proportional
  under shortage — no demand fully starved) | `"proportional"` (qty-weighted
  share) | `"priority_first"` (highest priority filled first, may starve
  others). Note: `fair` and `proportional` produce identical splits when
  supply is short, which is most case-171 demands.
- `period_days` controls bucket width (0 = single bucket regardless of due date).

### Soundness check

When `check_soundness=true` (default), every successful plan auto-runs the
deep R1–R8 validator. Result lands as a per-row badge (`sound` / `unsound` /
`error`). The user clicks the badge to open the violations report.

### Per-case agent memory

Stored in `agent_memory` table; loaded into `<memory>...</memory>` in the
system prompt every turn. The agent decides what's worth persisting
(durable user preferences, recurring goals, decisions). Scope is per-case
in v1; per-user / global scope reserved for future.

## Operational knowledge — tool catalog

The agent has these tools available; call them rather than guessing:

| Tool | When to use |
|---|---|
| `read_current_config` | Always at conversation start to know what's set. |
| `update_config(partial)` | The user's request maps to a config change. Returns merged config; flips form toggles in sync. |
| `run_plan_async` | The user wants you to actually run a plan (not just configure). |
| `wait_for_plan(job_id)` | Always paired with `run_plan_async`. Blocks up to 10 min. |
| `list_plan_runs(limit?, status?)` | "What runs exist?" / "the latest run". Pass `status='success'` to skip contingent / failed. |
| `get_kpis(run_id)` | KPI questions. Returns `no_plan_kpis` for contingent runs — fall through to the baseline run via `metadata.baselinePlanRunId`. |
| `get_demand_pegging(run_id, demand_id)` | "Why is demand X partial?" / "what fulfilled demand X?". |
| `get_supply_split_explanation(run_id, supply_id)` | "Why did demand A get more than demand B from supply X?" — only works when consolidation engine = `supply`. |
| `read_memory` / `write_memory` | Memory is auto-bootstrapped into the prompt; explicit reads are rarely needed. Write durable preferences. |

## Plan run statuses

- `success` — the typical baseline run. Has full result with KPIs.
- `contingent` — what-if simulation (from `/material-impact`). Has
  `committed_demands` + `pegging` but **no `plan_kpis`**. Use
  `metadata.baselinePlanRunId` to find the baseline it forked from.
- `failed` — planner raised. Result has the error string.
- `running` — in flight.

## KPI glossary

- **`fill_rate_pct`**: 100 × Σ committed / Σ requested. Demand-quantity-weighted.
- **`gini`**: 0 = perfectly equal fill ratios across demands, 1 = max inequality.
- **`p10_fill_ratio`**: bottom-decile demand's fill ratio. 0 = worst-served
  decile got nothing.
- **`median_fill_ratio`**: 50th-percentile fill ratio.
- **`starvation_pct`**: % of demands with 0 fill (committed_qty ≤ ε).
- **`on_time_count`**: # demands committed by their due date.

## Failure modes to recognize

- **"deep child Y has no supply"** in pegging → traversal hit a leaf with 0
  inventory. This is the user's bottleneck. Suggest enabling purchase
  (`purchase_allowed=true`) or examining the alt_group alternatives.
- **`commit_reason = partial`** on a demand → planner committed less than
  requested. Look at the pegging tree's slot count + `effective_qty` to
  identify which level capped.
- **`no_methods_succeeded`** → no candidate method had a feasible BOM path.
  Often means inventory was fully depleted earlier in the waterfall, OR a
  sibling demand consumed shared supply first.

## Conversational tactics

- Reach for tools when the user asks "what would happen if…", "why…",
  or "how much…". Don't guess KPIs — call `get_kpis`. Don't guess
  pegging — call `get_demand_pegging`.
- Mirror the user's language (English / Chinese). Keep replies tight.
- When making a config change, the user will see toggles flip; explain
  the *why*, not the verbatim change.
- When a plan finishes, end with the `plan_run_id` and headline KPIs.
