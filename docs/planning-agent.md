# Planning Agent

## Status

Phase 1 (MVP) — shipped on `feat/planning-agent`.
Existing `/cases/{id}/planning-copilot` route stays parallel; will be deleted in Phase 2.

## Why

The original `/planning-copilot` is a single-shot config setter: user message →
LLM → optional partial-merge into `PlanningConfig`. It can flip a knob; it
can't actually do anything. Users still have to click Run Plan, wait, click
the Pegging panel, click Why, scroll the KPI card — then return to the chat
and ask "is this fair?".

The Planning Agent collapses that flow into one conversation. It's a domain
expert that:

1. **Configures parameters** from natural-language goals (the copilot's job).
2. **Runs plans on the user's behalf** — kicks off the planner, waits for
   completion, fetches headline KPIs.
3. **Explains decisions** when asked — fetches pegging trees, supply-level
   allocations, and answers "why was this demand fulfilled this way".
4. **Maintains memory** across sessions per case (preferences, recurring
   goals, decisions worth remembering).

## Knowledge primer (and maintenance contract)

The agent's domain expertise lives in
[`backend-kotlin/src/main/resources/agent-knowledge.md`](../backend-kotlin/src/main/resources/agent-knowledge.md).
That file is loaded once at JVM startup and prepended to the system prompt
on every conversation turn. It carries:

- the **value propositions** of the system (what makes it different from MRP)
- the load-bearing **design decisions** (waterfall vs proportional, mode
  semantics, consolidation engine choices, soundness, memory)
- the **tool catalog** with usage guidance per tool
- a **plan-run-status** table (success / contingent / failed / running) so the
  agent knows what each status implies for available data
- a **KPI glossary** (fill_rate, Gini, p10, median, starvation, on_time)
- known **failure modes** and recommended diagnostic steps

**Maintenance contract** (the central reason this file exists): when you
ship a major design decision or a new agent tool, update `agent-knowledge.md`
in the same PR. Otherwise the agent will give stale advice. Detailed
design notes still live in `docs/`; the knowledge primer is the curated
digest.

Pattern for a doc update PR:

1. Land the feature change as usual (code + tests).
2. Update `agent-knowledge.md`:
   - If new tool: add a row to "Operational knowledge — tool catalog".
   - If design decision: add to "Design principles" + a one-line
     description of the trade-off.
   - If new failure mode: add to "Failure modes to recognize".
3. Optional: deeper exposition in `docs/<feature>.md`; reference it from
   the knowledge primer.

The primer is small on purpose — it's the agent's mental model, not the
specification.

## Architecture

New endpoint:

```
POST /cases/{caseId}/planning-agent
body:  { message, current_config?, history? }
reply: { reply, steps[], config_update?, fresh_run_id? }
```

Backend agent loop ([`PlanningAgentRoutes.kt`](../backend-kotlin/src/main/kotlin/com/allocator/api/PlanningAgentRoutes.kt)):

```
messages = [system_prompt + memory + current_config, ...history, user_message]
loop (max 8 iterations):
    resp = llmChatWithTools(messages, tools=TOOL_REGISTRY, provider="openai")
    if resp.toolCalls.isEmpty():
        return final reply
    for each tool_call:
        execute → record (tool, args, result_summary)
        append role=tool message with tool_call_id + result
    continue loop
```

Tool calls are sequential. 8-iteration cap prevents runaway. The LLM provider
is pinned to OpenAI for tool-use support — `llmChatWithTools` throws if
provider != "openai" (Anthropic / OpenClaw tool-use deferred to Phase 2).

Per-turn cost ceiling under typical usage: 1 LLM call to dispatch tools + 1
to summarize = 2 LLM calls. Worst case: 8 LLM calls + tool work.

## Tool registry (Phase 1, 9 tools)

All tools wrap existing planner internals. No new domain logic.

| Tool | Purpose | Notes |
|---|---|---|
| `read_current_config` | Return the working PlanningConfig for this conversation. | No-arg. Memory is read separately and bootstrapped into the system prompt. |
| `update_config(partial)` | Deep-merge a partial JsonObject into the working config; result becomes `config_update` in response. | Powered by `mergeJsonObject` (unit-tested). |
| `run_plan_async` | Kick off planner with the working config. Returns `job_id`. | Reuses `runPlanBackground` + `engineScope` + `planJobs` from `Allocate.kt` (visibility bumped to `internal`). |
| `wait_for_plan(job_id)` | Block up to 10 min, polling planJobs every 2s. Returns plan_run_id + headline KPIs (fill_rate_pct, total_committed, total_requested). | Phase 1 has no streaming — UI just sees one "Plan run N completed" step. |
| `get_kpis(run_id)` | Full plan_kpis dashboard (delivery / fairness / inventory / procurement / manufacturing / logistics). | Reads `plan_run.result` via `loadPlanResultFromDb`. |
| `get_demand_pegging(run_id, demand_id)` | Pegging tree for one demand — used to explain partial fulfillment, waterfall slot use, deep failures. | Reads `planning_pegging` from the plan result. |
| `get_supply_split_explanation(run_id, supply_id)` | Per-demand split for a consolidated supply — answers "why did demand A get more than demand B from this supply?". | Reads `supply_level_allocations`. Supply consolidation engine only. |
| `read_memory` | All per-case agent_memory entries (key/value JSON map). | Auto-bootstrapped into system prompt; the tool exists for explicit re-reads mid-conversation. |
| `write_memory(key, value)` | Upsert key→value into per-case agent_memory. | Agent decides what's worth remembering. Examples: `purchase_default=false`, `target_metric="gini"`. |

Out of scope for MVP (Phase 2): `analyze_bottlenecks`, `compare_runs`,
`check_soundness`, `promote_run`, `designate_active`.

## Memory

New table `agent_memory`:

```sql
CREATE TABLE agent_memory (
  id BIGSERIAL PRIMARY KEY,
  case_id INTEGER REFERENCES cases(id) ON DELETE CASCADE,
  scope VARCHAR(32) DEFAULT 'case',  -- reserved for future user/global expansion
  key VARCHAR(128) NOT NULL,
  value TEXT NOT NULL,                -- JSON-encoded; agent owns the schema
  updated_at TIMESTAMP DEFAULT NOW(),
  UNIQUE (case_id, scope, key)
);
```

Schema [`Tables.kt::AgentMemory`](../backend-kotlin/src/main/kotlin/com/allocator/Tables.kt). Auto-created in `Database.createTables()`.

At the start of every conversation turn, all per-case memory rows are loaded
and injected into the system prompt as `<memory>...</memory>`. The model can
also call `read_memory` mid-conversation to refresh, and `write_memory` to
persist new facts.

Per-case scope only in Phase 1. Per-user (cross-case) memory needs a user
identity, which the app currently lacks; deferred.

## Intent classification (system prompt structure)

The system prompt instructs the model to label every user message into 1–N
of three intent buckets:

  - **PURCHASE**: "see what we can deliver without buying more"
    → set `purchase_allowed=false`, run plan, summarize fill_rate +
      top short-supplied products
  - **DEMAND**: "treat all demands fairly", "no one starved"
    → `consolidation.enabled=true, allocation_mode=fair, max_methods≥2,
      mode=preference`; explain fairness mechanic
  - **SUPPLY**: "maximize delivery / use inventory / minimize purchase"
    → `mode=elaborate` with `score_weights` tuned to the user's axis
      (commit_time / inventory_consumed / purchase)

The prompt also embeds planner knowledge extracted from
[`waterfall-allocation.md`](./waterfall-allocation.md): waterfall semantics
(max_methods cap, mode, depth, allocation_mode) and the case-171 empirical
sweet spot (`mode=preference + max=2`).

## Frontend

The existing planning chat panel in
[`_CaseSectionPage.tsx`](../frontend/app/cases/[id]/_CaseSectionPage.tsx)
calls `planningAgent` first, falling back to `planningCopilot`, and finally
to the local rule-based `parseCopilotIntent`. This means a user with
`OPENAI_API_KEY` configured gets the full agent; a deployment without one
silently falls back to the existing copilot.

Tool-call steps render inline as gray italic rows under the assistant
bubble, separated by a left border. Example UI:

```
You: I want to analyze material bottlenecks
Copilot: I'll set purchase off and run a plan to surface the bottlenecks.
│  read_current_config · Read working config (consolidation, method_selection, …)
│  update_config       · Updated config: purchase_allowed
│  run_plan_async      · Started plan job 8f2c-… (208 demands)
│  wait_for_plan       · Plan run 421 completed: fill 13.37%
│  get_kpis            · Run 421 KPIs (fill 13.37%)
Copilot: Run 421: fill 13.37% (42,977 / 321,417 units, 197 of 208 on time).
The biggest shortfall is on 500-4327 — only 786 of 1,571 needed. Want me
to try with purchase enabled to see how much that closes the gap?
```

When `fresh_run_id` is set on the response, the frontend refreshes the run
history list so the new run appears immediately.

## Provider strategy

Phase 1 pins to OpenAI function-calling — `llmChatWithTools` throws
`LlmNotConfiguredException` for any other provider. This matches the
existing `/material-impact-assessment` and `/planning-copilot` routes
which both pin to OpenAI explicitly.

Phase 2 should A/B test agent reply quality across providers and decide
whether to extend `llmChatWithTools` to Anthropic's tool_use blocks
(potentially tighter explanations) and OpenClaw's gateway (deployment
flexibility).

