const API = typeof window !== 'undefined' ? '/allocator/api' : 'http://localhost:8000';

export type Case = { id: number; name: string; created_at: string; demand_count?: number; supply_count?: number; run_count?: number; plan_run_count?: number; active_plan_run_id?: number | null };
export type AllocationRun = { id: number; case_id: number; created_at: string; status: string; config?: Record<string, unknown> };
export type AllocationAction = { id: number; run_id: number; variant_key: string; req_component_ids: string[]; qty: number; demand_id?: string; target_product_id?: string; target_location_id?: string };
export type FeasibleDemand = { demand_id: string; customer_id?: string | null; customer?: string | null; product_id: string; requested_qty: number; allocated_qty: number; fulfillment_rate?: number | null; status: string; suggested_revision?: string; request_due_time?: string | null; revised_time?: string | null };
export type SupplyViewRow = { id?: number; component_key: string; supply_id: string; supply_date?: string | null; product_id: string; location_id: string; initial_qty: number; consumed_qty: number; residual_qty: number; utilization_rate?: number | null; pegged_demands?: number; total_pegged_qty?: number };
export type AllocationViewCandidate = {
  to_inventory_id: string;
  to_inventory_display: string;
  qty: number;
  edge_type: string;
  to_variant_key: string;
};

export type BasketItem = { key: string; display: string; qty: number };

export type AllocationViewRow = {
  row_type: 'component' | 'demand';
  step?: number;
  scarcity_rank?: number;
  edge_type: string;
  from_inventory_id: string | null;
  from_inventory_display: string;
  critical_component_key: string | null;
  to_inventory_id: string | null;
  to_inventory_display: string | null;
  candidates: AllocationViewCandidate[];
  total_qty: number;
  split_explanation: string | null;
  basket_after?: BasketItem[];
  output_period?: number | null;
  output_date?: string | null;
  qty?: number;
  demand_ids: string[];
  supply_id?: string;
  to_variant_key?: string;
  from_components?: string[];
  critical_component_index?: number | null;
  to_variant_key_display?: string;
  to_product_id?: string;
  to_location_id?: string;
  supply_component_key?: string;
  supply_product_id?: string;
  supply_location_id?: string;
  target_variant_key?: string;
  target_product_id?: string;
  target_location_id?: string;
  basket_step_index?: number;
  /** Step index after this row (sometimes provided by backend). */
  after_step?: number | null;
};

export async function listCases(): Promise<Case[]> {
  const r = await fetch(`${API}/cases`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getCase(id: number): Promise<Case> {
  const r = await fetch(`${API}/cases/${id}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function createCase(name: string): Promise<Case> {
  const r = await fetch(`${API}/cases`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function updateCase(id: number, name: string): Promise<Case> {
  const r = await fetch(`${API}/cases/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function deleteCase(id: number): Promise<void> {
  const r = await fetch(`${API}/cases/${id}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

export async function importCsv(caseId: number, folderPath?: string): Promise<{ status: string }> {
  const q = folderPath ? `?folder_path=${encodeURIComponent(folderPath)}` : '';
  const r = await fetch(`${API}/cases/${caseId}/import-csv${q}`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Allocate starts a background job; request returns quickly with 202 and run_id. */
const ALLOCATE_TIMEOUT_MS = 3_600_000; //30_000;

export async function runAllocate(caseId: number): Promise<AllocationRun> {
  const r = await fetchWithTimeout(`${API}/cases/${caseId}/allocate`, { method: 'POST' }, ALLOCATE_TIMEOUT_MS);
  if (r.status !== 202 && !r.ok) throw new Error(await r.text());
  return r.json();
}

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_WAIT_MS = 3_600_000 * 10; //1_200_000; // 20 min for large cases

export type AllocationProgress = {
  steps: number;
  max_steps: number;
  basket_total_qty: number;
  basket_keys: number;
  initial_basket_total_qty: number;
  initial_basket_keys: number;
};

/** Poll run until status is not 'running'. Optionally report progress on each poll. Returns final run. Throws on timeout. */
export async function pollRunUntilComplete(
  caseId: number,
  runId: number,
  opts?: {
    intervalMs?: number;
    maxWaitMs?: number;
    onProgress?: (progress: AllocationProgress) => void;
  }
): Promise<{ id: number; status: string; config?: Record<string, unknown> }> {
  const intervalMs = opts?.intervalMs ?? POLL_INTERVAL_MS;
  const maxWaitMs = opts?.maxWaitMs ?? POLL_MAX_WAIT_MS;
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    const statusRes = await getRunStatus(caseId, runId);
    if (statusRes.status !== 'running') return statusRes;
    const progress = statusRes.config?.progress as AllocationProgress | undefined;
    if (progress && opts?.onProgress) opts.onProgress(progress);
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error('ALLOCATION_POLL_TIMEOUT');
}

export async function listRuns(caseId: number): Promise<AllocationRun[]> {
  const r = await fetch(`${API}/cases/${caseId}/runs`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getRun(caseId: number, runId: number): Promise<{ run: AllocationRun; actions: AllocationAction[]; feasible_demands: FeasibleDemand[] }> {
  const r = await fetch(`${API}/cases/${caseId}/runs/${runId}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Lightweight status for polling (no actions). */
export async function getRunStatus(caseId: number, runId: number): Promise<{ id: number; status: string; config?: Record<string, unknown> }> {
  const r = await fetch(`${API}/cases/${caseId}/runs/${runId}/status`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

const FETCH_TIMEOUT_MS = 90_000;

function fetchWithTimeout(url: string, options?: RequestInit, timeoutMs = FETCH_TIMEOUT_MS): Promise<Response> {
  const ctrl = new AbortController();
  const id = setTimeout(() => ctrl.abort(), timeoutMs);
  return fetch(url, { ...options, signal: ctrl.signal }).finally(() => clearTimeout(id));
}

export async function getFeasibleDemands(caseId: number, runId: number): Promise<{ feasible_demands: FeasibleDemand[] }> {
  const r = await fetchWithTimeout(`${API}/cases/${caseId}/runs/${runId}/feasible-demands`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Demand-to-supply planning: returns committed demands (with commit_time), work orders, and planning pegging trees. */
export type CommittedDemand = { demand_id?: string | null; customer_id?: string | null; customer?: string | null; product_id: string; location_id: string; quantity: number; requested_qty?: number | null; shortage?: number | null; is_failed?: boolean; request_time?: string | null; commit_time: string | null; commit_reason?: string | null };
export type WorkOrder = {
  product_id: string;
  location_id: string;
  quantity: number;
  start_time: string | null;
  end_time: string | null;
  method: string;
  location_source?: string | null;
  demand_id?: string | null;
  prod_area?: string | null;
  /** True if this WO's pegging (supplies that fulfill it) includes a real make (non-virtual BOM). */
  pegging_includes_real_make?: boolean;
  /** True if this WO's pegging includes a buy (purchase). */
  pegging_includes_buy?: boolean;
  /** True if this WO's pegging includes a real move (TRANSIT_TIME > 0). */
  pegging_includes_real_move?: boolean;
  /** True if this WO's product+location appears in the pegging trees of more than one demand (shared component). */
  demanded_by_multiple?: boolean;
  /** True if this WO's product+location has more than one supply method available in the BOM graph. */
  multi_supply_available?: boolean;
  /** Why this supply method (make/move/buy) was chosen — propagated from pegging node. */
  wo_explanation_method?: string | null;
  /** BOM-graph demand products that also require this component (pid|lid format). */
  wo_competing_demands?: string[];
  /** On a cross-demand batched WO: the constituent demand_ids it was merged from — used to
   *  trace the batch back to each demand's original pegging. */
  consolidated_demand_ids?: string[];
  /** True on a cross-demand batched work order. */
  consolidated?: boolean;
  /** Links a native (work_orders_native) WO to the ONE consolidated WO it rolls into, and is also
   *  set on each consolidated WO (its own id). Native ↔ consolidated form a strict partition. */
  consolidated_group_id?: string;
  /** UI-only: when a table row groups several WOs, each constituent's [start,end] so the schedule
   *  bar can draw one segment per real WO instead of one solid min-start→max-end span. */
  _segments?: { start: string | null; end: string | null }[];
  /** On a mixed-product MOVE shipment (product_id=null): the per-component cargo manifest. */
  move_components?: { product_id: string; quantity: number; demand_ids?: string[] }[];
  /** On a batched WO: the original start-window of its constituents (for precise pegging trace). */
  wo_window_start?: string | null;
  wo_window_end?: string | null;
  /** Present on consolidated WOs: "priority_first" | "proportional". */
  wo_consolidation_split_mode?: string | null;
  /** Present on consolidated WOs: total qty planned for the merged group. */
  wo_consolidation_total_planned?: number | null;
  /** Present on consolidated WOs: per-demand split breakdown. */
  wo_consolidation_split_details?: Array<{
    demand_id: string | null;
    parent_product: string;
    requested_qty: number;
    allocated_qty: number;
    priority: number;
  }> | null;
  /** Stable canonical id assigned during planning regen — same id across all lots emitted from the same planMethodSlot decision. Used as the selector for WO schedule-impact analysis. */
  wo_group_id?: string | null;
  /** Within an OR-merged wo_group_id, distinguishes alternatives (0, 1, …). null/undefined for non-OR (single-alt) WOs. */
  method_slot_index?: number | null;
  /** Native WOs only: per-demand natural start before Pass-2b consolidated batch timing was
   *  applied. Always present for any WO that belongs to a consolidated group. Equal to start_time
   *  for the "bottleneck" demand (whose natural start drove the batch forward). */
  original_start_time?: string | null;
  /** Native WOs only: per-demand natural lead in calendar days (original_end − original_start)
   *  before consolidation merged the group into a shared batch window. Always present alongside
   *  original_start_time. The consolidated batch lead = end_time − start_time. */
  original_lead_days?: number | null;
};

/** A pointer from a constrained pegging node to the OTHER node currently determining its
 *  committed quantity or committed time — mirrors the backend's `DominatorRef` (see
 *  services/PlanningEngine.kt). `label` is precomputed server-side; the UI never re-derives it. */
export type DominatorRef = {
  kind: 'bom_child' | 'sibling_wo' | 'method_alternative' | 'wave_peer' | 'shared_supply_budget' | 'resource_contention';
  product_id?: string | null;
  location_id?: string | null;
  demand_id?: string | null;
  wo_group_id?: string | null;
  supply_id?: string | null;
  competing_demand_ids?: string[] | null;
  label: string;
};

/** Planning pegging tree node: demand (root) -> work_order -> ... -> supply | purchase (leaves).
 *  Make WOs that have an applicable operation also emit `operation` and `resource` children
 *  carrying the bill-of-resources detail; those don't participate in supply/demand flow. */
export type PlanningPeggingNode = {
  type: 'demand' | 'work_order' | 'supply' | 'purchase' | 'operation' | 'resource';
  demand_id?: string | null;
  product_id?: string;
  location_id?: string;
  quantity?: number;
  /** operation nodes: identifier from operation.OPERATION_ID. */
  operation_id?: string | null;
  /** operation nodes: BOR id; resource nodes: id of the consumed resource. */
  resource_id?: string | null;
  /** operation nodes: production rate fields. */
  uph?: number | null;
  yield_factor?: number | null;
  process_time?: number | null;
  pre_process_time?: number | null;
  post_process_time?: number | null;
  prod_area?: string | null;
  wo_group_id?: string | null;
  /** resource nodes: per-unit consumption rate and the available pool size at this location. */
  resource_rate?: number | null;
  size?: number | null;
  /** Concurrent lots allowed under the operation override: min(floor(size/rate))
   *  across BOR resources. Present on operation nodes; present on work_order
   *  nodes when > 1 (omitted for sequential WOs to keep payload small). */
  parallelism_cap?: number | null;
  /** Number of sequential waves the planner uses to run lot_count lots at the
   *  parallelism cap. Present on make work_order nodes only. */
  wave_count?: number | null;
  request_time?: string | null;
  commit_time?: string | null;
  commit_reason?: string | null;
  start_time?: string | null;
  end_time?: string | null;
  method?: string;
  location_source?: string | null;
  /** Why this method was chosen when multiple alternatives exist. */
  method_choice_explanation?: string | null;
  /** How this node's children relate logically, when known. 'or' is used when single-component variants are alternatives. */
  children_relation?: 'and' | 'or';
  /** For make work orders: how many production lots were created and the max lot size used. */
  lot_count?: number | null;
  max_lot_size?: number | null;
  /** For supply nodes: the specific supply record that was consumed. */
  supply_id?: string | null;
  /** For purchase leaves: the vendor the supply is procured from (no source supply record exists). */
  vendor_id?: string | null;
  /** Marker on work_order nodes from the AND-bottleneck blocked branch.
   *  Indicates a debug snapshot of "what would have happened" — the
   *  subtree's child takes were rolled back at the planner level, but
   *  the structure is preserved for diagnosis. UI keeps the children
   *  expandable; soundness checker skips the entire subtree. */
  failed?: boolean;
  /** Marker on demand nodes that AND-bottlenecked their parent make.
   *  Set on the child(ren) whose first-pass `effectiveQty / neededQty`
   *  ratio was the minimum across the parent's AND children — the limiter
   *  whose constraint propagated up through `min` to cap the parent's
   *  achievable qty. Useful for visually surfacing which sibling caused
   *  a partial commit at the parent level. Tied limiters are all flagged. */
  is_bottleneck?: boolean;
  /** Marker on the GENUINE root bottleneck — the child whose iter-0
   *  consolidation-allocation cap (cap/need ratio) was the smallest among
   *  AND siblings. Distinct from `is_bottleneck`, which after the
   *  consolidation-engine cap-refinement loop converges, ends up flagging
   *  ALL siblings tied at the smeared AND-feasible point. The root flag
   *  identifies the ORIGIN that dragged the others down via convergence
   *  — typically the only child a user can actually unblock by adding
   *  supply or reducing competition. */
  is_root_bottleneck?: boolean;
  /** Diagnostic message attached to a failed demand node when commit_reason
   *  is `no_methods` / `no_preferred_method`. Spells out why no method could
   *  source this (product, location): purchase filtered out, methods exist
   *  at other locations, or no methods at all. Rendered inline in the
   *  pegging panel in place of the generic "No work orders" copy. */
  failure_explanation?: string | null;
  /** Committed quantity at this node — always present at runtime even though it was
   *  previously missing from this type (a pre-existing schema gap). */
  committed_qty?: number;
  /** "Least quantity dominates": the other node(s) currently determining this node's
   *  committed quantity, captured inline at the planner's existing min-collapse points
   *  (AND-sibling min, bottom-up reconcile, cross-demand shared-supply budget). Absent/empty
   *  means nothing else currently constrains this node's quantity. */
  quantity_dominator?: DominatorRef[];
  /** "Latest time dominates": the other node(s) currently determining this node's committed
   *  time, captured inline at the planner's existing max-collapse points (BOM child push,
   *  method-alternative OR, wave consolidation). Absent/empty means nothing else currently
   *  pushed this node's timing out. */
  time_dominator?: DominatorRef[];
  children: PlanningPeggingNode[];
};

export type PlanningPeggingEntry = {
  demand_id: string | null;
  tree: PlanningPeggingNode;
  passthrough?: boolean;
  consolidated?: boolean;
  /** Demand IDs that share this consolidated supply group (multi-demand consolidation only). */
  consolidated_demand_ids?: string[];
  /**
   * Demand → allocated qty weights for consolidated entries (passthrough or multi-demand).
   * Used to attribute every supply leaf in the tree (including raw materials deep in the BOM)
   * to the right demands without relying on synthetic tagged consolidated buckets.
   */
  per_demand_allocations?: Record<string, number> | null;
};

/** Plan KPI dashboard: delivery, fairness, inventory, procurement, manufacturing, logistics. */
export type PlanKpis = {
  delivery: {
    total_requested: number;
    total_committed: number;
    fill_rate_pct: number | null;
    demand_count: number;
    on_time_count: number;
    fulfilled_with_tree_count?: number;
    fulfilled_by_real_make_count?: number;
    fulfilled_by_inventory_only_count?: number;
  };
  /**
   * Distribution of fill ratios across demands. Aggregate fill_rate_pct hides
   * whether shortage was spread evenly or concentrated on a few demands; these
   * four numbers describe the shape of the distribution.
   *
   * All fields are null when there are no demands with requested_qty > 0.
   */
  fairness?: {
    /** 0 = perfectly equal, 1 = max inequality. */
    gini: number | null;
    /** Bottom-decile demand's fill ratio; reflects worst-served experience. */
    p10_fill_ratio: number | null;
    /** Median fill ratio; robust complement to the existing mean fill_rate_pct. */
    median_fill_ratio: number | null;
    /** % of demands with 0% fill (committed_qty ≤ ε). */
    starvation_pct: number | null;
  };
  inventory: {
    initial_total: number;
    consumed_total: number;
    consumption_rate: number | null;
  };
  procurement: { order_count: number; total_quantity: number };
  manufacturing: { order_count: number; total_quantity: number };
  logistics: { order_count: number; total_quantity: number };
};

/** Config for planning. Sent in POST body to /plan. */
export type PlanningConfig = {
  /**
   * Method selection shape: `mode` + `depth` + `multiple`.
   * `mode: "elaborate"` scores each candidate by commit_time/inventory/purchase;
   * `depth` (≥1, default 1) controls how many recursion levels elaborate applies at.
   * Legacy `elaborate: boolean` is still accepted by the backend.
   */
  method_selection?: {
    mode?: 'preference' | 'elaborate';
    depth?: number;
    elaborate?: boolean;
    /**
     * @deprecated use `max_methods` instead. Kept for back-compat reading of
     * legacy saved configs. The UI no longer writes this field — saving a
     * legacy `multiple: true` config from the form re-emits `max_methods`.
     */
    multiple?: boolean;
    /**
     * Waterfall cap: how many ranked methods may be tried before giving up.
     * Integer >= 1. Default 2 (in sync with the backend default).
     *   1 = single best method (no fallback)
     *   2-4 = exhaust best, then resort to lesser only if demand isn't met
     * Methods are ranked once at the call site (preference int asc, or
     * elaborate score desc). Inventory carries forward across slots.
     */
    max_methods?: number;
    /**
     * Maximum real-make recursion depth admitted at the reactive make-fallback
     * site. Default 3; clamped 1..10 by the backend. A make alternative whose
     * precomputed maxMakeDepth exceeds this cap is skipped without recursing.
     */
    max_bom_depth?: number;
    /** Relative weights for elaborate scoring. Backend normalizes so absolute values don't matter. */
    score_weights?: { commit_time?: number; inventory_consumed?: number; purchase?: number };
  };
  /** When false, the buy/purchase method is excluded from planning. Default: true. */
  purchase_allowed?: boolean;
  /**
   * Selective-purchase whitelist of buyable raw-material product_ids. Only meaningful
   * when purchase_allowed !== false. Empty/absent ⇒ all raw materials are purchasable
   * (default). Non-empty ⇒ strict whitelist: only listed materials keep their buy
   * method; every other product's buy method is dropped.
   */
  purchasable_materials?: string[];
  /**
   * Customer-specific BOM-alternative constraints. Each rule pins which child a given
   * customer's demand must resolve to for a parent product (location empty/'*' = any).
   */
  constraints?: { customer: string; parent: string; location: string; child: string }[];
  /** Consolidate shared component demands within a time bucket before planning. */
  consolidation?: {
    enabled?: boolean;
    /** Width of the supply-side time bucket in days (0–365). */
    period_days?: number;
    /** Legacy global WO batch scale — used as fallback when per-type scales are absent. */
    wo_batch_scale?: 'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';
    /** Per-type WO batch scales. Override wo_batch_scale when present. */
    make_batch_scale?:     'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';
    move_batch_scale?:     'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';
    purchase_batch_scale?: 'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';
  };
  /**
   * Post-plan UI behavior toggles. These do not affect planner output — they
   * control what the UI does *after* a successful plan run. Lifted into
   * PlanningConfig so the planning-copilot can read/set them in the same
   * round-trip as the real planner config.
   */
  /** When true, automatically save + analyze criticality after each successful plan. Default: false. */
  analyze_criticality?: boolean;
  /** When true, automatically run the soundness check (always deep) after each successful plan. Default: true. */
  check_soundness?: boolean;
};

export type PlanSupplyAllocation = {
  supply_id: string;
  demand_id: string | null;
  qty_consumed: number;
  qty_allocated?: number;
};

/**
 * Per-(supply_id) allocation record emitted by the supply-level consolidation
 * pipeline (Phase 2's per-supply policy split + Phase 3b compensation results).
 * Frontend reads this list (when present in the plan result) to populate the
 * supply-view chip + slide-in's per-supply allocation info when scope=all.
 * Empty/absent under scope=leaf-only — its splitInfos come from consolidated
 * pegging entries instead.
 */
export type SupplyLevelAllocation = {
  supply_id: string;
  group_product_id: string;
  group_location_id: string;
  mode: string;
  group_total_need: number;
  group_total_produced: number;
  candidate_count: number;
  per_demand_allocations: Record<string, number>;
};

export type PlanResult = {
  committed_demands: CommittedDemand[];
  work_orders: WorkOrder[];
  /** Native per-demand work orders (pre-consolidation, 1:1 with the pegging). Shown in its own tab
   *  — separate from `work_orders` (consolidated) so aggregates never double-count. */
  work_orders_native?: WorkOrder[];
  planning_pegging: PlanningPeggingEntry[];
  supply_allocations?: PlanSupplyAllocation[];
  supply_level_allocations?: SupplyLevelAllocation[];
  /** Headline KPIs computed by the planner. Already declared as `PlanKpis`
   *  earlier in this file (see `getPlanKpis`); pulled in here so the chat
   *  panel can read fill_rate_pct etc. when a pending plan completes. */
  plan_kpis?: PlanKpis;
  /** Number of WO groups whose start was pushed by ResourceScheduler.arbitrate
   *  to wait for contended resources. Zero when nothing was contended. */
  resource_contention_pushed_wos?: number;
};

export async function runPlan(
  caseId: number,
  config?: PlanningConfig | null
): Promise<PlanResult> {
  const body = JSON.stringify({ config: config ?? undefined, async: false });
  const r = await fetchWithTimeout(`${API}/cases/${caseId}/plan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** BOM (PARENT_ID, CHILD_ID) pairs that are "real" (VIRTUAL <> 'Y' in bom.csv). */
export async function getBomRealPairs(caseId: number): Promise<{ pairs: [string, string][] }> {
  const r = await fetch(`${API}/cases/${caseId}/plan/products-with-real-bom`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Move methods with TRANSIT_TIME > 0 (real moves). Each tuple: [product_id, from_location_id, to_location_id]. */
export async function getMovesWithTransit(caseId: number): Promise<{ moves: [string, string, string][] }> {
  const r = await fetch(`${API}/cases/${caseId}/plan/moves-with-transit`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** A purchasable raw material: a product tagged prod_area='raw' that has a method_buy. */
export type PurchasableRawMaterial = {
  product_id: string;
  description?: string | null;
  vendor_id?: string | null;
  lead_days_supply?: number | null;
  sku_pattern?: string | null;
};

/** Raw materials (productlocation.prod_area='raw') that have a method_buy — feeds the
 *  "selective purchase" whitelist dropdown and the copilot /raw picker. */
export async function getPurchasableRawMaterials(caseId: number): Promise<{ materials: PurchasableRawMaterial[] }> {
  const r = await fetch(`${API}/cases/${caseId}/plan/purchasable-raw-materials`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Options for the planning "Constraints" section. */
export type ConstraintOptions = {
  customers: { customer_id: string; description?: string | null }[];
  /** Parent products that have BOM alternatives, with their make locations + forceable children. */
  parents: { parent: string; locations: string[]; children: string[] }[];
};

/** Customers (on this case's demands) + parent products with BOM alternatives — drives the
 *  4 cascading dropdowns in the Constraints section. */
export async function getConstraintOptions(caseId: number): Promise<ConstraintOptions> {
  const r = await fetch(`${API}/cases/${caseId}/plan/constraint-options`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Per-resource utilization rows for a plan run. Each row is a (resource_id, location_id) with
 *  daily load values aligned to `buckets` (ISO date strings) and the resource's static `size`.
 *  `contributors` lists the work_orders that drove the load (debug/cross-highlight hook). */
export type ResourceUtilizationRow = {
  resource_id: string;
  location_id: string;
  size: number;
  load: number[];
  contributors?: Array<{
    wo_group_id?: string | null;
    demand_id?: string | null;
    /** Every demand this WO serves. For a cross-demand consolidated batch
     *  (demand_id null), pass this as `demand_ids` to getWorkOrderPegging —
     *  there's no single demand_id to key pegging resolution off. */
    demand_ids?: string[];
    product_id?: string;
    location_id?: string;
    quantity?: number;
    start_time?: string;
    end_time?: string;
    /** Number of lots collapsed into this WO row (≥1). The view groups
     *  rows by wo_group_id so a multi-lot WO is a single row. */
    lot_count?: number;
    rate?: number;
    /** This WO's own peak contribution to the resource's daily load (busiest
     *  wave's concurrent-lot count × rate) — more meaningful than `rate` alone,
     *  which is a per-lot constant that doesn't vary by row. */
    peak_load?: number;
  }>;
};

export type ResourceUtilization = {
  horizon: { start: string; end: string };
  buckets: string[];
  rows: ResourceUtilizationRow[];
};

export async function getResourceUtilization(caseId: number, runId: number): Promise<ResourceUtilization> {
  const r = await fetch(`${API}/cases/${caseId}/runs/${runId}/resource-utilization`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Fetch work-order pegging on demand: how this WO is fulfilled by its supplies (all levels). Requires a prior plan run.
 *  `start_time` disambiguates which slot/lot to return when a (demand, product, location, method) tuple
 *  has multiple matches (waterfall slots or multi-lot WOs). Pass the WO row's start_time.
 *  `wo_group_id` disambiguates further: two separate lots for the same demand can share an
 *  identical start_time, which start_time alone can't tell apart — pass the lot's own native
 *  wo_group_id (unique per physical lot) when known. */
export async function getWorkOrderPegging(
  caseId: number,
  params: { demand_id: string; product_id: string; location_id: string; method: string; start_time?: string | null; wo_group_id?: string | null; run_id?: number; demand_ids?: string[]; win_start?: string | null; win_end?: string | null }
): Promise<{ tree: PlanningPeggingNode }> {
  const sp = new URLSearchParams({
    demand_id: params.demand_id,
    product_id: params.product_id,
    location_id: params.location_id,
    method: params.method,
  });
  if (params.start_time) sp.set('start_time', params.start_time);
  if (params.wo_group_id) sp.set('wo_group_id', params.wo_group_id);
  if (params.run_id != null) sp.set('run_id', String(params.run_id));
  // For a cross-demand batched WO (demand_id blank), forward its constituent demands so the
  // endpoint can aggregate the original per-demand pegging nodes.
  if (params.demand_ids && params.demand_ids.length > 0) sp.set('demand_ids', params.demand_ids.join(','));
  if (params.win_start) sp.set('win_start', params.win_start);
  if (params.win_end) sp.set('win_end', params.win_end);
  const r = await fetch(`${API}/cases/${caseId}/plan/work-order-pegging?${sp.toString()}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Start async plan; returns job_id. Poll getPlanStatus(caseId, job_id) for progress and result. */
/**
 * Normalize method_selection so the backend always receives canonical
 * `max_methods` instead of the legacy `multiple` boolean. Mirrors the form
 * defaults: `multiple: false` (no max_methods) → 1; `multiple: true` (no
 * max_methods) → 2; nothing set → 2. Strips legacy `multiple` and the
 * obsolete `split_mechanism` (proportional split removed in favor of
 * waterfall) from the outgoing config so saved runs migrate forward.
 */
function normalizeMethodSelection(config: PlanningConfig | null | undefined): PlanningConfig | null | undefined {
  if (!config) return config;
  const ms = config.method_selection;
  if (!ms) {
    return { ...config, method_selection: { max_methods: 2 } };
  }
  // Drop legacy `multiple` + obsolete `split_mechanism`. Derive `max_methods` if absent.
  const { multiple: legacyMultiple, split_mechanism: _drop, ...rest } = ms as typeof ms & { split_mechanism?: unknown };
  void _drop;
  let max = rest.max_methods;
  if (typeof max !== 'number' || !Number.isFinite(max) || max < 1) {
    max = legacyMultiple === false ? 1 : 2;
  }
  return { ...config, method_selection: { ...rest, max_methods: max } };
}

export async function runPlanAsync(caseId: number, config?: PlanningConfig | null): Promise<{ job_id: string }> {
  const normalized = normalizeMethodSelection(config);
  const r = await fetch(`${API}/cases/${caseId}/plan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ config: normalized ?? undefined, async: true }),
  });
  if (r.status !== 202) {
    const text = await r.text();
    throw new Error(r.ok ? text : `Plan start failed: ${text}`);
  }
  const data = await r.json();
  if (!data?.job_id) throw new Error('No job_id in response');
  return { job_id: data.job_id };
}

export type PlanStatusResponse = {
  status: 'running' | 'completed' | 'failed';
  progress?: {
    current: number;
    total: number;
    /** Fixed-point iteration number (1-indexed) the planner is currently running. Absent when consolidation is off. */
    iteration?: number;
    /** Max iterations the fixed-point controller will run before giving up and falling back to single-pass trim. */
    iterations_max?: number;
  };
  result?: PlanResult;
  error?: string;
  plan_run_id?: number;
};

export async function getPlanStatus(caseId: number, jobId: string): Promise<PlanStatusResponse> {
  const r = await fetch(`${API}/cases/${caseId}/plan/status/${jobId}`);
  if (!r.ok) {
    const err = new Error(await r.text()) as Error & { status?: number };
    err.status = r.status;
    throw err;
  }
  return r.json();
}

export type PeggingSaveStatus = {
  run_id: number;
  chunks_done: number;
  chunks_total: number;
  pct: number;
};

/** Returns null when no background pegging save is in flight for this case. */
export async function getPeggingSaveStatus(caseId: number): Promise<PeggingSaveStatus | null> {
  const r = await fetch(`${API}/cases/${caseId}/plan/pegging-save-status`);
  if (r.status === 204) return null;
  if (!r.ok) return null;
  return r.json();
}

export type PlanningCopilotMessage = {
  role: 'user' | 'assistant';
  text: string;
  /** Tool-call steps the agent ran while producing this assistant turn (planning-agent only). */
  steps?: PlanningAgentStep[];
  /** plan_run_id if this assistant turn ran a fresh plan (planning-agent only). */
  fresh_run_id?: number | null;
  /** Special render mode. 'raw_picker' renders the interactive purchasable-raw-material
   *  selector (from the `/raw` slash command) instead of plain text. */
  kind?: 'raw_picker';
  /** Optional pre-applied text filter for the 'raw_picker' (from `/raw <filter>`). */
  filter?: string;
};

export type PlanningCopilotResponse = { reply: string; config_update: PlanningConfig | null };

export async function planningCopilot(
  caseId: number,
  message: string,
  currentConfig: PlanningConfig,
  history: PlanningCopilotMessage[]
): Promise<PlanningCopilotResponse> {
  const r = await fetch(`${API}/cases/${caseId}/planning-copilot`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, current_config: currentConfig, history }),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** One tool invocation the agent ran, surfaced in the chat as a gray "step" row. */
export type PlanningAgentStep = {
  tool: string;
  args: Record<string, unknown>;
  result_summary: string;
};

/**
 * Full agent response. Unlike copilot, the agent may have run a plan
 * (`fresh_run_id`), updated config (`config_update`), and recorded multiple
 * tool steps. The chat panel should render every step inline so the user
 * can see what the agent did.
 */
export type PlanningAgentResponse = {
  reply: string;
  steps: PlanningAgentStep[];
  config_update: PlanningConfig | null;
  fresh_run_id: number | null;
  /** When run_plan_async returned 'still_running' (plan exceeded the 25s
   *  blocking window), this carries the job_id so the chat panel can keep
   *  polling and post a completion message itself. */
  pending_job_id?: string | null;
};

export async function planningAgent(
  caseId: number,
  message: string,
  currentConfig: PlanningConfig,
  history: PlanningCopilotMessage[],
  /** The plan_run id the user is currently viewing on the page. Lets the agent
   *  default to this run when the user asks a run-scoped question without
   *  naming a number. Pass null when no run is selected. */
  viewingRunId: number | null
): Promise<PlanningAgentResponse> {
  const r = await fetch(`${API}/cases/${caseId}/planning-agent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message,
      current_config: currentConfig,
      // The agent's history shape mirrors the copilot's: role + content.
      history: history.map((m) => ({ role: m.role, content: m.text })),
      viewing_run_id: viewingRunId,
    }),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type ActivePlanJob = {
  job_id: string;
  status: string;
  progress: { current: number; total: number };
};

/**
 * Polled by the planning agent's chat panel while a `run_plan_async` call is
 * in flight (the chat HTTP request is blocked inside the 60-second
 * wait_for_plan window). Lets the chat surface live progress instead of a
 * generic spinner. Returns an empty list when no plan jobs are running for
 * this case.
 */
export async function listActivePlanJobs(caseId: number): Promise<ActivePlanJob[]> {
  const r = await fetch(`${API}/cases/${caseId}/plan/active-jobs`);
  if (!r.ok) throw new Error(await r.text());
  const body = await r.json();
  return (body?.jobs ?? []) as ActivePlanJob[];
}

export type PlanRun = {
  id: number;
  case_id: number;
  job_id: string | null;
  status: 'running' | 'success' | 'failed' | 'contingent';
  config: Record<string, unknown> | null;
  name: string | null;
  notes: string | null;
  is_initial?: boolean;
  is_active?: boolean;
  is_active_designated?: boolean;
  created_at: string;
  finished_at?: string | null;
  duration_ms?: number | null;
  chosen_depth?: number | null;
  attempts?: Array<{ depth: number; duration_ms: number }> | null;
  soundness_status?: 'unchecked' | 'checking' | 'sound' | 'unsound' | 'error';
  soundness_checked_at?: string | null;
  /** Free-form provenance — `{ bootstrap: true, preset_id, preset_label, ... }`
   *  for KB-seeded runs, undefined/null for user-driven. */
  metadata?: Record<string, unknown> | null;
  // Inline KPI snapshot (from kb_records when present, else parsed from
  // plan_run.result on the fly). Each is undefined when the run isn't
  // success-status or KPIs weren't computed.
  fill_rate_pct?: number;
  gini?: number;
  p10_fill_ratio?: number;
  median_fill_ratio?: number;
  starvation_pct?: number;
  on_time_count?: number;
  total_committed?: number;
  total_requested?: number;
  manufacturing_total_quantity?: number;
  inventory_consumed_total?: number;
};

export type SoundnessViolation = {
  rule: string;
  node_path: string;
  message: string;
  expected?: unknown;
  actual?: unknown;
};

export type SoundnessReport = {
  overall_sound: boolean;
  demand_count: number;
  sound_count: number;
  deep_check: boolean;
  demands: Array<{
    demand_id: string;
    sound: boolean;
    violations: SoundnessViolation[];
  }>;
  cross_demand_violations: SoundnessViolation[];
  wo_gid_orphan_violations?: SoundnessViolation[];
  resource_overload_violations?: SoundnessViolation[];
};

export type PlanRunEvent = {
  id: number;
  kind: 'created' | 'saved' | 'renamed' | 'promoted' | 'designated_active' | 'undesignated' | string;
  payload: Record<string, unknown> | null;
  created_at: string;
};

export type PlanRunFull = PlanRun & {
  result: PlanResult | null;
  error: string | null;
  events?: PlanRunEvent[];
};

export async function listPlanRuns(caseId: number): Promise<PlanRun[]> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getPlanRun(caseId: number, runId: number): Promise<PlanRunFull> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getPlanRunPegging(caseId: number, runId: number, demandId: string): Promise<{ planning_pegging: unknown[] }> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}/pegging/${encodeURIComponent(demandId)}`);
  if (!r.ok) throw new Error(await r.text());
  // Backend returns the raw pegging entry JSON; wrap it in an array to match the old bulk shape.
  const entry = await r.json();
  return { planning_pegging: [entry] };
}

/** Returns the current unsaved (status="ready") plan run with its in-memory result,
 *  or null if none exists or the server-side memory has expired. */
export async function getUnsavedPlanRun(caseId: number): Promise<PlanRunFull | null> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/unsaved`);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function deletePlanRun(caseId: number, runId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

export async function savePlanRun(
  caseId: number,
  runId: number,
  opts?: { name?: string; notes?: string; mode?: 'new' | 'override'; target_run_id?: number },
): Promise<{ id: number; status: string }> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(opts ?? {}),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function updatePlanRun(caseId: number, runId: number, data: { name?: string; notes?: string }): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!r.ok) throw new Error(await r.text());
}

export async function designateActivePlanRun(caseId: number, runId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}/designate-active`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
}

export async function clearDesignatedActivePlanRun(caseId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/designated-active`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

/** Run the soundness check synchronously and return the persisted report. */
export async function checkPlanRunSoundness(
  caseId: number,
  runId: number,
  opts?: { deep_check?: boolean },
): Promise<SoundnessReport> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}/check-soundness`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(opts ?? {}),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Fetch a previously computed soundness report. Returns null if never run. */
export async function getPlanRunSoundness(caseId: number, runId: number): Promise<SoundnessReport | null> {
  const r = await fetch(`${API}/cases/${caseId}/plan-runs/${runId}/soundness`);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getExplanations(caseId: number, runId: number, supplyId: string): Promise<{ supply_id: string; split: { demand_id: string; quantity: number; reason: string }[] }> {
  const r = await fetch(`${API}/cases/${caseId}/runs/${runId}/explanations?supply_id=${encodeURIComponent(supplyId)}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type AllocationExplanation = {
  component_key: string;
  component_display: string;
  weight_formula?: string;
  candidate_targets: { variant_key: string; variant_display: string; edge_type: string; target_weight?: number; weight_calculation?: string }[];
  total_candidate_weight?: number;
  steps: { to_variant_key: string; to_variant_display: string; qty: number; edge_type: string; available_before: number }[];
  total_supply_for_component: number;
  available_during_run?: number;
  reason: string;
  supply_note?: string;
};

export async function getAllocationExplanation(
  caseId: number,
  runId: number,
  componentKey: string,
  toVariantKey?: string
): Promise<AllocationExplanation> {
  const params = new URLSearchParams({ component_key: componentKey });
  if (toVariantKey) params.set('to_variant_key', toVariantKey);
  const r = await fetch(`${API}/cases/${caseId}/runs/${runId}/allocation-explanation?${params}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

const PEGGING_PARSE_TIMEOUT_MS = 15000;

export async function getPegging(
  caseId: number,
  runId: number,
  direction: 'demand-to-supply' | 'supply-to-demand',
  demandId?: string,
  supplyId?: string,
  verify = false
): Promise<{ direction: string; nodes: { id: string; label: string; type: string }[]; edges: { from: string; to: string; qty: number }[]; critical_path?: unknown; critical_paths_by_demand?: unknown; _verify?: { node_id: string; node_qty: number; demand_edge_sum: number; ok: boolean }[] }> {
  const params = new URLSearchParams({ direction });
  if (demandId) params.set('demand_id', demandId);
  if (supplyId) params.set('supply_id', supplyId);
  if (verify) params.set('verify', '1');
  const url = `${API}/cases/${caseId}/runs/${runId}/pegging?${params}`;
  const r = await fetchWithTimeout(url);
  if (!r.ok) throw new Error(await r.text());
  const json = await Promise.race([
    r.json(),
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error('Pegging response timeout (body/parse)')), PEGGING_PARSE_TIMEOUT_MS)
    ),
  ]);
  return json as { direction: string; nodes: { id: string; label: string; type: string }[]; edges: { from: string; to: string; qty: number }[]; critical_path?: unknown; critical_paths_by_demand?: unknown; _verify?: { node_id: string; node_qty: number; demand_edge_sum: number; ok: boolean }[] };
}

export async function getSupplyView(
  caseId: number,
  runId: number,
  opts?: { debug_component_key?: string; plan_run_id?: number },
): Promise<{ supply_view: SupplyViewRow[]; _debug?: Record<string, unknown>; source?: string; plan_run_id?: number }> {
  const params = new URLSearchParams();
  if (opts?.debug_component_key) params.set('debug_component_key', opts.debug_component_key);
  if (opts?.plan_run_id != null) params.set('plan_run_id', String(opts.plan_run_id));
  const qs = params.toString();
  const r = await fetchWithTimeout(`${API}/cases/${caseId}/runs/${runId}/supply-view${qs ? `?${qs}` : ''}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type RawMaterialInvolvementEntry = {
  role: 'critical' | 'companion';
  step: number;
  comp_key: string;
  pattern: string;
  /** True if this comp_key (product|location) has at least one Supply row; false if allocation came from move/production. */
  in_supply_view?: boolean;
  avail?: number;
  allocated?: boolean;
  breakdown?: { variant_key: string; output_qty: number }[];
  considered_but_skipped?: boolean;
  variant_key?: string;
  reason?: string;
  output_cap?: number;
  critical_component?: string;
  taken?: number;
  need_actual?: number;
  output_qty_actual?: number;
};

export type RawMaterialUsageReport = {
  run_id: number;
  supply_patterns_used: string[];
  summary: Record<string, { total_consumed_qty: number; node_count: number }>;
  details: { pattern: string; product_id: string; location_id: string; node: string; consumed_qty: number }[];
  involvement_trace?: RawMaterialInvolvementEntry[];
};

export async function getRawMaterialUsage(
  caseId: number,
  runId: number,
): Promise<RawMaterialUsageReport> {
  const r = await fetchWithTimeout(`${API}/cases/${caseId}/runs/${runId}/raw-material-usage`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getAllocationView(
  caseId: number,
  runId: number,
  opts?: { max_actions?: number; from_step?: number; to_step?: number; skip_basket?: boolean },
): Promise<{
  allocation_view: AllocationViewRow[];
  truncated?: boolean;
  total_actions?: number;
  limit?: number;
  total_steps?: number;
  from_step?: number | null;
  to_step?: number | null;
  basket_initial?: { key: string; display: string; qty: number }[];
  basket_deltas?: { purged: { key: string; display: string; qty: number }[]; added: { key: string; display: string; qty: number }[] }[];
  basket_final?: { key: string; display: string; qty: number }[] | null;
  basket_prunes?: { after_step: number; comp_keys: string[] }[];
}> {
  const params = new URLSearchParams();
  if (opts?.max_actions != null) params.set('max_actions', String(opts.max_actions));
  if (opts?.from_step != null) params.set('from_step', String(opts.from_step));
  if (opts?.to_step != null) params.set('to_step', String(opts.to_step));
  if (opts?.skip_basket != null) params.set('skip_basket', String(opts.skip_basket));
  const qs = params.toString();
  const url = `${API}/cases/${caseId}/runs/${runId}/allocation-view${qs ? `?${qs}` : ''}`;
  const r = await fetchWithTimeout(url);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getAllocationViewBasket(
  caseId: number,
  runId: number,
  opts?: { max_actions?: number },
): Promise<{
  basket_initial: { key: string; display: string; qty: number }[];
  basket_deltas: { purged: { key: string; display: string; qty: number }[]; added: { key: string; display: string; qty: number }[] }[];
  basket_final: { key: string; display: string; qty: number }[] | null;
  basket_prunes: { after_step: number; comp_keys: string[] }[];
}> {
  const params = new URLSearchParams();
  if (opts?.max_actions != null) params.set('max_actions', String(opts.max_actions));
  const qs = params.toString();
  const url = `${API}/cases/${caseId}/runs/${runId}/allocation-view-basket${qs ? `?${qs}` : ''}`;
  const r = await fetchWithTimeout(url);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type AllocationActionRow = {
  id: number;
  run_id: number;
  variant_key: string;
  req_component_ids: string[];
  qty: number;
  demand_id?: string;
  target_product_id?: string;
  target_location_id?: string;
  output_period?: number;
  edge_type?: string;
  scarcity_rank?: number;
};

export async function getAllocationActions(
  caseId: number,
  runId: number,
  opts?: { offset?: number; limit?: number },
): Promise<{ actions: AllocationActionRow[]; total_count: number; offset: number; limit: number }> {
  const params = new URLSearchParams();
  if (opts?.offset != null) params.set('offset', String(opts.offset));
  if (opts?.limit != null) params.set('limit', String(opts.limit));
  const qs = params.toString();
  const r = await fetchWithTimeout(`${API}/cases/${caseId}/runs/${runId}/allocation-actions${qs ? `?${qs}` : ''}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type BomGraphNode = {
  id: string;
  productId: string;
  locationId: string;
  productDescription: string | null;
  locationDescription: string | null;
  establishedBy: string[];
  isDemand: boolean;
};

export type BomGraphEdge = {
  id: string;
  source: string;
  target: string;
  edgeType: string;
  bomId: string | null;
  altGroup: string | null;
  rate: number | null;
  preference: number | null;
  leadDays: number | null;
};

export type BomGraphResponse = {
  nodes: BomGraphNode[];
  edges: BomGraphEdge[];
  nodeCount: number;
  edgeCount: number;
};

export async function getBomGraph(caseId: number): Promise<BomGraphResponse> {
  const r = await fetch(`${API}/cases/${caseId}/bom-graph`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// ── Planning Supply View ───────────────────────────────────────────────────────

/** A supply record belonging to a case — returned by GET /cases/{id}/supplies. */
export type CaseSupplyRow = {
  id: number;
  supplyId: string;
  productId: string;
  locationId: string | null;
  vendorId: string | null;
  supplyDate: string | null;
  qty: number;
  description: string | null;
};

/** A demand pegged to a supply, computed client-side from planning_pegging inversion. */
export type PeggedDemandEntry = {
  demandId: string;
  customer: string | null;
  qtyConsumed: number;
};

/** Consolidation split context for a supply — populated only when the supply was consumed
 *  by a consolidated work order (shared-component allocation). Lets the Supply View show
 *  HOW the shared supply was divided across the consolidation group's candidate demands. */
export type SupplySplitInfo = {
  /** "fair" | "proportional" | "priority_first" — policy that drove the split. */
  mode: string;
  /** Top-level product/location of the consolidated group (e.g., the FG or intermediate). */
  groupProductId: string;
  groupLocationId: string;
  /** Sum of requested_qty across the group's candidate demands. */
  groupTotalNeed: number;
  /** Qty actually produced for the group (what the consolidated WO delivered). */
  groupTotalProduced: number;
  /** Number of candidate demands in the consolidation group (including zero-share ones). */
  candidateCount: number;
  /**
   * Demand → allocated qty inside the consolidation group (from the pegging entry's
   * per_demand_allocations). Lets the Supply Explain panel show the math behind each
   * per-demand consumed qty: share % = allocated[d] / Σ allocated.
   */
  perDemandAllocations?: Record<string, number> | null;
  /** Source of the policy: "wo" (carried on a real consolidated WO) or "config" (raw-material
   *  fallback — no WO; uses the planning configuration's allocation_mode). */
  policySource?: 'wo' | 'config';
};

/** Enriched supply row for the Plan Supply View table (supply metadata + pegging aggregates). */
export type PlanSupplyViewRow = CaseSupplyRow & {
  consumedQty: number;
  residualQty: number;
  utilizationRate: number | null;
  peggedDemandCount: number;
  totalPeggedQty: number;
  peggedDemands: PeggedDemandEntry[];
  /**
   * Every consolidation group that drew from this supply. A single raw-material supply is
   * commonly consumed by multiple merged-leaf groups (each merged leaf's plan() walks down
   * to shared raw-material inventory), so this is an array, not a single entry. Empty when
   * the supply is consumed only by non-consolidated demands.
   */
  splitInfos: SupplySplitInfo[];
  /**
   * demand_id → "<groupPid>@<groupLid>" describing the consolidation path each pegged demand
   * took to reach this supply. Covers passthrough singletons + multi-demand groups. Demands
   * absent from this map consumed via the main-loop (direct walk, no consolidation).
   */
  demandPath: Record<string, string>;
};

/** Fetch all supply records for a case (flat table scan, no run context needed). */
export async function getCaseSupplies(caseId: number): Promise<CaseSupplyRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/supplies`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// ── Material Events ────────────────────────────────────────────────────────────

export type MaterialEvent = {
  id: number;
  caseId: number;
  supplyId: string;
  delayDays: number;
  qtyDecreasePct: number;
  qtyDecreaseAbs: number | null;
  note: string | null;
  createdAt: string;
};

export type MaterialImpactedDemand = {
  demandId: string;
  productId: string;
  locationId: string | null;
  customerId: string;
  description: string | null;
  priority: number | null;
  requestDueTime: string | null;
  requestedQty: number;
  consumedSupplyQty: number;
  status: string; // "newly_failed" | "qty_reduced" | "delayed" | "at_risk"
  // Re-plan diff fields
  baselineCommittedQty: number;
  contingentCommittedQty: number;
  qtyDelta: number;
  baselineFailed: boolean;
  contingentFailed: boolean;
  contingentCommitReason: string | null;
};

export type MaterialImpactResult = {
  caseId: number;
  planRunId: number | null;
  contingentPlanRunId: number | null;
  supply: {
    supplyId: string;
    productId: string;
    qty: number;
    supplyDate: string | null;
    locationId: string | null;
    vendorId: string | null;
  };
  deliveryDelayDays: number;
  quantityDecreasePct: number;
  impactedDemandCount: number;
  impacts: MaterialImpactedDemand[];
  note: string | null;
};

export async function listMaterialEvents(caseId: number): Promise<MaterialEvent[]> {
  const r = await fetch(`${API}/cases/${caseId}/material-events`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function createMaterialEvent(
  caseId: number,
  body: { supplyId: string; delayDays: number; qtyDecreasePct: number; qtyDecreaseAbs?: number | null; note?: string | null },
): Promise<MaterialEvent> {
  const r = await fetch(`${API}/cases/${caseId}/material-events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ supplyId: body.supplyId, delayDays: body.delayDays, qtyDecreasePct: body.qtyDecreasePct, qtyDecreaseAbs: body.qtyDecreaseAbs ?? null, note: body.note }),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function updateMaterialEvent(
  caseId: number,
  eventId: number,
  body: { supplyId: string; delayDays: number; qtyDecreasePct: number; qtyDecreaseAbs?: number | null; note?: string | null },
): Promise<MaterialEvent> {
  const r = await fetch(`${API}/cases/${caseId}/material-events/${eventId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ supplyId: body.supplyId, delayDays: body.delayDays, qtyDecreasePct: body.qtyDecreasePct, qtyDecreaseAbs: body.qtyDecreaseAbs ?? null, note: body.note }),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function deleteMaterialEvent(caseId: number, eventId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/material-events/${eventId}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

export type MaterialImpactProgress = {
  current: number;
  total: number;
  iteration?: number;
  iterations_max?: number;
};

export async function analyzeMaterialImpact(
  supplyId: string,
  deliveryDelayDays: number,
  quantityDecreasePct: number,
  persist = true,
  quantityDecreaseAbs?: number | null,
  onProgress?: (p: MaterialImpactProgress) => void,
): Promise<MaterialImpactResult> {
  // 1. Submit async re-plan job
  const submit = await fetch(`${API}/material-impact`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ supplyId, deliveryDelayDays, quantityDecreasePct, quantityDecreaseAbs: quantityDecreaseAbs ?? null, persist }),
  });
  if (!submit.ok) throw new Error(await submit.text());
  const { jobId } = await submit.json();
  if (!jobId) throw new Error('No jobId returned from material-impact');

  // 2. Poll until completed. Fast initial cadence so progress feels live, then
  // back off; total budget is generous because v2 fixed-point iteration can
  // run 5x for hard cases.
  let delay = 500;
  const maxDelay = 2000;
  const maxWait = 600_000; // 10 min
  const start = Date.now();
  while (Date.now() - start < maxWait) {
    await new Promise(res => setTimeout(res, delay));
    delay = Math.min(delay * 2, maxDelay);

    const poll = await fetch(`${API}/material-impact/status/${encodeURIComponent(jobId)}`);
    if (!poll.ok) throw new Error(`Poll failed: ${poll.status}`);
    const body = await poll.json();
    if (body.progress && onProgress) onProgress(body.progress as MaterialImpactProgress);
    if (body.status === 'completed') return body.result as MaterialImpactResult;
    if (body.status === 'failed') throw new Error(body.error ?? 'Re-plan job failed');
  }
  throw new Error('Material impact analysis timed out');
}

// ── WO Schedule Events ─────────────────────────────────────────────────────────

/**
 * Bulk WO schedule shift selector. UI assembles this after the user picks a
 * concrete set of WOs from a filterable preview; the backend trusts woGroupIds
 * verbatim. bucketStart anchors `delayToDate` math: shift = delayToDate - bucketStart.
 */
export type WoScheduleSelector = {
  /** ISO yyyy-MM-dd. UI default: min(start_time) over selected WOs. */
  bucketStart: string;
  /** Concrete WOs picked from the preview list. Must be non-empty. */
  woGroupIds: string[];
};

export type WoScheduleEvent = {
  id: number;
  caseId: number;
  selectors: WoScheduleSelector[];
  delayDays: number | null;
  delayToDate: string | null;
  note: string | null;
  createdAt: string;
};

export type WoImpactedDemand = {
  demandId: string;
  productId: string;
  locationId: string | null;
  customerId: string;
  description: string | null;
  priority: number | null;
  requestDueTime: string | null;
  requestedQty: number;
  baselineCommitTime: string | null;
  contingentCommitTime: string | null;
  daysDelta: number;
  status: 'delayed' | 'no_change';
};

export type WoAvailabilityBottleneck = {
  /** "boundary" = parent-child edge slack; "demand_root" = demand commit constraint. */
  kind: 'boundary' | 'demand_root';
  gid: string;
  parentGid?: string | null;
  demandId?: string | null;
  slackDays: number;
};

export type WoScheduleImpactResult = {
  caseId: number;
  planRunId: number | null;
  contingentPlanRunId: number | null;
  matchedWoCount: number;
  delayDays: number | null;
  delayToDate: string | null;
  impactedDemandCount: number;
  impacts: WoImpactedDemand[];
  note: string | null;
  /** Closed-form max safe delay for the same selectors (front-of-bucket
   *  displacement that leaves every demand commit_time unchanged). Populated
   *  by the impact endpoint at no extra cost. */
  maxFeasibleDays?: number | null;
  bottlenecks?: WoAvailabilityBottleneck[];
};

export type WoAvailabilityRequest = {
  selectors: WoScheduleSelector[];
  planRunId?: number | null;
  caseId?: number | null;
};

export type WoAvailabilityResult = {
  caseId: number;
  planRunId: number | null;
  matchedWoCount: number;
  maxFeasibleDays: number;
  bottlenecks: WoAvailabilityBottleneck[];
  bottleneckDemands: WoImpactedDemand[];
};

export type WoScheduleImpactRequest = {
  selectors: WoScheduleSelector[];
  delayDays?: number | null;
  delayToDate?: string | null;
  planRunId?: number | null;
  caseId?: number | null;
  persist?: boolean;
  note?: string | null;
  /** When set, tags the resulting contingent plan run back to the saved event so
   *  it appears in the per-event run-history endpoint. */
  woScheduleEventId?: number | null;
};

/** A historical analysis run linked to a saved WO schedule event. */
export type WoScheduleRun = {
  planRunId: number;
  baselinePlanRunId: number | null;
  createdAt: string;
  matchedWoCount: number;
  impactedDemandCount: number;
  delayDays: number | null;
  delayToDate: string | null;
  note: string | null;
  status: string; // "contingent" | "success" (after promotion)
  impacts: WoImpactedDemand[];
};

export async function listWoScheduleEvents(caseId: number): Promise<WoScheduleEvent[]> {
  const r = await fetch(`${API}/cases/${caseId}/wo-schedule-events`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function createWoScheduleEvent(
  caseId: number,
  body: { selectors: WoScheduleSelector[]; delayDays?: number | null; delayToDate?: string | null; note?: string | null },
): Promise<WoScheduleEvent> {
  const r = await fetch(`${API}/cases/${caseId}/wo-schedule-events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      selectors: body.selectors,
      delayDays: body.delayDays ?? null,
      delayToDate: body.delayToDate ?? null,
      note: body.note ?? null,
    }),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function updateWoScheduleEvent(
  caseId: number,
  eventId: number,
  body: { selectors: WoScheduleSelector[]; delayDays?: number | null; delayToDate?: string | null; note?: string | null },
): Promise<WoScheduleEvent> {
  const r = await fetch(`${API}/cases/${caseId}/wo-schedule-events/${eventId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      selectors: body.selectors,
      delayDays: body.delayDays ?? null,
      delayToDate: body.delayToDate ?? null,
      note: body.note ?? null,
    }),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function deleteWoScheduleEvent(caseId: number, eventId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/wo-schedule-events/${eventId}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

export async function listWoScheduleRuns(caseId: number, eventId: number): Promise<WoScheduleRun[]> {
  const r = await fetch(`${API}/cases/${caseId}/wo-schedule-events/${eventId}/runs`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Synchronous closed-form availability probe — sub-millisecond on the backend. */
export async function analyzeWoAvailability(req: WoAvailabilityRequest): Promise<WoAvailabilityResult> {
  const r = await fetch(`${API}/wo-schedule-impact/availability`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function analyzeWoScheduleImpact(
  req: WoScheduleImpactRequest,
  onProgress?: (p: { current: number; total: number }) => void,
): Promise<WoScheduleImpactResult> {
  const submit = await fetch(`${API}/wo-schedule-impact`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!submit.ok) throw new Error(await submit.text());
  const { jobId } = await submit.json();
  if (!jobId) throw new Error('No jobId returned from wo-schedule-impact');

  let delay = 500;
  const maxDelay = 2000;
  const maxWait = 120_000; // 2 min — sequencing-only path is fast
  const start = Date.now();
  while (Date.now() - start < maxWait) {
    await new Promise(res => setTimeout(res, delay));
    delay = Math.min(delay * 2, maxDelay);
    const poll = await fetch(`${API}/wo-schedule-impact/status/${encodeURIComponent(jobId)}`);
    if (!poll.ok) throw new Error(`Poll failed: ${poll.status}`);
    const body = await poll.json();
    if (body.progress && onProgress) onProgress(body.progress as { current: number; total: number });
    if (body.status === 'completed') return body.result as WoScheduleImpactResult;
    if (body.status === 'failed') throw new Error(body.error ?? 'WO schedule impact job failed');
  }
  throw new Error('WO schedule impact analysis timed out');
}

// ── Material Impact Assessment ─────────────────────────────────────────────────

export type AssessmentSummary = {
  id: number;
  supplyId: string;
  deliveryDelayDays: number;
  quantityDecreasePct: number;
  rating: 'LOW' | 'MEDIUM' | 'HIGH';
  explanation: string;
  criteria: string;
  createdAt: string;
};

export type AssessmentResponse = {
  id: number;
  rating: 'LOW' | 'MEDIUM' | 'HIGH';
  explanation: string;
  criteria: string;
  caseId: number;
  planRunId: number | null;
  supply: {
    supplyId: string;
    productId: string;
    qty: number;
    supplyDate: string | null;
    locationId: string | null;
    vendorId: string | null;
  };
  impactedDemandCount: number;
  impacts: MaterialImpactedDemand[];
  createdAt: string;
};

/** Get the assessment criteria text stored for a case (null = not set). */
export async function getAssessmentCriteria(caseId: number): Promise<string | null> {
  const r = await fetch(`${API}/cases/${caseId}/assessment-criteria`);
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return (data as { criteria: string | null }).criteria ?? null;
}

/** Persist updated assessment criteria for a case. */
export async function setAssessmentCriteria(caseId: number, criteria: string): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/assessment-criteria`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ criteria }),
  });
  if (!r.ok) throw new Error(await r.text());
}

/** List past assessments for a case, optionally filtered by supplyId. */
export async function listAssessments(caseId: number, supplyId?: string): Promise<AssessmentSummary[]> {
  const sp = new URLSearchParams();
  if (supplyId) sp.set('supplyId', supplyId);
  const q = sp.toString() ? `?${sp}` : '';
  const r = await fetch(`${API}/cases/${caseId}/material-impact-assessments${q}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// ── Negotiation Chain ─────────────────────────────────────────────────────────

export type NegotiationChainEntry = {
  planRunId: number;
  round: number | null;
  parentPlanRunId: number | null;
  supersededByPlanRunId: number | null;
  status: string;
  supplyId: string | null;
  deliveryDelayDays: number | null;
  quantityDecreasePct: number | null;
  baselinePlanRunId: number | null;
  rating: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  explanation: string | null;
  createdAt: string;
};

export async function getNegotiationChain(
  caseId: number,
  baselinePlanRunId: number,
): Promise<NegotiationChainEntry[]> {
  const r = await fetch(`${API}/cases/${caseId}/negotiation-chains/${baselinePlanRunId}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type NegotiationReplyAction = 'keep' | 'abandon' | 'counter';

export type NegotiationReplyPayload = {
  sessionKey: string;
  action: NegotiationReplyAction;
  round?: number;
  delayDays?: number;
  qtyPct?: number;
  baselinePlanRunId?: number;
  contingentPlanRunId?: number;
  supplyId?: string;
};

export async function sendNegotiationReply(
  caseId: number,
  payload: NegotiationReplyPayload,
): Promise<{ status: string; sessionKey: string; action: string }> {
  const r = await fetch(`${API}/cases/${caseId}/negotiation-reply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export type NegotiationWait = {
  id: number;
  caseId: number;
  sessionKey: string;
  round: number;
  rating: 'LOW' | 'MEDIUM' | 'HIGH';
  explanation: string | null;
  currentDelayDays: number;
  currentQtyPct: number;
  baselinePlanRunId: number;
  contingentPlanRunId: number | null;
  supplyId: string;
  impactedDemandCount: number;
  createdAt: string;
  resolvedAt: string | null;
  resolvedAction: string | null;
};

export async function getActiveNegotiationWait(
  caseId: number,
  baselinePlanRunId?: number,
): Promise<NegotiationWait | null> {
  const sp = new URLSearchParams();
  if (baselinePlanRunId != null) sp.set('baselinePlanRunId', String(baselinePlanRunId));
  const q = sp.toString() ? `?${sp}` : '';
  const r = await fetch(`${API}/cases/${caseId}/negotiation-waits/active${q}`);
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data === null ? null : (data as NegotiationWait);
}

export async function listNegotiationWaits(
  caseId: number,
  baselinePlanRunId?: number,
): Promise<NegotiationWait[]> {
  const sp = new URLSearchParams();
  if (baselinePlanRunId != null) sp.set('baselinePlanRunId', String(baselinePlanRunId));
  const q = sp.toString() ? `?${sp}` : '';
  const r = await fetch(`${API}/cases/${caseId}/negotiation-waits${q}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// ──────────────────────────────────────────────────────────────────────────────

/** Run a new assessment.
 *  Mode B (preferred): pass `impact` to skip re-computation and use the already-run re-plan result.
 *  Mode A (fallback):  omit `impact`; backend computes impact via pegging-tree walk (less accurate).
 */
export async function runAssessment(
  caseId: number,
  supplyId: string,
  deliveryDelayDays: number,
  quantityDecreasePct: number,
  planRunId?: number | null,
  impact?: MaterialImpactResult | null,
  quantityDecreaseAbs?: number | null,
  locale?: string | null,
): Promise<AssessmentResponse> {
  const body: Record<string, unknown> = { supplyId, deliveryDelayDays, quantityDecreasePct, caseId };
  if (planRunId != null) body.planRunId = planRunId;
  if (impact != null) body.impact = impact;
  if (quantityDecreaseAbs != null && quantityDecreaseAbs > 0) body.quantityDecreaseAbs = quantityDecreaseAbs;
  if (locale) body.locale = locale;
  const r = await fetchWithTimeout(
    `${API}/material-impact-assessment`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) },
    120_000,
  );
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// ── Case bootstrap (KB seeding) ─────────────────────────────────────────────

export type BootstrapPreset = {
  preset_id: string;
  preset_label: string;
  preset_index: number;
  primary_axis: string;
  config: Record<string, unknown>;
  /** Set on `next_batch[]` entries: this preset's config is already in the KB,
   *  so submitting it unchanged will be skipped at the dedup step. The dialog
   *  surfaces an "in KB" hint and offers Edit-config to make it unique. */
  already_covered?: boolean;
  // Present only on items in `already_run[]` — carries the KB record id (for
  // delete) + the source plan_run pointer (may be deleted) + headline KPIs.
  // KB rows are dissociated from plan_runs; the source link can be severed
  // without losing this row.
  kb_record_id?: number;
  plan_run_id?: number;
  source_plan_run_deleted?: boolean;
  soundness_status?: string;
  fill_rate_pct?: number;
  gini?: number;
  p10_fill_ratio?: number;
  median_fill_ratio?: number;
  starvation_pct?: number;
  on_time_count?: number;
  total_committed?: number;
  total_requested?: number;
  manufacturing_total_quantity?: number;
  inventory_consumed_total?: number;
};

export type BootstrapPreview = {
  library_size: number;
  already_run_count: number;
  remaining_count: number;
  /** Total KB rows on this case (library + user-driven). Backend addition;
   *  may be undefined when talking to an older backend. */
  kb_record_count?: number;
  batch_size: number;
  already_run: BootstrapPreset[];
  next_batch: BootstrapPreset[];
  /** Axis-level metadata for the dialog's next-batch UI. One entry per
   *  knob the user can vary off the baseline. */
  axes?: BootstrapAxisSpec[];
};

export type BootstrapAxisSpec = {
  /** Canonical knob id (matches the backend's switch in buildConfigForAxisValue). */
  name: string;
  /** Display label. */
  label: string;
  /** Short help text. */
  description: string;
  /** "int" | "bool" | "enum" — drives the input widget. */
  value_type: 'int' | 'bool' | 'enum';
  /** Populated for value_type='enum'. */
  enum_values?: string[];
  /** Value at the baseline — shown as a "varies from X" hint. */
  baseline_value: unknown;
  /** Suggested initial value when the user enables this axis (next-uncovered). */
  default_seed: unknown;
  /** Values the curated library enumerates (datalist suggestions). */
  variations: unknown[];
  /** Group id — axes in the same group share a collapsible header in the
   *  dialog. Captures logical dependencies (e.g. consolidation cluster). */
  group: string;
};

export type BootstrapStartResponse =
  | { status: 'library_exhausted'; library_size: number; message: string }
  | { status: 'all_already_covered'; library_size: number; message: string; skipped: BootstrapPreset[] }
  | { bootstrap_job_id: string; total: number; presets: BootstrapPreset[]; skipped: BootstrapPreset[] };

export type BootstrapJobStatus = {
  status: 'running' | 'completed' | 'cancelled' | 'unknown';
  total: number;
  completed: number;
  current_preset_id: string;
  current_preset_label: string;
  cancelled?: boolean;
  plan_run_ids: number[];
  errors: string[];
};

/** "Best run" criterion that drives the suggestion seed in the KB dialog.
 *  • fill_rate → seed = highest fill_rate_pct (tiebreak gini asc)
 *  • fairness  → seed = lowest gini (tiebreak fill_rate desc)
 *  • pareto    → seed = balanced winner: max (fill_rate_pct/100 - gini) */
export type BootstrapCriterion = 'fill_rate' | 'fairness' | 'pareto';

export async function getBootstrapPreview(
  caseId: number,
  batchSize = 5,
  criterion: BootstrapCriterion = 'fill_rate',
): Promise<BootstrapPreview> {
  const r = await fetch(`${API}/cases/${caseId}/bootstrap?batch_size=${batchSize}&criterion=${criterion}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Start a KB expansion batch.
 *  When `presets` is provided, those override the system's selectNextBatch —
 *  use this when the user has reviewed/edited the suggested configs in the
 *  KB dialog. Each entry needs preset_id, preset_label, primary_axis, config.
 *  When omitted, the server picks the next round-robin batch from the library. */
export async function startBootstrap(
  caseId: number,
  batchSize = 5,
  presets?: Array<Pick<BootstrapPreset, 'preset_id' | 'preset_label' | 'primary_axis' | 'config'>>,
): Promise<BootstrapStartResponse> {
  const body: Record<string, unknown> = { batch_size: batchSize };
  if (presets && presets.length > 0) body.presets = presets;
  const r = await fetch(`${API}/cases/${caseId}/bootstrap`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getBootstrapJobStatus(caseId: number, jobId: string): Promise<BootstrapJobStatus> {
  const r = await fetch(`${API}/cases/${caseId}/bootstrap/status/${jobId}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

/** Interrupt a running KB expansion job. Cooperative — the currently running
 *  preset finishes; subsequent presets are skipped. Job ends in status=cancelled. */
export async function cancelBootstrap(caseId: number, jobId: string): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/bootstrap/cancel/${jobId}`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
}

/** Delete a KB record. Does NOT touch the source plan_run (KB is dissociated). */
export async function deleteKbRecord(caseId: number, recordId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/kb-records/${recordId}`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

// ── Case demands (lightweight) ─────────────────────────────────────────────────

export type CaseDemandRow = {
  demand_id: string;
  customer_id: string;
  product_id: string;
  location_id: string | null;
  quantity: number;
  request_due_time: string | null;
};

export async function getCaseDemands(caseId: number): Promise<CaseDemandRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/demands`);
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.demands as CaseDemandRow[];
}

// ── Allocation map ─────────────────────────────────────────────────────────────

export type AllocationRow = {
  supply_id: string;
  demand_id: string | null;
  qty_allocated: number;
};

/** GET /cases/{id}/allocation — null when no allocation exists yet (204). */
export async function getAllocation(caseId: number): Promise<AllocationRow[] | null> {
  const r = await fetch(`${API}/cases/${caseId}/allocation`);
  if (r.status === 204) return null;
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as AllocationRow[];
}

/** POST /cases/{id}/allocation/generate — runs SupplyAllocator and saves result. */
export async function generateAllocation(caseId: number): Promise<AllocationRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/allocation/generate`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as AllocationRow[];
}

/** PUT /cases/{id}/allocation — upsert (partial or full) rows. */
export async function updateAllocationRows(caseId: number, rows: AllocationRow[]): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/allocation`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rows }),
  });
  if (!r.ok) throw new Error(await r.text());
}

/** DELETE /cases/{id}/allocation — clear all rows. */
export async function deleteAllocation(caseId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/allocation`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

/** POST /cases/{id}/allocation/import — upload CSV, replace all rows. */
export async function importAllocationCsv(caseId: number, csvText: string): Promise<AllocationRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/allocation/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: csvText,
  });
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as AllocationRow[];
}

/** GET /cases/{id}/allocation/export — download CSV text. */
export async function exportAllocationCsv(caseId: number): Promise<string> {
  const r = await fetch(`${API}/cases/${caseId}/allocation/export`);
  if (!r.ok) throw new Error(await r.text());
  return r.text();
}

// ── Preferences KB ───────────────────────────────────────────────────────────────

export type PreferenceRow = {
  product_id: string;
  location_id: string;
  method_type: 'make' | 'move' | 'purchase';
  method_key: string;
  preference: number;
  inventory_score: number | null;
  delivery_score: number | null;
  prod_area: string | null;
};

export type PreferenceConfig = {
  max_bom_depth: number;
  delivery_weight: number;
  inventory_weight: number;
  generated_at: string;
};

export type PreferenceGenerateParams = {
  max_bom_depth: number;
  delivery_weight: number;
  inventory_weight: number;
};

/** GET /cases/{id}/preferences — null when no Preferences KB exists yet (204). */
export async function getPreferences(caseId: number): Promise<{ rows: PreferenceRow[]; config: PreferenceConfig | null } | null> {
  const r = await fetch(`${API}/cases/${caseId}/preferences`);
  if (r.status === 204) return null;
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return { rows: data.rows as PreferenceRow[], config: (data.config ?? null) as PreferenceConfig | null };
}

/** POST /cases/{id}/preferences/generate — builds the KB from case data and saves it. */
export async function generatePreferences(caseId: number, params: PreferenceGenerateParams): Promise<PreferenceRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/preferences/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as PreferenceRow[];
}

/** PUT /cases/{id}/preferences — upsert edited preference values by natural key. */
export async function updatePreferenceRows(caseId: number, rows: PreferenceRow[]): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/preferences`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rows }),
  });
  if (!r.ok) throw new Error(await r.text());
}

/** DELETE /cases/{id}/preferences — clear the KB. */
export async function deletePreferences(caseId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/preferences`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

/** POST /cases/{id}/preferences/import — upload CSV, replace all rows. */
export async function importPreferencesCsv(caseId: number, csvText: string): Promise<PreferenceRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/preferences/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: csvText,
  });
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as PreferenceRow[];
}

/** GET /cases/{id}/preferences/export — download CSV text. */
export async function exportPreferencesCsv(caseId: number): Promise<string> {
  const r = await fetch(`${API}/cases/${caseId}/preferences/export`);
  if (!r.ok) throw new Error(await r.text());
  return r.text();
}

// ── Demand Ordering ─────────────────────────────────────────────────────────────

export type DemandOrderRow = {
  demand_id: string;
  order: number;
  request_due_time: string | null;
  priority: number;
  product_id: string;
  customer_id: string;
};

export type DemandOrderConfig = {
  generated_at: string;
};

/** GET /cases/{id}/demand-ordering — null when no Demand Ordering KB exists yet (204). */
export async function getDemandOrdering(caseId: number): Promise<{ rows: DemandOrderRow[]; config: DemandOrderConfig | null } | null> {
  const r = await fetch(`${API}/cases/${caseId}/demand-ordering`);
  if (r.status === 204) return null;
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return { rows: data.rows as DemandOrderRow[], config: (data.config ?? null) as DemandOrderConfig | null };
}

/** POST /cases/{id}/demand-ordering/generate — builds the order from case data and saves it. */
export async function generateDemandOrdering(caseId: number): Promise<DemandOrderRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/demand-ordering/generate`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as DemandOrderRow[];
}

/** PUT /cases/{id}/demand-ordering — upsert edited order values by demand_id. */
export async function updateDemandOrderRows(caseId: number, rows: { demand_id: string; order: number }[]): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/demand-ordering`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rows }),
  });
  if (!r.ok) throw new Error(await r.text());
}

/** DELETE /cases/{id}/demand-ordering — clear the KB. */
export async function deleteDemandOrdering(caseId: number): Promise<void> {
  const r = await fetch(`${API}/cases/${caseId}/demand-ordering`, { method: 'DELETE' });
  if (!r.ok) throw new Error(await r.text());
}

/** POST /cases/{id}/demand-ordering/import — upload CSV, replace all rows. */
export async function importDemandOrderingCsv(caseId: number, csvText: string): Promise<DemandOrderRow[]> {
  const r = await fetch(`${API}/cases/${caseId}/demand-ordering/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: csvText,
  });
  if (!r.ok) throw new Error(await r.text());
  const data = await r.json();
  return data.rows as DemandOrderRow[];
}

/** GET /cases/{id}/demand-ordering/export — download CSV text. */
export async function exportDemandOrderingCsv(caseId: number): Promise<string> {
  const r = await fetch(`${API}/cases/${caseId}/demand-ordering/export`);
  if (!r.ok) throw new Error(await r.text());
  return r.text();
}