## Verification

Backend unit tests:

```
cd backend-kotlin && ./gradlew test --tests "*PlanningAgentMergeTest*" --info
```

Six cases covering the JsonObject deep-merge against PURCHASE / DEMAND /
SUPPLY intent shapes. Total backend suite: 265/265 passing.

End-to-end smoke (requires OPENAI_API_KEY in the running stack):

```
make build
docker compose --env-file .env.dev -f docker-compose.yml -f docker-compose.dev.yml up -d --no-deps allocator-backend allocator-frontend
```

Open the planning chat panel in case-171 and try one phrase per intent bucket:

  1. **"analyze material bottlenecks"** → agent calls update_config
     (purchase_allowed=false) → run_plan_async → wait_for_plan → get_kpis.
     Form's purchase checkbox flips. New run appears in run history.
  2. **"treat all demands fairly"** → agent sets consolidation.enabled=true,
     allocation_mode=fair, max_methods=2, mode=preference. Form toggles
     update; no plan run yet (DEMAND intent doesn't auto-run).
  3. **"why does demand 818_F30_2024_07_VIRTUAL get partial?"** → agent
     calls get_demand_pegging on the latest run, summarizes the waterfall
     slot structure, and offers a remedy.

Bilingual smoke: same three phrases in Chinese should produce Chinese
replies (the system prompt instructs language-mirroring; OpenAI follows
this reliably).

DB sanity:

```sql
SELECT case_id, key, value, updated_at FROM agent_memory ORDER BY updated_at DESC LIMIT 10;
```

After running scenarios above, expect at least one entry per scenario
where the agent decided to remember a preference.

## What's NOT in Phase 1

These are tracked in the plan and deferred to Phase 2:

- `analyze_bottlenecks` tool (top short-supplied products, ranked by gap)
- `compare_runs` tool (KPI deltas between two run_ids)
- `check_soundness` / `promote_run` / `designate_active` tools
- Streaming (SSE) so tokens render as they arrive instead of one big response
- Per-user memory scope (cross-case) — needs user identity in the app first
- Auto-suggestions on plan completion ("Run is short on 500-4327 — want max=3?")
- Deletion of `/planning-copilot` + the rule-based `parseCopilotIntent`
- Memory editing UI (read-only is fine in Phase 1; user can edit via the agent)
- LLM-loop integration tests (require LlmClient stubbable abstraction)

## Files

Created:

- `backend-kotlin/src/main/kotlin/com/allocator/api/PlanningAgentRoutes.kt`
  — endpoint, tool registry, system prompt, agent loop
- `backend-kotlin/src/test/kotlin/com/allocator/PlanningAgentMergeTest.kt`
- This doc.

Modified:

- `backend-kotlin/src/main/kotlin/com/allocator/Tables.kt` — `AgentMemory`
- `backend-kotlin/src/main/kotlin/com/allocator/Database.kt` — register table
- `backend-kotlin/src/main/kotlin/com/allocator/Plugins.kt` — mount route
- `backend-kotlin/src/main/kotlin/com/allocator/services/LlmClient.kt`
  — added `llmChatWithTools` + supporting types
- `backend-kotlin/src/main/kotlin/com/allocator/api/Allocate.kt`
  — bumped `engineScope`, `planJobs`, `planJobRunIds`, `runPlanBackground`,
    `loadPlanResultFromDb` from `private` to `internal` so the agent can
    reuse them without duplicating planner orchestration
- `frontend/lib/api.ts` — `planningAgent()` client + types
- `frontend/app/cases/[id]/_CaseSectionPage.tsx` — chat handler tries
  agent first, falls back to copilot; renders tool-call steps inline

## Origin

Designed during the post-waterfall planning session on 2026-04-29 after
the user requested converting the existing copilot into a domain expert
that can configure, run, and explain plans. User decisions (recorded
verbatim in the plan file):

  - Phase 1 MVP only on its own branch (deferred bottleneck analysis,
    streaming, per-user memory)
  - OpenAI function-calling (sticks with the existing pinning)
  - Per-case memory only
  - Keep `/planning-copilot` parallel during MVP; delete in Phase 2
