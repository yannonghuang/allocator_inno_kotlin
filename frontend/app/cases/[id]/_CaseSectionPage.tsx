'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
  getCase,
  getFeasibleDemands,
  listRuns,
  runAllocate,
  pollRunUntilComplete,
  listOverrides,
  addOverride,
  deleteOverride,
  upsertOverride,
  getSupplyView,
  getRawMaterialUsage,
  type RawMaterialUsageReport,
  getAllocationView,
  getAllocationViewBasket,
  getAllocationActions,
  runPlan,
  runPlanAsync,
  getPlanStatus,
  planningCopilot,
  planningAgent,
  listActivePlanJobs,
  type ActivePlanJob,
  listPlanRuns,
  // Post-response polling: chat reuses getPlanStatus to track plans that
  // exceeded the agent's 25s blocking window.
  getPlanRun,
  deletePlanRun,
  type PlanRun,
  type PlanRunFull,
  type PlanningConfig,
  type PlanningCopilotMessage,
  type CommittedDemand,
  type WorkOrder,
  type PlanningPeggingNode,
  type PlanningPeggingEntry,
  type PlanKpis,
  type AllocationActionRow,
  getAllocationExplanation,
  getPegging,
  getBomRealPairs,
  getMovesWithTransit,
  getWorkOrderPegging,
  type Case as CaseType,
  type AllocationRun as RunType,
  type FeasibleDemand,
  type ManualOverride as OverrideType,
  type SupplyViewRow,
  type AllocationViewRow,
  type AllocationExplanation,
  type AllocationProgress,
  type MaterialEvent,
  type MaterialImpactResult,
  type MaterialImpactProgress,
  listMaterialEvents,
  createMaterialEvent,
  updateMaterialEvent,
  deleteMaterialEvent,
  analyzeMaterialImpact,
  type CaseSupplyRow,
  type PeggedDemandEntry,
  type PlanSupplyViewRow,
  type SupplyLevelAllocation,
  type SupplySplitInfo,
  getCaseSupplies,
  type AssessmentSummary,
  type AssessmentResponse,
  getAssessmentCriteria,
  setAssessmentCriteria,
  listAssessments,
  runAssessment,
  savePlanRun,
  updatePlanRun,
  designateActivePlanRun,
  clearDesignatedActivePlanRun,
  checkPlanRunSoundness,
  type SoundnessReport,
  type PlanRunEvent,
  type PlanSupplyAllocation,
  getBootstrapPreview,
  startBootstrap,
  getBootstrapJobStatus,
  cancelBootstrap,
  deleteKbRecord,
  type BootstrapPreset,
  type BootstrapPreview,
  type BootstrapJobStatus,
  type BootstrapCriterion,
} from '@/lib/api';
import { computeHorizon, ScheduleBar, ScheduleHorizonRuler } from './_workOrderSchedule';

type SupplySuggestion = {
  id: string;
  description: string | null;
  productId: string;
  supplyDate: string | null;
  qty: number;
  vendorId: string | null;
  locationId: string | null;
};

/** True if the pegging tree has any make WO that the backend marked as involving a real (non-virtual) BOM link.
 *  Fallback: if backend flag is missing, use BOM (parent, child) pairs from bomRealPairKeys.
 *  A make at location "VIRTUAL" is never considered real. */
function peggingTreeContainsRealMake(node: PlanningPeggingNode, bomRealPairKeys: Set<string>): boolean {
  if (node.type === 'work_order' && (node.method ?? '').toLowerCase() === 'make') {
    // Any make at a physical (non-VIRTUAL) location is a real make operation.
    // VIRTUAL locations are used only for synthetic BOM alt-group intermediate nodes.
    const loc = (node.location_id ?? '').trim().toUpperCase();
    if (loc !== 'VIRTUAL') return true;
  }
  for (const child of node.children ?? []) {
    if (peggingTreeContainsRealMake(child, bomRealPairKeys)) return true;
  }
  return false;
}

/** True if the pegging tree has any work order with method 'purchase' (buy). */
function peggingTreeContainsPurchase(node: PlanningPeggingNode): boolean {
  if (node.type === 'work_order' && (node.method ?? '').toLowerCase() === 'purchase') {
    return true;
  }
  for (const child of node.children ?? []) {
    if (peggingTreeContainsPurchase(child)) return true;
  }
  return false;
}

/** True if the actual pegging tree has at least one site where the planner had to
 *  pick between alternatives (and used >1 of them). Two such sites:
 *    1. demand → multiple work_order children — the waterfall actually fired and
 *       used multiple methods to fulfill the demand (max_methods >= 2 realized).
 *    2. work_order → multiple children with relation = 'or' — the planner used
 *       multiple BOM alt_group variants (OR-relation, not AND-relation).
 *  Rules out static-BOM-only alternatives where the planner picked a single
 *  candidate; this filter shows demands whose pegging actually exercises the
 *  alternatives. */
function peggingTreeHasAlternatives(node: PlanningPeggingNode): boolean {
  if (node.type === 'demand') {
    const woChildren = (node.children ?? []).filter((c) => c.type === 'work_order');
    if (woChildren.length > 1) return true;
  }
  if (node.type === 'work_order' && node.children_relation === 'or' && (node.children ?? []).length > 1) {
    return true;
  }
  for (const child of node.children ?? []) {
    if (peggingTreeHasAlternatives(child)) return true;
  }
  return false;
}

/** True if the pegging tree has any move WO whose (product_id, from_location, to_location) is in realMoveKeys (TRANSIT_TIME > 0). */
function peggingTreeContainsRealMove(node: PlanningPeggingNode, realMoveKeys: Set<string>): boolean {
  if (node.type === 'work_order' && (node.method ?? '').toLowerCase() === 'move') {
    const productId = (node.product_id ?? '').trim();
    const toId = (node.location_id ?? '').trim();
    const fromId = (node.location_source ?? '').trim();
    if (productId && realMoveKeys.has(`${productId}|${fromId}|${toId}`)) return true;
  }
  for (const child of node.children ?? []) {
    if (peggingTreeContainsRealMove(child, realMoveKeys)) return true;
  }
  return false;
}

/** Renders Plan KPIs when plan result exists; builds kpis from backend plan_kpis or derives from committed_demands/work_orders. */
/**
 * Mirror of the backend's computeFairness (Allocate.kt). Used by the fallback
 * path when plan_kpis is missing — keeps the Fairness card populated for
 * legacy runs. Same formulas, same null semantics.
 */
function computeFairnessFromCommitted(committed: CommittedDemand[]): NonNullable<PlanKpis['fairness']> {
  const fills: number[] = [];
  for (const row of committed) {
    const req = Number(row.requested_qty);
    if (!Number.isFinite(req) || req <= 0) continue;
    const cmt = Number(row.quantity) || 0;
    fills.push(Math.max(0, Math.min(1, cmt / req)));
  }
  const n = fills.length;
  if (n === 0) {
    return { gini: null, p10_fill_ratio: null, median_fill_ratio: null, starvation_pct: null };
  }
  fills.sort((a, b) => a - b);
  const sum = fills.reduce((a, b) => a + b, 0);
  let gini = 0;
  if (n > 1 && sum >= 1e-12) {
    let weighted = 0;
    for (let i = 0; i < n; i += 1) weighted += (i + 1) * fills[i];
    gini = (2 * weighted) / (n * sum) - (n + 1) / n;
  }
  const p10Idx = Math.min(n - 1, Math.max(0, Math.ceil(0.1 * n) - 1));
  const median = n % 2 === 1 ? fills[(n - 1) / 2] : (fills[n / 2 - 1] + fills[n / 2]) / 2;
  const starved = fills.filter((x) => x <= 1e-9).length;
  const r4 = (x: number) => Math.round(x * 10000) / 10000;
  const r2 = (x: number) => Math.round(x * 100) / 100;
  return {
    gini: r4(gini),
    p10_fill_ratio: r4(fills[p10Idx]),
    median_fill_ratio: r4(median),
    starvation_pct: r2((starved / n) * 100),
  };
}

export function PlanKpiDashboard({
  planResult,
}: {
  planResult: {
    committed_demands?: CommittedDemand[];
    work_orders?: WorkOrder[];
    plan_kpis?: PlanKpis;
    supply_summary?: { initial_total: number; consumed_total: number; consumption_rate: number | null };
  };
}) {
  const tK = useTranslations('planning.planKpis');
  let kpis: PlanKpis;
  if (planResult.plan_kpis && typeof planResult.plan_kpis === 'object') {
    kpis = planResult.plan_kpis;
  } else {
    const committed = planResult.committed_demands ?? [];
    const wos = planResult.work_orders ?? [];
    const totalCommitted = committed.reduce((s, c) => s + (Number(c.quantity) || 0), 0);
    const byMethod = (method: string) => {
      const list = wos.filter((wo) => (String(wo.method ?? '').trim().toLowerCase() === method));
      const keyed = new Map<string, number>();
      list.forEach((wo) => {
        const key = `${wo.demand_id ?? ''}|${wo.product_id ?? ''}|${wo.location_id ?? ''}|${wo.method ?? ''}`;
        keyed.set(key, (keyed.get(key) ?? 0) + (Number(wo.quantity) || 0));
      });
      return { order_count: keyed.size, total_quantity: Array.from(keyed.values()).reduce((a, b) => a + b, 0) };
    };
    const inv = planResult.supply_summary ?? { initial_total: 0, consumed_total: 0, consumption_rate: null };
    kpis = {
      delivery: {
        total_requested: 0,
        total_committed: totalCommitted,
        fill_rate_pct: null,
        demand_count: committed.length,
        on_time_count: 0,
      },
      fairness: computeFairnessFromCommitted(committed),
      inventory: inv,
      procurement: byMethod('purchase'),
      manufacturing: byMethod('make'),
      logistics: byMethod('move'),
    };
  }
  const d = kpis.delivery ?? {};
  const fair = kpis.fairness;
  const inv = kpis.inventory ?? {};
  const proc = kpis.procurement ?? {};
  const mfg = kpis.manufacturing ?? {};
  const log = kpis.logistics ?? {};
  const card = (title: string, items: { label: string; value: string; tooltip?: string }[], accent?: string) => (
    <div key={title} style={{ flex: '1 1 160px', minWidth: 140, padding: '0.75rem 1rem', background: 'rgba(255,255,255,0.04)', borderRadius: 8, border: '1px solid #3d3d40' }}>
      <div style={{ fontSize: '0.7rem', color: '#a1a1aa', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '0.5rem', fontWeight: 600 }}>{title}</div>
      {items.map(({ label, value, tooltip }) => (
        <div key={label} style={{ marginTop: 4 }} title={tooltip}>
          <span style={{ fontSize: '0.75rem', color: '#71717a', cursor: tooltip ? 'help' : undefined }}>{label}</span>
          <span style={{ display: 'block', fontSize: '1rem', fontWeight: 600, color: accent ?? '#e4e4e7' }}>{value}</span>
        </div>
      ))}
    </div>
  );
  return (
    <div style={{ marginTop: '1rem', marginBottom: '1rem', padding: '1rem', background: 'rgba(0,0,0,0.2)', borderRadius: 8, border: '1px solid #52525b' }}>
      <h4 style={{ margin: '0 0 0.75rem', fontSize: '1rem', color: '#e4e4e7', fontWeight: 600 }}>{tK('title')}</h4>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem' }}>
        {card(tK('delivery'), [
          { label: tK('fillRate'), value: d.fill_rate_pct != null ? `${Number(d.fill_rate_pct).toFixed(1)}%` : '–' },
          { label: tK('committedRequested'), value: `${qtyFmt(Number(d.total_committed ?? 0))} / ${qtyFmt(Number(d.total_requested ?? 0))}` },
          { label: tK('onTime'), value: `${d.on_time_count ?? 0} / ${d.demand_count ?? 0}` },
          (() => {
            const denom = d.fulfilled_with_tree_count ?? d.demand_count ?? 0;
            if (!denom) return { label: tK('fulfilledByBuilds'), value: '–' };
            const count = d.fulfilled_by_real_make_count ?? 0;
            const pct = ((count / denom) * 100).toFixed(1);
            return {
              label: tK('fulfilledByBuilds'),
              value: `${count} / ${denom} (${pct}%)`,
            };
          })(),
          (() => {
            const denom = d.fulfilled_with_tree_count ?? d.demand_count ?? 0;
            if (!denom) return { label: tK('fulfilledByInventory'), value: '–' };
            const count = d.fulfilled_by_inventory_only_count ?? 0;
            const pct = ((count / denom) * 100).toFixed(1);
            return {
              label: tK('fulfilledByInventory'),
              value: `${count} / ${denom} (${pct}%)`,
            };
          })(),
        ], '#34d399')}
        {card(tK('fairness'), [
          { label: tK('fairnessGini'), value: fair?.gini != null ? Number(fair.gini).toFixed(2) : '–', tooltip: tK('fairnessGiniTooltip') },
          { label: tK('fairnessP10'), value: fair?.p10_fill_ratio != null ? Number(fair.p10_fill_ratio).toFixed(2) : '–', tooltip: tK('fairnessP10Tooltip') },
          { label: tK('fairnessMedian'), value: fair?.median_fill_ratio != null ? Number(fair.median_fill_ratio).toFixed(2) : '–', tooltip: tK('fairnessMedianTooltip') },
          { label: tK('fairnessStarvation'), value: fair?.starvation_pct != null ? `${Number(fair.starvation_pct).toFixed(1)}%` : '–', tooltip: tK('fairnessStarvationTooltip') },
        ], '#f472b6')}
        {card(tK('inventory'), [
          { label: tK('consumptionRate'), value: inv.consumption_rate != null ? `${(Number(inv.consumption_rate) * 100).toFixed(1)}%` : '–' },
          { label: tK('consumed'), value: qtyFmt(Number(inv.consumed_total ?? 0)) },
          { label: tK('initialSupply'), value: qtyFmt(Number(inv.initial_total ?? 0)) },
        ], '#a78bfa')}
        {card(tK('procurement'), [
          { label: tK('orders'), value: String(proc.order_count ?? 0) },
          { label: tK('totalQuantity'), value: qtyFmt(Number(proc.total_quantity ?? 0)) },
        ], '#22c55e')}
        {card(tK('manufacturing'), [
          { label: tK('orders'), value: String(mfg.order_count ?? 0) },
          { label: tK('totalQuantity'), value: qtyFmt(Number(mfg.total_quantity ?? 0)) },
        ], '#3b82f6')}
        {card(tK('logistics'), [
          { label: tK('orders'), value: String(log.order_count ?? 0) },
          { label: tK('totalQuantity'), value: qtyFmt(Number(log.total_quantity ?? 0)) },
        ], '#f59e0b')}
      </div>
    </div>
  );
}

import { SortFilterTable } from '@/app/components/SortFilterTable';
import { AssessmentHistoryTable } from '@/app/components/AssessmentHistoryTable';

/** Extract a human-readable message from an API error.
 *  The backend often returns JSON bodies like {"detail":"..."} or {"error":"..."}.
 *  If the raw string is valid JSON with one of those fields, return that field's value;
 *  otherwise return the raw string as-is. */
function formatElapsedMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
}

function parseApiError(raw: unknown): string {
  const s = raw instanceof Error ? raw.message : String(raw ?? 'Unknown error');
  try {
    const parsed = JSON.parse(s);
    if (typeof parsed === 'object' && parsed !== null) {
      const msg = parsed.detail ?? parsed.message ?? parsed.error;
      if (typeof msg === 'string') return msg;
    }
  } catch { /* not JSON */ }
  return s;
}

// ── Commit-reason formatting ───────────────────────────────────────────────────

/** Parse a nested child_failed chain: "child_failed:PROD@LOC(reason)" recursively. */
function parseCommitReasonChain(reason: string): { chain: Array<{ product: string; location: string }>; rootCause: string } {
  const chain: Array<{ product: string; location: string }> = [];
  let current = reason;
  // Greedy (.+) with $ anchor correctly extracts content inside outermost parens.
  const pattern = /^child_failed:([^@(]+)@([^(]+)\((.+)\)$/;
  while (true) {
    const m = current.match(pattern);
    if (!m) break;
    chain.push({ product: m[1].trim(), location: m[2].trim() });
    current = m[3];
  }
  return { chain, rootCause: current.trim() };
}

/** Return a { label, tooltip } pair for a commit_reason string. */
function formatCommitReason(reason: string, purchaseAllowed: boolean): { label: string; tooltip: string } {
  const { chain, rootCause } = parseCommitReasonChain(reason);

  let rootLabel: string;
  switch (rootCause) {
    case 'no_methods':
      rootLabel = purchaseAllowed ? 'no supply method' : 'no supply method — purchase is disabled';
      break;
    case 'no_preferred_method':
      rootLabel = 'no preferred supply method';
      break;
    case 'cycle_stopped':
      rootLabel = 'supply chain loop detected';
      break;
    case 'depth_limit':
      rootLabel = 'supply chain too deep';
      break;
    case 'partial':
      rootLabel = 'partial fulfillment — insufficient components';
      break;
    default:
      rootLabel = rootCause;
  }

  if (chain.length === 0) return { label: rootLabel, tooltip: '' };

  // Drop VIRTUAL-location hops for the display label — they are BOM graph artifacts.
  const physicalChain = chain.filter(c => c.location.toUpperCase() !== 'VIRTUAL');
  const displayLeaf = physicalChain.length > 0 ? physicalChain[physicalChain.length - 1] : chain[chain.length - 1];
  const label = `${displayLeaf.product} @ ${displayLeaf.location}: ${rootLabel}`;

  // Tooltip shows the full failure path for debugging.
  const tooltip = chain.map(c => `${c.product} @ ${c.location}`).join(' → ') + ` → ${rootCause}`;
  return { label, tooltip };
}
/**
 * Human-readable description of a consolidation split policy.
 * Used in both the WO and Supply Explain panels so users can see what
 * "Fair" / "Proportional" / "Priority first" actually mean — the formula,
 * what it splits with respect to, and the shortage behaviour.
 */
function splitPolicyExplanation(
  mode: string | null | undefined,
  tP: (k: string) => string,
): { headline: string; detail: string } {
  switch (mode) {
    case 'proportional':
      return { headline: tP('policyExp.proportionalHeadline'), detail: tP('policyExp.proportionalDetail') };
    case 'priority_first':
      return { headline: tP('policyExp.priorityFirstHeadline'), detail: tP('policyExp.priorityFirstDetail') };
    case 'fair':
      return { headline: tP('policyExp.fairHeadline'), detail: tP('policyExp.fairDetail') };
    default:
      return { headline: mode ?? tP('policyExp.unknownHeadline'), detail: tP('policyExp.unknownDetail') };
  }
}

import { PeggingTree, pathKeyFromPath, type PeggingGraph } from '@/app/components/PeggingTree';
import BomGraphTab from '@/app/components/BomGraphTab';
import { qtyFmt } from '@/app/lib/format';

// ── Assessment criteria helpers ────────────────────────────────────────────────

/** Parse criteria text into per-tier parts. Recognises both English and Chinese section headers. */
function parseCriteriaParts(text: string): { high: string; low: string; medium: string } {
  const result: Record<string, string[]> = { high: [], low: [], medium: [] };
  let current: string | null = null;
  for (const line of text.split('\n')) {
    const t = line.trimStart().toLowerCase();
    // English: "the following are criteria for high/low/medium rating:"
    // Chinese: "以下是high/low/medium评级的标准："
    if (t.startsWith('the following are criteria for high') || t.startsWith('以下是high')) { current = 'high'; continue; }
    if (t.startsWith('the following are criteria for low')  || t.startsWith('以下是low'))  { current = 'low';  continue; }
    if (t.startsWith('the following are criteria for medium') || t.startsWith('以下是medium')) { current = 'medium'; continue; }
    if (current) result[current].push(line);
  }
  return {
    high: result.high.join('\n').trim(),
    low: result.low.join('\n').trim(),
    medium: result.medium.join('\n').trim(),
  };
}

function buildCriteriaText(
  high: string, low: string, medium: string,
  headers: { high: string; low: string; medium: string },
): string {
  const parts: string[] = [];
  if (high.trim()) { parts.push(headers.high); parts.push(high.trim()); }
  if (low.trim()) { parts.push(headers.low); parts.push(low.trim()); }
  if (medium.trim()) { parts.push(headers.medium); parts.push(medium.trim()); }
  return parts.join('\n');
}

// ── Work-order pivot helpers ───────────────────────────────────────────────────
type WoEnrichedRow = WorkOrder & {
  _key?: string;
  _prod_area?: string;
  _peg_order?: number;
  _demand_label?: string;
  _demand_ids?: string[];
  _requested_qty?: number;
  _shortage?: number;
  // demand-pivot extras
  _split_qty?: number;     // allocated_qty for this demand's slice of a shared WO
  _is_shared?: boolean;    // true if WO serves >1 demand
  _is_inventory?: boolean; // true if this row is a synthetic inventory-fulfilled placeholder
};

type WoPivotGroup = {
  _pivot_key: string;
  qty_total: number;
  wo_count: number;
  product_count: number;
  demand_count: number;
  methods: string;
  start_time_min: string | null;
  end_time_max: string | null;
  rows: WoEnrichedRow[];
};

type WoNestedGroup = WoPivotGroup & { subGroups: WoPivotGroup[] };

interface WoDemandGroup {
  demand_id: string;
  label: string;        // product + demand_id
  demand_qty: number;   // original requested quantity
  requested_qty: number; // demand_qty - inventory_fulfilled
  shortage: number;
  rows: WoEnrichedRow[]; // WO rows + optional synthetic inventory row
}

function buildWoNestedPivotGroups(rows: WoEnrichedRow[]): WoNestedGroup[] {
  const outerMap = new Map<string, WoEnrichedRow[]>();
  for (const row of rows) {
    const key = row._prod_area || '(none)';
    if (!outerMap.has(key)) outerMap.set(key, []);
    outerMap.get(key)!.push(row);
  }
  return Array.from(outerMap.entries())
    .map(([key, outerRows]) => ({
      _pivot_key: key,
      qty_total: outerRows.reduce((s, r) => s + (Number(r.quantity) || 0), 0),
      wo_count: outerRows.length,
      product_count: new Set(outerRows.map((r) => r.product_id)).size,
      demand_count: new Set(
        outerRows.flatMap((r) =>
          (r._demand_ids?.length ? r._demand_ids : [r.demand_id])
        ).filter((d): d is string => d != null && d !== '')
      ).size,
      methods: Array.from(new Set(outerRows.map((r) => r.method).filter(Boolean))).join(', '),
      start_time_min: outerRows.map((r) => r.start_time).filter(Boolean).sort()[0] ?? null,
      end_time_max: outerRows.map((r) => r.end_time).filter(Boolean).sort().reverse()[0] ?? null,
      rows: outerRows,
      subGroups: buildWoPivotGroups(outerRows, 'location'),
    }))
    .sort((a, b) => b.qty_total - a.qty_total);
}

function buildWoPivotGroups(rows: WoEnrichedRow[], pivot: 'prod_area' | 'location'): WoPivotGroup[] {
  const groupMap = new Map<string, WoEnrichedRow[]>();
  for (const row of rows) {
    const key = pivot === 'prod_area'
      ? (row._prod_area || '(none)')
      : (row.location_id || '(none)');
    if (!groupMap.has(key)) groupMap.set(key, []);
    groupMap.get(key)!.push(row);
  }
  return Array.from(groupMap.entries())
    .map(([key, groupRows]) => ({
      _pivot_key: key,
      qty_total: groupRows.reduce((s, r) => s + (Number(r.quantity) || 0), 0),
      wo_count: groupRows.length,
      product_count: new Set(groupRows.map((r) => r.product_id)).size,
      demand_count: new Set(
        groupRows.flatMap((r) =>
          (r._demand_ids?.length ? r._demand_ids : [r.demand_id])
        ).filter((d): d is string => d != null && d !== '')
      ).size,
      methods: Array.from(new Set(groupRows.map((r) => r.method).filter(Boolean))).join(', '),
      start_time_min: groupRows.map((r) => r.start_time).filter(Boolean).sort()[0] ?? null,
      end_time_max: groupRows.map((r) => r.end_time).filter(Boolean).sort().reverse()[0] ?? null,
      rows: groupRows,
    }))
    .sort((a, b) => b.qty_total - a.qty_total);
}

function buildWoDemandGroups(
  committedDemands: CommittedDemand[],
  woRows: WoEnrichedRow[], // must be enriched rows (not raw groupedRows) so _key/_prod_area/etc. are present
  demandInventoryMap: Map<string, number>,
  demandShortageMap: Map<string, number>,
  demandRequestedMap: Map<string, number>,
  demandLabelMap: Map<string, string>, // demand_id → product_id label
): WoDemandGroup[] {
  // Collect unique demand IDs in order of first appearance
  const seen = new Set<string>();
  const demandIds: string[] = [];
  for (const d of committedDemands) {
    const did = d.demand_id ?? '';
    if (did && !seen.has(did)) { seen.add(did); demandIds.push(did); }
  }

  return demandIds.map((did) => {
    const inventoryFulfilled = demandInventoryMap.get(did) ?? 0;
    const demandQty = demandRequestedMap.get(did) ?? 0;
    const rows: WoEnrichedRow[] = [];

    // Synthetic inventory row (shown first)
    if (inventoryFulfilled > 0) {
      rows.push({
        product_id: demandLabelMap.get(did) ?? did,
        location_id: '',
        quantity: inventoryFulfilled,
        start_time: null,
        end_time: null,
        method: 'inventory',
        demand_id: did,
        _is_inventory: true,
        _demand_label: did,
        _split_qty: inventoryFulfilled,
      } as WoEnrichedRow);
    }

    // WO rows for this demand — use enriched rows so _key, _prod_area, _peg_order, etc. are present
    for (const wo of woRows) {
      if (wo.demand_id === did) {
        const isShared = (wo.wo_consolidation_split_details?.length ?? 0) > 1;
        rows.push({
          ...wo,
          _is_shared: isShared,
          _split_qty: isShared
            ? (wo.wo_consolidation_split_details?.find((x) => x.demand_id === did)?.allocated_qty ?? wo.quantity)
            : wo.quantity,
        });
      } else if (wo.wo_consolidation_split_details?.some((x) => x.demand_id === did)) {
        const split = wo.wo_consolidation_split_details!.find((x) => x.demand_id === did)!;
        rows.push({
          ...wo,
          _is_shared: true,
          _split_qty: split.allocated_qty,
        });
      }
    }

    return {
      demand_id: did,
      label: `${demandLabelMap.get(did) ?? did} [${did}]`,
      demand_qty: demandQty,
      requested_qty: Math.max(0, demandQty - inventoryFulfilled),
      shortage: demandShortageMap.get(did) ?? 0,
      rows,
    };
  }).filter((g) => g.rows.length > 0);
}

/** Recursively collect all supply/purchase leaf nodes at any depth. */
function collectAllSupplyLeaves(node: PlanningPeggingNode): PlanningPeggingNode[] {
  if (node.type === 'supply' || node.type === 'purchase') return [node];
  return (node.children ?? []).flatMap(collectAllSupplyLeaves);
}

/** Walk the pegging tree and index supply/purchase leaf nodes by WO key.
 *
 * Each WO is indexed by `${demandId}|${product_id}|${location_id}|${method}`.
 * For move/purchase WOs the supply has the same product as the WO, so we
 * filter leaves to product_id === node.product_id.  For make WOs the
 * component leaves have different product_ids — those are indexed separately
 * when the WO for that component is visited.
 */
/** Returns:
 *  - suppliesMap: keyed by `demandId|product|location|method`.
 *    For make WOs → direct demand children (components consumed).
 *    For move/purchase WOs → same-product supply/purchase leaf nodes.
 *  - crossEntrySupplyMap: keyed by `entryDemandId|productId`.
 *    All supply/purchase leaves from any pegging entry for that top-level demand.
 *    Used in the expand panel to show inventory that pre-fills the same demand
 *    independently of the make WO (looked up via the WO row's demand_id).
 */
function buildWoMaps(pegging: PlanningPeggingEntry[]): {
  suppliesMap: Map<string, PlanningPeggingNode[]>;
  crossEntrySupplyMap: Map<string, PlanningPeggingNode[]>;
  peggedQtyMap: Map<string, number>;
} {
  const suppliesMap = new Map<string, PlanningPeggingNode[]>();
  // Maps WO key → quantity of the demand node directly above the WO in the pegging tree.
  // For a component WO this is the component demand qty, not the top-level FG demand qty.
  const peggedQtyMap = new Map<string, number>();

  // Index all supply/purchase leaf nodes by `${entry.demand_id}|${productId}`.
  // Used by the expand panel for make WOs: given a WO row with demand_id=X and product_id=P,
  // look up crossEntrySupplyMap['X|P'] to find inventory that filled the same demand from a
  // different branch or sibling pegging entry (not via this WO).
  const crossEntrySupplyMap = new Map<string, PlanningPeggingNode[]>();
  const collectCrossEntrySupplies = (node: PlanningPeggingNode, entryDemandId: string): void => {
    if (node.type === 'supply' || node.type === 'purchase') {
      const k = `${entryDemandId}|${node.product_id ?? ''}`;
      crossEntrySupplyMap.set(k, [...(crossEntrySupplyMap.get(k) ?? []), node]);
    }
    (node.children ?? []).forEach((c) => collectCrossEntrySupplies(c, entryDemandId));
  };
  for (const entry of pegging) {
    collectCrossEntrySupplies(entry.tree, entry.demand_id ?? '');
  }

  function walk(node: PlanningPeggingNode, demandId: string | null, parentDemand: PlanningPeggingNode | null) {
    if (node.type === 'work_order') {
      const key = `${demandId ?? ''}|${node.product_id ?? ''}|${node.location_id ?? ''}|${node.method ?? ''}`;
      // Record the direct parent demand's quantity for this WO key.
      // parentDemand is the demand node immediately above — for a component WO this is the
      // component demand (qty=300), not the root FG demand (qty=600).
      if (parentDemand?.type === 'demand' && parentDemand.quantity != null) {
        if (!peggedQtyMap.has(key)) peggedQtyMap.set(key, parentDemand.quantity);
      }
      const isMake = (node.method ?? '').toLowerCase() === 'make';
      if (isMake) {
        // Component demands actually consumed to produce this WO's committed qty.
        // Drop rows that failed (commit_reason starts with "child_failed:" or
        // similar hard-failure markers) — those represent attempts that consumed
        // nothing; including them would over-count consumption and clutter the
        // panel. Then dedupe by (product, location, qty, reason): a WO can be
        // referenced from multiple pegging entries (consolidation / waterfall),
        // and the walker visits each independently. Without dedupe, the same
        // component would appear N times for N entry references.
        const HARD_FAIL = /^(child_failed:|no_methods$|no_preferred_method$|depth_limit$)/;
        const isConsumedRow = (c: PlanningPeggingNode): boolean => {
          const r = (c.commit_reason ?? '').trim();
          if (!r) return true;
          if (HARD_FAIL.test(r)) return false;
          return true;
        };
        const demandChildren = (node.children ?? []).filter((c) => c.type === 'demand' && isConsumedRow(c));
        if (demandChildren.length > 0) {
          const existing = suppliesMap.get(key) ?? [];
          const seen = new Set(existing.map((c) =>
            `${c.demand_id ?? ''}|${c.product_id ?? ''}|${c.location_id ?? ''}|${c.quantity ?? ''}|${c.commit_reason ?? ''}`,
          ));
          const fresh = demandChildren.filter((c) => {
            const k2 = `${c.demand_id ?? ''}|${c.product_id ?? ''}|${c.location_id ?? ''}|${c.quantity ?? ''}|${c.commit_reason ?? ''}`;
            if (seen.has(k2)) return false;
            seen.add(k2);
            return true;
          });
          if (fresh.length > 0) suppliesMap.set(key, [...existing, ...fresh]);
        }
      } else {
        // Move/purchase WOs: show same-product supply/purchase leaf nodes.
        const woLeaves = collectAllSupplyLeaves(node).filter((s) => s.product_id === node.product_id);
        if (woLeaves.length > 0) {
          suppliesMap.set(key, [...(suppliesMap.get(key) ?? []), ...woLeaves]);
        }
      }
      (node.children ?? []).forEach((child) => walk(child, demandId, node));
    } else if (node.type === 'demand') {
      const nextDemand = node.demand_id ?? demandId;
      (node.children ?? []).forEach((child) => walk(child, nextDemand, node));
    } else {
      (node.children ?? []).forEach((child) => walk(child, demandId, parentDemand));
    }
  }

  for (const entry of pegging) {
    walk(entry.tree, entry.demand_id ?? null, null);
  }
  return { suppliesMap, crossEntrySupplyMap, peggedQtyMap };
}

export function CaseDetail({ section: sectionProp = 'planning', subsection }: { section?: string; subsection?: string }) {
  const tNav = useTranslations('nav');
  const tSec = useTranslations('sections');
  const tA = useTranslations('allocation');
  const tP = useTranslations('planning');
  const tc = useTranslations('common');
  const locale = useLocale();
  // Locale-aware section headers used when assembling / saving criteria text
  const criteriaHeaders = {
    high:   tP('supplyView.assessment.criteriaHeader', { tier: 'HIGH' }),
    low:    tP('supplyView.assessment.criteriaHeader', { tier: 'LOW' }),
    medium: tP('supplyView.assessment.criteriaHeader', { tier: 'MEDIUM' }),
  };
  const params = useParams();
  const id = Number(params.id);
  const [c, setC] = useState<CaseType | null>(null);
  const [runs, setRuns] = useState<RunType[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const [runDetail, setRunDetail] = useState<{ run: RunType; actions: unknown[]; feasible_demands: FeasibleDemand[] } | null>(null);
  const [feasibleDemands, setFeasibleDemands] = useState<FeasibleDemand[] | null>(null);
  const [demandsLoadError, setDemandsLoadError] = useState<string | null>(null);
  const [overrides, setOverrides] = useState<OverrideType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [allocating, setAllocating] = useState(false);
  const [allocationProgress, setAllocationProgress] = useState<AllocationProgress | null>(null);
  const [etaRemainingSeconds, setEtaRemainingSeconds] = useState<number | null>(null);
  const allocationProgressRef = useRef<{ steps: number; timestamp: number } | null>(null);
  const [overrideForm, setOverrideForm] = useState({ entity_type: 'supply', entity_key: '', payload: '{}' });
  const [supplyView, setSupplyView] = useState<SupplyViewRow[]>([]);
  const [supplyViewLoading, setSupplyViewLoading] = useState(false);
  const [allocationView, setAllocationView] = useState<AllocationViewRow[]>([]);
  const [allocationViewTruncated, setAllocationViewTruncated] = useState<{ total_actions: number; limit: number; total_steps: number } | null>(null);
  const ALLOCATION_VIEW_PAGE_SIZE = 1000;
  const [allocationViewLoading, setAllocationViewLoading] = useState(false);
  const [allocationViewError, setAllocationViewError] = useState<string | null>(null);
  const [allocationActions, setAllocationActions] = useState<AllocationActionRow[]>([]);
  const [allocationActionsTotal, setAllocationActionsTotal] = useState(0);
  const [allocationActionsLoading, setAllocationActionsLoading] = useState(false);
  const [allocationActionsOffset, setAllocationActionsOffset] = useState(0);
  const [basketInitial, setBasketInitial] = useState<{ key: string; display: string; qty: number }[]>([]);
  const [basketDeltas, setBasketDeltas] = useState<{ purged: { key: string; display: string; qty: number }[]; added: { key: string; display: string; qty: number }[] }[]>([]);
  const [basketFinal, setBasketFinal] = useState<{ key: string; display: string; qty: number }[] | null>(null);
  const [basketPrunes, setBasketPrunes] = useState<{ after_step: number; comp_keys: string[] }[]>([]);
  const [basketShowingFinal, setBasketShowingFinal] = useState(false);
  const [basketLoading, setBasketLoading] = useState(false);
  const allocationViewRunIdRef = useRef<number | null>(null);
  const allocationViewFetchingRef = useRef<boolean>(false);
  const activeViewRef = useRef<'supply' | 'allocation' | 'suggested' | 'raw-material'>('supply');
  const selectedRunIdRef = useRef<number | null>(null);
  const caseSection = sectionProp;
  const [rawMaterialReport, setRawMaterialReport] = useState<RawMaterialUsageReport | null>(null);
  const [rawMaterialReportLoading, setRawMaterialReportLoading] = useState(false);

  // ── Material impact events ─────────────────────────────────────────────────
  const [materialEvents, setMaterialEvents] = useState<MaterialEvent[]>([]);
  const [materialEventsLoading, setMaterialEventsLoading] = useState(false);
  const [materialImpacts, setMaterialImpacts] = useState<Record<number, MaterialImpactResult | null>>({});
  const [materialImpactLoading, setMaterialImpactLoading] = useState<Record<number, boolean>>({});
  const [materialImpactProgress, setMaterialImpactProgress] = useState<Record<number, MaterialImpactProgress | null>>({});
  const [materialImpactError, setMaterialImpactError] = useState<Record<number, string | null>>({});
  const [materialAssessments, setMaterialAssessments] = useState<Record<number, AssessmentResponse | null>>({});
  const [materialAssessmentLoading, setMaterialAssessmentLoading] = useState<Record<number, boolean>>({});
  const [materialAssessmentError, setMaterialAssessmentError] = useState<Record<number, string | null>>({});
  const [materialAssessmentHistory, setMaterialAssessmentHistory] = useState<Record<number, AssessmentSummary[]>>({});
  const [materialAssessmentHistoryOpen, setMaterialAssessmentHistoryOpen] = useState<Record<number, boolean>>({});
  const [materialEventCollapsed, setMaterialEventCollapsed] = useState<Record<number, boolean>>({});
  const [materialEventEditing, setMaterialEventEditing] = useState<Record<number, { supplyId: string; delayDays: number; qtyDecreaseMode: 'pct' | 'abs'; qtyDecreasePct: number; qtyDecreaseAbs: number; note: string }>>({});
  const [materialEventSaving, setMaterialEventSaving] = useState<Record<number, boolean>>({});
  // New event form state (-1 = "new" sentinel)
  const [materialNewEvent, setMaterialNewEvent] = useState<{ supplyId: string; delayDays: number; qtyDecreaseMode: 'pct' | 'abs'; qtyDecreasePct: number; qtyDecreaseAbs: number; note: string } | null>(null);
  const [materialNewSaving, setMaterialNewSaving] = useState(false);
  const [materialNewSupplySuggestions, setMaterialNewSupplySuggestions] = useState<SupplySuggestion[]>([]);
  const [materialNewShowSuggestions, setMaterialNewShowSuggestions] = useState(false);
  const materialNewDebounce = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [planResult, setPlanResult] = useState<{
    committed_demands: CommittedDemand[];
    work_orders: WorkOrder[];
    planning_pegging: PlanningPeggingEntry[];
    supply_allocations?: PlanSupplyAllocation[];
    supply_level_allocations?: SupplyLevelAllocation[];
    supply_summary?: { initial_total: number; consumed_total: number; consumption_rate: number | null };
    plan_kpis?: PlanKpis;
  } | null>(null);
  const [planLoading, setPlanLoading] = useState(false);
  const [planError, setPlanError] = useState<string | null>(null);
  const [planPeggingOpen, setPlanPeggingOpen] = useState(false);
  const [planPeggingContext, setPlanPeggingContext] = useState<
    | { type: 'demand'; row: CommittedDemand }
    | { type: 'work_order'; row: WorkOrder }
    | { type: 'supply'; supplyId: string; peggedDemands: PeggedDemandEntry[]; initialQty: number; consumedQty: number }
    | null
  >(null);
  const [planPeggingExpanded, setPlanPeggingExpanded] = useState<Set<string>>(new Set(['0']));
  const [planPeggingSearch, setPlanPeggingSearch] = useState('');
  const [planPeggingMatchPath, setPlanPeggingMatchPath] = useState<string | null>(null);
  const [planPeggingMatchIndex, setPlanPeggingMatchIndex] = useState(0);
  const [planPeggingMatchPaths, setPlanPeggingMatchPaths] = useState<string[]>([]);
  useEffect(() => {
    setPlanPeggingSearch('');
    setPlanPeggingMatchPath(null);
    setPlanPeggingMatchPaths([]);
    setPlanPeggingMatchIndex(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planPeggingContext?.type === 'work_order' ? (planPeggingContext.row as WorkOrder).demand_id ?? '' : null,
      planPeggingContext?.type === 'demand' ? (planPeggingContext.row as CommittedDemand).demand_id ?? '' : null,
      planPeggingContext?.type === 'supply' ? planPeggingContext.supplyId : null]);
  const [planExplanationExpanded, setPlanExplanationExpanded] = useState<Set<string>>(new Set());
  const [planPeggingPanelWidth, setPlanPeggingPanelWidth] = useState(420);
  const [planWorkOrderPeggingCache, setPlanWorkOrderPeggingCache] = useState<Record<string, PlanningPeggingNode>>({});
  // Active demand for WO pegging panel; null = use the row's own demand_id (default)
  const [woPeggingActiveDemandId, setWoPeggingActiveDemandId] = useState<string | null>(null);
  // When drilling from supply pegging panel into a demand tree, remembers the supply context for the back link.
  const [previousPeggingContext, setPreviousPeggingContext] = useState<{
    type: 'supply'; supplyId: string; peggedDemands: PeggedDemandEntry[]; initialQty: number; consumedQty: number;
  } | null>(null);
  // When drilling from the Breakdown slide-in (supExplain) into a demand's pegging tree,
  // remembers the supExplain row for the back link in the planPegging slide-in. Cleared
  // whenever the planPegging slide-in is closed via any path (Close button or new context).
  const [previousSupExplainRow, setPreviousSupExplainRow] = useState<PlanSupplyViewRow | null>(null);
  const [planWorkOrderPeggingLoading, setPlanWorkOrderPeggingLoading] = useState<string | null>(null);
  const [planWorkOrderPeggingError, setPlanWorkOrderPeggingError] = useState<string | null>(null);
  const planPeggingResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [planPeggingResizing, setPlanPeggingResizing] = useState(false);
  const [planResultTab, setPlanResultTab] = useState<'demands' | 'work_orders' | 'supplies'>('demands');
  // ID of the plan run currently loaded in planResult; null = freshly-run (not from history)
  const [currentPlanRunId, setCurrentPlanRunId] = useState<number | null>(null);
  // DB run ID for the current fresh (unsaved) plan result; null once saved or when loading from history
  const [freshPlanRunId, setFreshPlanRunId] = useState<number | null>(null);
  // Run ID loaded at the moment re-plan was dispatched — candidate target for "Save as override".
  // Survives past freshPlanRunId's arrival (unlike currentPlanRunId, which gets cleared).
  const [overrideCandidateRunId, setOverrideCandidateRunId] = useState<number | null>(null);
  const [planRunSaving, setPlanRunSaving] = useState(false);
  const [planRunSaveError, setPlanRunSaveError] = useState<string | null>(null);
  // Name/notes for the fresh unsaved run
  const [freshRunName, setFreshRunName] = useState('');
  const [freshRunNotes, setFreshRunNotes] = useState('');
  // Inline editing state for history panel: runId → { name, notes }
  const [planRunEditing, setPlanRunEditing] = useState<Record<number, { name: string; notes: string }>>({});
  const [planRunEditSaving, setPlanRunEditSaving] = useState<Record<number, boolean>>({});
  const [caseSupplies, setCaseSupplies] = useState<CaseSupplyRow[]>([]);
  const [caseSuppliesLoading, setCaseSuppliesLoading] = useState(false);
  const [caseSuppliesError, setCaseSuppliesError] = useState<string | null>(null);
  const [planSupplyTextFilter, setPlanSupplyTextFilter] = useState('');
  const [planSupplyUnusedOnly, setPlanSupplyUnusedOnly] = useState(false);
  const [planSupplyPartialOnly, setPlanSupplyPartialOnly] = useState(false);
  const [planSupplyHideDummy, setPlanSupplyHideDummy] = useState(true);
  const [planWorkOrderHideDummyProdArea, setPlanWorkOrderHideDummyProdArea] = useState(true);
  // ── Assessment state ────────────────────────────────────────────────────────
  const [assessCriteria, setAssessCriteria] = useState('');
  const [assessCriteriaHigh, setAssessCriteriaHigh] = useState('');
  const [assessCriteriaLow, setAssessCriteriaLow] = useState('');
  const [assessCriteriaMedium, setAssessCriteriaMedium] = useState('');
  const [assessCriteriaOpen, setAssessCriteriaOpen] = useState(false);
  const [assessCriteriaSaving, setAssessCriteriaSaving] = useState(false);
  const [assessCriteriaLoaded, setAssessCriteriaLoaded] = useState(false);
  const [assessmentRunning, setAssessmentRunning] = useState(false);
  const [assessmentResult, setAssessmentResult] = useState<AssessmentResponse | null>(null);
  const [assessmentHistory, setAssessmentHistory] = useState<AssessmentSummary[]>([]);
  const [assessmentHistoryOpen, setAssessmentHistoryOpen] = useState(false);
  const [assessDelayDays, setAssessDelayDays] = useState(0);
  const [assessQtyDecreaseMode, setAssessQtyDecreaseMode] = useState<'pct' | 'abs'>('pct');
  const [assessQtyDecreasePct, setAssessQtyDecreasePct] = useState(0);
  const [assessQtyDecreaseAbs, setAssessQtyDecreaseAbs] = useState(0);
  const [assessError, setAssessError] = useState<string | null>(null);
  const [planDemandRealMakeOnly, setPlanDemandRealMakeOnly] = useState(false);
  const [planDemandShortOnly, setPlanDemandShortOnly] = useState(false);
  const [planDemandBuyOnly, setPlanDemandBuyOnly] = useState(false);
  const [planDemandRealMoveOnly, setPlanDemandRealMoveOnly] = useState(false);
  const [planDemandAlternativesOnly, setPlanDemandAlternativesOnly] = useState(false);
  const [planDemandSupplyFilter, setPlanDemandSupplyFilter] = useState('');
  const [supplyFilterInput, setSupplyFilterInput] = useState('');
  const [supplyFilterSuggestions, setSupplyFilterSuggestions] = useState<SupplySuggestion[]>([]);
  const [showSupplyFilterSuggestions, setShowSupplyFilterSuggestions] = useState(false);
  const [supplyFilterSuggestionIndex, setSupplyFilterSuggestionIndex] = useState(-1);
  const supplyFilterDebounce = useRef<ReturnType<typeof setTimeout> | null>(null);
  const supplyFilterListRef = useRef<HTMLUListElement>(null);
  const [planWoDemandedByMultiple, setPlanWoDemandedByMultiple] = useState(false);
  const [planWoMultiSupply, setPlanWoMultiSupply] = useState(false);
  const [planWoPurchaseOnly, setPlanWoPurchaseOnly] = useState(false);
  const [planWoMoveOnly, setPlanWoMoveOnly] = useState(false);
  const [planWoHasOverride, setPlanWoHasOverride] = useState(false);
  const [planWoPivot, setPlanWoPivot] = useState<'none' | 'prod_area' | 'location' | 'nested' | 'demand'>('none');
  const [planWoLayoutMode, setPlanWoLayoutMode] = useState<'data' | 'split' | 'timeline'>('split');
  const [woPegHighlightRow, setWoPegHighlightRow] = useState<WoEnrichedRow | null>(null);
  const [woPegFilterPeggedOnly, setWoPegFilterPeggedOnly] = useState(true);
  const [planWoPivotExpanded, setPlanWoPivotExpanded] = useState<Set<string>>(new Set());
  const [planWoPivotSubExpanded, setPlanWoPivotSubExpanded] = useState<Set<string>>(new Set());
  const [woExpandedKeys, setWoExpandedKeys] = useState<Set<string>>(new Set());
  const [woExplainOpen, setWoExplainOpen] = useState(false);
  const [woExplainRow, setWoExplainRow] = useState<WorkOrder | null>(null);
  const [woExplainKey, setWoExplainKey] = useState<string | null>(null);
  const [supExplainOpen, setSupExplainOpen] = useState(false);
  const [supExplainRow, setSupExplainRow] = useState<PlanSupplyViewRow | null>(null);
  const [supExplainKey, setSupExplainKey] = useState<string | null>(null);
  const [woPeggingRowKey, setWoPeggingRowKey] = useState<string | null>(null);
  const [bomRealPairs, setBomRealPairs] = useState<[string, string][] | null>(null);
  const [realMoveTriples, setRealMoveTriples] = useState<[string, string, string][] | null>(null);
  const [planningConfig, setPlanningConfig] = useState<PlanningConfig>({ consolidation: { enabled: true, period_days: 0, allocation_mode: 'fair', scope: 'leaf-only' }, purchase_allowed: false, analyze_criticality: false, check_soundness: true });
  const [planJobId, setPlanJobId] = useState<string | null>(null);
  const [planProgress, setPlanProgress] = useState<{ current: number; total: number; iteration?: number; iterations_max?: number } | null>(null);
  const planPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [copilotOpen, setCopilotOpen] = useState(false);
  const [copilotMessages, setCopilotMessages] = useState<PlanningCopilotMessage[]>([]);
  const [copilotInput, setCopilotInput] = useState('');
  const [copilotLoading, setCopilotLoading] = useState(false);
  // Live progress for an in-flight plan started by the agent. Two polling
  // modes feed this state:
  //   1. While a chat HTTP request is in flight (copilotLoading=true), we
  //      poll /cases/{id}/plan/active-jobs every 1.5s.
  //   2. After the chat response returned with `pending_job_id` (plan
  //      exceeded the agent's 25s blocking window), we poll
  //      /cases/{id}/plan/status/{jobId} every 1.5s until the plan finishes,
  //      then append a synthetic completion assistant message.
  // Mode 2 lets the user keep chatting (or just walk away) while the plan
  // continues — they no longer have to re-prompt to see the result.
  const [copilotActiveJob, setCopilotActiveJob] = useState<ActivePlanJob | null>(null);
  const [copilotPendingJobId, setCopilotPendingJobId] = useState<string | null>(null);
  const [copilotPanelWidth, setCopilotPanelWidth] = useState(440);
  const copilotResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [copilotResizing, setCopilotResizing] = useState(false);
  const copilotMessagesEndRef = useRef<HTMLDivElement | null>(null);

  // ── Supply criticality analysis state ─────────────────────────────────────
  const [supplyCriticalityMap, setSupplyCriticalityMap] = useState<Record<string, 'critical' | 'not_critical' | 'running' | 'error'>>({});
  const [criticalityRunning, setCriticalityRunning] = useState(false);
  const [criticalityProgress, setCriticalityProgress] = useState<{ done: number; total: number } | null>(null);
  // Whether to auto-run criticality scan after planning (config option, default: off)
  // analyzeCriticalityEnabled + checkSoundnessEnabled are derived from planningConfig so the
  // planning-copilot's config_update can flip them via natural language ("turn off
  // soundness", "也要做关键度分析"). Setting back through setPlanningConfig keeps the form
  // and the copilot in sync via a single source of truth.
  const analyzeCriticalityEnabled = planningConfig.analyze_criticality === true;
  const setAnalyzeCriticalityEnabled = (v: boolean) => setPlanningConfig((c) => ({ ...c, analyze_criticality: v }));
  // Set to true after plan completes with analyzeCriticalityEnabled; cleared once caseSupplies loads and analysis starts
  const [autoCriticalityPending, setAutoCriticalityPending] = useState(false);

  // ── Plan run history state ──────────────────────────────────────────────────
  const [planRunHistory, setPlanRunHistory] = useState<PlanRun[]>([]);
  // Sort + filter for the run-history slide-in. Mirrors the KB workspace:
  // null sort = default (created_at desc, as returned by the backend).
  const [planRunHistorySort, setPlanRunHistorySort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(null);
  const [planRunHistorySoundOnly, setPlanRunHistorySoundOnly] = useState(false);
  const currentRunIsContingent = currentPlanRunId != null &&
    planRunHistory.find(r => r.id === currentPlanRunId)?.status === 'contingent';
  const [planRunHistoryOpen, setPlanRunHistoryOpen] = useState(false);
  // ── Bootstrap (KB seeding) state ────────────────────────────────────────
  const [bootstrapDialogOpen, setBootstrapDialogOpen] = useState(false);
  const [bootstrapPreview, setBootstrapPreview] = useState<BootstrapPreview | null>(null);
  const [bootstrapPreviewLoading, setBootstrapPreviewLoading] = useState(false);
  const [bootstrapBatchSize, setBootstrapBatchSize] = useState(5);
  const [bootstrapStarting, setBootstrapStarting] = useState(false);
  const [bootstrapJobId, setBootstrapJobId] = useState<string | null>(null);
  const [bootstrapJobStatus, setBootstrapJobStatus] = useState<BootstrapJobStatus | null>(null);
  const [bootstrapCancelling, setBootstrapCancelling] = useState(false);
  // Notice surfaced when the start endpoint dropped some submitted presets
  // because their config signature is already covered in the KB. Cleared
  // when the dialog reopens or the user dismisses.
  const [bootstrapSkippedNotice, setBootstrapSkippedNotice] = useState<BootstrapPreset[] | null>(null);
  const bootstrapPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [bootstrapExpandedPresets, setBootstrapExpandedPresets] = useState<Set<string>>(new Set());
  // KB-inspector sort state. `key` matches a BootstrapPreset KPI field name;
  // null = no sort (preserve backend / library order).
  const [bootstrapKbSort, setBootstrapKbSort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(null);
  const [bootstrapKbSoundOnly, setBootstrapKbSoundOnly] = useState(false);
  // Which "best run" criterion drives the suggestion seed. Default fill_rate;
  // user can switch to fairness (lowest gini) or pareto (balanced score).
  const [bootstrapCriterion, setBootstrapCriterion] = useState<BootstrapCriterion>('fill_rate');
  // Per-row config overrides. The backend suggests N single-knob variations
  // around the best KB run; users can alter any row's config via the
  // planning-page-style mini-form (max_methods dropdown, mode toggle, etc.)
  // before submitting. Empty = use system suggestion as-is. Edits get
  // re-dedup'd against KB on save.
  const [bootstrapEditedConfigs, setBootstrapEditedConfigs] = useState<Record<string, Record<string, unknown>>>({});
  // Set of preset_ids whose row is expanded to show the edit form.
  const [bootstrapEditingRows, setBootstrapEditingRows] = useState<Set<string>>(new Set());
  const toggleBootstrapPresetExpanded = (presetId: string) => {
    setBootstrapExpandedPresets((prev) => {
      const next = new Set(prev);
      if (next.has(presetId)) next.delete(presetId); else next.add(presetId);
      return next;
    });
  };

  // Library baseline: the canonical config every bootstrap preset is varying
  // from. Showing this once at the top of the dialog + a per-preset DIFF line
  // is far less misleading than stacking 5 full JSONs that are 95% identical.
  // Matches the cfg() defaults in CaseBootstrap.kt.
  const presetDiffSummary = (config: Record<string, unknown>): string => {
    const ms = (config.method_selection ?? {}) as Record<string, unknown>;
    const cs = (config.consolidation ?? {}) as Record<string, unknown>;
    const sw = (ms.score_weights ?? {}) as Record<string, number>;
    const diffs: string[] = [];
    if (ms.mode !== 'preference') diffs.push(`mode: preference → ${ms.mode}`);
    if (Number(ms.max_methods) !== 1) diffs.push(`max_methods: 1 → ${ms.max_methods}`);
    if (Number(ms.depth) !== 1) diffs.push(`depth: 1 → ${ms.depth}`);
    if (Number(sw.commit_time) !== 0.4 || Number(sw.inventory_consumed) !== 0.35 || Number(sw.purchase) !== 0.25) {
      diffs.push(`weights: (${sw.commit_time}, ${sw.inventory_consumed}, ${sw.purchase})`);
    }
    if (cs.scope !== 'leaf-only') diffs.push(`scope: leaf-only → ${cs.scope}`);
    if (cs.allocation_mode !== 'fair') diffs.push(`allocation_mode: fair → ${cs.allocation_mode}`);
    if (cs.enabled === false) diffs.push(`consolidation: on → off`);
    if (Number(cs.period_days) !== 0) diffs.push(`period_days: 0 → ${cs.period_days}`);
    if (config.purchase_allowed === true) diffs.push(`purchase: off → on`);
    return diffs.join(' · ');
  };
  /**
   * Self-describing summary of a config — always emits the same set of axes,
   * regardless of any baseline. Used in the KB / Already-covered list, where
   * rows are heterogeneous (library + user-driven) and a baseline-relative
   * diff would be misleading. Storage in kb_records is the full JSON; this
   * function is purely presentation.
   */
  const presetConfigSummary = (config: Record<string, unknown>): string => {
    const ms = (config.method_selection ?? {}) as Record<string, unknown>;
    const cs = (config.consolidation ?? {}) as Record<string, unknown>;
    const sw = (ms.score_weights ?? {}) as Record<string, number>;
    const parts: string[] = [];
    parts.push(`m=${ms.mode ?? 'preference'}`);
    parts.push(`max=${ms.max_methods ?? 2}`);
    parts.push(`d=${ms.depth ?? 1}`);
    if (sw && (sw.commit_time != null || sw.inventory_consumed != null || sw.purchase != null)) {
      parts.push(`w=(${Number(sw.commit_time ?? 0)}, ${Number(sw.inventory_consumed ?? 0)}, ${Number(sw.purchase ?? 0)})`);
    }
    parts.push(`scope=${cs.scope ?? 'leaf-only'}`);
    parts.push(`alloc=${cs.allocation_mode ?? 'fair'}`);
    parts.push(`cons=${cs.enabled === false ? 'off' : 'on'}`);
    if (Number(cs.period_days ?? 0) !== 0) parts.push(`p=${cs.period_days}`);
    parts.push(`purch=${config.purchase_allowed === true ? 'on' : 'off'}`);
    return parts.join(' · ');
  };
  /** Canonical baseline config (mirrors the Kotlin cfg() defaults). */
  const makeBaselineConfig = (): Record<string, unknown> => ({
    method_selection: {
      mode: 'preference',
      depth: 1,
      multiple: false,
      elaborate: false,
      max_methods: 1,
      depth_optimal: false,
      score_weights: { commit_time: 0.4, inventory_consumed: 0.35, purchase: 0.25 },
    },
    consolidation: {
      enabled: true,
      period_days: 0,
      allocation_mode: 'fair',
      scope: 'leaf-only',
    },
    variant_selection: { multiple: true },
    purchase_allowed: false,
    analyze_criticality: false,
    check_soundness: true,
  });
  /** Build a full PlanningConfig from baseline + a single axis-value override.
   *  Mirrors the Kotlin buildConfigForAxisValue. The axis names must match
   *  the backend's AXIS_CATALOG entries. */
  const buildConfigFromAxisValue = (axisName: string, value: unknown): Record<string, unknown> => {
    const cfg = makeBaselineConfig();
    const ms = cfg.method_selection as Record<string, unknown>;
    const cs = cfg.consolidation as Record<string, unknown>;
    switch (axisName) {
      case 'max_methods':         ms.max_methods = value; break;
      case 'depth':               ms.depth = value; break;
      case 'scope':               cs.scope = value; break;
      case 'allocation_mode':     cs.allocation_mode = value; break;
      case 'consolidation_enabled': cs.enabled = value; break;
      case 'period_days':         cs.period_days = value; break;
      case 'purchase_allowed':    cfg.purchase_allowed = value; break;
      case 'mode':
        ms.mode = value;
        ms.elaborate = value === 'elaborate';
        break;
      // Compound axis: scoring profile implies mode=elaborate + a weight triple.
      case 'score_weights': {
        ms.mode = 'elaborate';
        ms.elaborate = true;
        const profile = String(value);
        const weights: Record<string, number> =
          profile === 'commit'    ? { commit_time: 1, inventory_consumed: 0, purchase: 0 } :
          profile === 'inventory' ? { commit_time: 0, inventory_consumed: 1, purchase: 0 } :
          profile === 'purchase'  ? { commit_time: 0, inventory_consumed: 0, purchase: 1 } :
                                     { commit_time: 0.4, inventory_consumed: 0.35, purchase: 0.25 }; // balanced
        ms.score_weights = weights;
        break;
      }
    }
    return cfg;
  };
  const handleDeleteKbRecord = async (recordId: number) => {
    if (!id) return;
    try {
      await deleteKbRecord(id, recordId);
      // Refresh preview so the deleted KB row drops out of "already covered".
      // The underlying plan_run (if any) is untouched — KB is dissociated.
      const preview = await getBootstrapPreview(id, bootstrapBatchSize, bootstrapCriterion);
      setBootstrapPreview(preview);
    } catch (e) {
      console.error('Failed to delete KB record', e);
    }
  };
  const [planRunHistoryLoading, setPlanRunHistoryLoading] = useState(false);
  const [planRunLoadingId, setPlanRunLoadingId] = useState<number | null>(null);
  // Per-run expansion: which tab, plus lazy-loaded full detail (for overrides + events)
  const [planRunExpandedTab, setPlanRunExpandedTab] = useState<Record<number, 'config' | 'overrides' | 'events' | null>>({});
  const [planRunDetailCache, setPlanRunDetailCache] = useState<Record<number, PlanRunFull>>({});
  const [planRunDetailLoading, setPlanRunDetailLoading] = useState<Record<number, boolean>>({});
  const [planRunDesignating, setPlanRunDesignating] = useState<Record<number, boolean>>({});
  // Soundness check state: per-run busy flag + currently-displayed report (when slide-in open).
  const [soundnessChecking, setSoundnessChecking] = useState<Record<number, boolean>>({});
  const [soundnessReportOpen, setSoundnessReportOpen] = useState<{ runId: number; report: SoundnessReport } | null>(null);
  // When true, every successful plan auto-runs the soundness check (always deep — R8 chain
  // conservation is cheap relative to a full re-plan, no reason to expose the speed knob).
  // Same source-of-truth pattern as analyzeCriticalityEnabled — derives from planningConfig.
  const checkSoundnessEnabled = planningConfig.check_soundness !== false;
  const setCheckSoundnessEnabled = (v: boolean) => setPlanningConfig((c) => ({ ...c, check_soundness: v }));

  // ── Override dialog state ───────────────────────────────────────────────────
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [overrideDialogType, setOverrideDialogType] = useState<'method_selection' | 'component_split' | 'supply_split' | null>(null);
  const [overrideDialogWo, setOverrideDialogWo] = useState<WorkOrder | null>(null);
  const [overrideDialogSupply, setOverrideDialogSupply] = useState<PlanSupplyViewRow | null>(null);
  const [overrideDialogSaving, setOverrideDialogSaving] = useState(false);
  const [overrideDialogError, setOverrideDialogError] = useState<string | null>(null);
  // Structured override form state (type-specific; avoids raw JSON editing)
  const [overrideMethodValue, setOverrideMethodValue] = useState<string>('make');
  const [overrideSplitRows, setOverrideSplitRows] = useState<Array<{
    demand_id: string | null;
    qty: number;
    requested_qty: number;
    priority: number;
    parent_product: string;
  }>>([]);
  const [overrideSupplyRows, setOverrideSupplyRows] = useState<Array<{
    demand_id: string;
    qty: number;
    requested_qty: number;
    consumed_qty: number;
    customer: string | null;
  }>>([]);

  // Panel resize state
  const [woExplainPanelWidth, setWoExplainPanelWidth] = useState(420);
  const woExplainResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [woExplainResizing, setWoExplainResizing] = useState(false);
  const [supExplainPanelWidth, setSupExplainPanelWidth] = useState(420);
  const supExplainResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [supExplainResizing, setSupExplainResizing] = useState(false);
  const [planRunHistoryPanelWidth, setPlanRunHistoryPanelWidth] = useState(520);
  const planRunHistoryResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [planRunHistoryResizing, setPlanRunHistoryResizing] = useState(false);
  // KB slide-in panel resize state (mirrors planRunHistory).
  const [bootstrapPanelWidth, setBootstrapPanelWidth] = useState(720);
  const bootstrapResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [bootstrapResizing, setBootstrapResizing] = useState(false);

  /** Rule-based intent: map user message to config updates and a reply for method selection and consolidation. */
  function parseCopilotIntent(message: string, currentConfig: PlanningConfig): { reply: string; configUpdate?: PlanningConfig } {
    const t = message.trim().toLowerCase();
    const ms = currentConfig.method_selection ?? {};
    const cs = currentConfig.consolidation ?? {};

    if (!t) return { reply: tP('copilot.replies.empty') };

    // Accept both English and Chinese triggers.
    if (/show|current|what('s| is)? (my )?config|settings|config|显示配置|当前配置|查看配置/.test(t)) {
      const methodMode = ms.multiple === true ? tP('copilot.equalSplit') : ms.elaborate === true ? tP('copilot.oneByScore') : tP('copilot.oneByPreference');
      const methodDepth = ms.elaborate === true ? (ms.depth ?? 1) : null;
      const purchaseMode = currentConfig.purchase_allowed === false ? tP('copilot.disabled') : tP('copilot.allowed');
      const splitLabel = cs.allocation_mode === 'proportional'
        ? tP('copilot.splitProportional')
        : cs.allocation_mode === 'priority_first'
          ? tP('copilot.splitPriorityFirst')
          : tP('copilot.splitFair');
      const consolidationMode = cs.enabled
        ? tP('copilot.consolidationOnDetail', { days: cs.period_days ?? 0, split: splitLabel })
        : tP('copilot.off');
      const methodLine = methodDepth != null
        ? tP('copilot.replies.showConfigMethodDepth', { methodMode, depth: methodDepth })
        : tP('copilot.replies.showConfigMethod', { methodMode });
      return { reply: tP('copilot.replies.showConfig', { methodLine, purchaseMode, consolidationMode }) };
    }

    // Explicit "max methods N" / "最多方法 N" → set the waterfall cap.
    const maxMethodsMatch = t.match(/max\s*methods?\s*(?:=|:|to)?\s*(\d+)|最多方法\s*[:=]?\s*(\d+)|方法上限\s*[:=]?\s*(\d+)/);
    if (maxMethodsMatch) {
      const n = Math.max(1, Math.min(4, parseInt(maxMethodsMatch[1] ?? maxMethodsMatch[2] ?? maxMethodsMatch[3] ?? '2', 10)));
      return {
        reply: tP('copilot.replies.methodMaxMethods', { n }),
        configUpdate: { method_selection: { ...ms, max_methods: n } },
      };
    }
    // Legacy "equal split" / "split across methods" intent — under waterfall the
    // closest behavior is max_methods=2 (use the best, fall back to the next when short).
    if (/equal.?split.?method|split.?method.?equal|split across method|multiple method|use all method|方法等量拆分|等量拆分方法|跨方法拆分/.test(t)) {
      return {
        reply: tP('copilot.replies.methodMaxMethods', { n: 2 }),
        configUpdate: { method_selection: { ...ms, max_methods: 2 } },
      };
    }

    if (/optimal depth|auto depth|auto-depth|best depth|find depth|search depth|自动深度|最优深度|最佳深度/.test(t)) {
      return {
        reply: tP('copilot.replies.methodDepthOptimal'),
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false, depth_optimal: true } },
      };
    }

    if (/manual depth|fixed depth|固定深度|手动深度|关闭(自动|最优)深度/.test(t)) {
      return {
        reply: tP('copilot.replies.methodDepthManual'),
        configUpdate: { method_selection: { ...ms, depth_optimal: false } },
      };
    }

    const depthMatch = t.match(/(?:method\s+)?depth\s*(?:=|:|to)?\s*(\d+)|方法深度\s*[:=]?\s*(\d+)|深度\s*[:=]?\s*(\d+)/);
    if (depthMatch) {
      const d = Math.max(1, Math.min(500, parseInt(depthMatch[1] ?? depthMatch[2] ?? depthMatch[3] ?? '1', 10)));
      return {
        reply: tP('copilot.replies.methodDepth', { depth: d }),
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false, depth: d, depth_optimal: false } },
      };
    }

    if (/elaborate method|simulate method|score method|method by score|精细方法|方法评分|按评分选方法/.test(t)) {
      return {
        reply: tP('copilot.replies.methodElaborate'),
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false } },
      };
    }

    if (/by preference|prefer method|cascade method|preferred method|one by preference|按偏好|偏好方法|级联方法/.test(t)) {
      return {
        reply: tP('copilot.replies.methodPreference'),
        configUpdate: { method_selection: { ...ms, multiple: false, elaborate: false } },
      };
    }

    if (/earliest (commit|delivery|ship)|fastest (commit|delivery|ship)|soonest (commit|delivery|ship)|prioriti[sz]e (commit|delivery|ship)|最早交付|最快交付|优先交付|按交付/.test(t)) {
      return {
        reply: tP('copilot.replies.methodWeightCommit'),
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false, score_weights: { commit_time: 1, inventory_consumed: 0, purchase: 0 } } },
      };
    }

    if (/prefer inventor(y|ies)|use (existing )?inventor(y|ies)|favou?r inventor(y|ies)|consume inventor(y|ies)|existing stock|maximi[sz]e inventor(y|ies)|优先库存|使用库存|消耗库存|最多库存/.test(t)) {
      return {
        reply: tP('copilot.replies.methodWeightInventory'),
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false, score_weights: { commit_time: 0, inventory_consumed: 1, purchase: 0 } } },
      };
    }

    if (/minim(al|i[sz]e) (additional )?purchase|minim(al|i[sz]e) (additional )?buy|least purchase|least buy|fewest purchase|avoid purchase|最少采购|最小采购|减少采购|最少购买/.test(t)) {
      return {
        reply: tP('copilot.replies.methodWeightPurchase'),
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false, score_weights: { commit_time: 0, inventory_consumed: 0, purchase: 1 } } },
      };
    }

    if (/no purchase|disable purchase|disallow purchase|no buy|exclude buy|without purchase|禁用采购|不采购|不允许采购/.test(t)) {
      return {
        reply: tP('copilot.replies.purchaseOff'),
        configUpdate: { purchase_allowed: false },
      };
    }

    if (/allow purchase|enable purchase|purchase allowed|include buy|with purchase|允许采购|启用采购|开启采购/.test(t)) {
      return {
        reply: tP('copilot.replies.purchaseOn'),
        configUpdate: { purchase_allowed: true },
      };
    }

    if (/enable consolidat|turn on consolidat|consolidate demand|group demand|shared.?component|启用合并|开启合并|合并需求|共享组件/.test(t)) {
      const modeLabel = cs.allocation_mode === 'proportional'
        ? tP('copilot.replies.consolidationOnModeProportional')
        : cs.allocation_mode === 'priority_first'
          ? tP('copilot.replies.consolidationOnModePriority')
          : tP('copilot.replies.consolidationOnModeFair');
      return {
        reply: tP('copilot.replies.consolidationOn', { days: cs.period_days ?? 0, mode: modeLabel }),
        configUpdate: { consolidation: { ...cs, enabled: true } },
      };
    }

    if (/disable consolidat|turn off consolidat|no consolidat|禁用合并|关闭合并|不合并/.test(t)) {
      return {
        reply: tP('copilot.replies.consolidationOff'),
        configUpdate: { consolidation: { ...cs, enabled: false } },
      };
    }

    const periodMatch = t.match(/(\d+)\s*(?:-\s*)?day(?:s)?\s*(?:bucket|period|window)/);
    const zhPeriodMatch = t.match(/(\d+)\s*天(?:桶|窗口|周期)?/);
    if (periodMatch || zhPeriodMatch || /bucket.*(\d+)|period.*(\d+)|时间桶.*(\d+)/.test(t)) {
      const m2 = t.match(/(\d+)/);
      const days = Math.max(1, Math.min(365, parseInt(periodMatch?.[1] ?? zhPeriodMatch?.[1] ?? m2?.[1] ?? '365', 10)));
      return {
        reply: tP('copilot.replies.periodBucket', { days, plural: days === 1 ? '' : 's' }),
        configUpdate: { consolidation: { ...cs, period_days: days } },
      };
    }

    if (/proportional|split by (qty|quantity|share)|by share|按比例|按数量|按份额/.test(t)) {
      return {
        reply: tP('copilot.replies.splitProportional'),
        configUpdate: { consolidation: { ...cs, allocation_mode: 'proportional' } },
      };
    }

    if (/priority.?first|fill highest priority|by priority|priority order|优先级优先|按优先级|优先级顺序/.test(t)) {
      return {
        reply: tP('copilot.replies.splitPriority'),
        configUpdate: { consolidation: { ...cs, allocation_mode: 'priority_first' } },
      };
    }

    if (/\bfair\b|hybrid split|no one starved|公平|混合拆分/.test(t)) {
      return {
        reply: tP('copilot.replies.splitFair'),
        configUpdate: { consolidation: { ...cs, allocation_mode: 'fair' } },
      };
    }

    // Post-plan UI toggles.
    if (/(?:analyz|analys)e?\s*criticality|criticality\s*(?:on|enable|analysis)|enable\s*criticality|启用关键度|做关键度|开启关键度|开启临界|做临界/.test(t)) {
      return {
        reply: tP('copilot.replies.criticalityOn'),
        configUpdate: { analyze_criticality: true },
      };
    }
    if (/(?:no|skip|disable|turn off)\s*criticality|criticality\s*off|关闭关键度|不做关键度|停用关键度|不分析关键度|关闭临界/.test(t)) {
      return {
        reply: tP('copilot.replies.criticalityOff'),
        configUpdate: { analyze_criticality: false },
      };
    }
    if (/(?:check|validate|verify|run)\s*soundness|soundness\s*(?:check\s*)?(?:on|enable)|enable\s*soundness|开启完整性|校验完整性|做合理性|检查合理性|开启校验/.test(t)) {
      return {
        reply: tP('copilot.replies.soundnessOn'),
        configUpdate: { check_soundness: true },
      };
    }
    if (/(?:no|skip|disable|turn off)\s*soundness|soundness\s*off|关闭完整性|不做合理性|跳过校验|不校验/.test(t)) {
      return {
        reply: tP('copilot.replies.soundnessOff'),
        configUpdate: { check_soundness: false },
      };
    }

    if (/reset|default|clear|重置|默认|清除/.test(t)) {
      return {
        reply: tP('copilot.replies.reset'),
        configUpdate: { method_selection: { multiple: false, elaborate: false, depth: 1, depth_optimal: false, max_methods: 2, score_weights: { commit_time: 0.4, inventory_consumed: 0.35, purchase: 0.25 } }, purchase_allowed: false, consolidation: { enabled: true, period_days: 0, allocation_mode: 'fair', scope: 'leaf-only' }, analyze_criticality: false, check_soundness: true },
      };
    }

    return {
      reply: tP('copilot.replies.help'),
    };
  }

  useEffect(() => {
    copilotMessagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [copilotMessages]);

  // Persist chat history per case in localStorage so the conversation
  // survives navigating between pages or reopening the case. Capped at 100
  // messages to keep the storage entry small. The pending_job_id flow is
  // intentionally NOT persisted — if the user navigates away while a plan
  // is in flight, the in-flight tracking dies with the page; the user can
  // still inspect the saved run in the run-history list when they return.
  const COPILOT_HISTORY_LIMIT = 100;
  const copilotHistoryKey = (caseId: number) => `chat-history-${caseId}`;
  // Hydrate on case load. Reset to an empty list if storage has nothing for
  // this case (each case has its own conversation).
  useEffect(() => {
    if (!Number.isFinite(id)) return;
    try {
      const raw = window.localStorage.getItem(copilotHistoryKey(id));
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) setCopilotMessages(parsed as PlanningCopilotMessage[]);
        else setCopilotMessages([]);
      } else {
        setCopilotMessages([]);
      }
    } catch {
      setCopilotMessages([]);
    }
  }, [id]);
  // Persist on every change. Trim to the most recent COPILOT_HISTORY_LIMIT
  // entries before writing — long conversations otherwise grow without bound.
  useEffect(() => {
    if (!Number.isFinite(id)) return;
    try {
      const trimmed = copilotMessages.length > COPILOT_HISTORY_LIMIT
        ? copilotMessages.slice(-COPILOT_HISTORY_LIMIT)
        : copilotMessages;
      window.localStorage.setItem(copilotHistoryKey(id), JSON.stringify(trimmed));
    } catch {
      /* localStorage might be full or disabled — silently skip */
    }
  }, [copilotMessages, id]);

  // Mode 1 — in-flight chat: poll /plan/active-jobs every 1.5s. The first
  // running job (matching this case) drives the inline progress bar.
  useEffect(() => {
    if (!copilotLoading || id == null) {
      // Don't blank the bar if mode 2 is about to take over — clearing only
      // happens explicitly when the pending job completes / fails.
      if (!copilotPendingJobId) setCopilotActiveJob(null);
      return;
    }
    let cancelled = false;
    const tick = async () => {
      try {
        const jobs = await listActivePlanJobs(id);
        if (cancelled) return;
        // Pick the most-progressed running job (heuristic: highest current).
        const best = jobs.reduce<ActivePlanJob | null>((acc, j) => {
          if (!acc) return j;
          return (j.progress.current ?? 0) > (acc.progress.current ?? 0) ? j : acc;
        }, null);
        setCopilotActiveJob(best);
      } catch {
        /* ignore polling failures — they're transient */
      }
    };
    tick();
    const handle = setInterval(tick, 1500);
    return () => {
      cancelled = true;
      clearInterval(handle);
    };
  }, [copilotLoading, id, copilotPendingJobId]);  // eslint-disable-line react-hooks/exhaustive-deps

  // Mode 2 — post-response tracking: when the agent handed back a
  // pending_job_id (plan exceeded the 25s blocking window), keep polling
  // /plan/status/{jobId} until the plan finishes. On completion, append a
  // synthetic "✓ Plan run N completed" assistant message and refresh the
  // run-history list. On failure, append a "⚠ Plan failed" message. Mode 1
  // skips clearing copilotActiveJob while mode 2 is active so the progress
  // bar stays visible across new chat turns.
  useEffect(() => {
    if (!copilotPendingJobId || id == null) return;
    if (copilotLoading) return; // mode 1 owns the bar while a chat is in flight
    let cancelled = false;
    const jobId = copilotPendingJobId;
    const tick = async () => {
      try {
        const status = await getPlanStatus(id, jobId);
        if (cancelled) return;
        const progress = status.progress;
        if (progress) {
          setCopilotActiveJob({
            job_id: jobId,
            status: status.status,
            progress: { current: progress.current, total: progress.total },
          });
        }
        if (status.status === 'completed') {
          const delivery = status.result?.plan_kpis?.delivery;
          const fillPct = delivery?.fill_rate_pct;
          const tc = delivery?.total_committed;
          const tr = delivery?.total_requested;
          const planRunId = status.plan_run_id;
          const summary = (planRunId != null && fillPct != null && tc != null && tr != null)
            ? tP('copilot.planCompleted', {
                runId: planRunId,
                fillPct: fillPct.toFixed(2),
                committed: tc.toLocaleString(),
                requested: tr.toLocaleString(),
              })
            : tP('copilot.planCompletedNoKpis', { runId: planRunId ?? '?' });
          setCopilotMessages((prev) => [...prev, { role: 'assistant', text: summary }]);
          // Chat-driven runs are auto-saved server-side (runPlanBackground
          // with autoSave=true persists the result, promotes to 'success',
          // and seeds the KB). All we need to do here is fetch the persisted
          // row (canonicalised config, resolved depth) and load it into the
          // result panel — same shape as handleRestorePlanRun.
          if (planRunId != null) {
            (async () => {
              try {
                const full = await getPlanRun(id, planRunId);
                if (full.result) setPlanResult(full.result as typeof planResult);
                setCurrentPlanRunId(planRunId);
                setFreshPlanRunId(planRunId);
                setOverrideCandidateRunId(null);
                setPlanRunSaveError(null);
                setPlanWorkOrderPeggingCache({});
                if (full.config) {
                  const cfg = full.config as PlanningConfig;
                  const chosen = full.chosen_depth ?? null;
                  setPlanningConfig({
                    ...cfg,
                    method_selection: {
                      ...cfg.method_selection,
                      depth: chosen ?? cfg.method_selection?.depth ?? 1,
                      depth_optimal: false,
                    },
                  });
                }
                listPlanRuns(id).then(setPlanRunHistory).catch(() => { /* ignore */ });
              } catch {
                // Fall back to the in-memory result so the panel isn't blank.
                if (status.result) setPlanResult(status.result as typeof planResult);
                listPlanRuns(id).then(setPlanRunHistory).catch(() => { /* ignore */ });
              }
            })();
          }
          setCopilotPendingJobId(null);
          setCopilotActiveJob(null);
        } else if (status.status === 'failed') {
          const summary = tP('copilot.planFailed', { error: status.error ?? 'unknown' });
          setCopilotMessages((prev) => [...prev, { role: 'assistant', text: summary }]);
          setCopilotPendingJobId(null);
          setCopilotActiveJob(null);
        }
      } catch {
        /* transient */
      }
    };
    tick();
    const handle = setInterval(tick, 1500);
    return () => {
      cancelled = true;
      clearInterval(handle);
    };
  }, [copilotPendingJobId, id, copilotLoading]);  // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!planJobId || id == null) return;
    const poll = async () => {
      try {
        const st = await getPlanStatus(id, planJobId);
        if (st.progress) setPlanProgress(st.progress);
        if (st.status === 'completed' && st.result) {
          const freshId = st.plan_run_id ?? null;
          setPlanResult(st.result);
          setPlanRunSaveError(null);
          setPlanWorkOrderPeggingCache({});
          setSupplyCriticalityMap({});
          try { sessionStorage.removeItem(`criticality-case-${id}`); } catch { /* ignore */ }
          // Soundness needs a persisted run_id; criticality also requires save. If either
          // toggle is on, save first, then trigger their respective async work.
          const needsSave = (analyzeCriticalityEnabled || checkSoundnessEnabled) && freshId != null;
          if (needsSave && freshId && id != null) {
            // Auto-persist so impact/criticality and soundness can use the persisted run.
            // Carry the unsaved-banner's name/notes through — otherwise anything the user
            // typed (when soundness/criticality were off on a prior plan and the inputs
            // retained their value) is dropped silently when auto-save fires.
            let saved = false;
            try {
              await savePlanRun(Number(id), freshId, {
                name: freshRunName.trim() || undefined,
                notes: freshRunNotes.trim() || undefined,
              });
              setCurrentPlanRunId(freshId);
              setFreshPlanRunId(null);
              setOverrideCandidateRunId(null);
              setFreshRunName('');
              setFreshRunNotes('');
              saved = true;
            } catch {
              // Auto-save failed — leave as unsaved; user can save manually.
              setCurrentPlanRunId(null);
              setFreshPlanRunId(freshId);
            }
            if (analyzeCriticalityEnabled) setAutoCriticalityPending(true);
            // Fire-and-forget the soundness check — UI updates the per-row badge from
            // listPlanRuns polling, and the user can click the badge to open the report.
            if (saved && checkSoundnessEnabled) {
              setSoundnessChecking((prev) => ({ ...prev, [freshId]: true }));
              checkPlanRunSoundness(Number(id), freshId, { deep_check: true })
                .then(async () => {
                  try {
                    const updated = await listPlanRuns(Number(id));
                    setPlanRunHistory(updated);
                  } catch { /* ignore — badge will refresh on next history open */ }
                })
                .catch(() => { /* error surfaces via plan_run.soundness_status='error' */ })
                .finally(() => {
                  setSoundnessChecking((prev) => { const n = { ...prev }; delete n[freshId]; return n; });
                });
            }
          } else {
            setCurrentPlanRunId(null);
            setFreshPlanRunId(freshId);
          }
          setPlanJobId(null);
          setPlanLoading(false);
          setPlanProgress(null);
          setPlanError(null);
          if (planPollRef.current) {
            clearInterval(planPollRef.current);
            planPollRef.current = null;
          }
          return;
        }
        if (st.status === 'failed') {
          setPlanError(st.error ?? 'Plan failed');
          setPlanJobId(null);
          setPlanLoading(false);
          setPlanProgress(null);
          if (planPollRef.current) {
            clearInterval(planPollRef.current);
            planPollRef.current = null;
          }
        }
      } catch (e) {
        // 404 ⇒ the backend has no record of this job. Almost always means the
        // backend was rebuilt/restarted while a tab held a stale planJobId in
        // React state — the in-memory planJobs map is wiped on restart. Stop
        // polling instead of churning forever (the original code's bare
        // `catch {}` was the source of the "ghost curl" log spam).
        if ((e as { status?: number } | null)?.status === 404) {
          setPlanJobId(null);
          setPlanLoading(false);
          setPlanProgress(null);
          setPlanError(
            'Plan job no longer exists on the server (likely a backend restart). ' +
            'Page is out of sync — refresh to re-sync.'
          );
          if (planPollRef.current) { clearInterval(planPollRef.current); planPollRef.current = null; }
          return;
        }
        // Other (transient network, 5xx) — keep polling.
      }
    };
    poll();
    planPollRef.current = setInterval(poll, 1500);
    return () => {
      if (planPollRef.current) {
        clearInterval(planPollRef.current);
        planPollRef.current = null;
      }
    };
  }, [planJobId, id]);

  // Load case supplies once we have a plan result (fetched once; cleared when planResult is cleared).
  useEffect(() => {
    if (!planResult || !id) {
      setCaseSupplies([]);
      setCaseSuppliesError(null);
      return;
    }
    let cancelled = false;
    setCaseSuppliesLoading(true);
    getCaseSupplies(id)
      .then((rows) => { if (!cancelled) setCaseSupplies(rows); })
      .catch((e) => { if (!cancelled) setCaseSuppliesError(parseApiError(e)); })
      .finally(() => { if (!cancelled) setCaseSuppliesLoading(false); });
    return () => { cancelled = true; };
  }, [id, planResult]);

  // Auto-run criticality analysis once caseSupplies finishes loading after a plan run with analyze_criticality on.
  useEffect(() => {
    if (!autoCriticalityPending || caseSuppliesLoading || caseSupplies.length === 0) return;
    setAutoCriticalityPending(false);
    handleAnalyzeCriticality();
  // handleAnalyzeCriticality is stable enough for this use; listed deps drive the trigger condition
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoCriticalityPending, caseSuppliesLoading, caseSupplies.length]);

  // Persist criticality map to sessionStorage so it survives page swaps.
  useEffect(() => {
    if (!id || Object.keys(supplyCriticalityMap).length === 0 || criticalityRunning) return;
    const runKey: number | null = currentPlanRunId ?? (freshPlanRunId ?? null);
    try {
      sessionStorage.setItem(`criticality-case-${id}`, JSON.stringify({ runKey, map: supplyCriticalityMap }));
    } catch { /* ignore */ }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [supplyCriticalityMap]);

  // Load real BOM pairs and real move triples once we have a plan result.
  useEffect(() => {
    if (!planResult || !planResult.committed_demands.length || !id) {
      setBomRealPairs(null);
      setRealMoveTriples(null);
      return;
    }
    let cancelled = false;
    Promise.all([getBomRealPairs(id), getMovesWithTransit(id)])
      .then(([bomRes, moveRes]) => {
        if (!cancelled) {
          setBomRealPairs(bomRes.pairs ?? []);
          setRealMoveTriples(moveRes.moves ?? []);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setBomRealPairs([]);
          setRealMoveTriples([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, planResult]);

  // Reset active-demand selection when the user opens pegging for a different WO
  useEffect(() => {
    setWoPeggingActiveDemandId(null);
  }, [planPeggingContext]);

  // Reset assessment result/history when a different supply is opened in the
  // breakdown slide-in (where the assessment UI now lives).
  const currentExplainSupplyId = supExplainRow?.supplyId ?? null;
  useEffect(() => {
    if (currentExplainSupplyId) {
      setAssessmentResult(null);
      setAssessmentHistory([]);
      setAssessmentHistoryOpen(false);
      setAssessError(null);
    }
  }, [currentExplainSupplyId]);

  // Load assessment criteria lazily when the editor is opened for the first time
  useEffect(() => {
    if (!assessCriteriaOpen || assessCriteriaLoaded || !id) return;
    getAssessmentCriteria(id)
      .then((c) => {
        const val = c ?? '';
        setAssessCriteria(val);
        if (val) {
          const parts = parseCriteriaParts(val);
          setAssessCriteriaHigh(parts.high);
          setAssessCriteriaLow(parts.low);
          setAssessCriteriaMedium(parts.medium);
        } else {
          // No custom criteria saved — pre-fill with locale defaults as a starting point
          setAssessCriteriaHigh(tP('supplyView.assessment.criteriaDefaultHigh'));
          setAssessCriteriaLow(tP('supplyView.assessment.criteriaDefaultLow'));
          setAssessCriteriaMedium(tP('supplyView.assessment.criteriaDefaultMedium'));
        }
        setAssessCriteriaLoaded(true);
      })
      .catch(() => setAssessCriteriaLoaded(true));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assessCriteriaOpen, assessCriteriaLoaded, id]);

  // Fetch work-order pegging on demand when slide-in opens for a WO.
  // For consolidated WOs (demand_id=null) the pegging tree is always the shared/merged one;
  // demand tabs are informational only and must NOT change the fetch key.
  const woPeggingKey =
    planPeggingOpen && planPeggingContext?.type === 'work_order' && id
      ? (() => {
          const row = planPeggingContext.row as WorkOrder;
          const isConsolidated = row.demand_id == null;
          const demandPart = isConsolidated ? '' : String(woPeggingActiveDemandId ?? row.demand_id ?? '').trim();
          return `${demandPart}|${String(row.product_id ?? '').trim()}|${String(row.location_id ?? '').trim()}|${String(row.method ?? '').trim()}`;
        })()
      : null;
  useEffect(() => {
    if (!woPeggingKey || !id || planPeggingContext?.type !== 'work_order') return;
    const row = planPeggingContext.row as WorkOrder;
    if (planWorkOrderPeggingCache[woPeggingKey]) return;
    if (planWorkOrderPeggingLoading === woPeggingKey) return;
    // Consolidated WO: always fetch with demand_id='' so backend searches consolidated trees.
    const demand_id = row.demand_id == null ? '' : String(woPeggingActiveDemandId ?? row.demand_id ?? '').trim();
    const product_id = String(row.product_id ?? '').trim();
    const location_id = String(row.location_id ?? '').trim();
    const method = String(row.method ?? '').trim();
    if (!product_id || !location_id || !method) {
      const msg = `Missing work-order params (product_id=${product_id ? 'set' : 'empty'}, location_id=${location_id ? 'set' : 'empty'}, method=${method ? 'set' : 'empty'}).`;
      if (typeof console !== 'undefined' && console.warn) console.warn('[WO pegging]', msg);
      setPlanWorkOrderPeggingError(msg);
      return;
    }
    setPlanWorkOrderPeggingError(null);
    setPlanWorkOrderPeggingLoading(woPeggingKey);
    if (typeof console !== 'undefined' && console.log) console.log('[WO pegging] Fetching', { caseId: id, demand_id, product_id, location_id, method });
    getWorkOrderPegging(Number(id), { demand_id, product_id, location_id, method, ...(currentPlanRunId != null ? { run_id: currentPlanRunId } : {}) })
      .then((res) => {
        if (typeof console !== 'undefined' && console.log) console.log('[WO pegging] Loaded tree for', woPeggingKey);
        setPlanWorkOrderPeggingCache((prev) => ({ ...prev, [woPeggingKey]: res.tree }));
      })
      .catch((err) => {
        const message = parseApiError(err);
        if (typeof console !== 'undefined' && console.error) console.error('[WO pegging] Error', message, err);
        setPlanWorkOrderPeggingError(message);
      })
      .finally(() => {
        setPlanWorkOrderPeggingLoading(null);
      });
  }, [id, woPeggingKey, planPeggingContext?.type]);

  const basketInitialKeys = new Set(basketInitial.map((b) => b.key));

  function computeBasketAfterStep(stepIndex: number): { key: string; display: string; qty: number; fromInitialSupply: boolean }[] {
    const map = new Map<string, { display: string; qty: number }>();
    for (const b of basketInitial) {
      map.set(b.key, { display: b.display, qty: b.qty });
    }
    for (let i = 0; i < basketDeltas.length && i <= stepIndex; i++) {
      const d = basketDeltas[i];
      for (const p of d.purged ?? []) {
        const cur = map.get(p.key);
        if (cur) {
          cur.qty -= p.qty;
          if (cur.qty <= 0) map.delete(p.key);
        }
      }
      for (const a of d.added ?? []) {
        const cur = map.get(a.key);
        if (cur) cur.qty += a.qty;
        else map.set(a.key, { display: a.display, qty: a.qty });
      }
    }
    // Apply engine prunes: remove components unrelated to remaining allocation targets
    const keysToDelete: string[] = [];
    for (let i = 0; i < basketPrunes.length; i++) {
      const prune = basketPrunes[i];
      if (prune.after_step > stepIndex) continue;
      for (const compKey of prune.comp_keys ?? []) {
        for (const key of Array.from(map.keys())) {
          const comp = key.includes('|') ? key.replace(/\|[^|]*$/, '') : key;
          if (comp === compKey) keysToDelete.push(key);
        }
      }
    }
    for (const key of keysToDelete) map.delete(key);
    const items = Array.from(map.entries())
      .filter(([, v]) => v.qty > 0)
      .map(([key, v]) => ({ key, display: v.display, qty: v.qty, fromInitialSupply: basketInitialKeys.has(key) }));
    // Same scarcity as engine: sort by total qty per component (ascending), then key
    const compTotal = new Map<string, number>();
    for (const { key, qty } of items) {
      const comp = key.includes('|') ? key.replace(/\|[^|]*$/, '') : key;
      compTotal.set(comp, (compTotal.get(comp) ?? 0) + qty);
    }
    return items.sort((a, b) => {
      const compA = a.key.includes('|') ? a.key.replace(/\|[^|]*$/, '') : a.key;
      const compB = b.key.includes('|') ? b.key.replace(/\|[^|]*$/, '') : b.key;
      const totA = compTotal.get(compA) ?? 0;
      const totB = compTotal.get(compB) ?? 0;
      if (totA !== totB) return totA - totB;
      return a.key.localeCompare(b.key);
    });
  }
  const [activeView, setActiveView] = useState<'supply' | 'allocation' | 'suggested' | 'raw-material'>('supply');
  activeViewRef.current = activeView;
  selectedRunIdRef.current = selectedRunId;
  const [explanationOpen, setExplanationOpen] = useState(false);
  const [explanationLoading, setExplanationLoading] = useState(false);
  const [explanationData, setExplanationData] = useState<AllocationExplanation | null>(null);
  const [peggingOpen, setPeggingOpen] = useState(false);
  const [peggingLoading, setPeggingLoading] = useState(false);
  const [peggingTitle, setPeggingTitle] = useState('');
  const [peggingData, setPeggingData] = useState<{ direction: string; nodes: { id: string; label: string; type: string }[]; edges: { from: string; to: string; qty: number }[]; critical_path?: { path: string[] }; critical_paths_by_demand?: { component_key?: string; paths_by_demand: { demand_id: string; path: string[] }[] }; demand_id?: string; demand_root_id?: string; demand_allocated_qty?: number; demand_requested_qty?: number } | null>(null);
  const [peggingTreeReady, setPeggingTreeReady] = useState<boolean>(false);
  const [peggingExpanded, setPeggingExpanded] = useState<Set<string>>(new Set());
  const [peggingExpandingNodeId, setPeggingExpandingNodeId] = useState<string | null>(null);
  /** Node ids for which we have allowed rendering children (so first paint can show spinner before heavy subtree) */
  const [peggingChildrenAllowedFor, setPeggingChildrenAllowedFor] = useState<Set<string>>(new Set());
  /** For expanded nodes with many children: render only this many per frame so UI stays responsive */
  const [peggingPanelWidth, setPeggingPanelWidth] = useState(520);
  const peggingResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [peggingResizing, setPeggingResizing] = useState(false);
  const [supplyFilterConsumedOnly, setSupplyFilterConsumedOnly] = useState(false);
  const [basketSlideInRow, setBasketSlideInRow] = useState<AllocationViewRow | null>(null);

  const loadCase = async () => {
    try {
      const data = await getCase(id);
      setC(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load case');
    }
  };

  const loadRuns = async (allowAutoSelect = true) => {
    try {
      const data = await listRuns(id);
      setRuns(data);
      if (allowAutoSelect && data.length > 0 && !selectedRunId) {
        const completed = data.find((r) => r.status !== 'running');
        if (completed) setSelectedRunId(completed.id);
      }
    } catch {
      setRuns([]);
    }
  };

  const loadRunDetail = (runId: number) => {
    setRunDetail(null);
    setFeasibleDemands(null);
    setDemandsLoadError(null);
    setSupplyView([]);
    // Only clear allocation view when switching to a different run; keep current view while refreshing same run
    if (allocationViewRunIdRef.current !== null && allocationViewRunIdRef.current !== runId) {
      setAllocationView([]);
      setAllocationViewTruncated(null);
      setAllocationViewError(null);
      setBasketInitial([]);
      setBasketDeltas([]);
      setBasketFinal(null);
      setBasketPrunes([]);
      setAllocationActions([]);
      setAllocationActionsTotal(0);
      allocationViewFetchingRef.current = false;
    } else {
      setAllocationViewError(null);
    }
    allocationViewRunIdRef.current = runId;
    setSupplyViewLoading(true);
    getSupplyView(id, runId, currentPlanRunId != null ? { plan_run_id: currentPlanRunId } : undefined)
      .then((s) => setSupplyView(s.supply_view))
      .catch(() => setSupplyView([]))
      .finally(() => setSupplyViewLoading(false));
    getFeasibleDemands(id, runId)
      .then((d) => {
        setFeasibleDemands(d.feasible_demands);
        setDemandsLoadError(null);
      })
      .catch((e) => {
        setFeasibleDemands([]);
        setDemandsLoadError(e instanceof Error ? e.message : 'Failed to load demands');
      });
  };

  const loadOverrides = async () => {
    try {
      const data = await listOverrides(id);
      setOverrides(data);
    } catch {
      setOverrides([]);
    }
  };

  const restoreCriticality = (runKey: number | null) => {
    try {
      const stored = sessionStorage.getItem(`criticality-case-${id}`);
      if (!stored) return;
      const { runKey: storedKey, map } = JSON.parse(stored);
      if (storedKey === runKey) setSupplyCriticalityMap(map);
    } catch { /* ignore */ }
  };

  const loadLatestPlanRun = async () => {
    try {
      const runs = await listPlanRuns(id);
      setPlanRunHistory(runs);
      const latest = runs.find((r) => r.status === 'success');
      if (!latest) return;
      const full = await getPlanRun(id, latest.id);
      if (full.result) {
        setPlanResult(full.result as typeof planResult);
        setCurrentPlanRunId(latest.id);
        setPlanWorkOrderPeggingCache({});
        if (full.config) {
          const cfg = full.config as PlanningConfig;
          const chosen = full.chosen_depth ?? null;
          setPlanningConfig({
            ...cfg,
            method_selection: {
              ...cfg.method_selection,
              depth: chosen ?? cfg.method_selection?.depth ?? 1,
              depth_optimal: false,
            },
          });
        }
        restoreCriticality(latest.id);
      }
    } catch {
      // non-fatal — plan results simply won't be pre-loaded
    }
  };

  useEffect(() => {
    setLoading(true);
    setError(null);
    const timeoutId = setTimeout(() => setLoading(false), 20000);
    Promise.all([loadCase(), loadRuns(), loadOverrides(), loadLatestPlanRun()]).finally(() => {
      clearTimeout(timeoutId);
      setLoading(false);
    });
  }, [id]);

  useEffect(() => {
    if (selectedRunId) loadRunDetail(selectedRunId);
    else {
      setRunDetail(null);
      setFeasibleDemands(null);
      setDemandsLoadError(null);
      setAllocationView([]);
      setAllocationViewTruncated(null);
      setAllocationViewError(null);
      setBasketInitial([]);
      setBasketDeltas([]);
      setBasketFinal(null);
      setBasketPrunes([]);
      setAllocationActions([]);
      setAllocationActionsTotal(0);
      allocationViewRunIdRef.current = null;
      allocationViewFetchingRef.current = false;
    }
  }, [selectedRunId, id]);

  // Refetch supply-view when the active plan-run context changes so the Supplies panel
  // reflects plan_supply_allocation (plan-side utilization) instead of the legacy
  // allocation_action FIFO replay.
  useEffect(() => {
    if (!selectedRunId) return;
    setSupplyViewLoading(true);
    getSupplyView(id, selectedRunId, currentPlanRunId != null ? { plan_run_id: currentPlanRunId } : undefined)
      .then((s) => setSupplyView(s.supply_view))
      .catch(() => setSupplyView([]))
      .finally(() => setSupplyViewLoading(false));
  }, [currentPlanRunId, selectedRunId, id]);

  // Lazy-load allocation view only when user opens the Allocation tab (scalable: no heavy request on run select)
  useEffect(() => {
    if (
      activeView !== 'allocation'
      || !selectedRunId
      || allocationViewRunIdRef.current !== selectedRunId
      || allocationView.length > 0
      || allocationViewLoading
      || allocationViewFetchingRef.current
    ) return;
    allocationViewFetchingRef.current = true;
    setAllocationViewLoading(true);
    // Load in one go: full view up to backend cap (5000 actions). Basket loaded on-demand when user opens basket slide-in.
    getAllocationView(id, selectedRunId, {
      max_actions: 5000,
      from_step: 1,
      to_step: 100000,
      skip_basket: true,
    })
      .then((a) => {
        if (allocationViewRunIdRef.current === selectedRunId) {
          setAllocationView(a.allocation_view);
          // Basket is loaded on-demand when user opens the basket slide-in (skip_basket: true)
          setBasketInitial(a.basket_initial ?? []);
          setBasketDeltas(a.basket_deltas ?? []);
          setBasketFinal(a.basket_final ?? null);
          setBasketPrunes(a.basket_prunes ?? []);
          const totalSteps = a.total_steps ?? a.allocation_view.length;
          setAllocationViewTruncated(
            totalSteps > 0
              ? { total_actions: a.total_actions ?? 0, limit: a.limit ?? 5000, total_steps: totalSteps }
              : null,
          );
          setAllocationViewError(null);
        }
      })
      .catch(() => {
        if (allocationViewRunIdRef.current === selectedRunId) {
          setAllocationViewError('Full allocation view unavailable for this run (timeout or too large).');
        }
      })
      .finally(() => {
        allocationViewFetchingRef.current = false;
        setAllocationViewLoading(false);
      });
  }, [activeView, selectedRunId, id, allocationView.length, allocationViewLoading]);

  // Load basket on-demand when user opens the basket slide-in and we don't have basket data yet
  useEffect(() => {
    const slideInOpen = basketSlideInRow != null || basketShowingFinal;
    if (!slideInOpen || basketDeltas.length > 0 || !id || !selectedRunId || basketLoading) return;
    setBasketLoading(true);
    getAllocationViewBasket(id, selectedRunId, { max_actions: 5000 })
      .then((a) => {
        if (allocationViewRunIdRef.current === selectedRunId) {
          setBasketInitial(a.basket_initial ?? []);
          setBasketDeltas(a.basket_deltas ?? []);
          setBasketFinal(a.basket_final ?? null);
          setBasketPrunes(a.basket_prunes ?? []);
        }
      })
      .catch(() => {})
      .finally(() => setBasketLoading(false));
  }, [basketSlideInRow, basketShowingFinal, basketDeltas.length, id, selectedRunId, basketLoading]);

  // When allocation view fails, auto-load lightweight raw steps so user still sees data
  useEffect(() => {
    if (
      !allocationViewError
      || activeView !== 'allocation'
      || !selectedRunId
      || allocationActions.length > 0
      || allocationActionsLoading
    ) return;
    setAllocationActionsLoading(true);
    getAllocationActions(id, selectedRunId, { offset: 0, limit: 500 })
      .then((d) => {
        if (allocationViewRunIdRef.current === selectedRunId) {
          setAllocationActions(d.actions);
          setAllocationActionsTotal(d.total_count);
          setAllocationActionsOffset(0);
        }
      })
      .catch(() => {})
      .finally(() => setAllocationActionsLoading(false));
  }, [allocationViewError, activeView, selectedRunId, id, allocationActions.length, allocationActionsLoading]);

  // Fetch raw material usage report when Raw material usage view is active
  useEffect(() => {
    if (activeView !== 'raw-material' || !selectedRunId) {
      return;
    }
    setRawMaterialReportLoading(true);
    setRawMaterialReport(null);
    getRawMaterialUsage(id, selectedRunId)
      .then(setRawMaterialReport)
      .catch(() => setRawMaterialReport(null))
      .finally(() => setRawMaterialReportLoading(false));
  }, [activeView, selectedRunId, id]);

  // Load material events when Material Impact section becomes active
  useEffect(() => {
    if (caseSection !== 'material') return;
    setMaterialEventsLoading(true);
    listMaterialEvents(id)
      .then(setMaterialEvents)
      .catch(() => {})
      .finally(() => setMaterialEventsLoading(false));
  }, [caseSection, id]);

  useEffect(() => {
    if (!peggingResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = peggingResizeRef.current;
      if (!r) return;
      const delta = r.startX - e.clientX;
      setPeggingPanelWidth(Math.min(window.innerWidth * 0.9, Math.max(320, r.startW + delta)));
    };
    const onUp = () => {
      peggingResizeRef.current = null;
      setPeggingResizing(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [peggingResizing]);

  useEffect(() => {
    if (!planPeggingResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = planPeggingResizeRef.current;
      if (!r) return;
      const delta = r.startX - e.clientX;
      setPlanPeggingPanelWidth(Math.min(window.innerWidth * 0.9, Math.max(320, r.startW + delta)));
    };
    const onUp = () => {
      planPeggingResizeRef.current = null;
      setPlanPeggingResizing(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [planPeggingResizing]);

  useEffect(() => {
    if (!copilotResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = copilotResizeRef.current;
      if (!r) return;
      const delta = r.startX - e.clientX;
      setCopilotPanelWidth(Math.min(window.innerWidth * 0.9, Math.max(320, r.startW + delta)));
    };
    const onUp = () => {
      copilotResizeRef.current = null;
      setCopilotResizing(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [copilotResizing]);

  useEffect(() => {
    if (!woExplainResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = woExplainResizeRef.current;
      if (!r) return;
      setWoExplainPanelWidth(Math.min(window.innerWidth * 0.9, Math.max(280, r.startW + (r.startX - e.clientX))));
    };
    const onUp = () => { woExplainResizeRef.current = null; setWoExplainResizing(false); window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
  }, [woExplainResizing]);

  useEffect(() => {
    if (!supExplainResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = supExplainResizeRef.current;
      if (!r) return;
      setSupExplainPanelWidth(Math.min(window.innerWidth * 0.9, Math.max(280, r.startW + (r.startX - e.clientX))));
    };
    const onUp = () => { supExplainResizeRef.current = null; setSupExplainResizing(false); window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
  }, [supExplainResizing]);

  useEffect(() => {
    if (!planRunHistoryResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = planRunHistoryResizeRef.current;
      if (!r) return;
      setPlanRunHistoryPanelWidth(Math.min(window.innerWidth * 0.9, Math.max(280, r.startW + (r.startX - e.clientX))));
    };
    const onUp = () => { planRunHistoryResizeRef.current = null; setPlanRunHistoryResizing(false); window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
  }, [planRunHistoryResizing]);

  useEffect(() => {
    if (!bootstrapResizing) return;
    const onMove = (e: MouseEvent) => {
      const r = bootstrapResizeRef.current;
      if (!r) return;
      setBootstrapPanelWidth(Math.min(window.innerWidth * 0.95, Math.max(420, r.startW + (r.startX - e.clientX))));
    };
    const onUp = () => { bootstrapResizeRef.current = null; setBootstrapResizing(false); window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp); };
  }, [bootstrapResizing]);

  // Defer tree building so first paint shows counts and "Building tree…" instead of blocking on huge graph
  useEffect(() => {
    if (!peggingData) {
      setPeggingTreeReady(false);
      return;
    }
    const nodes = Array.isArray(peggingData.nodes) ? peggingData.nodes : [];
    const edges = Array.isArray(peggingData.edges) ? peggingData.edges : [];
    const isSupplyToDemand = peggingData.direction === 'supply-to-demand';
    const rootId = isSupplyToDemand
      ? (peggingData.critical_paths_by_demand?.component_key ?? peggingData.critical_paths_by_demand?.paths_by_demand?.[0]?.path?.[0] ?? edges[0]?.from)
      : (peggingData.critical_path?.path?.[0] ?? edges[0]?.to);
    const rootPathKey = rootId ? pathKeyFromPath([rootId]) : '';
    const t = setTimeout(() => {
      setPeggingTreeReady(true);
      setPeggingExpanded(rootPathKey ? new Set([rootPathKey]) : new Set());
      setPeggingChildrenAllowedFor(rootPathKey ? new Set([rootPathKey]) : new Set());
    }, 0);
    return () => clearTimeout(t);
  }, [peggingData]);

  // Supply filter typeahead: scroll selected item into view
  useEffect(() => {
    if (supplyFilterSuggestionIndex >= 0 && supplyFilterListRef.current) {
      const item = supplyFilterListRef.current.children[supplyFilterSuggestionIndex] as HTMLElement | undefined;
      item?.scrollIntoView({ block: 'nearest' });
    }
  }, [supplyFilterSuggestionIndex]);

  const handleSupplyFilterInputChange = useCallback((val: string) => {
    setSupplyFilterInput(val);
    setSupplyFilterSuggestionIndex(-1);
    if (!val.trim()) {
      setShowSupplyFilterSuggestions(false);
      setSupplyFilterSuggestions([]);
      setPlanDemandSupplyFilter('');
      return;
    }
    if (supplyFilterDebounce.current) clearTimeout(supplyFilterDebounce.current);
    supplyFilterDebounce.current = setTimeout(async () => {
      try {
        const res = await fetch(`/allocator/api/supplies?q=${encodeURIComponent(val.trim())}`);
        if (res.ok) {
          setSupplyFilterSuggestions(await res.json());
          setShowSupplyFilterSuggestions(true);
        }
      } catch { /* backend unreachable */ }
    }, 200);
  }, []);

  const selectSupplyFilterSuggestion = useCallback((s: SupplySuggestion) => {
    setSupplyFilterInput(s.id);
    setPlanDemandSupplyFilter(s.id);
    setShowSupplyFilterSuggestions(false);
    setSupplyFilterSuggestions([]);
    setSupplyFilterSuggestionIndex(-1);
  }, []);

  // Pegging data comes from API (getPegging): nodes and edges (each edge has from, to, qty).
  // For "allocated" on demand children we use edge.qty (inv→demand = share from that parent node).
  const peggingGraph = useMemo(() => {
    if (!peggingData) return null;
    const nodes = Array.isArray(peggingData.nodes) ? peggingData.nodes : [];
    const edges = Array.isArray(peggingData.edges) ? peggingData.edges : [];
    const nodeById = Object.fromEntries(nodes.map((n) => [n.id, n]));
    const pathSet = new Set<string>([
      ...(peggingData.critical_path?.path ?? []),
      ...(peggingData.critical_paths_by_demand?.paths_by_demand?.flatMap((p) => p.path) ?? []),
    ]);
    const outEdges: Record<string, { to: string; qty: number }[]> = {};
    const inEdges: Record<string, { from: string; qty: number }[]> = {};
    edges.forEach((e) => {
      if (!outEdges[e.from]) outEdges[e.from] = [];
      outEdges[e.from].push({ to: e.to, qty: e.qty });
      if (!inEdges[e.to]) inEdges[e.to] = [];
      inEdges[e.to].push({ from: e.from, qty: e.qty });
    });
    const isSupplyToDemand = peggingData.direction === 'supply-to-demand';
    const demandRootId = (peggingData as { demand_root_id?: string }).demand_root_id ?? ((peggingData as { demand_id?: string }).demand_id != null ? `demand|${(peggingData as { demand_id: string }).demand_id}` : undefined);
    const rootId = isSupplyToDemand
      ? (peggingData.critical_paths_by_demand?.component_key ?? peggingData.critical_paths_by_demand?.paths_by_demand?.[0]?.path?.[0] ?? edges[0]?.from)
      : (demandRootId ?? peggingData.critical_path?.path?.[0] ?? edges[0]?.to);
    // Traverse by edge structure only (aligned with backend: from=component, to=variant).
    // supply-to-demand: children = outEdges (toward demand). demand-to-supply: root = demand node so all contributors (inv→demand edges) appear as children.
    function getChildren(nodeId: string): { nextId: string; qty: number }[] {
      if (isSupplyToDemand) {
        return (outEdges[nodeId] ?? []).map((item) => ({ nextId: item.to, qty: item.qty }));
      }
      return (inEdges[nodeId] ?? []).map((item) => ({ nextId: item.from, qty: item.qty }));
    }
    return { nodeById, pathSet, rootId, getChildren, nodes, edges };
  }, [peggingData]);

  // Full "entityType|entityKey" set + per-WO helper for precise pending-override detection
  const woHasSavedOverride = useMemo(() => {
    const s = new Set<string>();
    for (const o of overrides) s.add(`${o.entity_type}|${o.entity_key}`);
    return (r: WorkOrder): boolean => {
      const productId = r.product_id ?? '';
      const locationId = r.location_id ?? '';
      const demandId = r.demand_id ?? '';
      const methodKey = demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`;
      const splitKey = r.start_time ? `${productId}|${locationId}|${r.start_time.slice(0, 10)}` : `${productId}|${locationId}`;
      return s.has(`method_selection|${methodKey}`) || s.has(`component_split|${splitKey}`);
    };
  }, [overrides]);

  // DFS pre-order traversal of planning_pegging trees → "demandId|productId|locationId|method" → order index
  const pegOrderMap = useMemo(() => {
    const order: string[] = [];
    function visit(node: PlanningPeggingNode, demandId: string) {
      const nodeDemand = node.demand_id ?? demandId;
      if (node.type === 'work_order') {
        order.push(`${nodeDemand}|${node.product_id ?? ''}|${node.location_id ?? ''}|${node.method ?? ''}`);
        for (const child of node.children ?? []) visit(child, nodeDemand);
      } else {
        for (const child of node.children ?? []) visit(child, nodeDemand);
      }
    }
    for (const entry of planResult?.planning_pegging ?? []) {
      visit(entry.tree, entry.demand_id ?? '');
    }
    const map = new Map<string, number>();
    order.forEach((k, i) => { if (!map.has(k)) map.set(k, i); });
    return map;
  }, [planResult]);

  // Direct-edge graph of WO → tree-parent WOs (downstream consumers) and tree-child WOs (upstream suppliers).
  // Key format matches pegOrderMap: "demandId|productId|locationId|method".
  const woPegRelations = useMemo(() => {
    const parentMap = new Map<string, Set<string>>(); // childKey → set of parent (downstream) keys
    const childMap = new Map<string, Set<string>>();  // parentKey → set of child (upstream) keys
    function visit(node: PlanningPeggingNode, ancestorWoKey: string | null, demandId: string) {
      const nodeDemand = node.demand_id ?? demandId;
      let myKey: string | null = null;
      if (node.type === 'work_order') {
        myKey = `${nodeDemand}|${node.product_id ?? ''}|${node.location_id ?? ''}|${node.method ?? ''}`;
        if (ancestorWoKey) {
          if (!parentMap.has(myKey)) parentMap.set(myKey, new Set());
          parentMap.get(myKey)!.add(ancestorWoKey);
          if (!childMap.has(ancestorWoKey)) childMap.set(ancestorWoKey, new Set());
          childMap.get(ancestorWoKey)!.add(myKey);
        }
      }
      const nextAncestor = myKey ?? ancestorWoKey;
      for (const child of node.children ?? []) visit(child, nextAncestor, nodeDemand);
    }
    for (const entry of planResult?.planning_pegging ?? []) {
      visit(entry.tree, null, entry.demand_id ?? '');
    }
    return { parentMap, childMap };
  }, [planResult]);

  // 4-part WO keys for a row, considering multi-demand consolidation.
  const woRowPegKeys = useCallback((r: WoEnrichedRow): string[] => {
    const ids = (r._demand_ids?.length ? r._demand_ids : [r.demand_id ?? ''])
      .filter((d): d is string => d != null);
    return ids.map((d) => `${d}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`);
  }, []);

  // Transitive ancestor / descendant sets for the highlighted WO row.
  // ancestors = downstream (consumers, closer to demand), descendants = upstream (suppliers).
  const woPegHighlightSets = useMemo(() => {
    const ancestors = new Set<string>();
    const descendants = new Set<string>();
    if (!woPegHighlightRow) return { ancestors, descendants };
    const seedKeys = woRowPegKeys(woPegHighlightRow);
    const bfs = (start: string, edges: Map<string, Set<string>>, out: Set<string>) => {
      const queue = [start];
      while (queue.length) {
        const k = queue.shift()!;
        const next = edges.get(k);
        if (!next) continue;
        next.forEach((n) => { if (!out.has(n)) { out.add(n); queue.push(n); } });
      }
    };
    for (const sk of seedKeys) {
      bfs(sk, woPegRelations.parentMap, ancestors);
      bfs(sk, woPegRelations.childMap, descendants);
    }
    return { ancestors, descendants };
  }, [woPegHighlightRow, woPegRelations, woRowPegKeys]);

  // Clear highlight if the highlighted row no longer exists in the new plan run.
  useEffect(() => {
    if (woPegHighlightRow && planResult) {
      const stillExists = planResult.work_orders?.some((w) =>
        w.product_id === woPegHighlightRow.product_id
          && w.location_id === woPegHighlightRow.location_id
          && (w.method ?? '') === (woPegHighlightRow.method ?? '')
          && (w.demand_id ?? '') === (woPegHighlightRow.demand_id ?? ''),
      );
      if (!stillExists) setWoPegHighlightRow(null);
    }
  }, [planResult, woPegHighlightRow]);

  /**
   * Invert demand-centric planning_pegging trees into a supply-centric map.
   * Key: supply_id → { totalPeggedQty, demands[] }
   *
   * For consolidated entries (demand_id = null), attribution is driven by each demand's
   * ACTUAL consumption of its tagged bucket (`consolidated_<demandId>_<pid>`) as observed
   * in the demand's own main pegging tree. Demands whose upstream chain failed before
   * reaching the tagged bucket contribute zero weight and are not credited — this keeps
   * the Supply View's `Pegged Demands` count consistent with what's findable via the
   * demand-pegging search.
   */
  const supplyPeggingMap = useMemo(() => {
    const map = new Map<string, { totalPeggedQty: number; demands: PeggedDemandEntry[] }>();
    if (!planResult?.planning_pegging) return map;

    const demandCustomerMap = new Map<string, string | null>();
    for (const d of planResult.committed_demands ?? []) {
      if (d.demand_id) demandCustomerMap.set(d.demand_id, d.customer ?? null);
    }

    function addPegging(sid: string, qty: number, demandId: string) {
      if (qty <= 1e-9) return;
      const existing = map.get(sid);
      if (existing) {
        const existingForDemand = existing.demands.find((d) => d.demandId === demandId);
        if (existingForDemand) {
          existingForDemand.qtyConsumed += qty;
        } else {
          existing.demands.push({ demandId, customer: demandCustomerMap.get(demandId) ?? null, qtyConsumed: qty });
        }
        existing.totalPeggedQty += qty;
      } else {
        map.set(sid, { totalPeggedQty: qty, demands: [{ demandId, customer: demandCustomerMap.get(demandId) ?? null, qtyConsumed: qty }] });
      }
    }

    // ── Pre-pass: build per-demand tagged-bucket consumption map from main-loop entries.
    // Key: "<pid>|<lid>" → demandId → actual qty the demand consumed from its
    // `consolidated_<demandId>_<pid>` bucket (for that product/location).
    const taggedConsumption = new Map<string, Map<string, number>>();
    const collectTagged = (node: PlanningPeggingNode, entryDemandId: string): void => {
      if (node.type === 'supply' && node.supply_id && node.supply_id.startsWith(`consolidated_${entryDemandId}_`)) {
        const pid = node.product_id ?? '';
        const lid = node.location_id ?? '';
        const qty = Number(node.quantity ?? 0);
        if (qty > 1e-9 && pid) {
          const key = `${pid}|${lid}`;
          let inner = taggedConsumption.get(key);
          if (!inner) { inner = new Map(); taggedConsumption.set(key, inner); }
          inner.set(entryDemandId, (inner.get(entryDemandId) ?? 0) + qty);
        }
      }
      for (const child of node.children ?? []) collectTagged(child, entryDemandId);
    };
    for (const entry of planResult.planning_pegging) {
      // Only main-loop entries carry a specific demand_id and are NOT flagged consolidated/passthrough.
      if (!entry.demand_id || entry.consolidated || entry.passthrough) continue;
      collectTagged(entry.tree, entry.demand_id);
    }

    // ── Walk pass.
    // For non-consolidated entries: peg straight to the entry's demand.
    // For consolidated entries: distribute each real supply leaf across the demands that
    // actually consumed the root-level tagged bucket, proportional to that consumption.
    const walk = (
      node: PlanningPeggingNode,
      activeDemandId: string | null,
      consolidatedWeights: Map<string, number> | null,
    ): void => {
      const effectiveDemandId = node.type === 'demand' && node.demand_id ? node.demand_id : activeDemandId;

      if (node.type === 'supply' && node.supply_id) {
        const sid = node.supply_id;
        const qty = Number(node.quantity ?? 0);
        if (qty > 1e-9) {
          if (effectiveDemandId) {
            // Normal (non-consolidated): peg to the specific demand.
            // Skip synthetic tagged buckets — the physical supplies underlying them are
            // credited via the consolidated entry so we do not double-count.
            if (!sid.startsWith('consolidated_')) addPegging(sid, qty, effectiveDemandId);
          } else if (consolidatedWeights && consolidatedWeights.size > 0) {
            let total = 0;
            consolidatedWeights.forEach((w) => { total += w; });
            if (total > 1e-9) {
              consolidatedWeights.forEach((w, demandId) => {
                if (w > 1e-9) addPegging(sid, (qty * w) / total, demandId);
              });
            }
            // If total === 0 (no demand actually consumed), we intentionally drop this
            // contribution rather than fabricating recipients.
          }
        }
      }
      for (const child of node.children ?? []) walk(child, effectiveDemandId, consolidatedWeights);
    };

    for (const entry of planResult.planning_pegging) {
      if (entry.passthrough && entry.demand_id) {
        // Single-demand consolidation passthrough: all real supplies go to this demand.
        // The main-loop entry for this demand stops at the synthetic tagged bucket, which
        // we skip above — so this entry is the one that credits real supplies.
        walk(entry.tree, entry.demand_id, null);
      } else if (entry.consolidated && !entry.demand_id) {
        // Multi-demand consolidated: distribute real supplies across the demands that
        // share this group. Prefer the backend-supplied `per_demand_allocations` weights
        // (covers raw-material groups where there is no synthetic tagged bucket); fall
        // back to the tagged-consumption inversion for older runs without that field.
        let narrowed: Map<string, number> | null = null;
        if (entry.per_demand_allocations && Object.keys(entry.per_demand_allocations).length > 0) {
          narrowed = new Map();
          for (const [demandId, w] of Object.entries(entry.per_demand_allocations)) {
            if (typeof w === 'number' && w > 1e-9) narrowed.set(demandId, w);
          }
          if (narrowed.size === 0) narrowed = null;
        }
        if (!narrowed) {
          const rootPid = entry.tree?.product_id ?? '';
          const rootLid = entry.tree?.location_id ?? '';
          const weights = taggedConsumption.get(`${rootPid}|${rootLid}`) ?? null;
          if (weights && entry.consolidated_demand_ids && entry.consolidated_demand_ids.length > 0) {
            narrowed = new Map();
            for (const demandId of entry.consolidated_demand_ids) {
              const w = weights.get(demandId);
              if (w && w > 1e-9) narrowed.set(demandId, w);
            }
          } else if (weights) {
            narrowed = weights;
          }
        }
        walk(entry.tree, null, narrowed);
      } else if (entry.demand_id && !entry.consolidated && !entry.passthrough) {
        // Main-loop entry for a specific demand — supply leaves peg straight to it.
        walk(entry.tree, entry.demand_id, null);
      }
    }
    return map;
  }, [planResult]);

  /** Sum of initial_qty per product_id across all supply view rows (unfiltered). */
  const supplyProductTotalMap = useMemo((): Record<string, number> => {
    const m: Record<string, number> = {};
    for (const r of supplyView) {
      const pid = r.product_id ?? '';
      if (pid) m[pid] = (m[pid] ?? 0) + (Number(r.initial_qty) || 0);
    }
    return m;
  }, [supplyView]);

  /**
   * Build a supply_id → total consumed qty map from supply_allocations (written on plan save).
   * Falls back gracefully to undefined when the field is absent (pre-save preview).
   */
  const supplyConsumedMap = useMemo(() => {
    const m = new Map<string, number>();
    for (const a of planResult?.supply_allocations ?? []) {
      m.set(a.supply_id, (m.get(a.supply_id) ?? 0) + a.qty_consumed);
    }
    return m;
  }, [planResult?.supply_allocations]);

  /**
   * Map supply_id → SupplySplitInfo for supplies consumed by a consolidated WO.
   * Walks each consolidated pegging entry's tree to find real supply leaves and
   * attaches the group's policy / total-need / produced figures from any matching
   * consolidated work order (all WOs in the group share the same split details).
   */
  const supplySplitInfoMap = useMemo(() => {
    // supplyId → list of SupplySplitInfo, one per consolidation group that drew from this
    // supply. Deduped by (groupProductId, groupLocationId): the same group may appear via
    // multiple sub-trees of a pegging entry, but should be counted once. Multiple distinct
    // groups for the same supply are common — a deep raw material is typically pulled by
    // every merged-leaf group whose internal plan() walks the BOM down to it.
    const map = new Map<string, SupplySplitInfo[]>();
    if (!planResult?.planning_pegging) return map;

    // Index consolidated WOs by "pid|lid" → split details (first match wins).
    // Raw-material groups have no WO (consumed from inventory directly), so this lookup
    // may miss — we fall back to deriving split info from the pegging entry itself below.
    const woSplitByGroup = new Map<string, { mode: string; totalPlanned: number; splitDetails: Array<{ demand_id: string | null; requested_qty: number; allocated_qty: number; priority: number; parent_product: string }> }>();
    let fallbackMode: string | null = null;
    for (const wo of planResult.work_orders ?? []) {
      if (!wo.wo_consolidation_split_mode) continue;
      if (!fallbackMode) fallbackMode = wo.wo_consolidation_split_mode;
      const key = `${wo.product_id ?? ''}|${wo.location_id ?? ''}`;
      if (woSplitByGroup.has(key)) continue;
      woSplitByGroup.set(key, {
        mode: wo.wo_consolidation_split_mode,
        totalPlanned: wo.wo_consolidation_total_planned ?? 0,
        splitDetails: wo.wo_consolidation_split_details ?? [],
      });
    }

    const collectSupplies = (node: PlanningPeggingNode, acc: Set<string>): void => {
      if (node.type === 'supply' && node.supply_id && !node.supply_id.startsWith('consolidated_')) {
        acc.add(node.supply_id);
      }
      for (const child of node.children ?? []) collectSupplies(child, acc);
    };

    for (const entry of planResult.planning_pegging) {
      if (!entry.consolidated || entry.demand_id) continue;
      const rootPid = entry.tree?.product_id ?? '';
      const rootLid = entry.tree?.location_id ?? '';
      const split = woSplitByGroup.get(`${rootPid}|${rootLid}`);

      // Prefer backend-supplied per_demand_allocations (covers raw-material groups too);
      // fall back to deriving from split details when not present.
      const perDemandAllocations: Record<string, number> | null = entry.per_demand_allocations
        ? Object.fromEntries(
            Object.entries(entry.per_demand_allocations).filter(([, v]) => typeof v === 'number' && (v as number) > 1e-9) as [string, number][]
          )
        : null;

      let info: SupplySplitInfo;
      if (split) {
        const totalNeed = split.splitDetails.reduce((s, d) => s + (d.requested_qty ?? 0), 0);
        const fallbackPda: Record<string, number> = {};
        for (const d of split.splitDetails) {
          if (d.demand_id && d.allocated_qty > 1e-9) fallbackPda[d.demand_id] = d.allocated_qty;
        }
        info = {
          mode: split.mode,
          groupProductId: rootPid,
          groupLocationId: rootLid,
          groupTotalNeed: totalNeed,
          groupTotalProduced: split.totalPlanned,
          candidateCount: split.splitDetails.length,
          perDemandAllocations: perDemandAllocations ?? (Object.keys(fallbackPda).length > 0 ? fallbackPda : null),
          policySource: 'wo',
        };
      } else {
        // Fallback: raw-material group (no WO). Derive from the pegging entry itself.
        const tree = entry.tree;
        const totalNeed = Number(tree?.quantity ?? 0);
        const committedRaw = (tree as PlanningPeggingNode & { committed_qty?: number })?.committed_qty;
        const totalProduced = Number(committedRaw ?? 0);
        const candidateCount = entry.consolidated_demand_ids?.length ?? 0;
        if (!rootPid || (totalNeed <= 0 && totalProduced <= 0)) continue;
        info = {
          mode: fallbackMode ?? (planningConfig.consolidation?.allocation_mode ?? 'fair'),
          groupProductId: rootPid,
          groupLocationId: rootLid,
          groupTotalNeed: totalNeed,
          groupTotalProduced: totalProduced,
          candidateCount,
          perDemandAllocations,
          policySource: 'config',
        };
      }

      const sids = new Set<string>();
      collectSupplies(entry.tree, sids);
      const groupKey = `${info.groupProductId}|${info.groupLocationId}`;
      sids.forEach((sid) => {
        const list = map.get(sid);
        if (!list) {
          map.set(sid, [info]);
        } else if (!list.some((x) => `${x.groupProductId}|${x.groupLocationId}` === groupKey)) {
          list.push(info);
        }
      });
    }

    // scope=all path: when planResult carries supply_level_allocations,
    // each record is a per-supply_id allocation summary that becomes its own
    // SupplySplitInfo entry. Multiple records for the same supply (different
    // groupKeys) are deduped by groupProductId|groupLocationId, just like the
    // scope=leaf-only path above. scope=leaf-only emits an empty list here,
    // so this loop is a no-op under it.
    for (const rec of planResult.supply_level_allocations ?? []) {
      const info: SupplySplitInfo = {
        mode: rec.mode,
        groupProductId: rec.group_product_id,
        groupLocationId: rec.group_location_id,
        groupTotalNeed: rec.group_total_need,
        groupTotalProduced: rec.group_total_produced,
        candidateCount: rec.candidate_count,
        perDemandAllocations: rec.per_demand_allocations,
        policySource: 'config',
      };
      const groupKey = `${info.groupProductId}|${info.groupLocationId}`;
      const list = map.get(rec.supply_id);
      if (!list) {
        map.set(rec.supply_id, [info]);
      } else if (!list.some((x) => `${x.groupProductId}|${x.groupLocationId}` === groupKey)) {
        list.push(info);
      }
    }

    return map;
  }, [planResult, planningConfig]);

  /**
   * Map (supplyId, demandId) → "<groupPid>@<groupLid>" describing the consolidation path
   * the demand took to reach this supply. Covers BOTH:
   *   - multi-demand consolidated entries (entry.consolidated && !entry.demand_id):
   *     the per_demand_allocations keys link to the entry's tree pid/lid
   *   - passthrough entries (entry.passthrough && entry.demand_id): single-demand
   *     consolidation; the demand's path is the entry's tree pid/lid
   *
   * Demands whose pegging comes only via main-loop entries (entry.demand_id &&
   * !entry.consolidated && !entry.passthrough) are intentionally absent here — they
   * walked their own BOM directly to this supply, no consolidation involved. Those
   * surface as "direct" in the slide-in's Path column.
   *
   * Keyed as `${supplyId}|${demandId}` for O(1) lookup during render.
   */
  const supplyDemandPathMap = useMemo(() => {
    const map = new Map<string, string>();
    if (!planResult?.planning_pegging) return map;

    const collectSupplies = (node: PlanningPeggingNode, acc: Set<string>): void => {
      if (node.type === 'supply' && node.supply_id && !node.supply_id.startsWith('consolidated_')) {
        acc.add(node.supply_id);
      }
      for (const child of node.children ?? []) collectSupplies(child, acc);
    };

    for (const entry of planResult.planning_pegging) {
      const treePid = entry.tree?.product_id ?? '';
      const treeLid = entry.tree?.location_id ?? '';
      const label = `${treePid}@${treeLid}`;
      const sids = new Set<string>();
      collectSupplies(entry.tree, sids);

      if (entry.passthrough && entry.demand_id) {
        // Single-demand consolidation: bind every supply in this tree to the one demand.
        sids.forEach((sid) => map.set(`${sid}|${entry.demand_id}`, label));
      } else if (entry.consolidated && !entry.demand_id) {
        // Multi-demand: bind each supply to every demand whose per_demand_allocation > 0.
        const pda = entry.per_demand_allocations ?? {};
        for (const did of Object.keys(pda)) {
          if (typeof pda[did] === 'number' && pda[did] > 1e-9) {
            sids.forEach((sid) => map.set(`${sid}|${did}`, label));
          }
        }
      }
      // Main-loop entries (entry.demand_id && !consolidated && !passthrough) deliberately
      // skipped: their consumption of this supply is direct, not via any merged group.
    }

    // scope=all path: every demand allocated at a supply is bound to that
    // supply's group label. No "direct" semantics under scope=all — every
    // consumption goes through supply-level allocation. Empty/absent under
    // scope=leaf-only, so this loop is a no-op there.
    for (const rec of planResult.supply_level_allocations ?? []) {
      const label = `${rec.group_product_id}@${rec.group_location_id}`;
      for (const did of Object.keys(rec.per_demand_allocations)) {
        if (rec.per_demand_allocations[did] > 1e-9) {
          map.set(`${rec.supply_id}|${did}`, label);
        }
      }
    }

    return map;
  }, [planResult]);

  /** Join caseSupplies rows with supplyPeggingMap to produce the enriched supply view. */
  const planSupplyViewRows = useMemo((): PlanSupplyViewRow[] => {
    return caseSupplies.map((s) => {
      const pegging = supplyPeggingMap.get(s.supplyId);
      // Prefer persisted supply_allocations (written on save); fall back to pegging-tree traversal
      // so the pre-save preview still shows approximate consumption figures.
      const consumedQty = supplyConsumedMap.size > 0
        ? (supplyConsumedMap.get(s.supplyId) ?? 0)
        : (pegging?.totalPeggedQty ?? 0);
      const residualQty = Math.max(0, s.qty - consumedQty);
      const utilizationRate = s.qty > 0 ? consumedQty / s.qty : null;
      // Pre-extract the demand→path entries for this supply so the slide-in can do an
      // O(1) lookup without re-scanning the global map. Keys in supplyDemandPathMap
      // are "<supplyId>|<demandId>"; we strip the supplyId prefix here.
      const demandPath: Record<string, string> = {};
      const sidPrefix = `${s.supplyId}|`;
      supplyDemandPathMap.forEach((v, k) => {
        if (k.startsWith(sidPrefix)) demandPath[k.slice(sidPrefix.length)] = v;
      });
      return {
        ...s,
        consumedQty,
        residualQty,
        utilizationRate,
        peggedDemandCount: pegging?.demands.length ?? 0,
        totalPeggedQty: pegging?.totalPeggedQty ?? 0,
        peggedDemands: pegging?.demands ?? [],
        splitInfos: supplySplitInfoMap.get(s.supplyId) ?? [],
        demandPath,
      };
    });
  }, [caseSupplies, supplyPeggingMap, supplyConsumedMap, supplySplitInfoMap, supplyDemandPathMap]);

  /** Sum of qty per productId across all plan supply view rows (unfiltered). */
  const planSupplyProductTotalMap = useMemo((): Record<string, number> => {
    const m: Record<string, number> = {};
    for (const r of planSupplyViewRows) {
      const pid = r.productId ?? '';
      if (pid) m[pid] = (m[pid] ?? 0) + (Number(r.qty) || 0);
    }
    return m;
  }, [planSupplyViewRows]);

  /** Sum of requested qty per productId across all USER demands (i.e. the
   *  demand records that drove the plan), excluding BOM-exploded intermediates.
   *  committed_demands holds exactly the user-facing demand rows; planner-internal
   *  intermediate consumption lives in planning_pegging, not here, so summing
   *  requested_qty here is double-count safe. Used as the *direct* component of
   *  the Scarcity column in the Plan Supply View. */
  const productDemandTotalMap = useMemo((): Record<string, number> => {
    const m: Record<string, number> = {};
    if (!planResult?.committed_demands) return m;
    for (const d of planResult.committed_demands) {
      const pid = d.product_id ?? '';
      if (pid) m[pid] = (m[pid] ?? 0) + (Number(d.requested_qty) || 0);
    }
    return m;
  }, [planResult]);

  /** Sum of totalPeggedQty per productId across all plan supply view rows.
   *  Used as the *BOM-derived* component of Scarcity for intermediate
   *  materials (which have zero direct user demand but are consumed via
   *  upstream demands' BOM explosion). The pegging tree captures
   *  user-demand→supply traversal in supply units, so summing across all
   *  supplies of a given productId gives the total committed consumption
   *  of that material from end-product demands.
   *  Caveat: this is committed-based. If a material is the bottleneck and
   *  the planner could not fully satisfy upstream demand, this number is
   *  capped at supply availability — true requested pressure could be
   *  higher. Documented in the column tooltip. */
  const productConsumedTotalMap = useMemo((): Record<string, number> => {
    const m: Record<string, number> = {};
    for (const r of planSupplyViewRows) {
      const pid = r.productId ?? '';
      if (pid) m[pid] = (m[pid] ?? 0) + (Number(r.totalPeggedQty) || 0);
    }
    return m;
  }, [planSupplyViewRows]);

  const handleAnalyzeCriticalityForSupply = async (supplyId: string) => {
    setSupplyCriticalityMap(prev => ({ ...prev, [supplyId]: 'running' }));
    try {
      const result = await analyzeMaterialImpact(supplyId, 0, 100, false);
      setSupplyCriticalityMap(prev => ({ ...prev, [supplyId]: result.impactedDemandCount > 0 ? 'critical' : 'not_critical' }));
    } catch {
      setSupplyCriticalityMap(prev => ({ ...prev, [supplyId]: 'error' }));
    }
  };

  const handleAnalyzeCriticality = async () => {
    const all = planSupplyViewRows.filter(r => !r.supplyId.toLowerCase().endsWith('_dummy'));
    if (all.length === 0) return;

    // Skip supplies that already have a status (critical / not_critical / error) —
    // users can retry individual errors via the per-row Analyze button, or Reset to clear.
    const pending = all.filter(r => !supplyCriticalityMap[r.supplyId]);
    // Among pending, zero-consumption is trivially safe — skip re-plan for them
    const trivialSafe = pending.filter(r => r.consumedQty === 0);
    const needsAnalysis = pending.filter(r => r.consumedQty > 0);
    const alreadyDone = all.length - pending.length;

    setCriticalityRunning(true);
    setCriticalityProgress({ done: alreadyDone + trivialSafe.length, total: all.length });
    setSupplyCriticalityMap(prev => {
      const next = { ...prev };
      for (const r of trivialSafe) next[r.supplyId] = 'not_critical';
      for (const r of needsAnalysis) next[r.supplyId] = 'running';
      return next;
    });

    const BATCH = 3;
    const total = all.length;
    let done = alreadyDone + trivialSafe.length;
    for (let i = 0; i < needsAnalysis.length; i += BATCH) {
      // Only keep (supplyId, status) — the MaterialImpactResult contains a large
      // `impacts[]` array we don't need; destructuring immediately lets it be GC'd
      // as soon as analyzeMaterialImpact's microtask settles.
      const updates: Array<[string, 'critical' | 'not_critical' | 'error']> = [];
      await Promise.all(
        needsAnalysis.slice(i, i + BATCH).map(async (r) => {
          const sid = r.supplyId;
          try {
            const { impactedDemandCount } = await analyzeMaterialImpact(sid, 0, 100, false);
            updates.push([sid, impactedDemandCount > 0 ? 'critical' : 'not_critical']);
          } catch {
            updates.push([sid, 'error']);
          }
        })
      );
      // Single setState per batch instead of per result → O(N) clones instead of O(N²).
      done += updates.length;
      setSupplyCriticalityMap(prev => {
        const next = { ...prev };
        for (const [sid, status] of updates) next[sid] = status;
        return next;
      });
      setCriticalityProgress({ done, total });
      // Yield to the event loop so the browser can run idle GC + paint updates
      // before we spin up the next batch of re-plans.
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    setCriticalityRunning(false);
  };

  const handleAllocate = async () => {
    setAllocating(true);
    setError(null);
    setAllocationProgress(null);
    setEtaRemainingSeconds(null);
    allocationProgressRef.current = null;
    try {
      const run = await runAllocate(id);
      setSelectedRunId(run.id);
      if (run.status === 'running') {
        const finalRun = await pollRunUntilComplete(id, run.id, {
          onProgress: (p) => {
            setAllocationProgress(p);
            const now = Date.now();
            const prev = allocationProgressRef.current;
            if (prev != null && now > prev.timestamp) {
              const elapsedSec = (now - prev.timestamp) / 1000;
              const stepDelta = p.steps - prev.steps;
              if (elapsedSec > 0 && stepDelta >= 0) {
                const rate = stepDelta / elapsedSec;
                if (rate > 0.1) {
                  const remaining = p.max_steps - p.steps;
                  if (remaining > 0) setEtaRemainingSeconds(remaining / rate);
                }
              }
            }
            allocationProgressRef.current = { steps: p.steps, timestamp: now };
            // Load once: no allocation-view refresh during run; only refresh run list/detail for progress
            if (p.steps > 0 && p.steps % 1000 === 0) {
              loadRuns(false);
              loadRunDetail(run.id);
            }
          },
        });
        if (finalRun.status === 'failed') {
          const msg = (finalRun.config as { error?: string } | null)?.error ?? 'Allocation failed';
          setError(msg);
        }
        await loadRuns(false);
        if (finalRun.status === 'success') {
          setSelectedRunId(run.id);
          loadRunDetail(run.id);
        } else {
          const data = await listRuns(id);
          if (data.length > 0) setSelectedRunId(data[0].id);
        }
      } else {
        await loadRuns();
        const data = await listRuns(id);
        if (data.length > 0) setSelectedRunId(data[0].id);
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Allocation failed';
      if (msg === 'ALLOCATION_POLL_TIMEOUT') {
        setError('Stopped waiting for allocation (20 min). The run may still be in progress—refresh the page or check the Run list.');
        await loadRuns();
      } else {
        setError(msg);
      }
    } finally {
      setAllocating(false);
      setAllocationProgress(null);
      setEtaRemainingSeconds(null);
      allocationProgressRef.current = null;
    }
  };

  const handleAddOverride = async () => {
    try {
      let payload: Record<string, unknown> = {};
      try {
        payload = JSON.parse(overrideForm.payload || '{}');
      } catch {
        setError('Invalid JSON payload');
        return;
      }
      await addOverride(id, overrideForm.entity_type, overrideForm.entity_key, payload);
      await loadOverrides();
      setOverrideForm({ ...overrideForm, entity_key: '', payload: '{}' });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to add override');
    }
  };

  const handleCriticalClick = async (componentKey: string, toVariantKey: string) => {
    if (!selectedRunId) return;
    setExplanationOpen(true);
    setExplanationLoading(true);
    setExplanationData(null);
    try {
      const data = await getAllocationExplanation(id, selectedRunId, componentKey, toVariantKey);
      setExplanationData(data);
    } catch {
      setExplanationData(null);
    } finally {
      setExplanationLoading(false);
    }
  };

  const handleSupplyPeggingClick = async (row: SupplyViewRow) => {
    if (!selectedRunId) return;
    setPeggingOpen(true);
    setPeggingLoading(true);
    setPeggingTitle(tP('peggingPanel.titleWorkOrder', { product: row.component_key.split('|')[0] ?? row.component_key, location: row.component_key.split('|')[1] ?? '' }));
    setPeggingData(null);
    setPeggingTreeReady(false);
    setPeggingExpanded(new Set());
    setPeggingExpandingNodeId(null);
    setPeggingChildrenAllowedFor(new Set());
    const timeoutId = setTimeout(() => {
      setPeggingLoading(false);
      setPeggingData(null);
    }, 15000);
    try {
      const supplyId = row.supply_id || row.component_key;
      const data = await getPegging(id, selectedRunId, 'supply-to-demand', undefined, supplyId, true);
      if (data._verify && typeof window !== 'undefined') {
        console.log('[Pegging verify] inv→demand edge sum vs node qty:', data._verify);
        const bad = data._verify.filter((v) => !v.ok);
        if (bad.length) console.warn('[Pegging verify] nodes with sum > qty:', bad);
      }
      setPeggingData({
        direction: data.direction ?? 'supply-to-demand',
        nodes: Array.isArray(data.nodes) ? data.nodes : [],
        edges: Array.isArray(data.edges) ? data.edges : [],
        critical_path: data.critical_path as { path: string[] } | undefined,
        critical_paths_by_demand: data.critical_paths_by_demand as { paths_by_demand: { demand_id: string; path: string[] }[] } | undefined,
      });
    } catch {
      setPeggingData(null);
    } finally {
      clearTimeout(timeoutId);
      setPeggingLoading(false);
    }
  };

  const handleDemandPeggingClick = async (row: FeasibleDemand) => {
    if (!selectedRunId) return;
    setPeggingOpen(true);
    setPeggingLoading(true);
    setPeggingTitle(tP('peggingPanel.titleDemand', { label: String(row.demand_id) }));
    setPeggingData(null);
    setPeggingTreeReady(false);
    setPeggingExpanded(new Set());
    setPeggingExpandingNodeId(null);
    setPeggingChildrenAllowedFor(new Set());
    const timeoutId = setTimeout(() => {
      setPeggingLoading(false);
      setPeggingData(null);
    }, 15000);
    try {
      const data = await getPegging(id, selectedRunId, 'demand-to-supply', String(row.demand_id), undefined);
      setPeggingData({
        direction: data.direction ?? 'demand-to-supply',
        nodes: Array.isArray(data.nodes) ? data.nodes : [],
        edges: Array.isArray(data.edges) ? data.edges : [],
        critical_path: data.critical_path as { path: string[] } | undefined,
        critical_paths_by_demand: data.critical_paths_by_demand as { paths_by_demand: { demand_id: string; path: string[] }[] } | undefined,
        demand_id: (data as { demand_id?: string }).demand_id,
        demand_root_id: (data as { demand_root_id?: string }).demand_root_id,
        demand_allocated_qty: (data as { demand_allocated_qty?: number }).demand_allocated_qty,
        demand_requested_qty: (data as { demand_requested_qty?: number }).demand_requested_qty,
      });
    } catch {
      setPeggingData(null);
    } finally {
      clearTimeout(timeoutId);
      setPeggingLoading(false);
    }
  };

  const handleDeleteOverride = async (overrideId: number) => {
    try {
      await deleteOverride(id, overrideId);
      await loadOverrides();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete');
    }
  };

  const loadPlanRunHistory = async () => {
    setPlanRunHistoryLoading(true);
    try {
      const data = await listPlanRuns(id);
      setPlanRunHistory(data);
    } catch {
      setPlanRunHistory([]);
    } finally {
      setPlanRunHistoryLoading(false);
    }
  };

  // ── Bootstrap handlers ──────────────────────────────────────────────────
  const openBootstrapDialog = async () => {
    if (!id) return;
    setBootstrapDialogOpen(true);
    setBootstrapPreviewLoading(true);
    setBootstrapSkippedNotice(null);
    try {
      const preview = await getBootstrapPreview(id, bootstrapBatchSize, bootstrapCriterion);
      setBootstrapPreview(preview);
    } catch {
      setBootstrapPreview(null);
    } finally {
      setBootstrapPreviewLoading(false);
    }
  };

  // Re-fetch the preview whenever batch size changes while the dialog is open
  // — the next-batch list shifts as the user dials the size up/down.
  useEffect(() => {
    if (!bootstrapDialogOpen || !id) return;
    let cancelled = false;
    (async () => {
      try {
        const preview = await getBootstrapPreview(id, bootstrapBatchSize, bootstrapCriterion);
        if (!cancelled) setBootstrapPreview(preview);
      } catch { /* keep prior preview */ }
    })();
    return () => { cancelled = true; };
  }, [bootstrapBatchSize, bootstrapCriterion, bootstrapDialogOpen, id]);

  const handleStartBootstrap = async () => {
    if (!id) return;
    setBootstrapStarting(true);
    try {
      // Build presets[] from the dialog's next_batch suggestions, applying
      // any per-row edits the user has made. When no edits exist, omit the
      // override so the backend re-runs selectNextBatch fresh.
      const hasEdits = Object.keys(bootstrapEditedConfigs).length > 0;
      const customPresets = hasEdits && bootstrapPreview
        ? bootstrapPreview.next_batch.map((p) => ({
            preset_id: p.preset_id,
            preset_label: p.preset_label,
            primary_axis: p.primary_axis,
            config: bootstrapEditedConfigs[p.preset_id] ?? p.config,
          }))
        : undefined;
      const r = await startBootstrap(id, bootstrapBatchSize, customPresets);
      if ('status' in r) {
        // Either library_exhausted or all submitted presets matched existing
        // KB entries. Refresh the preview so the dialog shows the appropriate
        // message; surface the skipped list if present.
        if (r.status === 'all_already_covered' && r.skipped.length > 0) {
          setBootstrapSkippedNotice(r.skipped);
        }
        const preview = await getBootstrapPreview(id, bootstrapBatchSize, bootstrapCriterion);
        setBootstrapPreview(preview);
        return;
      }
      // Partial dedup: some submitted presets ran, some were skipped. Show
      // a notice so the user knows their batch was trimmed.
      if (r.skipped && r.skipped.length > 0) {
        setBootstrapSkippedNotice(r.skipped);
      }
      setBootstrapJobId(r.bootstrap_job_id);
      // Seed status immediately so the progress block can render before the
      // first poll lands.
      setBootstrapJobStatus({
        status: 'running',
        total: r.total,
        completed: 0,
        current_preset_id: r.presets[0]?.preset_id ?? '',
        current_preset_label: r.presets[0]?.preset_label ?? '',
        plan_run_ids: [],
        errors: [],
      });
      // Edits have been submitted — clear local override state so the next
      // dialog open shows fresh system suggestions.
      setBootstrapEditedConfigs({});
      setBootstrapEditingRows(new Set());
    } catch (e) {
      console.error('Bootstrap start failed', e);
    } finally {
      setBootstrapStarting(false);
    }
  };
  const handleCancelBootstrap = async () => {
    if (!id || !bootstrapJobId) return;
    setBootstrapCancelling(true);
    try {
      await cancelBootstrap(id, bootstrapJobId);
      // The polling loop will pick up the new status; no need to setBootstrapJobStatus here.
    } catch (e) {
      console.error('Bootstrap cancel failed', e);
      setBootstrapCancelling(false);
    }
  };

  // Poll bootstrap progress every 3s while a job is running. Stop on
  // completion or when the user dismisses the dialog.
  useEffect(() => {
    if (!bootstrapJobId || !id) return;
    const tick = async () => {
      try {
        const status = await getBootstrapJobStatus(id, bootstrapJobId);
        setBootstrapJobStatus(status);
        if (status.status === 'completed' || status.status === 'cancelled') {
          if (bootstrapPollRef.current) { clearInterval(bootstrapPollRef.current); bootstrapPollRef.current = null; }
          setBootstrapCancelling(false);
          // Refresh the preview + run history now that new runs exist (some
          // presets may have completed even on a cancelled run).
          getBootstrapPreview(id, bootstrapBatchSize, bootstrapCriterion).then(setBootstrapPreview).catch(() => {});
          loadPlanRunHistory();
        }
      } catch { /* keep polling on transient errors */ }
    };
    tick();
    bootstrapPollRef.current = setInterval(tick, 3000);
    return () => {
      if (bootstrapPollRef.current) { clearInterval(bootstrapPollRef.current); bootstrapPollRef.current = null; }
    };
  }, [bootstrapJobId, id, bootstrapBatchSize]);

  const closeBootstrapDialog = () => {
    setBootstrapDialogOpen(false);
    // Don't clear job id — the run may still be in flight in the background;
    // the next time the user opens the dialog they'll see live progress.
  };

  const handleRestorePlanRun = async (runId: number) => {
    setPlanRunLoadingId(runId);
    try {
      const full = await getPlanRun(id, runId);
      if (full.result) {
        setPlanResult(full.result as typeof planResult);
        setCurrentPlanRunId(runId);
        setFreshPlanRunId(null);
        setOverrideCandidateRunId(null);
        setPlanRunSaveError(null);
        setPlanWorkOrderPeggingCache({});
        if (full.config) {
          // Default the depth to the run's chosen_depth (from optimal search) when present;
          // fall back to 1 so the form starts from a sane baseline rather than carrying
          // over whatever depth was in the saved config snapshot.
          const cfg = full.config as PlanningConfig;
          const chosen = full.chosen_depth ?? null;
          setPlanningConfig({
            ...cfg,
            method_selection: {
              ...cfg.method_selection,
              depth: chosen ?? 1,
              depth_optimal: false,
            },
          });
        }
        setPlanRunHistoryOpen(false);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load plan run');
    } finally {
      setPlanRunLoadingId(null);
    }
  };

  const handleDeletePlanRun = async (runId: number) => {
    try {
      await deletePlanRun(id, runId);
      setPlanRunHistory((prev) => prev.filter((r) => r.id !== runId));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete plan run');
    }
  };

  // ── Active-run designation: pin a specific run, or clear the pin to fall back
  //    to the default resolver (latest successful run).
  const handleDesignateActive = async (runId: number) => {
    setPlanRunDesignating((prev) => ({ ...prev, [runId]: true }));
    try {
      await designateActivePlanRun(id, runId);
      await loadPlanRunHistory();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to designate active plan run');
    } finally {
      setPlanRunDesignating((prev) => { const n = { ...prev }; delete n[runId]; return n; });
    }
  };
  const handleUnpinActive = async (runId: number) => {
    setPlanRunDesignating((prev) => ({ ...prev, [runId]: true }));
    try {
      await clearDesignatedActivePlanRun(id);
      await loadPlanRunHistory();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to clear active designation');
    } finally {
      setPlanRunDesignating((prev) => { const n = { ...prev }; delete n[runId]; return n; });
    }
  };

  // Toggle an expansion tab for a run. Lazy-fetches full detail (which carries
  // override_snapshot + events) the first time any tab opens.
  const handleTogglePlanRunTab = async (runId: number, tab: 'config' | 'overrides' | 'events') => {
    const current = planRunExpandedTab[runId];
    const next = current === tab ? null : tab;
    setPlanRunExpandedTab((prev) => ({ ...prev, [runId]: next }));
    if (next && (tab === 'overrides' || tab === 'events') && !planRunDetailCache[runId]) {
      setPlanRunDetailLoading((prev) => ({ ...prev, [runId]: true }));
      try {
        const full = await getPlanRun(id, runId);
        setPlanRunDetailCache((prev) => ({ ...prev, [runId]: full }));
      } catch {
        // Leave tab open; user can click again to retry.
      } finally {
        setPlanRunDetailLoading((prev) => { const n = { ...prev }; delete n[runId]; return n; });
      }
    }
  };

  const openOverrideDialog = (type: 'method_selection' | 'component_split', wo: WorkOrder) => {
    setOverrideDialogType(type);
    setOverrideDialogWo(wo);
    setOverrideDialogError(null);
    if (type === 'method_selection') {
      const productId = wo.product_id ?? '';
      const locationId = wo.location_id ?? '';
      const demandId = wo.demand_id ?? '';
      const entityKey = demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`;
      const existing = overrides.find((o) => o.entity_type === 'method_selection' && o.entity_key === entityKey);
      const savedMethod = existing ? (existing.payload as Record<string, unknown>).method as string | undefined : undefined;
      setOverrideMethodValue(savedMethod?.toLowerCase() ?? (wo.method ?? 'make').toLowerCase());
    } else {
      setOverrideSplitRows(
        (wo.wo_consolidation_split_details ?? []).map((d) => ({
          demand_id: d.demand_id,
          qty: d.allocated_qty,
          requested_qty: d.requested_qty,
          priority: d.priority,
          parent_product: d.parent_product,
        }))
      );
    }
    setOverrideDialogOpen(true);
  };

  const handleSaveOverride = async () => {
    if (!overrideDialogType) return;
    // supply_split uses overrideDialogSupply, not overrideDialogWo
    if (overrideDialogType !== 'supply_split' && !overrideDialogWo) return;
    if (overrideDialogType === 'supply_split' && !overrideDialogSupply) return;
    setOverrideDialogSaving(true);
    setOverrideDialogError(null);
    try {
      let payload: Record<string, unknown>;
      let entityKey: string;

      if (overrideDialogType === 'supply_split') {
        const supply = overrideDialogSupply!;
        const allocations = overrideSupplyRows
          .filter((r) => Number(r.qty) > 0)
          .map((r) => ({ demand_id: r.demand_id, qty: Number(r.qty) }));
        payload = { allocations };
        entityKey = supply.supplyId;
      } else {
        const wo = overrideDialogWo!;
        const productId = wo.product_id ?? '';
        const locationId = wo.location_id ?? '';
        const demandId = wo.demand_id ?? '';

        if (overrideDialogType === 'method_selection') {
          if (!overrideMethodValue.trim()) { setOverrideDialogError('Select a method'); setOverrideDialogSaving(false); return; }
          payload = { method: overrideMethodValue.trim() };
          entityKey = demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`;
        } else {
          // component_split
          const allocations = overrideSplitRows.map((r) => ({ demand_id: r.demand_id, qty: Number(r.qty) }));
          payload = { allocations };
          entityKey = wo.start_time
            ? `${productId}|${locationId}|${wo.start_time.slice(0, 10)}`
            : `${productId}|${locationId}`;
        }
      }
      await upsertOverride(id, overrideDialogType, entityKey, payload);
      await loadOverrides();
      setOverrideDialogOpen(false);
      setOverrideDialogWo(null);
      setOverrideDialogSupply(null);
      setOverrideDialogType(null);
    } catch (e) {
      setOverrideDialogError(e instanceof Error ? e.message : 'Failed to save override');
    } finally {
      setOverrideDialogSaving(false);
    }
  };

  const openSupplyOverrideDialog = (supply: PlanSupplyViewRow) => {
    setOverrideDialogType('supply_split');
    setOverrideDialogSupply(supply);
    setOverrideDialogWo(null);
    setOverrideDialogError(null);
    // Pre-populate: one row per pegged demand, qty = current consumed_qty
    const existing = overrides.find(
      (o) => o.entity_type === 'supply_split' && o.entity_key === supply.supplyId
    );
    const savedAllocs = existing
      ? (((existing.payload as Record<string, unknown>).allocations as Array<{ demand_id: string; qty: number }> | undefined) ?? [])
      : [];
    const byDemand = new Map<string, number>();
    for (const a of savedAllocs) byDemand.set(a.demand_id, Number(a.qty));

    const rows = supply.peggedDemands.map((d) => {
      const requested = feasibleDemands?.find((fd) => fd.demand_id === d.demandId)?.requested_qty ?? 0;
      return {
        demand_id: d.demandId,
        qty: byDemand.has(d.demandId) ? byDemand.get(d.demandId)! : d.qtyConsumed,
        requested_qty: requested,
        consumed_qty: d.qtyConsumed,
        customer: d.customer,
      };
    });
    setOverrideSupplyRows(rows);
    setOverrideDialogOpen(true);
  };

  // ── Assessment handlers ─────────────────────────────────────────────────────

  const handleAssess = async () => {
    const supplyId = supExplainRow?.supplyId;
    if (!supplyId) return;
    setAssessmentRunning(true);
    setAssessError(null);
    try {
      const result = await runAssessment(
        id,
        supplyId,
        assessDelayDays,
        assessQtyDecreaseMode === 'pct' ? assessQtyDecreasePct : 0,
        currentPlanRunId,
        undefined,
        assessQtyDecreaseMode === 'abs' ? assessQtyDecreaseAbs : null,
        locale,
      );
      setAssessmentResult(result);
      const hist = await listAssessments(id, supplyId);
      setAssessmentHistory(hist);
      setAssessmentHistoryOpen(true);
    } catch (e) {
      setAssessError(e instanceof Error ? e.message : 'Assessment failed');
    } finally {
      setAssessmentRunning(false);
    }
  };

  const handleLoadHistory = async () => {
    const supplyId = supExplainRow?.supplyId;
    if (!supplyId) return;
    if (!assessmentHistoryOpen) {
      try {
        const hist = await listAssessments(id, supplyId);
        setAssessmentHistory(hist);
      } catch (_) { /* best-effort */ }
    }
    setAssessmentHistoryOpen((o) => !o);
  };

  const handleSaveCriteria = async () => {
    setAssessCriteriaSaving(true);
    try {
      const combined = buildCriteriaText(assessCriteriaHigh, assessCriteriaLow, assessCriteriaMedium, criteriaHeaders);
      await setAssessmentCriteria(id, combined);
      setAssessCriteria(combined);
    } catch (_) { /* TODO: surface error */ } finally {
      setAssessCriteriaSaving(false);
    }
  };

  if (loading || !c) {
    return <div><Link href="/cases">{tNav('backToCases')}</Link>{loading ? <p>{tc('loading')}</p> : <p>{tc('notFound')}</p>}</div>;
  }

  return (
    <div>
      {caseSection === 'allocation' && (
      <section>
        <h2>{tSec('allocation')}</h2>
        <button onClick={handleAllocate} disabled={allocating}>{allocating ? tA('running') : tA('runAllocation')}</button>
        {allocating && allocationProgress != null && (
          <div style={{ marginTop: '0.75rem', maxWidth: 420 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.875rem', marginBottom: '0.25rem', color: '#64748b' }}>
              <span>{tA('progress.steps')} {qtyFmt(allocationProgress.steps ?? 0)} / {qtyFmt(allocationProgress.max_steps ?? 0)}</span>
              <span>{tA('progress.basket')} {allocationProgress.basket_keys ?? 0} {tA('progress.items')}, {qtyFmt(Number(allocationProgress.basket_total_qty) ?? 0)} {tA('progress.qty')}</span>
            </div>
            <div style={{ height: 8, backgroundColor: '#e2e8f0', borderRadius: 4, overflow: 'hidden' }}>
              <div
                style={{
                  height: '100%',
                  width: (Number(allocationProgress.initial_basket_total_qty) ?? 0) > 0
                    ? `${Math.min(100, 100 * (1 - (Number(allocationProgress.basket_total_qty) ?? 0) / (Number(allocationProgress.initial_basket_total_qty) ?? 1)))}%`
                    : `${Math.min(100, 100 * ((Number(allocationProgress.steps) ?? 0) / ((Number(allocationProgress.max_steps) ?? 1) || 1)))}%`,
                  backgroundColor: '#0ea5e9',
                  borderRadius: 4,
                  transition: 'width 0.3s ease',
                }}
              />
            </div>
            <div style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: '0.25rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>
                {(Number(allocationProgress.initial_basket_total_qty) ?? 0) > 0
                  ? `${((1 - (Number(allocationProgress.basket_total_qty) ?? 0) / (Number(allocationProgress.initial_basket_total_qty) ?? 1)) * 100).toFixed(1)}% ${tA('progress.pctAllocated')}`
                  : `${(100 * (Number(allocationProgress.steps) ?? 0) / (Number(allocationProgress.max_steps) ?? 1)).toFixed(1)}% ${tA('progress.pctSteps')}`}
              </span>
              {etaRemainingSeconds != null && etaRemainingSeconds > 0 && (
                <span style={{ fontWeight: 500, color: '#64748b' }}>
                  ~{etaRemainingSeconds >= 3600
                    ? `${Math.floor(etaRemainingSeconds / 3600)} h ${Math.floor((etaRemainingSeconds % 3600) / 60)} min`
                    : etaRemainingSeconds >= 60
                      ? `${Math.floor(etaRemainingSeconds / 60)} min ${Math.round(etaRemainingSeconds % 60)} s`
                      : `${Math.round(etaRemainingSeconds)} s`} {tA('progress.left')}
                </span>
              )}
            </div>
          </div>
        )}
        {runs.length > 0 && (
          <div style={{ marginTop: '1rem' }}>
            <label>{tA('run')} </label>
            <select value={selectedRunId ?? ''} onChange={(e) => setSelectedRunId(Number(e.target.value))}>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>{new Date(r.created_at).toLocaleString()} – {r.status}</option>
              ))}
            </select>
            {' '}
            <Link href={`/cases/${id}/runs/${selectedRunId}`} className="btn">{tA('explainabilityPegging')}</Link>
          </div>
        )}
        {selectedRunId && (
          <>
            <div style={{ marginBottom: '0.5rem' }}>
              <button
                type="button"
                className={activeView === 'supply' ? '' : 'secondary'}
                onClick={() => setActiveView('supply')}
              >
                {tA('views.supply')}
              </button>
              {' '}
              <button
                type="button"
                className={activeView === 'allocation' ? '' : 'secondary'}
                onClick={() => setActiveView('allocation')}
              >
                {tA('views.allocation')}
              </button>
              {' '}
              <button
                type="button"
                className={activeView === 'suggested' ? '' : 'secondary'}
                onClick={() => setActiveView('suggested')}
              >
                {tA('views.demand')}
              </button>
              {' '}
              <button
                type="button"
                className={activeView === 'raw-material' ? '' : 'secondary'}
                onClick={() => setActiveView('raw-material')}
              >
                {tA('views.rawMaterial')}
              </button>
            </div>
            {activeView === 'supply' && (
              <>
                {supplyViewLoading && supplyView.length === 0 && (
                  <p style={{ color: '#71717a' }}>{tA('supplyView.loading')}</p>
                )}
                {!supplyViewLoading && supplyView.length <= 1 && supplyView.length > 0 && (
                  <p style={{ fontSize: '0.875rem', color: '#71717a', marginBottom: '0.5rem' }}>
                    {tA('supplyView.hint')}
                  </p>
                )}
                {!supplyViewLoading && (
                <>
                {supplyView.length > 0 && (() => {
                  const totalInitial = supplyView.reduce((s, r) => s + (Number(r.initial_qty) || 0), 0);
                  const totalConsumed = supplyView.reduce((s, r) => s + (Number(r.consumed_qty) || 0), 0);
                  const overallUtil = totalInitial > 0 ? (totalConsumed / totalInitial) * 100 : 0;
                  return (
                    <p style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                      {tA('supplyView.overallUtilization')} <strong>{qtyFmt(totalConsumed)}</strong> / <strong>{qtyFmt(totalInitial)}</strong> {tA('supplyView.initial')} = <strong>{overallUtil.toFixed(1)}%</strong>
                    </p>
                  );
                })()}
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: '0.5rem' }}>
                  <input
                    type="checkbox"
                    checked={supplyFilterConsumedOnly}
                    onChange={(e) => setSupplyFilterConsumedOnly(e.target.checked)}
                  />
                  {tA('supplyView.onlyConsumed')}
                </label>
                <SortFilterTable<SupplyViewRow & { _rowKey?: string; product_total: number }>
                idKey="_rowKey"
                rows={supplyView
                  .filter((r) => !supplyFilterConsumedOnly || (Number(r.consumed_qty) || 0) > 0)
                  .map((r, i) => ({ ...r, _rowKey: r.id != null ? String(r.id) : `supply-${r.supply_id}-${i}`, product_total: supplyProductTotalMap[r.product_id] ?? 0 }))}
                rowId={(r) => (r.id != null ? `supply-${r.id}` : r.supply_id ? `supply-${r.supply_id}` : undefined)}
                onRowClick={handleSupplyPeggingClick}
                filterKeys={['supply_id', 'product_id', 'location_id', 'component_key', 'supply_date', 'consumed_qty', 'utilization_rate']}
                defaultSortKey="utilization_rate"
                columns={[
                  { key: 'supply_id', label: tA('supplyView.columns.supplyId'), sortable: true },
                  { key: 'supply_date', label: tA('supplyView.columns.time'), sortable: true, render: (r) => r.supply_date ?? '–' },
                  { key: 'product_id', label: tA('supplyView.columns.product'), sortable: true },
                  { key: 'location_id', label: tA('supplyView.columns.location'), sortable: true },
                  { key: 'initial_qty', label: tA('supplyView.columns.initialQty'), sortable: true },
                  { key: 'product_total', label: tA('supplyView.columns.productTotal'), sortable: true, render: (r) => r.product_total > 0 ? qtyFmt(r.product_total) : '–' },
                  { key: 'consumed_qty', label: tA('supplyView.columns.consumedQty'), sortable: true },
                  { key: 'residual_qty', label: tA('supplyView.columns.residualQty'), sortable: true },
                  { key: 'utilization_rate', label: tA('supplyView.columns.utilization'), sortable: true, render: (r) => r.utilization_rate != null ? `${(Number(r.utilization_rate) * 100).toFixed(1)}%` : '–' },
                  { key: '_pegging', label: tA('supplyView.columns.pegging'), sortable: false, render: (r) => <button type="button" className="secondary" onClick={() => handleSupplyPeggingClick(r)}>{tc('show')}</button> },
                ]}
              />
                </>
                )}
              </>
            )}
            {activeView === 'allocation' && (
              <>
                {allocationViewLoading && allocationView.length === 0 && (
                  <p style={{ color: '#71717a' }}>Loading allocation…</p>
                )}
                {allocationViewLoading && allocationView.length > 0 && (
                  <p style={{ fontSize: '0.875rem', color: '#71717a', marginBottom: '0.25rem' }}>Loading more…</p>
                )}
                {allocationViewError && (
                  <p style={{ fontSize: '0.875rem', color: '#dc2626', marginBottom: '0.5rem' }}>{allocationViewError}</p>
                )}
                {allocationViewError && (allocationActions.length > 0 || allocationActionsLoading) && (
                  <>
                    <p style={{ fontSize: '0.875rem', color: '#71717a', marginBottom: '0.5rem' }}>
                      Showing raw allocation steps (lightweight, paginated). {allocationActionsTotal > 0 && `Total: ${qtyFmt(allocationActionsTotal)} steps.`}
                    </p>
                    {allocationActionsLoading && allocationActions.length === 0 && <p style={{ color: '#71717a' }}>Loading raw steps…</p>}
                    {allocationActions.length > 0 && (
                      <>
                        <div style={{ marginBottom: '0.5rem', display: 'flex', gap: 8, alignItems: 'center' }}>
                          <button
                            type="button"
                            className="secondary"
                            disabled={allocationActionsOffset <= 0 || allocationActionsLoading}
                            onClick={() => {
                              const nextOffset = Math.max(0, allocationActionsOffset - 500);
                              setAllocationActionsLoading(true);
                              getAllocationActions(id, selectedRunId!, { offset: nextOffset, limit: 500 })
                                .then((d) => {
                                  setAllocationActions(d.actions);
                                  setAllocationActionsOffset(nextOffset);
                                })
                                .finally(() => setAllocationActionsLoading(false));
                            }}
                          >
                            Previous
                          </button>
                          <span style={{ fontSize: '0.875rem', color: '#71717a' }}>
                            {allocationActionsOffset + 1}–{allocationActionsOffset + allocationActions.length} of {qtyFmt(allocationActionsTotal)}
                          </span>
                          <button
                            type="button"
                            className="secondary"
                            disabled={allocationActionsOffset + allocationActions.length >= allocationActionsTotal || allocationActionsLoading}
                            onClick={() => {
                              const nextOffset = allocationActionsOffset + 500;
                              setAllocationActionsLoading(true);
                              getAllocationActions(id, selectedRunId!, { offset: nextOffset, limit: 500 })
                                .then((d) => {
                                  setAllocationActions(d.actions);
                                  setAllocationActionsOffset(nextOffset);
                                })
                                .finally(() => setAllocationActionsLoading(false));
                            }}
                          >
                            Next
                          </button>
                        </div>
                        <SortFilterTable<AllocationActionRow & { _key?: string }>
                          idKey="_key"
                          rows={allocationActions.map((a, i) => ({ ...a, _key: `action-${a.id}-${i}` }))}
                          filterKeys={['variant_key', 'edge_type', 'demand_id', 'target_product_id']}
                          defaultSortKey="scarcity_rank"
                          columns={[
                            { key: 'id', label: 'ID', sortable: true },
                            { key: 'scarcity_rank', label: 'Rank', sortable: true, render: (r) => (r.scarcity_rank != null ? String(r.scarcity_rank) : '–') },
                            { key: 'variant_key', label: 'Variant', sortable: true },
                            { key: 'edge_type', label: 'Edge', sortable: true, render: (r) => r.edge_type || '–' },
                            { key: 'qty', label: 'Qty', sortable: true },
                            { key: 'demand_id', label: 'Demand', sortable: true, render: (r) => r.demand_id ?? '–' },
                            { key: 'target_product_id', label: 'Target product', sortable: true, render: (r) => r.target_product_id ?? '–' },
                            { key: 'target_location_id', label: 'Target location', sortable: true, render: (r) => r.target_location_id ?? '–' },
                            { key: 'output_period', label: 'Output period', sortable: true, render: (r) => (r.output_period != null ? String(r.output_period) : '–') },
                          ]}
                        />
                      </>
                    )}
                  </>
                )}
                {(allocationView.length > 0 || !allocationViewLoading) && (
                <>
                {allocationViewTruncated && (
                  <p style={{ fontSize: '0.875rem', color: '#b45309', marginBottom: '0.5rem', fontWeight: 600 }}>
                    Showing {qtyFmt(allocationView.length)} of {qtyFmt(allocationViewTruncated.total_steps)} rows
                    {allocationViewTruncated.total_actions > allocationViewTruncated.limit && ` (${qtyFmt(allocationViewTruncated.total_actions)} steps)`}.
                    {' '}
                    {selectedRunId && allocationView.length < allocationViewTruncated.total_steps && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={allocationViewLoading}
                        onClick={() => {
                          setAllocationViewLoading(true);
                          const nextFrom = allocationView.length + 1;
                          const nextTo = Math.min(allocationView.length + ALLOCATION_VIEW_PAGE_SIZE, allocationViewTruncated.total_steps);
                          // Incremental: only view rows, no basket (stack on existing state)
                          getAllocationView(id, selectedRunId, {
                            max_actions: 300,
                            from_step: nextFrom,
                            to_step: nextTo,
                            skip_basket: true,
                          })
                            .then((a) => {
                              if (allocationViewRunIdRef.current === selectedRunId) {
                                setAllocationView((prev) => [...prev, ...a.allocation_view]);
                                const totalSteps = a.total_steps ?? allocationViewTruncated.total_steps;
                                setAllocationViewTruncated((prev) => (prev ? { ...prev, total_steps: totalSteps } : null));
                              }
                            })
                            .finally(() => setAllocationViewLoading(false));
                        }}
                      >
                        Load more
                      </button>
                    )}
                  </p>
                )}
                {allocating && allocationProgress != null && (
                  <p style={{ fontSize: '0.875rem', color: '#64748b', marginBottom: '0.5rem' }}>
                    Run progress: <strong>{qtyFmt(allocationProgress.steps ?? 0)} steps</strong>, basket <strong>{allocationProgress.basket_keys ?? 0} items</strong>. Table is ordered by scarcity; each row’s basket is after the step in <strong>After step</strong> (view may show fewer steps until it refreshes).
                  </p>
                )}
                {(basketDeltas.length > 0 || allocationView.length > 0) && (
                  <p style={{ fontSize: '0.875rem', color: '#64748b', marginBottom: '0.5rem' }}>
                    View has data through step <strong>{qtyFmt(basketDeltas.length || allocationView.length)}</strong>
                    {allocationViewTruncated && allocationView.length < allocationViewTruncated.total_steps ? (
                      <> (first chunk loaded; use Load more for rest).</>
                    ) : (
                      <>.</>
                    )}{' '}
                    {basketDeltas.length > 0 ? (
                      <>
                        Basket after last loaded step: <strong>{qtyFmt(basketFinal?.length ?? computeBasketAfterStep(basketDeltas.length - 1).length)} items</strong>
                        {basketFinal != null && (
                          <>
                            {' '}
                            <button
                              type="button"
                              className="secondary"
                              style={{ marginLeft: 4 }}
                              onClick={() => { setBasketSlideInRow(null); setBasketShowingFinal(true); }}
                            >
                              View basket at end of loaded view
                            </button>
                          </>
                        )}.
                      </>
                    ) : (
                      <>Open a row’s <strong>View</strong> in the Basket column to load basket state.</>
                    )}
                    {basketDeltas.length > 0 && allocating && allocationProgress != null && allocationProgress.steps > basketDeltas.length && (
                      <>
                        {' '}View is behind run (refresh runs every 1,000 steps).
                        {selectedRunId && (
                          <button
                            type="button"
                            className="secondary"
                            style={{ marginLeft: 8 }}
                            disabled={allocationViewLoading}
                            onClick={() => {
                              setAllocationViewLoading(true);
                              const maxActions = Math.min(allocationProgress!.steps, 5000);
                              getAllocationView(id, selectedRunId!, { max_actions: maxActions, from_step: 1, to_step: 5000, skip_basket: false })
                                .then((a) => {
                                  if (allocationViewRunIdRef.current === selectedRunId) {
                                    setAllocationView(a.allocation_view);
          setBasketInitial(a.basket_initial ?? []);
          setBasketDeltas(a.basket_deltas ?? []);
          setBasketFinal(a.basket_final ?? null);
          setBasketPrunes(a.basket_prunes ?? []);
                                    setAllocationViewTruncated(
                                      (a.total_steps ?? 0) > 0
                                        ? { total_actions: a.total_actions ?? 0, limit: a.limit ?? 300, total_steps: a.total_steps ?? a.allocation_view.length }
                                        : null,
                                    );
                                    setAllocationViewError(null);
                                  }
                                })
                                .finally(() => setAllocationViewLoading(false));
                            }}
                          >
                            Refresh view now
                          </button>
                        )}
                      </>
                    )}
                  </p>
                )}
                <p style={{ fontSize: '0.875rem', color: '#71717a', marginBottom: '0.5rem' }}>
                  Critical-component-centric (scarcity = total quantity; scarcest = smallest). <strong>After step</strong> = allocation step after which the basket is shown (same scale as progress bar). One row per critical component; multiple target candidates grouped with split explanation. <strong>Time</strong> = when that edge’s output becomes available. From/To are true inventories; To can be a customer demand. <strong>Demands</strong> = customer demand IDs that request this row’s output product (empty “–” for intermediate steps whose output feeds later steps, not a final demand). Edge types: make, move, demand.
                </p>
                <SortFilterTable<AllocationViewRow & { _id?: string }>
                idKey="_id"
                rows={allocationView.map((row, i) => ({
                  ...row,
                  _id: row.row_type === 'component'
                    ? `comp-${row.step ?? i}-${row.from_inventory_id ?? i}`
                    : `demand-${row.from_inventory_id}-${row.to_inventory_id}-${i}`,
                  after_step: (row.basket_step_index ?? 0) + 1,
                }))}
                filterKeys={['edge_type', 'from_inventory_display', 'to_inventory_display', 'split_explanation', 'demand_ids']}
                defaultSortKey="step"
                columns={[
                  { key: 'step', label: 'Row', sortable: true, render: (r) => (r.step != null ? String(r.step) : '–') },
                  { key: 'after_step', label: 'After step', sortable: true, render: (r) => (r.after_step != null ? String(r.after_step) : (r.basket_step_index != null ? String((r.basket_step_index as number) + 1) : '–')) },
                  { key: 'edge_type', label: 'Edge', sortable: true, render: (r) => r.edge_type || '–' },
                  { key: 'from_inventory_display', label: 'From (critical component)', sortable: true, render: (r) => {
                    const ck = r.critical_component_key ?? r.from_inventory_id?.split('|').slice(0, 2).join('|');
                    if (r.row_type === 'component' && ck) {
                      return (
                        <button type="button" onClick={() => handleCriticalClick(ck, '')} style={{ background: 'rgba(251,191,36,0.4)', padding: '2px 5px', borderRadius: 3, fontWeight: 600, border: 'none', font: 'inherit', cursor: 'pointer', textAlign: 'left' }} title="Explain split formula">
                          {r.from_inventory_display || '–'}
                        </button>
                      );
                    }
                    return <code>{r.from_inventory_display || '–'}</code>;
                  } },
                  { key: 'candidates', label: 'To (candidates)', sortable: false, render: (r) => {
                    if (r.row_type === 'demand') {
                      return <code style={{ color: '#a78bfa' }}>{r.to_inventory_display ?? r.to_inventory_id ?? '–'}</code>;
                    }
                    if (!r.candidates?.length) return '–';
                    return (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                        {r.candidates.map((c, j) => (
                          <span key={j}>
                            <code>{c.to_inventory_display}</code>
                            <span style={{ color: '#71717a', marginLeft: 4 }}>qty: {c.qty}</span>
                            {c.edge_type && <span style={{ fontSize: '0.75em', color: '#52525b', marginLeft: 4 }}>({c.edge_type})</span>}
                          </span>
                        ))}
                      </div>
                    );
                  } },
                  { key: 'total_qty', label: 'Total qty', sortable: true, render: (r) => r.row_type === 'component' ? r.total_qty : (r.qty ?? '–') },
                  { key: 'split_explanation', label: 'Split (how & why)', sortable: false, render: (r) => (
                    r.split_explanation ? <span style={{ fontSize: '0.85em', color: '#a1a1aa', maxWidth: 420, display: 'inline-block' }}>{r.split_explanation}</span> : '–'
                  ) },
                  { key: 'basket_after', label: 'Basket', sortable: false, render: (r) => (
                      <button
                        type="button"
                        className="secondary"
                        onClick={() => { setBasketSlideInRow(r); setBasketShowingFinal(false); }}
                        style={{ padding: '2px 8px', fontSize: '0.85em' }}
                        title={`View basket state after step ${(r.basket_step_index ?? 0) + 1}`}
                      >
                        View
                      </button>
                    ) },
                  { key: 'output_date', label: 'Time', sortable: true, render: (r) => r.output_date ?? (r.output_period != null ? String(r.output_period) : '–') },
                  { key: 'demand_ids', label: 'Demands', render: (r) => (
                    <span title={r.demand_ids?.length ? `Demands that request this row's output product` : `Intermediate: output feeds later steps, not a final customer demand`}>
                      {r.demand_ids?.length ? r.demand_ids.join(', ') : '–'}
                    </span>
                  ) },
                ]}
              />
                </>
                )}
              </>
            )}
            {activeView === 'raw-material' && (
              <>
                {!selectedRunId && (
                  <p style={{ color: '#71717a' }}>Select a run above to see raw material usage (1xx-xxxx, 2xx-xxxx, 3xx-xxxx).</p>
                )}
                {selectedRunId && rawMaterialReportLoading && (
                  <p style={{ color: '#71717a' }}>Loading raw material usage report…</p>
                )}
                {selectedRunId && !rawMaterialReportLoading && rawMaterialReport && (
                  <>
                    <p style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.75rem' }}>
                      Supplies of products matching 1xx-xxxx, 2xx-xxxx, 3xx-xxxx: consumption and involvement (critical or companion, allocated or only considered).
                    </p>
                    {rawMaterialReport.supply_patterns_used.length === 0 && (!rawMaterialReport.involvement_trace || rawMaterialReport.involvement_trace.length === 0) && (
                      <p style={{ color: '#71717a' }}>No raw material (1xx-xxxx, 2xx-xxxx, 3xx-xxxx) was consumed or involved in this run. Re-run allocation to record involvement trace.</p>
                    )}
                    {rawMaterialReport.supply_patterns_used.length > 0 && (
                      <>
                        <h4 style={{ marginTop: '1rem', marginBottom: '0.5rem', fontSize: '0.95rem' }}>Consumption</h4>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '1rem', marginBottom: '1rem' }}>
                          {rawMaterialReport.supply_patterns_used.map((pattern) => {
                            const s = rawMaterialReport.summary[pattern];
                            if (!s) return null;
                            return (
                              <div
                                key={pattern}
                                style={{
                                  background: '#252528',
                                  borderRadius: 8,
                                  padding: '0.75rem 1rem',
                                  minWidth: 140,
                                }}
                              >
                                <div style={{ fontSize: '0.75rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{pattern}</div>
                                <div style={{ fontSize: '1.25rem', fontWeight: 600, color: '#fafafa' }}>
                                  {qtyFmt(Number(s.total_consumed_qty))}
                                </div>
                                <div style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>{s.node_count} node(s) consumed</div>
                              </div>
                            );
                          })}
                        </div>
                        <SortFilterTable
                          idKey="node"
                          rows={rawMaterialReport.details}
                          filterKeys={['pattern', 'product_id', 'location_id', 'node', 'consumed_qty']}
                          defaultSortKey="consumed_qty"
                          columns={[
                            { key: 'pattern', label: 'Pattern', sortable: true },
                            { key: 'product_id', label: 'Product', sortable: true },
                            { key: 'location_id', label: 'Location', sortable: true },
                            { key: 'node', label: 'Node', sortable: true },
                            { key: 'consumed_qty', label: 'Consumed qty', sortable: true, render: (r) => qtyFmt(Number(r.consumed_qty)) },
                          ]}
                        />
                      </>
                    )}
                    {rawMaterialReport.involvement_trace && rawMaterialReport.involvement_trace.length > 0 && (
                      <>
                        <h4 style={{ marginTop: '1.5rem', marginBottom: '0.5rem', fontSize: '0.95rem' }}>Involvement trace (critical / companion, allocated or considered)</h4>
                        <p style={{ fontSize: '0.8rem', color: '#71717a', marginBottom: '0.5rem' }}>
                          Every step where a raw material was critical or companion; allocated = actually used, considered_but_skipped = recipe used it but output was capped or skipped.
                        </p>
                        <div style={{ background: '#252528', borderRadius: 8, padding: '0.5rem', maxHeight: '40vh', overflow: 'auto' }}>
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                            <thead>
                              <tr style={{ textAlign: 'left', borderBottom: '1px solid #3d3d40' }}>
                                <th style={{ padding: '6px 8px' }}>Step</th>
                                <th style={{ padding: '6px 8px' }}>Role</th>
                                <th style={{ padding: '6px 8px' }}>Comp</th>
                                <th style={{ padding: '6px 8px' }}>Pattern</th>
                                <th style={{ padding: '6px 8px' }}>Status</th>
                                <th style={{ padding: '6px 8px' }}>In supply view</th>
                                <th style={{ padding: '6px 8px' }}>Details</th>
                              </tr>
                            </thead>
                            <tbody>
                              {rawMaterialReport.involvement_trace.map((e, i) => (
                                <tr key={i} style={{ borderBottom: '1px solid #2d2d30' }}>
                                  <td style={{ padding: '6px 8px' }}>{e.step}</td>
                                  <td style={{ padding: '6px 8px' }}>{e.role}</td>
                                  <td style={{ padding: '6px 8px' }}>{(e.comp_key || '').replace('|', '@')}</td>
                                  <td style={{ padding: '6px 8px' }}>{e.pattern}</td>
                                  <td style={{ padding: '6px 8px' }}>
                                    {e.considered_but_skipped ? 'considered, skipped' : e.allocated ? 'allocated' : '—'}
                                  </td>
                                  <td style={{ padding: '6px 8px' }}>
                                    {e.in_supply_view === false ? (
                                      <span style={{ color: '#f59e0b' }} title="This product|location has no Supply row; allocation came from move or production output.">No</span>
                                    ) : e.in_supply_view === true ? (
                                      <span style={{ color: '#22c55e' }}>Yes</span>
                                    ) : (
                                      '—'
                                    )}
                                  </td>
                                  <td style={{ padding: '6px 8px', maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                    {e.breakdown && e.breakdown.length > 0 && (
                                      <span title={e.breakdown.map((b) => `${b.variant_key}=${b.output_qty}`).join('; ')}>
                                        {e.breakdown.map((b) => `${b.variant_key}=${b.output_qty}`).join('; ')}
                                      </span>
                                    )}
                                    {e.considered_but_skipped && e.reason && <span>{e.reason}</span>}
                                    {e.role === 'companion' && e.allocated && e.taken != null && (
                                      <span>taken={e.taken} → {e.variant_key} (critical: {e.critical_component?.replace('|', '@')})</span>
                                    )}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </>
                    )}
                    {selectedRunId && rawMaterialReport.supply_patterns_used.length === 0 && rawMaterialReport.involvement_trace && rawMaterialReport.involvement_trace.length > 0 && (
                      <p style={{ marginTop: '0.75rem', color: '#71717a', fontSize: '0.875rem' }}>No raw material was consumed in this run; trace above shows involvement as critical or companion (considered but skipped).</p>
                    )}
                  </>
                )}
                {selectedRunId && !rawMaterialReportLoading && !rawMaterialReport && (
                  <p style={{ color: '#71717a' }}>Could not load raw material usage report.</p>
                )}
              </>
            )}
            {activeView === 'suggested' && (
              feasibleDemands === null ? (
                <p style={{ color: '#71717a' }}>Loading demands…</p>
              ) : demandsLoadError ? (
                <p style={{ color: '#f87171' }}>{demandsLoadError}. Switch run or refresh to retry.</p>
              ) : (
              <>
                {feasibleDemands.length > 0 && (() => {
                  const totalRequested = feasibleDemands.reduce((s, f) => s + (Number(f.requested_qty) || 0), 0);
                  const totalAllocated = feasibleDemands.reduce((s, f) => s + (Number(f.allocated_qty) || 0), 0);
                  const overallRate = totalRequested > 0 ? (totalAllocated / totalRequested) * 100 : 0;
                  const byCustomer = feasibleDemands.reduce<Record<string, { requested: number; allocated: number }>>((acc, f) => {
                    const c = (f.customer ?? f.customer_id ?? '–') as string;
                    if (!acc[c]) acc[c] = { requested: 0, allocated: 0 };
                    acc[c].requested += Number(f.requested_qty) || 0;
                    acc[c].allocated += Number(f.allocated_qty) || 0;
                    return acc;
                  }, {});
                  return (
                    <>
                      <p style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                        Overall fulfillment: <strong>{qtyFmt(totalAllocated)}</strong> / <strong>{qtyFmt(totalRequested)}</strong> requested = <strong>{overallRate.toFixed(1)}%</strong>
                      </p>
                      {Object.keys(byCustomer).length > 1 && (
                        <details style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                          <summary style={{ cursor: 'pointer' }}>Rollup by customer</summary>
                          <ul style={{ marginTop: '0.25rem', paddingLeft: '1.25rem' }}>
                            {Object.entries(byCustomer).map(([cust, { requested, allocated }]) => {
                              const rate = requested > 0 ? (allocated / requested) * 100 : 0;
                              return (
                                <li key={cust}>
                                  <strong>{cust}</strong>: {qtyFmt(allocated)} / {qtyFmt(requested)} = {rate.toFixed(1)}%
                                </li>
                              );
                            })}
                          </ul>
                        </details>
                      )}
                    </>
                  );
                })()}
                <SortFilterTable<FeasibleDemand & { suggested_revision?: string }>
                idKey="demand_id"
                rows={feasibleDemands.map((f) => ({
                  ...f,
                  suggested_revision: f.suggested_revision ?? (f.status === 'fulfilled'
                    ? tA('demandView.fulfilled')
                    : f.allocated_qty > 0
                      ? `${tA('demandView.reduceTo')} ${f.allocated_qty}`
                      : tA('demandView.unfulfilled')),
                }))}
                rowId={(r) => `demand-${r.demand_id}`}
                onRowClick={handleDemandPeggingClick}
                filterKeys={['demand_id', 'customer', 'customer_id', 'product_id', 'status', 'suggested_revision', 'request_due_time', 'revised_time', 'fulfillment_rate']}
                defaultSortKey="fulfillment_rate"
                columns={[
                  { key: 'demand_id', label: tA('demandView.columns.demandId'), sortable: true },
                  { key: 'customer', label: tA('demandView.columns.customer'), sortable: true, render: (r) => r.customer ?? r.customer_id ?? '–' },
                  { key: 'request_due_time', label: tA('demandView.columns.time'), sortable: true, render: (r) => r.request_due_time ?? '–' },
                  { key: 'revised_time', label: tA('demandView.columns.revisedTime'), sortable: true, render: (r) => r.revised_time ?? '–' },
                  { key: 'product_id', label: tA('demandView.columns.product'), sortable: true },
                  { key: 'requested_qty', label: tA('demandView.columns.requested'), sortable: true },
                  { key: 'allocated_qty', label: tA('demandView.columns.allocated'), sortable: true },
                  { key: 'fulfillment_rate', label: tA('demandView.columns.fulfillment'), sortable: true, render: (r) => r.fulfillment_rate != null ? `${(Number(r.fulfillment_rate) * 100).toFixed(1)}%` : '–' },
                  { key: 'status', label: tA('demandView.columns.status'), sortable: true },
                  { key: 'suggested_revision', label: tA('demandView.columns.suggestedRevision'), sortable: true },
                  { key: '_pegging', label: tA('demandView.columns.pegging'), sortable: false, render: (r) => <button type="button" className="secondary" onClick={() => handleDemandPeggingClick(r)}>{tc('show')}</button> },
                ]}
              />
              </>
              )
            )}
          </>
        )}
      </section>
      )}
      {caseSection === 'planning' && (
      <section>
        <h2>{tSec('planning')}</h2>
        <div style={{ marginBottom: '0.75rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.5rem' }}>
            <span style={{ color: '#a1a1aa', fontSize: '0.875rem' }}>{tP('config.methodSelection')}</span>
            {/* Max methods (replaces the legacy `multiple` boolean). Defaults to 2 in
                sync with the backend; legacy `multiple: false` reads as 1, `multiple: true` as 2. */}
            <label
              style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', cursor: 'pointer' }}
              title={tP('config.methodMaxCountTooltip')}
            >
              <span style={{ color: '#a1a1aa' }}>{tP('config.methodMaxCount')}</span>
              <select
                value={(() => {
                  const ms = planningConfig.method_selection;
                  if (typeof ms?.max_methods === 'number') return Math.max(1, Math.min(4, Math.trunc(ms.max_methods)));
                  if (ms?.multiple === false) return 1;
                  return 2;
                })()}
                onChange={(e) => setPlanningConfig((c) => {
                  const v = Math.max(1, Math.min(4, parseInt(e.target.value, 10) || 2));
                  // Drop legacy `multiple` on save; backend resolution prefers max_methods anyway.
                  const { multiple: _drop, ...rest } = c.method_selection ?? {};
                  void _drop;
                  return { ...c, method_selection: { ...rest, max_methods: v } };
                })}
                style={{ padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
              >
                <option value={1}>1</option>
                <option value={2}>2</option>
                <option value={3}>3</option>
                <option value={4}>4</option>
              </select>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={(() => {
                  const ms = planningConfig.method_selection;
                  if (ms?.mode === 'elaborate') return true;
                  if (ms?.mode === 'preference') return false;
                  return ms?.elaborate === true;
                })()}
                onChange={(e) => setPlanningConfig((c) => ({
                  ...c,
                  method_selection: {
                    ...c.method_selection,
                    elaborate: e.target.checked,
                    mode: e.target.checked ? 'elaborate' : 'preference',
                  },
                }))}
              />
              <span>{tP('config.elaborateMethod')}</span>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', opacity: planningConfig.method_selection?.elaborate === true ? 1 : 0.4 }}>
              <span style={{ color: '#a1a1aa' }}>{tP('config.methodDepth')}</span>
              <input
                type="number"
                min={1}
                max={500}
                disabled={planningConfig.method_selection?.elaborate !== true || planningConfig.method_selection?.depth_optimal === true}
                value={planningConfig.method_selection?.depth ?? 1}
                onChange={(e) => {
                  const v = Math.max(1, Math.min(500, parseInt(e.target.value, 10) || 1));
                  setPlanningConfig((c) => ({ ...c, method_selection: { ...c.method_selection, depth: v } }));
                }}
                style={{ width: 56, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
              />
            </label>
            <label
              style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', cursor: 'pointer', opacity: planningConfig.method_selection?.elaborate === true ? 1 : 0.4 }}
              title={tP('config.depthOptimalHint')}
            >
              <input
                type="checkbox"
                disabled={planningConfig.method_selection?.elaborate !== true}
                checked={planningConfig.method_selection?.depth_optimal === true}
                onChange={(e) => setPlanningConfig((c) => ({
                  ...c,
                  method_selection: { ...c.method_selection, depth_optimal: e.target.checked },
                }))}
              />
              <span style={{ color: '#a1a1aa' }}>{tP('config.depthOptimal')}</span>
            </label>
          </div>
          {(() => {
            const elaborateOn = planningConfig.method_selection?.elaborate === true;
            const weights = planningConfig.method_selection?.score_weights;
            const wCommit = weights?.commit_time ?? 0.4;
            const wInv = weights?.inventory_consumed ?? 0.35;
            const wPurchase = weights?.purchase ?? 0.25;
            const updateWeight = (key: 'commit_time' | 'inventory_consumed' | 'purchase', v: number) => {
              const clamped = Math.max(0, Math.min(1, isNaN(v) ? 0 : v));
              setPlanningConfig((c) => ({
                ...c,
                method_selection: {
                  ...c.method_selection,
                  score_weights: {
                    commit_time: key === 'commit_time' ? clamped : (c.method_selection?.score_weights?.commit_time ?? 0.4),
                    inventory_consumed: key === 'inventory_consumed' ? clamped : (c.method_selection?.score_weights?.inventory_consumed ?? 0.35),
                    purchase: key === 'purchase' ? clamped : (c.method_selection?.score_weights?.purchase ?? 0.25),
                  },
                },
              }));
            };
            const inputStyle: React.CSSProperties = { width: 64, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' };
            const labelStyle: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: '0.35rem', fontSize: '0.875rem', opacity: elaborateOn ? 1 : 0.4 };
            return (
              <div
                style={{ display: 'flex', alignItems: 'center', gap: '0.9rem', flexWrap: 'wrap', marginBottom: '0.5rem', marginLeft: '1rem' }}
                title={tP('config.weightsHint')}
              >
                <span style={{ color: '#a1a1aa', fontSize: '0.8rem', opacity: elaborateOn ? 1 : 0.5 }}>{tP('config.weightsLabel')}</span>
                <label style={labelStyle}>
                  <span style={{ color: '#a1a1aa' }}>{tP('config.weightCommit')}</span>
                  <input type="number" min={0} max={1} step={0.05}
                    disabled={!elaborateOn}
                    value={wCommit}
                    onChange={(e) => updateWeight('commit_time', parseFloat(e.target.value))}
                    style={inputStyle} />
                </label>
                <label style={labelStyle}>
                  <span style={{ color: '#a1a1aa' }}>{tP('config.weightInventory')}</span>
                  <input type="number" min={0} max={1} step={0.05}
                    disabled={!elaborateOn}
                    value={wInv}
                    onChange={(e) => updateWeight('inventory_consumed', parseFloat(e.target.value))}
                    style={inputStyle} />
                </label>
                <label style={labelStyle}>
                  <span style={{ color: '#a1a1aa' }}>{tP('config.weightPurchase')}</span>
                  <input type="number" min={0} max={1} step={0.05}
                    disabled={!elaborateOn}
                    value={wPurchase}
                    onChange={(e) => updateWeight('purchase', parseFloat(e.target.value))}
                    style={inputStyle} />
                </label>
              </div>
            );
          })()}
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', marginRight: '1rem', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={planningConfig.purchase_allowed !== false}
              onChange={(e) => setPlanningConfig((c) => ({ ...c, purchase_allowed: e.target.checked }))}
            />
            <span>{tP('config.purchaseAllowed')}</span>
          </label>
          <div style={{ marginTop: '0.5rem', display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={planningConfig.consolidation?.enabled === true}
                onChange={(e) => setPlanningConfig((c) => ({
                  ...c,
                  consolidation: { ...c.consolidation, enabled: e.target.checked },
                }))}
              />
              <span>{tP('config.consolidate')}</span>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', opacity: planningConfig.consolidation?.enabled === true ? 1 : 0.4 }}>
              <span style={{ color: '#a1a1aa' }}>{tP('config.bucketDays')}</span>
              <input
                type="number"
                min={0}
                max={365}
                disabled={planningConfig.consolidation?.enabled !== true}
                value={planningConfig.consolidation?.period_days ?? 0}
                onChange={(e) => {
                  const raw = parseInt(e.target.value, 10);
                  const v = Math.max(0, Math.min(365, Number.isNaN(raw) ? 0 : raw));
                  setPlanningConfig((c) => ({ ...c, consolidation: { ...c.consolidation, period_days: v } }));
                }}
                style={{ width: 64, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
              />
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', opacity: planningConfig.consolidation?.enabled === true ? 1 : 0.4 }}>
              <span style={{ color: '#a1a1aa' }}>{tP('config.splitPolicy')}</span>
              <select
                disabled={planningConfig.consolidation?.enabled !== true}
                value={planningConfig.consolidation?.allocation_mode ?? 'fair'}
                onChange={(e) => setPlanningConfig((c) => ({
                  ...c,
                  consolidation: { ...c.consolidation, allocation_mode: e.target.value as 'priority_first' | 'proportional' | 'fair' },
                }))}
                style={{ padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
              >
                <option value="fair">{tP('config.fair')}</option>
                <option value="proportional">{tP('config.proportional')}</option>
                <option value="priority_first">{tP('config.priorityFirst')}</option>
              </select>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', opacity: planningConfig.consolidation?.enabled === true ? 1 : 0.4 }}>
              <span style={{ color: '#a1a1aa' }}>{tP('config.regulationScope')}</span>
              <select
                disabled={planningConfig.consolidation?.enabled !== true}
                value={planningConfig.consolidation?.scope ?? 'leaf-only'}
                onChange={(e) => setPlanningConfig((c) => ({
                  ...c,
                  consolidation: { ...c.consolidation, scope: e.target.value as 'leaf-only' | 'all' },
                }))}
                style={{ padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
                title={tP('config.scopeTooltip')}
              >
                <option value="leaf-only">{tP('config.scopeLeavesOnly')}</option>
                <option value="all">{tP('config.scopeAllLevels')}</option>
              </select>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={analyzeCriticalityEnabled}
                onChange={(e) => setAnalyzeCriticalityEnabled(e.target.checked)}
              />
              <span style={{ fontSize: '0.875rem' }}>{tP('config.analyzeCriticality')}</span>
            </label>
            <label
              style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}
              title={tP('config.checkSoundnessTooltip')}
            >
              <input
                type="checkbox"
                checked={checkSoundnessEnabled}
                onChange={(e) => setCheckSoundnessEnabled(e.target.checked)}
              />
              <span style={{ fontSize: '0.875rem' }}>{tP('config.checkSoundness')}</span>
            </label>
          </div>
          <br style={{ marginTop: '0.25rem' }} />
          <button
            type="button"
            disabled={planLoading || currentRunIsContingent}
            title={currentRunIsContingent ? 'Viewing a contingent (what-if) run — load a baseline run first to re-plan' : undefined}
            onClick={async () => {
              setPlanError(null);
              setPlanLoading(true);
              setPlanProgress(null);
              setFreshPlanRunId(null);
              setFreshRunName('');
              setFreshRunNotes('');
              setPlanRunSaveError(null);
              setAutoCriticalityPending(false);
              setSupplyCriticalityMap({});
              setCriticalityProgress(null);
              // Capture the currently-loaded run as override candidate (only if it's a success run).
              // Contingent runs are blocked from re-plan by the disabled guard, so currentPlanRunId
              // here is always either null or a success run.
              setOverrideCandidateRunId(currentPlanRunId);
              // runPlanAsync normalizes method_selection (drops legacy `multiple`,
              // derives `max_methods` if absent) so we don't duplicate that here.
              const config = Object.keys(planningConfig).length ? planningConfig : undefined;
              try {
                const { job_id } = await runPlanAsync(id, config);
                setPlanJobId(job_id);
                setPlanProgress({ current: 0, total: 1 });
              } catch (e) {
                setPlanError(e instanceof Error ? e.message : 'Plan failed');
                setPlanLoading(false);
              }
            }}
          >
            {planLoading ? tP('running') : tP('runPlan')}
          </button>
          <span style={{ marginLeft: '0.5rem' }} />
          <button
            type="button"
            className="secondary"
            disabled={planLoading}
            onClick={() => setPlanningConfig({
              method_selection: {
                multiple: false,
                elaborate: false,
                depth: 1,
                depth_optimal: false,
                score_weights: { commit_time: 0.4, inventory_consumed: 0.35, purchase: 0.25 },
              },
              purchase_allowed: false,
              consolidation: { enabled: true, period_days: 0, allocation_mode: 'fair', scope: 'leaf-only' },
            })}
            title={tP('config.resetDefaultsTitle')}
            style={{ padding: '6px 12px' }}
          >
            {tP('config.resetDefaults')}
          </button>
          <span style={{ marginLeft: '0.5rem' }} />
          <button
            type="button"
            className="secondary"
            onClick={() => { loadPlanRunHistory(); setPlanRunHistoryOpen(true); }}
            style={{ padding: '6px 12px' }}
          >
            {tP('planRunHistory')}
          </button>
          <span style={{ marginLeft: '0.5rem' }} />
          <button
            type="button"
            className="secondary"
            onClick={openBootstrapDialog}
            title={tP('bootstrap.buttonTooltip')}
            style={{ padding: '6px 12px' }}
          >
            {tP('bootstrap.button')}
          </button>
          <span style={{ marginLeft: '0.5rem' }} />
          <button
            type="button"
            onClick={() => setCopilotOpen((o) => !o)}
            aria-expanded={copilotOpen}
            style={{
              padding: '6px 12px',
              background: copilotOpen ? '#3b82f6' : 'transparent',
              color: copilotOpen ? '#fff' : '#3b82f6',
              border: '1px solid #3b82f6',
              borderRadius: 6,
              cursor: 'pointer',
              fontWeight: 500,
            }}
          >
            {copilotOpen ? tP('hideCopilot') : tP('configureCopilot')}
          </button>
        </div>
        {planLoading && planProgress && planProgress.total > 0 && (
          <div style={{ marginTop: '0.5rem', maxWidth: 400 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.25rem' }}>
              <span>
                {tP('planProgress')} {planProgress.current} / {planProgress.total} {tP('demands')}
                {planProgress.iteration && planProgress.iterations_max
                  ? ` (iter ${planProgress.iteration}/${planProgress.iterations_max})`
                  : ''}
              </span>
            </div>
            <div style={{ height: 8, backgroundColor: '#27272a', borderRadius: 4, overflow: 'hidden' }}>
              <div
                style={{
                  height: '100%',
                  width: `${Math.min(100, 100 * planProgress.current / planProgress.total)}%`,
                  backgroundColor: '#3b82f6',
                  transition: 'width 0.2s ease',
                }}
              />
            </div>
          </div>
        )}
        {planError && <p style={{ color: '#f87171', marginTop: '0.5rem' }}>{planError}</p>}
        {planResult && !planLoading && (
          <>
            {(() => {
              // Loaded-in-memory indicator. Shown for every non-contingent, non-unsaved state
              // (contingent and unsaved have their own banners below).
              if (currentRunIsContingent) return null;
              if (currentPlanRunId === null && freshPlanRunId !== null) return null;
              const loadedRunId = currentPlanRunId ?? freshPlanRunId;
              if (loadedRunId == null) return null;
              const loadedRow = planRunHistory.find(r => r.id === loadedRunId);
              const label = loadedRow?.name?.trim() || tP('runHistory.viewing.unnamed');
              return (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#0f1f33', border: '1px solid #1d4ed8', borderRadius: 6, padding: '6px 12px', marginBottom: '0.75rem', fontSize: '0.82rem', color: '#bfdbfe' }}>
                  <span style={{ fontWeight: 700, color: '#93c5fd' }}>{tP('runHistory.viewing.banner', { id: loadedRunId })}</span>
                  <span style={{ color: '#cbd5e1' }}>— {label}</span>
                  {loadedRow?.is_active && (
                    <span title={loadedRow.is_active_designated ? tP('runHistory.chips.activeDesignatedTitle') : tP('runHistory.chips.activeLatestTitle')} style={{ background: '#14532d', color: '#bbf7d0', borderRadius: 8, padding: '1px 7px', fontSize: '0.7rem', fontWeight: 600 }}>
                      {loadedRow.is_active_designated ? tP('runHistory.chips.activeDesignated') : tP('runHistory.chips.active')}
                    </span>
                  )}
                  {loadedRow?.is_initial && (
                    <span title={tP('runHistory.chips.initialTitle')} style={{ background: '#1e3a8a', color: '#bfdbfe', borderRadius: 8, padding: '1px 7px', fontSize: '0.7rem', fontWeight: 600 }}>
                      {tP('runHistory.chips.initial')}
                    </span>
                  )}
                </div>
              );
            })()}
            {currentRunIsContingent && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#2e1065', border: '1px solid #7c3aed', borderRadius: 6, padding: '6px 12px', marginBottom: '0.75rem', fontSize: '0.82rem', color: '#ddd6fe' }}>
                <span style={{ fontWeight: 700, color: '#a78bfa' }}>{tP('runHistory.viewing.contingentBanner', { id: currentPlanRunId ?? 0 })}</span>
                <span>{tP('runHistory.viewing.contingentNote')}</span>
              </div>
            )}
            {currentPlanRunId === null && freshPlanRunId !== null && (
              <div style={{ marginBottom: '0.75rem', padding: '0.6rem 0.75rem', background: '#1a2e1a', border: '1px solid #166534', borderRadius: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: '0.5rem' }}>
                  <span style={{ fontSize: '0.78rem', color: '#86efac', fontWeight: 600 }}>{tP('runHistory.unsaved.title', { id: freshPlanRunId })}</span>
                  <span style={{ fontSize: '0.75rem', color: '#71717a' }}>
                    {tP('runHistory.unsaved.note')}
                    {overrideCandidateRunId !== null && tP('runHistory.unsaved.reranOn', { id: overrideCandidateRunId })}
                  </span>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap' }}>
                  <input
                    type="text"
                    placeholder={tP('runHistory.unsaved.namePlaceholder')}
                    value={freshRunName}
                    onChange={(e) => setFreshRunName(e.target.value)}
                    style={{ flex: '1 1 180px', minWidth: 0, padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
                  />
                  <input
                    type="text"
                    placeholder={tP('runHistory.unsaved.notesPlaceholder')}
                    value={freshRunNotes}
                    onChange={(e) => setFreshRunNotes(e.target.value)}
                    style={{ flex: '2 1 240px', minWidth: 0, padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
                  />
                  {overrideCandidateRunId !== null && (
                    <button
                      type="button"
                      disabled={planRunSaving}
                      title={tP('runHistory.unsaved.saveOverrideTitle', { id: overrideCandidateRunId })}
                      onClick={async () => {
                        if (!freshPlanRunId || !id || overrideCandidateRunId == null) return;
                        setPlanRunSaving(true);
                        setPlanRunSaveError(null);
                        try {
                          await savePlanRun(id, freshPlanRunId, {
                            name: freshRunName.trim() || undefined,
                            notes: freshRunNotes.trim() || undefined,
                            mode: 'override',
                            target_run_id: overrideCandidateRunId,
                          });
                          setCurrentPlanRunId(overrideCandidateRunId);
                          setFreshPlanRunId(null);
                          setOverrideCandidateRunId(null);
                          setFreshRunName('');
                          setFreshRunNotes('');
                          const runs = await listPlanRuns(id);
                          setPlanRunHistory(runs);
                        } catch (e) {
                          setPlanRunSaveError(e instanceof Error ? e.message : 'Save failed');
                        } finally {
                          setPlanRunSaving(false);
                        }
                      }}
                      style={{ padding: '5px 14px', background: '#2563eb', color: '#fff', border: 'none', borderRadius: 6, cursor: planRunSaving ? 'wait' : 'pointer', fontWeight: 600, fontSize: '0.82rem', whiteSpace: 'nowrap' }}
                    >
                      {planRunSaving ? tP('runHistory.unsaved.saving') : tP('runHistory.unsaved.saveOverride', { id: overrideCandidateRunId })}
                    </button>
                  )}
                  <button
                    type="button"
                    disabled={planRunSaving}
                    onClick={async () => {
                      if (!freshPlanRunId || !id) return;
                      setPlanRunSaving(true);
                      setPlanRunSaveError(null);
                      try {
                        await savePlanRun(id, freshPlanRunId, {
                          name: freshRunName.trim() || undefined,
                          notes: freshRunNotes.trim() || undefined,
                        });
                        setCurrentPlanRunId(freshPlanRunId);
                        setFreshPlanRunId(null);
                        setOverrideCandidateRunId(null);
                        setFreshRunName('');
                        setFreshRunNotes('');
                        const runs = await listPlanRuns(id);
                        setPlanRunHistory(runs);
                      } catch (e) {
                        setPlanRunSaveError(e instanceof Error ? e.message : 'Save failed');
                      } finally {
                        setPlanRunSaving(false);
                      }
                    }}
                    style={{ padding: '5px 14px', background: '#16a34a', color: '#fff', border: 'none', borderRadius: 6, cursor: planRunSaving ? 'wait' : 'pointer', fontWeight: 600, fontSize: '0.82rem', whiteSpace: 'nowrap' }}
                  >
                    {planRunSaving ? tP('runHistory.unsaved.saving') : (overrideCandidateRunId !== null ? tP('runHistory.unsaved.saveAsNew') : tP('runHistory.unsaved.saveRun'))}
                  </button>
                </div>
                {planRunSaveError && <p style={{ margin: '0.4rem 0 0', fontSize: '0.75rem', color: '#f87171' }}>{planRunSaveError}</p>}
              </div>
            )}
            {/* Plan KPI dashboard – always show when plan result exists; build kpis safely from backend or client */}
            <PlanKpiDashboard planResult={planResult} />
            <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1.5rem', marginBottom: '0.5rem' }}>
              <button
                type="button"
                className={planResultTab === 'demands' ? '' : 'secondary'}
                onClick={() => setPlanResultTab('demands')}
              >
                {tP('tabs.committedDemands')}
              </button>
              <button
                type="button"
                className={planResultTab === 'work_orders' ? '' : 'secondary'}
                onClick={() => setPlanResultTab('work_orders')}
              >
                {tP('tabs.workOrders')}
              </button>
              <button
                type="button"
                className={planResultTab === 'supplies' ? '' : 'secondary'}
                onClick={() => setPlanResultTab('supplies')}
              >
                {tP('tabs.supplies', { count: qtyFmt(planSupplyViewRows.length) })}
              </button>
            </div>
            <div style={{ border: '1px solid #3d3d40', borderRadius: 6 }}>
              {planResultTab === 'demands' && (
                <div style={{ padding: '0.75rem 1rem' }}>
                  <h4 style={{ marginTop: 0, marginBottom: '0.5rem' }}>{tP('committedDemands.heading')}</h4>
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandRealMakeOnly}
                        onChange={(e) => setPlanDemandRealMakeOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterRealMake')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandBuyOnly}
                        onChange={(e) => setPlanDemandBuyOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterBuy')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandRealMoveOnly}
                        onChange={(e) => setPlanDemandRealMoveOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterRealMove')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandShortOnly}
                        onChange={(e) => setPlanDemandShortOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterShortOnly')}</span>
                    </label>
                    <label
                      style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}
                      title={tP('committedDemands.filterAlternativesOnlyTooltip')}
                    >
                      <input
                        type="checkbox"
                        checked={planDemandAlternativesOnly}
                        onChange={(e) => setPlanDemandAlternativesOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterAlternativesOnly')}</span>
                    </label>
                    {planDemandRealMakeOnly && (
                      <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>
                        {bomRealPairs === null
                          ? tP('committedDemands.loadingBomPairs')
                          : bomRealPairs.length === 0
                            ? tP('committedDemands.noBomPairs')
                            : ''}
                      </span>
                    )}
                    {planDemandRealMoveOnly && (
                      <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>
                        {realMoveTriples === null
                          ? tP('committedDemands.loadingMoves')
                          : realMoveTriples.length === 0
                            ? tP('committedDemands.noMoves')
                            : ''}
                      </span>
                    )}
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.8rem', color: '#e4e4e7', position: 'relative' }}>
                      <span style={{ whiteSpace: 'nowrap' }}>Supply:</span>
                      <div style={{ position: 'relative' }}>
                        {showSupplyFilterSuggestions && supplyFilterSuggestions.length > 0 && (
                          <ul
                            ref={supplyFilterListRef}
                            style={{
                              position: 'absolute',
                              bottom: '100%',
                              left: 0,
                              marginBottom: '2px',
                              background: '#fff',
                              border: '1px solid #d4d4d8',
                              borderRadius: '6px',
                              boxShadow: '0 4px 16px rgba(0,0,0,0.15)',
                              maxHeight: '220px',
                              overflowY: 'auto',
                              zIndex: 200,
                              minWidth: '260px',
                              listStyle: 'none',
                              margin: 0,
                              padding: 0,
                            }}
                          >
                            {supplyFilterSuggestions.map((s, i) => (
                              <li
                                key={s.id}
                                onMouseDown={(e) => { e.preventDefault(); selectSupplyFilterSuggestion(s); }}
                                style={{
                                  padding: '6px 10px',
                                  cursor: 'pointer',
                                  background: i === supplyFilterSuggestionIndex ? '#eff6ff' : 'transparent',
                                  borderBottom: i < supplyFilterSuggestions.length - 1 ? '1px solid #f4f4f5' : 'none',
                                }}
                                onMouseEnter={() => setSupplyFilterSuggestionIndex(i)}
                              >
                                <div style={{ fontFamily: 'monospace', fontWeight: 600, color: '#1d4ed8', fontSize: '0.8rem' }}>{s.id}</div>
                                <div style={{ fontSize: '0.72rem', color: '#71717a', marginTop: '1px' }}>
                                  {[s.productId, s.supplyDate, s.qty != null ? `qty ${s.qty}` : null, s.locationId].filter(Boolean).join(' · ')}
                                </div>
                              </li>
                            ))}
                          </ul>
                        )}
                        <input
                          type="text"
                          value={supplyFilterInput}
                          onChange={(e) => handleSupplyFilterInputChange(e.target.value)}
                          onKeyDown={(e) => {
                            if (showSupplyFilterSuggestions && supplyFilterSuggestions.length > 0) {
                              if (e.key === 'ArrowDown') { e.preventDefault(); setSupplyFilterSuggestionIndex(i => Math.min(i + 1, supplyFilterSuggestions.length - 1)); return; }
                              if (e.key === 'ArrowUp')   { e.preventDefault(); setSupplyFilterSuggestionIndex(i => Math.max(i - 1, -1)); return; }
                              if (e.key === 'Enter' && supplyFilterSuggestionIndex >= 0) { e.preventDefault(); selectSupplyFilterSuggestion(supplyFilterSuggestions[supplyFilterSuggestionIndex]); return; }
                            }
                            if (e.key === 'Escape') { setShowSupplyFilterSuggestions(false); setSupplyFilterSuggestionIndex(-1); }
                          }}
                          onBlur={() => setTimeout(() => setShowSupplyFilterSuggestions(false), 150)}
                          placeholder="type product id…"
                          style={{
                            fontSize: '0.8rem',
                            padding: '0.2rem 0.4rem',
                            borderRadius: '4px',
                            border: `1px solid ${planDemandSupplyFilter ? '#3b82f6' : '#52525b'}`,
                            background: '#27272a',
                            color: '#e4e4e7',
                            width: '160px',
                          }}
                        />
                      </div>
                      {(supplyFilterInput || planDemandSupplyFilter) && (
                        <button
                          onClick={() => { setSupplyFilterInput(''); setPlanDemandSupplyFilter(''); setShowSupplyFilterSuggestions(false); }}
                          style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.85rem', padding: 0 }}
                        >✕</button>
                      )}
                      {planDemandSupplyFilter && (
                        <span style={{ fontSize: '0.72rem', color: '#60a5fa', fontFamily: 'monospace' }}>{planDemandSupplyFilter}</span>
                      )}
                    </div>
                  </div>
                  {(() => {
                    if (!planResult?.committed_demands.length) return null;
                    // Last entry per demand_id — used by all filters except supply filter.
                    // (planning_pegging = consolidatedPegging + planningPegging; last = main planning tree)
                    const peggingByDemandId = (planResult.planning_pegging ?? []).reduce<Record<string, PlanningPeggingEntry>>((acc, e) => {
                      if (e.demand_id) acc[e.demand_id] = e;
                      return acc;
                    }, {});
                    let list = planResult.committed_demands;
                    if (planDemandRealMakeOnly && bomRealPairs !== null) {
                      const keys = new Set(bomRealPairs.map(([p, c]) => `${(p ?? '').trim()}|${(c ?? '').trim()}`));
                      list = list.filter((r) => {
                        const demandId = r.demand_id ?? (r as unknown as { demand_id?: string }).demand_id;
                        const entry = demandId ? peggingByDemandId[demandId] : null;
                        if (!entry?.tree) return false;
                        return peggingTreeContainsRealMake(entry.tree, keys);
                      });
                    }
                    if (planDemandBuyOnly) {
                      list = list.filter((r) => {
                        const demandId = r.demand_id ?? (r as unknown as { demand_id?: string }).demand_id;
                        const entry = demandId ? peggingByDemandId[demandId] : null;
                        if (!entry?.tree) return false;
                        return peggingTreeContainsPurchase(entry.tree);
                      });
                    }
                    if (planDemandRealMoveOnly && realMoveTriples !== null) {
                      const moveKeys = new Set(realMoveTriples.map(([p, from_, to]) => `${(p ?? '').trim()}|${(from_ ?? '').trim()}|${(to ?? '').trim()}`));
                      list = list.filter((r) => {
                        const demandId = r.demand_id ?? (r as unknown as { demand_id?: string }).demand_id;
                        const entry = demandId ? peggingByDemandId[demandId] : null;
                        if (!entry?.tree) return false;
                        return peggingTreeContainsRealMove(entry.tree, moveKeys);
                      });
                    }
                    if (planDemandShortOnly) {
                      list = list.filter((r) => (r.shortage ?? 0) > 0.01);
                    }
                    if (planDemandAlternativesOnly) {
                      list = list.filter((r) => {
                        const demandId = r.demand_id ?? (r as unknown as { demand_id?: string }).demand_id;
                        const entry = demandId ? peggingByDemandId[demandId] : null;
                        if (!entry?.tree) return false;
                        return peggingTreeHasAlternatives(entry.tree);
                      });
                    }
                    if (planDemandSupplyFilter.trim()) {
                      // Use the same criterion as the Supply View's "Pegged Demands" column:
                      // a demand matches iff it actually consumed from this supply_id (per
                      // supplyPeggingMap). This keeps the two views consistent — no more
                      // product-prefix false positives or full-shortage demands slipping in.
                      const sid = planDemandSupplyFilter.trim();
                      const pegging = supplyPeggingMap.get(sid);
                      const demandSet = new Set((pegging?.demands ?? []).map((d) => d.demandId));
                      list = list.filter((r) => demandSet.has(r.demand_id ?? ''));
                    }
                    const byCustomer = list.reduce<Record<string, number>>((acc, r) => {
                      const cName = (r.customer ?? r.customer_id ?? '–') as string;
                      acc[cName] = (acc[cName] ?? 0) + (Number(r.quantity) || 0);
                      return acc;
                    }, {});
                    const hasMultipleCustomers = Object.keys(byCustomer).length > 1;
                    return (
                      <>
                        <p style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: hasMultipleCustomers ? '0.5rem' : '0.25rem' }}>
                          {tP('committedDemands.showing')} {list.length} {tP('committedDemands.of')} {planResult.committed_demands.length} {tP('committedDemands.demandsLabel')}
                          {planDemandRealMakeOnly && !planDemandBuyOnly && !planDemandRealMoveOnly && ' ' + tP('committedDemands.filterDescMake')}
                          {!planDemandRealMakeOnly && planDemandBuyOnly && !planDemandRealMoveOnly && ' ' + tP('committedDemands.filterDescBuy')}
                          {!planDemandRealMakeOnly && !planDemandBuyOnly && planDemandRealMoveOnly && ' ' + tP('committedDemands.filterDescMove')}
                          {planDemandRealMakeOnly && planDemandBuyOnly && !planDemandRealMoveOnly && ' ' + tP('committedDemands.filterDescMakeAndBuy')}
                          {(planDemandRealMakeOnly || planDemandBuyOnly || planDemandRealMoveOnly) && [planDemandRealMakeOnly, planDemandBuyOnly, planDemandRealMoveOnly].filter(Boolean).length > 1 && ' ' + tP('committedDemands.filterDescMultiple')}
                        </p>
                        {planDemandRealMakeOnly && list.length === 0 && bomRealPairs !== null && bomRealPairs.length > 0 && (
                          <p style={{ fontSize: '0.8rem', color: '#71717a', marginBottom: '0.5rem' }}>
                            {tP('committedDemands.noMakeInPlan')}
                          </p>
                        )}
                        {planDemandBuyOnly && list.length === 0 && (
                          <p style={{ fontSize: '0.8rem', color: '#71717a', marginBottom: '0.5rem' }}>
                            {tP('committedDemands.noBuyInPlan')}
                          </p>
                        )}
                        {planDemandRealMoveOnly && list.length === 0 && realMoveTriples !== null && realMoveTriples.length > 0 && (
                          <p style={{ fontSize: '0.8rem', color: '#71717a', marginBottom: '0.5rem' }}>
                            {tP('committedDemands.noMoveInPlan')}
                          </p>
                        )}
                        {hasMultipleCustomers && (
                          <details style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                            <summary style={{ cursor: 'pointer' }}>{tP('committedDemands.rollupFiltered')}</summary>
                            <ul style={{ marginTop: '0.25rem', paddingLeft: '1.25rem' }}>
                              {Object.entries(byCustomer).map(([cust, qty]) => (
                                <li key={cust}><strong>{cust}</strong>: {qtyFmt(Number(qty))} {tP('committedDemands.committed')}</li>
                              ))}
                            </ul>
                          </details>
                        )}
                        <SortFilterTable<CommittedDemand & { _key?: string; _customer?: string }>
                          idKey="_key"
                          rows={list.map((r, i) => ({
                            ...r,
                            _key: `cd-${r.demand_id ?? ''}-${r.product_id}-${r.location_id}-${i}`,
                            _customer: String(r.customer ?? r.customer_id ?? ''),
                          }))}
                          filterKeys={['demand_id', '_customer', 'product_id', 'location_id', 'commit_time', 'commit_reason']}
                          filterPlaceholder={tP('committedDemands.filterPlaceholder')}
                          defaultSortKey="commit_time"
                          stickyHeader
                          rowStyle={(r) => {
                            const k = `demand|${r.demand_id ?? ''}|${r.product_id}|${r.location_id}`;
                            if (r.is_failed) return { background: 'rgba(248,113,113,0.08)', outline: '1px solid rgba(248,113,113,0.3)' };
                            if (woPeggingRowKey === k) return { background: 'rgba(56,189,248,0.12)', outline: '1px solid rgba(56,189,248,0.35)' };
                            return undefined;
                          }}
                          columns={[
                            { key: 'demand_id', label: tP('committedDemands.columns.demandId'), sortable: true, render: (r) => r.demand_id ?? '–' },
                            { key: '_customer', label: tP('committedDemands.columns.customer'), sortable: true, render: (r) => (r as { _customer?: string })._customer || (r.customer ?? r.customer_id ?? '–') },
                            { key: 'product_id', label: tP('committedDemands.columns.product'), sortable: true },
                            { key: 'location_id', label: tP('committedDemands.columns.location'), sortable: true },
                            { key: 'requested_qty', label: tP('committedDemands.columns.requested'), sortable: true, render: (r) => r.requested_qty != null ? qtyFmt(Number(r.requested_qty)) : '–' },
                            { key: 'quantity', label: tP('committedDemands.columns.committed'), sortable: true, render: (r) => r.is_failed
                              ? <span style={{ color: '#f87171', fontWeight: 600, fontSize: '0.78rem', background: 'rgba(248,113,113,0.15)', padding: '1px 6px', borderRadius: 4 }}>{tP('committedDemands.failed')}</span>
                              : qtyFmt(Number(r.quantity)) },
                            { key: 'shortage', label: tP('committedDemands.columns.shortage'), sortable: true, render: (r) => {
                              const s = r.shortage ?? 0;
                              return s > 0.01
                                ? <span style={{ color: '#f87171', fontWeight: 600 }}>{qtyFmt(Number(s))}</span>
                                : <span style={{ color: '#4ade80' }}>0</span>;
                            }},
                            { key: 'request_time', label: tP('committedDemands.columns.requestTime'), sortable: true, render: (r) => r.request_time ?? '–' },
                            { key: 'commit_time', label: tP('committedDemands.columns.commitTime'), sortable: true, render: (r) => r.commit_time ?? '–' },
                            { key: 'commit_reason', label: tP('committedDemands.columns.commitReason'), sortable: true, render: (r) => {
                              if (!r.commit_reason) return <span style={{ color: '#52525b' }}>–</span>;
                              const { label, tooltip } = formatCommitReason(r.commit_reason, planningConfig.purchase_allowed !== false);
                              if (!r.is_failed) {
                                // Non-failure reason (e.g. "partial") — amber, informational
                                const isPartial = r.commit_reason === 'partial' || r.commit_reason.startsWith('partial:');
                                return (
                                  <span title={tooltip || r.commit_reason} style={{ color: isPartial ? '#fb923c' : undefined, cursor: tooltip ? 'help' : 'default', fontSize: '0.78rem' }}>
                                    {label}
                                  </span>
                                );
                              }
                              return (
                                <span title={tooltip || r.commit_reason} style={{ color: '#f87171', cursor: tooltip ? 'help' : 'default' }}>
                                  {label}
                                </span>
                              );
                            } },
                            { key: '_pegging', label: tP('committedDemands.columns.pegging'), sortable: false, render: (r) => {
                              const k = `demand|${r.demand_id ?? ''}|${r.product_id}|${r.location_id}`;
                              const isSelected = woPeggingRowKey === k;
                              return (
                                <button
                                  type="button"
                                  className="secondary"
                                  style={isSelected ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : undefined}
                                  onClick={() => {
                                    if (isSelected) { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); }
                                    else { setPlanPeggingContext({ type: 'demand', row: r }); setPlanPeggingOpen(true); setWoPeggingRowKey(k); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); }
                                  }}
                                >{tc('show')}</button>
                              );
                            } },
                          ]}
                        />
                      </>
                    );
                  })()}
                </div>
              )}
              {planResultTab === 'work_orders' && (
                <div style={{ padding: '0.75rem 1rem' }}>
                  <h4 style={{ marginTop: 0, marginBottom: '0.5rem' }}>{tP('workOrders.heading')}</h4>
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.75rem', marginBottom: '0.25rem' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandRealMakeOnly}
                        onChange={(e) => setPlanDemandRealMakeOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterRealMake')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandBuyOnly}
                        onChange={(e) => setPlanDemandBuyOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterBuy')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandRealMoveOnly}
                        onChange={(e) => setPlanDemandRealMoveOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterRealMove')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planWoDemandedByMultiple}
                        onChange={(e) => setPlanWoDemandedByMultiple(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterMultipleDemands')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planWoMultiSupply}
                        onChange={(e) => setPlanWoMultiSupply(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterMultiSupply')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planWoPurchaseOnly}
                        onChange={(e) => setPlanWoPurchaseOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterPurchaseOnly')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planWoMoveOnly}
                        onChange={(e) => setPlanWoMoveOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterMoveOnly')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandShortOnly}
                        onChange={(e) => setPlanDemandShortOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterShortOnly')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planWoHasOverride}
                        onChange={(e) => setPlanWoHasOverride(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterHasOverride')}</span>
                    </label>
                  </div>
                  <div style={{ marginTop: '0.2rem', marginBottom: '0.4rem' }}>
                    <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem', fontSize: '0.72rem', color: '#71717a', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={planWorkOrderHideDummyProdArea}
                        onChange={(e) => setPlanWorkOrderHideDummyProdArea(e.target.checked)}
                        style={{ accentColor: '#71717a' }}
                      />
                      <span>{tP('workOrders.hideDummy')}</span>
                    </label>
                  </div>
                  {/* ── Pivot selector ── */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
                    <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>Pivot:</span>
                    {(['none', 'prod_area', 'location', 'nested'] as const).map((mode) => (
                      <button
                        key={mode}
                        type="button"
                        className={planWoPivot === mode ? '' : 'secondary'}
                        style={{ fontSize: '0.75rem', padding: '2px 10px' }}
                        onClick={() => { setPlanWoPivot(mode); setPlanWoPivotExpanded(new Set()); setPlanWoPivotSubExpanded(new Set()); }}
                      >
                        {mode === 'none' ? 'None' : mode === 'prod_area' ? 'PROD_AREA' : mode === 'location' ? 'Location' : 'PROD_AREA › Location'}
                      </button>
                    ))}
                    <button
                      type="button"
                      className={planWoPivot === 'demand' ? '' : 'secondary'}
                      style={{ fontSize: '0.75rem', padding: '2px 10px' }}
                      onClick={() => { setPlanWoPivot('demand'); setPlanWoPivotExpanded(new Set()); setPlanWoPivotSubExpanded(new Set()); }}
                    >
                      Demand
                    </button>
                  </div>
                  {/* ── Layout selector (Data / Split / Timeline) ── */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>{tP('workOrders.layout.label')}</span>
                    {(['data', 'split', 'timeline'] as const).map((mode) => {
                      const label = mode === 'data' ? tP('workOrders.layout.data')
                        : mode === 'split' ? tP('workOrders.layout.split')
                          : tP('workOrders.layout.timeline');
                      const tooltip = mode === 'data' ? tP('workOrders.layout.dataTooltip')
                        : mode === 'split' ? tP('workOrders.layout.splitTooltip')
                          : tP('workOrders.layout.timelineTooltip');
                      return (
                        <button
                          key={mode}
                          type="button"
                          className={planWoLayoutMode === mode ? '' : 'secondary'}
                          style={{ fontSize: '0.75rem', padding: '2px 10px' }}
                          onClick={() => setPlanWoLayoutMode(mode)}
                          title={tooltip}
                        >
                          {label}
                        </button>
                      );
                    })}
                    {woPegHighlightRow && (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.75rem', color: '#a1a1aa', marginLeft: '0.5rem' }}>
                        <span>
                          <span style={{ color: '#e4e4e7' }}>{woPegHighlightRow.product_id}</span>
                          {' @ '}
                          <span style={{ color: '#e4e4e7' }}>{woPegHighlightRow.location_id}</span>
                          {' · '}
                          <span style={{ color: '#ec4899' }}>↓ {woPegHighlightSets.ancestors.size}</span>
                          {' · '}
                          <span style={{ color: '#6366f1' }}>↑ {woPegHighlightSets.descendants.size}</span>
                        </span>
                        <button
                          type="button"
                          className={woPegFilterPeggedOnly ? '' : 'secondary'}
                          style={{ fontSize: '0.7rem', padding: '1px 8px' }}
                          onClick={() => setWoPegFilterPeggedOnly((v) => !v)}
                          title={tP('workOrders.filterPeggedOnlyTooltip')}
                        >{tP('workOrders.filterPeggedOnly')}</button>
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.7rem', padding: '1px 8px' }}
                          onClick={() => { setWoPegHighlightRow(null); setWoPegFilterPeggedOnly(false); }}
                        >{tc('clear')}</button>
                      </span>
                    )}
                  </div>
                  {planResult.work_orders.length > 0 && (() => {
                    const workOrdersFiltered = planWorkOrderHideDummyProdArea
                      ? planResult.work_orders.filter((r) => (r.prod_area ?? '').trim().toLowerCase() !== 'dummy')
                      : planResult.work_orders;
                    const byProdArea = workOrdersFiltered.reduce<Record<string, number>>((acc, r) => {
                      const pa = (r.prod_area ?? '–') as string;
                      acc[pa] = (acc[pa] ?? 0) + (Number(r.quantity) || 0);
                      return acc;
                    }, {});
                    const hasMultipleAreas = Object.keys(byProdArea).length > 1;
                    return hasMultipleAreas ? (
                      <details style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                        <summary style={{ cursor: 'pointer' }}>Rollup by PROD_AREA</summary>
                        <ul style={{ marginTop: '0.25rem', paddingLeft: '1.25rem' }}>
                          {Object.entries(byProdArea).map(([area, qty]) => (
                            <li key={area}><strong>{area}</strong>: {qtyFmt(Number(qty))} quantity</li>
                          ))}
                        </ul>
                      </details>
                    ) : null;
                  })()}
                  {(() => {
                    let workOrderRows = planWorkOrderHideDummyProdArea
                      ? planResult.work_orders.filter((r) => (r.prod_area ?? '').trim().toLowerCase() !== 'dummy')
                      : planResult.work_orders;
                    // Compute the supply-backing map early so it can drive the phantom filter below
                    // and also be used by the WO expand panel later in this block.
                    const { suppliesMap: woSuppliesMap, crossEntrySupplyMap: woCrossEntrySupplyMap, peggedQtyMap: woPeggedQtyMap } = buildWoMaps(planResult.planning_pegging ?? []);
                    // Short-supply filter is WO-level and applied after enrichment (see below),
                    // since WO-level shortage is computed from the enriched Requested/Committed.
                    // Filters refer to work-order pegging (each WO's supplies subtree), not demand pegging.
                    const anyPeggingFilter = planDemandRealMakeOnly || planDemandBuyOnly || planDemandRealMoveOnly || planWoDemandedByMultiple || planWoMultiSupply || planWoPurchaseOnly || planWoMoveOnly || planWoHasOverride;
                    if (anyPeggingFilter) {
                      workOrderRows = workOrderRows.filter((r) => {
                        if (planDemandRealMakeOnly && !(r.pegging_includes_real_make === true)) return false;
                        if (planDemandBuyOnly && !(r.pegging_includes_buy === true)) return false;
                        if (planDemandRealMoveOnly && !(r.pegging_includes_real_move === true)) return false;
                        if (planWoDemandedByMultiple && !(r.demanded_by_multiple === true)) return false;
                        if (planWoMultiSupply && !(r.multi_supply_available === true)) return false;
                        if (planWoPurchaseOnly) { const m = (r.method ?? '').toLowerCase(); if (m !== 'buy' && m !== 'purchase') return false; }
                        if (planWoMoveOnly && (r.method ?? '').toLowerCase() !== 'move') return false;
                        if (planWoHasOverride && !woHasSavedOverride(r)) return false;
                        return true;
                      });
                    }
                    // Build the set of "backed" WO signatures: product|location|method triples for
                    // which at least one WO node in the pegging tree has supply leaves.
                    // We intentionally ignore demand_id here because individual WO rows often carry
                    // a specific demand_id while the corresponding pegging WO node lives inside a
                    // consolidated entry (demand_id null) — the keys never reliably match.
                    // Make WOs are excluded from phantom filtering because their supply leaves are
                    // components with different product_ids and are not in woSuppliesMap.
                    const backedWoSigs = new Set<string>();
                    const collectBackedWos = (node: PlanningPeggingNode): void => {
                      if (node.type === 'work_order') {
                        if (collectAllSupplyLeaves(node).length > 0) {
                          backedWoSigs.add(`${node.product_id ?? ''}|${node.location_id ?? ''}|${(node.method ?? '').toLowerCase()}`);
                        }
                        (node.children ?? []).forEach(collectBackedWos);
                      } else {
                        (node.children ?? []).forEach(collectBackedWos);
                      }
                    };
                    for (const entry of planResult.planning_pegging ?? []) {
                      collectBackedWos(entry.tree);
                    }
                    workOrderRows = workOrderRows.filter((r) => {
                      const method = (r.method ?? '').toLowerCase();
                      if (method === 'make') return true;
                      return backedWoSigs.has(`${r.product_id ?? ''}|${r.location_id ?? ''}|${(r.method ?? '').toLowerCase()}`);
                    });
                    // Aggregate lots with the same logical WO key so the table shows total quantity per work order
                    const grouped = new Map<string, WorkOrder>();
                    for (const r of workOrderRows) {
                      const key = [
                        String(r.demand_id ?? ''),
                        String(r.product_id ?? ''),
                        String(r.location_id ?? ''),
                        String(r.method ?? ''),
                        String(r.location_source ?? ''),
                        String(r.prod_area ?? ''),
                      ].join('|');
                      const existing = grouped.get(key);
                      const rowQty = Number(r.quantity ?? 0) || 0;
                      if (!existing) {
                        grouped.set(key, { ...r, quantity: rowQty });
                      } else {
                        existing.quantity = (Number(existing.quantity ?? 0) || 0) + rowQty;
                        // For time range, keep earliest start and latest end across lots
                        if (r.start_time && (!existing.start_time || r.start_time < existing.start_time)) {
                          existing.start_time = r.start_time;
                        }
                        if (r.end_time && (!existing.end_time || r.end_time > existing.end_time)) {
                          existing.end_time = r.end_time;
                        }
                      }
                    }
                    const groupedRows = Array.from(grouped.values());
                    // Build demand_id → requested_qty / shortage / inventory-fulfilled / product label lookups
                    const demandRequestedMap = new Map<string, number>();
                    const demandShortageMap = new Map<string, number>();
                    const demandInventoryMap = new Map<string, number>();
                    const demandLabelMap = new Map<string, string>(); // demand_id → product_id
                    for (const d of planResult.committed_demands) {
                      const id = d.demand_id ?? '';
                      if (!id) continue;
                      if (d.requested_qty != null) demandRequestedMap.set(id, d.requested_qty);
                      if ((d.shortage ?? 0) > 0) demandShortageMap.set(id, d.shortage ?? 0);
                      if (d.commit_reason === 'inventory') {
                        demandInventoryMap.set(id, (demandInventoryMap.get(id) ?? 0) + d.quantity);
                      }
                      if (!demandLabelMap.has(id)) demandLabelMap.set(id, d.product_id);
                    }
                    const dummyHiddenCount = planResult.work_orders.filter((r) => (r.prod_area ?? '').trim().toLowerCase() === 'dummy').length;
                    const woRowsAll: WoEnrichedRow[] = groupedRows.map((r, i) => {
                      const splitDemandIds = (r.wo_consolidation_split_details ?? [])
                        .map((d) => d.demand_id)
                        .filter((d): d is string => d != null && d !== '');
                      const demandLabel = r.demand_id
                        ? r.demand_id
                        : splitDemandIds.length > 0
                          ? splitDemandIds.join(', ')
                          : undefined;
                      // Requested = what this WO was planned to produce.
                      //   shared/consolidated (split_details > 1) → equals Committed
                      //     (r.quantity). Per-demand `allocated_qty` in split_details is
                      //     in parent-product units and does not reconcile with the
                      //     component/variant-level r.quantity after BOM-rate scaling and
                      //     lot aggregation — using it produces "Committed > Requested"
                      //     display artifacts. WO-level shortage is always 0 here; any
                      //     demand shortage is visible in the committed_demands table.
                      //   single → the demand node directly above this WO in the pegging tree.
                      //   no peg data → fall back to committed_demand requested_qty.
                      const isSharedConsolidated = (r.wo_consolidation_split_details?.length ?? 0) > 1;
                      const woKeyFull = `${r.demand_id ?? ''}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                      const woKeyConsolidated = `|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                      const peggedQty = woPeggedQtyMap.get(woKeyFull) ?? woPeggedQtyMap.get(woKeyConsolidated) ?? null;
                      const demandRequested = isSharedConsolidated
                        ? (Number(r.quantity) || 0)
                        : peggedQty != null
                          ? peggedQty
                          : r.demand_id
                            ? (demandRequestedMap.get(r.demand_id) ?? undefined)
                            : splitDemandIds.reduce((s, did) => s + (demandRequestedMap.get(did) ?? 0), 0) || undefined;
                      // For shared WOs, allocated_qty already reflects post-inventory
                      // allocation — no further inventory subtraction needed. For
                      // non-shared WOs, subtract the demand's inventory fulfillment so
                      // Requested reflects only what was expected from this WO.
                      const inventoryFulfilled = isSharedConsolidated
                        ? 0
                        : r.demand_id
                          ? (demandInventoryMap.get(r.demand_id) ?? 0)
                          : splitDemandIds.reduce((s, did) => s + (demandInventoryMap.get(did) ?? 0), 0);
                      const requested = demandRequested != null ? Math.max(0, demandRequested - inventoryFulfilled) : undefined;
                      // WO-level shortage = WO's own requested minus its committed output.
                      const committedQty = Number(r.quantity) || 0;
                      const shortage = requested != null ? Math.max(0, requested - committedQty) : 0;
                      return {
                        ...r,
                        _key: `wo-${i}-${r.product_id}-${r.location_id}`,
                        _prod_area: String(r.prod_area ?? ''),
                        _peg_order: pegOrderMap.get(`${r.demand_id ?? ''}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`),
                        _demand_label: demandLabel,
                        _demand_ids: r.demand_id ? [r.demand_id] : splitDemandIds,
                        _requested_qty: requested,
                        _shortage: shortage > 0 ? shortage : undefined,
                      };
                    });
                    let woRows: WoEnrichedRow[] = planDemandShortOnly
                      ? woRowsAll.filter((r) => (r._shortage ?? 0) > 0.01)
                      : woRowsAll;
                    if (woPegHighlightRow && woPegFilterPeggedOnly) {
                      const allowed = new Set<string>();
                      woRowPegKeys(woPegHighlightRow).forEach((k) => allowed.add(k));
                      woPegHighlightSets.ancestors.forEach((k) => allowed.add(k));
                      woPegHighlightSets.descendants.forEach((k) => allowed.add(k));
                      woRows = woRows.filter((r) => woRowPegKeys(r).some((k) => allowed.has(k)));
                    }
                    const horizon = computeHorizon(woRows);
                    const woColumns: {
                      key: string;
                      label: string;
                      sortable?: boolean;
                      render?: (r: WoEnrichedRow) => React.ReactNode;
                      width?: string;
                      headerRender?: () => React.ReactNode;
                      sortValue?: (r: WoEnrichedRow, dir: 'asc' | 'desc') => unknown;
                    }[] = [
                      { key: 'product_id', label: tP('workOrders.columns.product'), sortable: true },
                      { key: 'location_id', label: tP('workOrders.columns.location'), sortable: true },
                      { key: '_prod_area', label: tP('workOrders.columns.prodArea'), sortable: true, render: (r) => r._prod_area || r.prod_area || '–' },
                      { key: '_requested_qty', label: tP('workOrders.columns.requested'), sortable: true, render: (r) =>
                        r._requested_qty != null ? qtyFmt(Number(r._requested_qty)) : '–'
                      },
                      { key: 'quantity', label: tP('workOrders.columns.committed'), sortable: true, render: (r) => qtyFmt(Number(r.quantity)) },
                      {
                        key: '_schedule',
                        label: tP('workOrders.columns.schedule'),
                        sortable: true,
                        width: '34%',
                        sortValue: (r: WoEnrichedRow, dir) => {
                          // Multi-level lexicographic sort encoded into one string. SortFilterTable
                          // uses asc-then-negate, so character-by-character compare gives the
                          // multi-level effect. PROD_AREA = "dummy" is the dummy classifier.
                          //
                          // UP   (asc): start asc → dummy-first at start tie → end asc → others-first at end tie.
                          // DOWN (desc): end desc → dummy-first at end tie → start desc → others-first at start tie.
                          //
                          // Tie-indicator encoding flips with direction because negate inverts ordering:
                          //   asc dummy-first  → dummy='0', non='1' (smaller = first in asc)
                          //   asc others-first → non='0',   dummy='1'
                          //   desc dummy-first → dummy='1', non='0' (larger = first in desc)
                          //   desc others-first→ non='1',   dummy='0'
                          const isDummy = (r._prod_area || r.prod_area || '').toString().trim().toLowerCase() === 'dummy';
                          const start = r.start_time ?? '';
                          const end = r.end_time ?? '';
                          if (dir === 'asc') {
                            return `${start}|${isDummy ? '0' : '1'}|${end}|${isDummy ? '1' : '0'}`;
                          }
                          return `${end}|${isDummy ? '1' : '0'}|${start}|${isDummy ? '0' : '1'}`;
                        },
                        headerRender: horizon
                          ? () => <ScheduleHorizonRuler horizon={horizon} locale={locale} />
                          : undefined,
                        render: (r) => {
                          if (!horizon) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                          const isSelf = !!woPegHighlightRow
                            && r.product_id === woPegHighlightRow.product_id
                            && r.location_id === woPegHighlightRow.location_id
                            && (r.method ?? '') === (woPegHighlightRow.method ?? '')
                            && (r.demand_id ?? '') === (woPegHighlightRow.demand_id ?? '')
                            && (r.start_time ?? '') === (woPegHighlightRow.start_time ?? '');
                          let colorOverride: string | undefined;
                          if (woPegHighlightRow && !isSelf) {
                            const rKeys = woRowPegKeys(r);
                            if (rKeys.some((rk) => woPegHighlightSets.ancestors.has(rk))) colorOverride = '#ec4899';
                            else if (rKeys.some((rk) => woPegHighlightSets.descendants.has(rk))) colorOverride = '#6366f1';
                          }
                          return (
                            <ScheduleBar
                              start={r.start_time}
                              end={r.end_time}
                              horizon={horizon}
                              method={r.method}
                              locale={locale}
                              selected={isSelf}
                              colorOverride={colorOverride}
                              onClick={() => setWoPegHighlightRow(isSelf ? null : r)}
                            />
                          );
                        },
                      },
                      { key: 'method', label: tP('workOrders.columns.method'), sortable: true, render: (r) => r.method ?? '–' },
                      { key: '_demand_label', label: tP('workOrders.columns.demand'), sortable: true, render: (r) => {
                        const ids = r._demand_ids ?? [];
                        const label = r._demand_label;
                        if (!label) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        if (ids.length <= 1) return <span>{label}</span>;
                        const preview = ids.length <= 3 ? label : `${ids.slice(0, 2).join(', ')} +${ids.length - 2} more`;
                        return (
                          <span title={label} style={{ cursor: 'default' }}>
                            {preview}
                            <span style={{ marginLeft: 5, background: '#0891b2', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.7rem', verticalAlign: 'middle' }}>
                              {tP('workOrders.shared')}
                            </span>
                          </span>
                        );
                      } },
                      { key: '_shortage', label: tP('workOrders.columns.shortage'), sortable: true, render: (r) => {
                        const s = r._shortage;
                        if (!s) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        return <span style={{ color: '#f87171' }}>{qtyFmt(Number(s))}</span>;
                      } },
                      { key: 'location_source', label: tP('workOrders.columns.locationSource'), sortable: true, render: (r) => r.location_source ?? '–' },
                      { key: '_peg_order', label: tP('workOrders.columns.pegging'), sortable: true, render: (r) => {
                        const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                        const isSelected = woPeggingRowKey === k;
                        return (
                          <button
                            type="button"
                            className="secondary"
                            style={isSelected ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : undefined}
                            onClick={(e) => {
                              e.stopPropagation();
                              if (isSelected) { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setWoPegHighlightRow(null); }
                              else { setPlanPeggingContext({ type: 'work_order', row: r }); setPlanPeggingOpen(true); setWoPeggingRowKey(k); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setWoPegHighlightRow(r); }
                            }}
                          >{tc('show')}</button>
                        );
                      } },
                      { key: '_explain', label: tP('workOrders.columns.explain'), sortable: false, render: (r) => {
                        const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                        const isSelected = woExplainKey === k;
                        if (!(r.wo_explanation_method || (r.wo_competing_demands?.length ?? 0) > 0 || (r.wo_consolidation_split_details?.length ?? 0) > 1))
                          return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        return (
                          <button
                            type="button"
                            className="secondary"
                            style={isSelected ? { background: 'rgba(167,139,250,0.25)', borderColor: '#a78bfa' } : undefined}
                            onClick={() => {
                              if (isSelected) { setWoExplainOpen(false); setWoExplainKey(null); setWoExplainRow(null); }
                              else { setWoExplainRow(r); setWoExplainKey(k); setWoExplainOpen(true); }
                            }}
                          >{tc('show')}</button>
                        );
                      }},
                      { key: '_override', label: tP('workOrders.columns.override'), sortable: false, render: (r) => {
                        const methodOptions = r.wo_explanation_method
                          ? new Set(Array.from(r.wo_explanation_method.matchAll(/\b(make|move|buy)\b/gi), (m) => m[1].toLowerCase()))
                          : new Set<string>();
                        const hasMethod = r.multi_supply_available === true && methodOptions.size > 1;
                        const hasSplit = (r.wo_consolidation_split_details?.length ?? 0) > 1;
                        if (!hasMethod && !hasSplit) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        return (
                          <div style={{ display: 'flex', gap: 4 }}>
                            {hasMethod && <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 6px' }} onClick={() => openOverrideDialog('method_selection', r)}>{tP('workOrders.overrideMethod')}</button>}
                            {hasSplit && <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 6px' }} onClick={() => openOverrideDialog('component_split', r)}>{tP('workOrders.overrideSplit')}</button>}
                          </div>
                        );
                      }},
                    ];
                    // ── Apply layout mode (data / split / timeline) ──
                    // 'data':     replace Schedule with start_time + end_time text columns; data spans full width.
                    // 'split':    keep all columns (default).
                    // 'timeline': keep only identity (product/location), Schedule, and Actions; Schedule fills the row.
                    if (planWoLayoutMode === 'data') {
                      const idx = woColumns.findIndex((c) => c.key === '_schedule');
                      if (idx >= 0) {
                        woColumns.splice(idx, 1,
                          { key: 'start_time', label: tP('workOrders.columns.startTime'), sortable: true, render: (r) => r.start_time ?? '–' },
                          { key: 'end_time', label: tP('workOrders.columns.endTime'), sortable: true, render: (r) => r.end_time ?? '–' },
                        );
                      }
                    } else if (planWoLayoutMode === 'timeline') {
                      const keep = new Set(['product_id', 'location_id', '_schedule', '_peg_order', '_explain', '_override']);
                      for (let i = woColumns.length - 1; i >= 0; i--) {
                        if (!keep.has(woColumns[i].key)) woColumns.splice(i, 1);
                      }
                      const sched = woColumns.find((c) => c.key === '_schedule');
                      if (sched) sched.width = '100%';
                    }
                    // ── Demand-pivot column set ──
                    // • Drop _requested_qty, _shortage, _demand_label: the group header already
                    //   shows demand-level totals, and per-WO Requested/Shortage (WO-scoped) would
                    //   duplicate or compete with the header instead of adding signal.
                    // • Override product_id / quantity / method to handle synthetic inventory rows.
                    const woDemandColumns = woColumns
                      .filter((col) => !['_requested_qty', '_shortage', '_demand_label'].includes(col.key as string))
                      .map((col) => {
                        if (col.key === 'product_id') return {
                          ...col,
                          render: (r: WoEnrichedRow) => {
                            if (r._is_inventory) return <span style={{ color: '#16a34a', fontStyle: 'italic' }}>Inventory</span>;
                            return <span>{r.product_id}</span>;
                          },
                        };
                        if (col.key === 'quantity') return {
                          ...col,
                          label: 'Alloc Qty',
                          render: (r: WoEnrichedRow) => {
                            if (r._is_inventory) return <span style={{ color: '#16a34a' }}>{qtyFmt(Number(r._split_qty ?? r.quantity))}</span>;
                            return (
                              <span>
                                {qtyFmt(Number(r._split_qty ?? r.quantity))}
                                {r._is_shared && <span style={{ marginLeft: 5, background: '#0891b2', color: '#fff', borderRadius: 8, padding: '1px 5px', fontSize: '0.7rem', verticalAlign: 'middle' }}>split</span>}
                              </span>
                            );
                          },
                        };
                        if (col.key === 'method') return {
                          ...col,
                          render: (r: WoEnrichedRow) => {
                            if (r._is_inventory) return <span style={{ color: '#16a34a', fontStyle: 'italic' }}>inventory</span>;
                            return col.render ? col.render(r) : (r.method ?? '–');
                          },
                        };
                        return col;
                      });
                    const pivotGroups = (planWoPivot === 'prod_area' || planWoPivot === 'location') ? buildWoPivotGroups(woRows, planWoPivot) : [];
                    const nestedGroups = planWoPivot === 'nested' ? buildWoNestedPivotGroups(woRows) : [];
                    const demandGroups = planWoPivot === 'demand'
                      ? buildWoDemandGroups(planResult.committed_demands, woRows, demandInventoryMap, demandShortageMap, demandRequestedMap, demandLabelMap)
                      : [];
                    const woRowStyle = (r: WoEnrichedRow) => {
                      if (r._is_inventory) return { background: 'rgba(34,197,94,0.08)', color: '#16a34a', fontStyle: 'italic' as const };
                      const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                      if (woExplainKey === k) return { background: 'rgba(167,139,250,0.15)', outline: '1px solid rgba(167,139,250,0.4)' };
                      if (woPeggingRowKey === k) return { background: 'rgba(56,189,248,0.12)', outline: '1px solid rgba(56,189,248,0.35)' };
                      // Pegging-graph highlight (driven by woPegHighlightRow / bar click or Show button).
                      if (woPegHighlightRow) {
                        const isSelf = r === woPegHighlightRow
                          || (r.product_id === woPegHighlightRow.product_id
                            && r.location_id === woPegHighlightRow.location_id
                            && (r.method ?? '') === (woPegHighlightRow.method ?? '')
                            && (r.demand_id ?? '') === (woPegHighlightRow.demand_id ?? '')
                            && (r.start_time ?? '') === (woPegHighlightRow.start_time ?? ''));
                        if (isSelf) return { background: 'rgba(56,189,248,0.18)', outline: '1px solid rgba(56,189,248,0.5)' };
                        const rKeys = woRowPegKeys(r);
                        const isAncestor = rKeys.some((rk) => woPegHighlightSets.ancestors.has(rk));
                        const isDescendant = rKeys.some((rk) => woPegHighlightSets.descendants.has(rk));
                        if (isAncestor) return { background: 'rgba(236,72,153,0.10)', borderLeft: '3px solid #ec4899' };
                        if (isDescendant) return { background: 'rgba(99,102,241,0.10)', borderLeft: '3px solid #6366f1' };
                      }
                      if (!r.override_active && !r.consolidation_override_active && woHasSavedOverride(r)) return { borderLeft: '3px solid #b45309' };
                      return undefined;
                    };
                    const pivotHeaderCols = (label: string) => (
                      <tr>
                        <th style={{ width: '1.5rem' }} />
                        <th>{label}</th>
                        <th style={{ textAlign: 'right' }}>Qty</th>
                        <th style={{ textAlign: 'right' }}>WOs</th>
                        <th style={{ textAlign: 'right' }}>Products</th>
                        <th style={{ textAlign: 'right' }}>Demands</th>
                        <th>Methods</th>
                        <th>Start</th>
                        <th>End</th>
                      </tr>
                    );
                    const pivotGroupRow = (group: WoPivotGroup, expanded: boolean, onToggle: () => void, indent = 0) => (
                      <tr
                        style={{ cursor: 'pointer', background: expanded ? 'rgba(59,130,246,0.08)' : undefined }}
                        onClick={onToggle}
                      >
                        <td style={{ color: '#3b82f6', fontSize: '0.85rem', userSelect: 'none', paddingLeft: indent > 0 ? `${indent * 1.5 + 0.5}rem` : undefined }}>{expanded ? '▼' : '▶'}</td>
                        <td style={{ fontWeight: indent === 0 ? 600 : 400, paddingLeft: indent > 0 ? `${indent * 0.5}rem` : undefined }}>{group._pivot_key}</td>
                        <td style={{ textAlign: 'right' }}>{qtyFmt(group.qty_total)}</td>
                        <td style={{ textAlign: 'right', color: '#a1a1aa' }}>{group.wo_count}</td>
                        <td style={{ textAlign: 'right', color: '#a1a1aa' }}>{group.product_count}</td>
                        <td style={{ textAlign: 'right', color: '#a1a1aa' }}>{group.demand_count}</td>
                        <td style={{ color: '#a1a1aa', fontSize: '0.8rem' }}>{group.methods || '–'}</td>
                        <td style={{ color: '#a1a1aa', fontSize: '0.8rem' }}>{group.start_time_min?.slice(0, 10) ?? '–'}</td>
                        <td style={{ color: '#a1a1aa', fontSize: '0.8rem' }}>{group.end_time_max?.slice(0, 10) ?? '–'}</td>
                      </tr>
                    );
                    return (
                      <>
                        <p style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                          Showing {qtyFmt(groupedRows.length)} work order{groupedRows.length !== 1 ? 's' : ''}
                          {planWorkOrderHideDummyProdArea && dummyHiddenCount > 0
                            ? ` (${qtyFmt(dummyHiddenCount)} with PROD_AREA = dummy hidden)`
                            : ''}
                          {anyPeggingFilter ? ' (filtered by work-order pegging: real make / buy / real move).' : ''}
                        </p>
                        {planWoPivot === 'prod_area' || planWoPivot === 'location' ? (
                          /* ── Flat pivot (PROD_AREA or Location) ── */
                          <table>
                            <thead>{pivotHeaderCols(planWoPivot === 'prod_area' ? 'PROD_AREA' : 'Location')}</thead>
                            <tbody>
                              {pivotGroups.map((group) => {
                                const expanded = planWoPivotExpanded.has(group._pivot_key);
                                return (
                                  <React.Fragment key={group._pivot_key}>
                                    {pivotGroupRow(group, expanded, () => setPlanWoPivotExpanded((prev) => {
                                      const next = new Set(prev);
                                      expanded ? next.delete(group._pivot_key) : next.add(group._pivot_key);
                                      return next;
                                    }))}
                                    {expanded && (
                                      <tr>
                                        <td colSpan={9} style={{ padding: 0 }}>
                                          <div style={{ paddingLeft: '1.5rem', borderLeft: '3px solid #3b82f6', margin: '0.25rem 0 0.5rem' }}>
                                            <SortFilterTable<WoEnrichedRow> columns={woColumns} rows={group.rows} idKey="_key" rowStyle={woRowStyle} />
                                          </div>
                                        </td>
                                      </tr>
                                    )}
                                  </React.Fragment>
                                );
                              })}
                            </tbody>
                          </table>
                        ) : planWoPivot === 'nested' ? (
                          /* ── Nested pivot (PROD_AREA → Location) ── */
                          <table>
                            <thead>{pivotHeaderCols('PROD_AREA / Location')}</thead>
                            <tbody>
                              {nestedGroups.map((outer) => {
                                const outerExpanded = planWoPivotExpanded.has(outer._pivot_key);
                                return (
                                  <React.Fragment key={outer._pivot_key}>
                                    {pivotGroupRow(outer, outerExpanded, () => setPlanWoPivotExpanded((prev) => {
                                      const next = new Set(prev);
                                      outerExpanded ? next.delete(outer._pivot_key) : next.add(outer._pivot_key);
                                      return next;
                                    }))}
                                    {outerExpanded && outer.subGroups.map((sub) => {
                                      const subKey = `${outer._pivot_key}|${sub._pivot_key}`;
                                      const subExpanded = planWoPivotSubExpanded.has(subKey);
                                      return (
                                        <React.Fragment key={subKey}>
                                          {pivotGroupRow(sub, subExpanded, () => setPlanWoPivotSubExpanded((prev) => {
                                            const next = new Set(prev);
                                            subExpanded ? next.delete(subKey) : next.add(subKey);
                                            return next;
                                          }), 1)}
                                          {subExpanded && (
                                            <tr>
                                              <td colSpan={9} style={{ padding: 0 }}>
                                                <div style={{ paddingLeft: '3rem', borderLeft: '3px solid #a78bfa', margin: '0.25rem 0 0.5rem' }}>
                                                  <SortFilterTable<WoEnrichedRow> columns={woColumns} rows={sub.rows} idKey="_key" rowStyle={woRowStyle} />
                                                </div>
                                              </td>
                                            </tr>
                                          )}
                                        </React.Fragment>
                                      );
                                    })}
                                  </React.Fragment>
                                );
                              })}
                            </tbody>
                          </table>
                        ) : planWoPivot === 'demand' ? (
                          /* ── Demand pivot: one collapsible group per demand ── */
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                            {demandGroups.length === 0 && (
                              <p style={{ color: '#a1a1aa', fontSize: '0.85rem' }}>No demand groups found.</p>
                            )}
                            {demandGroups.map((group) => {
                              const expanded = planWoPivotExpanded.has(group.demand_id);
                              return (
                                <div key={group.demand_id} style={{ border: '1px solid #3f3f46', borderRadius: 6, overflow: 'hidden' }}>
                                  <div
                                    style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', padding: '0.4rem 0.75rem', background: expanded ? 'rgba(59,130,246,0.08)' : '#27272a', cursor: 'pointer', userSelect: 'none' }}
                                    onClick={() => setPlanWoPivotExpanded((prev) => {
                                      const next = new Set(prev);
                                      expanded ? next.delete(group.demand_id) : next.add(group.demand_id);
                                      return next;
                                    })}
                                  >
                                    <span style={{ color: '#3b82f6', fontSize: '0.85rem' }}>{expanded ? '▼' : '▶'}</span>
                                    <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>{group.label}</span>
                                    <span style={{ color: '#a1a1aa', fontSize: '0.8rem' }}>
                                      Demand: {qtyFmt(Number(group.demand_qty))}
                                      {' · '}Requested: {qtyFmt(Number(group.requested_qty))}
                                      {group.shortage > 0 && (
                                        <span style={{ marginLeft: 6, color: '#f87171' }}>
                                          Shortage: {qtyFmt(Number(group.shortage))}
                                        </span>
                                      )}
                                    </span>
                                    <span style={{ marginLeft: 'auto', color: '#71717a', fontSize: '0.75rem' }}>
                                      {group.rows.filter((r) => !r._is_inventory).length} WO{group.rows.filter((r) => !r._is_inventory).length !== 1 ? 's' : ''}
                                    </span>
                                  </div>
                                  {expanded && (() => {
                                    // Sub-group rows by product_id to expose OR vs AND BOM structure.
                                    // Same product → OR alternatives (multiple supply paths for one product slot).
                                    // Different products → AND (distinct BOM components, all required).
                                    const invRows = group.rows.filter((r) => r._is_inventory);
                                    const byProduct = new Map<string, WoEnrichedRow[]>();
                                    for (const r of group.rows) {
                                      if (r._is_inventory) continue;
                                      const pid = r.product_id;
                                      if (!byProduct.has(pid)) byProduct.set(pid, []);
                                      byProduct.get(pid)!.push(r);
                                    }
                                    const productGroups = Array.from(byProduct.entries());
                                    const multiProduct = productGroups.length > 1;
                                    return (
                                      <div style={{ borderTop: '1px solid #3f3f46' }}>
                                        {/* Inventory fulfillment summary row */}
                                        {invRows.length > 0 && (
                                          <div style={{ padding: '0.3rem 0.75rem', background: 'rgba(34,197,94,0.06)', borderBottom: '1px solid #3f3f46', fontSize: '0.78rem', color: '#16a34a', fontStyle: 'italic' }}>
                                            Inventory: {qtyFmt(invRows.reduce((s, r) => s + (r._split_qty ?? r.quantity), 0))} units pre-fulfilled from stock
                                          </div>
                                        )}
                                        {productGroups.map(([pid, pidRows], gi) => {
                                          // OR: multiple WOs for same product where none feeds another
                                          //     (they're independent parallel sources for the same slot).
                                          // AND/sequential: WO_B.location_source === WO_A.location_id
                                          //     (WO_B consumes WO_A's output — a move chain).
                                          const isSequential = pidRows.some((r) =>
                                            pidRows.some((other) => other !== r && other.location_id === r.location_source)
                                          );
                                          const isOr = !isSequential && pidRows.length > 1;
                                          return (
                                            <div key={pid}>
                                              {/* Product sub-header — only shown when multiple products (AND) or multiple WOs for same product */}
                                              {(multiProduct || pidRows.length > 1) && (
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', padding: '0.2rem 0.75rem', background: '#1e1e20', borderTop: gi > 0 ? '1px solid #3f3f46' : undefined, borderBottom: '1px solid #3f3f46' }}>
                                                  {multiProduct && gi > 0 && (
                                                    <span style={{ fontSize: '0.65rem', color: '#3b82f6', fontWeight: 700, marginRight: 2 }}>AND</span>
                                                  )}
                                                  <span style={{ fontSize: '0.75rem', color: '#a1a1aa' }}>{pid}</span>
                                                  {isOr && (
                                                    <span style={{ fontSize: '0.65rem', padding: '1px 5px', borderRadius: 8, background: 'rgba(245,158,11,0.15)', color: '#f59e0b', border: '1px solid rgba(245,158,11,0.3)' }}>
                                                      OR · {pidRows.length} paths · {qtyFmt(pidRows.reduce((s, r) => s + (r._split_qty ?? r.quantity), 0))} total
                                                    </span>
                                                  )}
                                                  {isSequential && (
                                                    <span style={{ fontSize: '0.65rem', padding: '1px 5px', borderRadius: 8, background: 'rgba(99,102,241,0.15)', color: '#818cf8', border: '1px solid rgba(99,102,241,0.3)' }}>
                                                      chain · {pidRows.length} steps
                                                    </span>
                                                  )}
                                                </div>
                                              )}
                                              <SortFilterTable<WoEnrichedRow>
                                                columns={woDemandColumns}
                                                rows={pidRows}
                                                idKey="_key"
                                                rowStyle={woRowStyle}
                                              />
                                            </div>
                                          );
                                        })}
                                      </div>
                                    );
                                  })()}
                                </div>
                              );
                            })}
                          </div>
                        ) : (
                          /* ── Flat view with inline supply expansion ── */
                          <SortFilterTable<WoEnrichedRow>
                            columns={woColumns}
                            rows={woRows}
                            idKey="_key"
                            filterKeys={['product_id', 'location_id', '_prod_area', 'method', 'start_time', 'end_time', '_demand_label']}
                            filterPlaceholder="Filter by product, location, PROD_AREA, method…"
                            defaultSortKey="start_time"
                            stickyHeader
                            rowStyle={(r) => {
                              const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                              if (woExplainKey === k) return { background: 'rgba(167,139,250,0.15)', outline: '1px solid rgba(167,139,250,0.4)' };
                              if (woPeggingRowKey === k) return { background: 'rgba(56,189,248,0.12)', outline: '1px solid rgba(56,189,248,0.35)' };
                              if (!r.override_active && !r.consolidation_override_active && woHasSavedOverride(r)) return { borderLeft: '3px solid #b45309' };
                              return undefined;
                            }}
                            expandedKeys={woExpandedKeys}
                            onToggleExpand={(key) => setWoExpandedKeys((prev) => {
                              const next = new Set(prev);
                              prev.has(key) ? next.delete(key) : next.add(key);
                              return next;
                            })}
                            expandedRowContent={(r) => {
                              const woKey = `${r.demand_id ?? ''}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                              const consolidatedWoKey = `|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                              const woSupplies = woSuppliesMap.get(woKey) ?? woSuppliesMap.get(consolidatedWoKey) ?? [];
                              const isMakeExpand = woSupplies.length > 0 && woSupplies[0].type === 'demand';
                              // For make WOs: find supply/purchase nodes for the same product in any
                              // pegging entry with the same top-level demand_id. These represent
                              // inventory drawn from stock to fill the demand independently of this WO.
                              const directSupplies = isMakeExpand
                                ? (woCrossEntrySupplyMap.get(`${r.demand_id ?? ''}|${r.product_id ?? ''}`) ?? [])
                                : [];
                              if (woSupplies.length === 0 && directSupplies.length === 0) return null;
                              const supplyRowStyle: React.CSSProperties = { borderTop: '1px solid #3f3f46' };
                              const cellP: React.CSSProperties = { paddingRight: '1.25rem', paddingTop: '0.15rem', paddingBottom: '0.15rem' };
                              return (
                                <div style={{ padding: '0.3rem 1.75rem 0.5rem', background: 'rgba(59,130,246,0.04)', borderTop: '1px dashed #3f3f46' }}>
                                  {/* Direct supply section (make WOs only): inventory pre-fulfillment */}
                                  {directSupplies.length > 0 && (
                                    <div style={{ marginBottom: woSupplies.length > 0 ? '0.5rem' : 0 }}>
                                      <div style={{ fontSize: '0.7rem', color: '#16a34a', marginBottom: '0.2rem', fontWeight: 500 }}>
                                        From supply ({qtyFmt(Number(directSupplies.reduce((s, n) => s + (n.quantity ?? 0), 0)))} units pre-filled from inventory)
                                      </div>
                                      <table style={{ fontSize: '0.78rem', borderCollapse: 'collapse', width: '100%' }}>
                                        <thead>
                                          <tr style={{ color: '#71717a' }}>
                                            <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem', paddingBottom: '0.15rem' }}>Type</th>
                                            <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Supply ID</th>
                                            <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Location</th>
                                            <th style={{ textAlign: 'right', fontWeight: 400, paddingRight: '1.25rem' }}>Qty</th>
                                          </tr>
                                        </thead>
                                        <tbody>
                                          {directSupplies.map((s, si) => (
                                            <tr key={si} style={supplyRowStyle}>
                                              <td style={cellP}>
                                                <span style={{ fontSize: '0.65rem', padding: '1px 5px', borderRadius: 6, background: 'rgba(34,197,94,0.15)', color: '#16a34a', border: '1px solid rgba(34,197,94,0.3)' }}>{s.type}</span>
                                              </td>
                                              <td style={{ ...cellP, fontFamily: 'monospace', fontSize: '0.72rem', color: '#71717a' }}>{s.supply_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3' }}>{s.location_id ?? '–'}</td>
                                              <td style={{ textAlign: 'right', paddingRight: '1.25rem' }}>{qtyFmt(Number(s.quantity ?? 0))}</td>
                                            </tr>
                                          ))}
                                        </tbody>
                                      </table>
                                    </div>
                                  )}
                                  {/* Components or supply-leaf section */}
                                  {woSupplies.length > 0 && (
                                    <div>
                                      {isMakeExpand && (
                                        <div style={{ fontSize: '0.7rem', color: '#60a5fa', marginBottom: '0.2rem', fontWeight: 500 }}>
                                          Produced by this work order ({qtyFmt(Number(r.quantity))} units — components consumed)
                                        </div>
                                      )}
                                      <table style={{ fontSize: '0.78rem', borderCollapse: 'collapse', width: '100%' }}>
                                        <thead>
                                          <tr style={{ color: '#71717a' }}>
                                            {isMakeExpand ? (
                                              <>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem', paddingBottom: '0.15rem' }}>Component</th>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Location</th>
                                                <th style={{ textAlign: 'right', fontWeight: 400, paddingRight: '1.25rem' }}>Committed</th>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Reason</th>
                                              </>
                                            ) : (
                                              <>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem', paddingBottom: '0.15rem' }}>Type</th>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Supply ID</th>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Location</th>
                                                <th style={{ textAlign: 'right', fontWeight: 400, paddingRight: '1.25rem' }}>Qty</th>
                                              </>
                                            )}
                                          </tr>
                                        </thead>
                                        <tbody>
                                          {isMakeExpand ? woSupplies.map((comp, si) => (
                                            <tr key={si} style={supplyRowStyle}>
                                              <td style={{ ...cellP, fontFamily: 'monospace', fontSize: '0.72rem' }}>{comp.product_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3' }}>{comp.location_id ?? '–'}</td>
                                              <td style={{ textAlign: 'right', paddingRight: '1.25rem' }}>{qtyFmt(Number(comp.quantity ?? 0))}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3', fontSize: '0.72rem' }}>{comp.commit_reason ?? ''}</td>
                                            </tr>
                                          )) : woSupplies.map((s, si) => (
                                            <tr key={si} style={supplyRowStyle}>
                                              <td style={cellP}>
                                                <span style={{
                                                  fontSize: '0.65rem', padding: '1px 5px', borderRadius: 6,
                                                  background: s.type === 'purchase' ? 'rgba(16,185,129,0.15)' : 'rgba(59,130,246,0.15)',
                                                  color: s.type === 'purchase' ? '#10b981' : '#60a5fa',
                                                  border: `1px solid ${s.type === 'purchase' ? 'rgba(16,185,129,0.3)' : 'rgba(59,130,246,0.3)'}`,
                                                }}>{s.type}</span>
                                              </td>
                                              <td style={{ ...cellP, fontFamily: 'monospace', fontSize: '0.72rem', color: '#71717a' }}>{s.supply_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3' }}>{s.location_id ?? '–'}</td>
                                              <td style={{ textAlign: 'right', paddingRight: '1.25rem' }}>{qtyFmt(Number(s.quantity ?? 0))}</td>
                                            </tr>
                                          ))}
                                        </tbody>
                                      </table>
                                    </div>
                                  )}
                                </div>
                              );
                            }}
                          />
                        )}
                      </>
                    );
                  })()}
                </div>
              )}
              {planResultTab === 'supplies' && (
                <div style={{ padding: '0.75rem 1rem' }}>
                  <h4 style={{ marginTop: 0, marginBottom: '0.5rem' }}>{tP('supplyView.heading')}</h4>
                  {/* Filter controls */}
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
                    <div style={{ position: 'relative' }}>
                      <input
                        type="text"
                        value={planSupplyTextFilter}
                        onChange={(e) => setPlanSupplyTextFilter(e.target.value)}
                        placeholder={tP('supplyView.filterPlaceholder')}
                        style={{ fontSize: '0.8rem', padding: '0.2rem 0.4rem', paddingRight: planSupplyTextFilter ? '1.4rem' : '0.4rem', borderRadius: 4, border: `1px solid ${planSupplyTextFilter ? '#3b82f6' : '#52525b'}`, background: '#27272a', color: '#e4e4e7', width: 280 }}
                      />
                      {planSupplyTextFilter && (
                        <button onClick={() => setPlanSupplyTextFilter('')} style={{ position: 'absolute', right: 4, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.85rem', padding: 0, lineHeight: 1 }}>✕</button>
                      )}
                    </div>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input type="checkbox" checked={planSupplyUnusedOnly} onChange={(e) => { setPlanSupplyUnusedOnly(e.target.checked); if (e.target.checked) setPlanSupplyPartialOnly(false); }} />
                      <span>{tP('supplyView.filterUnusedOnly')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input type="checkbox" checked={planSupplyPartialOnly} onChange={(e) => { setPlanSupplyPartialOnly(e.target.checked); if (e.target.checked) setPlanSupplyUnusedOnly(false); }} />
                      <span>{tP('supplyView.filterPartialOnly')}</span>
                    </label>
                  </div>
                  <div style={{ marginTop: '0.2rem', marginBottom: '0.4rem' }}>
                    <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem', fontSize: '0.72rem', color: '#71717a', cursor: 'pointer' }}>
                      <input type="checkbox" checked={planSupplyHideDummy} onChange={(e) => setPlanSupplyHideDummy(e.target.checked)} style={{ accentColor: '#71717a' }} />
                      <span>{tP('supplyView.filterHideDummy')}</span>
                    </label>
                  </div>
                  {/* Criticality analysis */}
                  <div style={{ marginBottom: '0.75rem', borderTop: '1px solid #3d3d40', paddingTop: '0.5rem', display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
                    <button
                      type="button"
                      onClick={handleAnalyzeCriticality}
                      disabled={criticalityRunning || planSupplyViewRows.length === 0}
                      style={{ fontSize: '0.8rem' }}
                    >
                      {criticalityRunning
                        ? tP('config.analyzingCriticality', { done: criticalityProgress?.done ?? 0, total: criticalityProgress?.total ?? 0 })
                        : tP('config.analyzeCriticality')}
                    </button>
                    {!criticalityRunning && Object.keys(supplyCriticalityMap).length > 0 && (
                      <button
                        type="button"
                        className="secondary"
                        onClick={() => setSupplyCriticalityMap({})}
                        style={{ fontSize: '0.8rem' }}
                      >{tP('config.criticalityClear')}</button>
                    )}
                    {!criticalityRunning && Object.keys(supplyCriticalityMap).length > 0 && (() => {
                      const critical = Object.values(supplyCriticalityMap).filter(s => s === 'critical').length;
                      const safe = Object.values(supplyCriticalityMap).filter(s => s === 'not_critical').length;
                      return <span style={{ fontSize: '0.75rem', color: '#a1a1aa' }}>
                        <span style={{ color: '#f87171', fontWeight: 600 }}>{critical} critical</span>
                        {' · '}
                        <span style={{ color: '#34d399' }}>{safe} safe</span>
                      </span>;
                    })()}
                  </div>
                  {/* Assessment criteria editor */}
                  <div style={{ marginBottom: '0.75rem', borderTop: '1px solid #3d3d40', paddingTop: '0.5rem' }}>
                    <button
                      type="button"
                      onClick={() => setAssessCriteriaOpen((o) => !o)}
                      style={{ background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.75rem', padding: 0 }}
                    >
                      {assessCriteriaOpen ? '▾' : '▸'} {tP('supplyView.assessment.criteria')}
                    </button>
                    {assessCriteriaOpen && (
                      <div style={{ marginTop: '0.5rem' }}>
                        {(['high', 'low', 'medium'] as const).map((tier) => {
                          const tierColor = tier === 'high' ? '#f87171' : tier === 'low' ? '#34d399' : '#fbbf24';
                          const tierBg = tier === 'high' ? 'rgba(248,113,113,0.12)' : tier === 'low' ? 'rgba(52,211,153,0.12)' : 'rgba(251,191,36,0.12)';
                          const tierBorder = tier === 'high' ? 'rgba(248,113,113,0.35)' : tier === 'low' ? 'rgba(52,211,153,0.35)' : 'rgba(251,191,36,0.35)';
                          const tierValue = tier === 'high' ? assessCriteriaHigh : tier === 'low' ? assessCriteriaLow : assessCriteriaMedium;
                          const tierSetter = tier === 'high' ? setAssessCriteriaHigh : tier === 'low' ? setAssessCriteriaLow : setAssessCriteriaMedium;
                          return (
                            <div key={tier} style={{ marginBottom: '0.6rem' }}>
                              <p style={{ fontSize: '0.75rem', margin: '0 0 3px', color: '#a1a1aa' }}>
                                {tP('supplyView.assessment.criteriaHeaderBefore')}
                                <span style={{ padding: '1px 7px', borderRadius: 10, background: tierBg, color: tierColor, border: `1px solid ${tierColor}`, fontWeight: 700, fontSize: '0.72rem' }}>{tier.toUpperCase()}</span>
                                {tP('supplyView.assessment.criteriaHeaderAfter')}
                              </p>
                              <textarea
                                value={tierValue}
                                onChange={(e) => tierSetter(e.target.value)}
                                rows={2}
                                placeholder={tP('supplyView.assessment.criteriaPlaceholderTier', { tier: tier.toUpperCase() })}
                                style={{ width: '100%', fontSize: '0.8rem', background: '#27272a', color: '#e4e4e7', border: `1px solid ${tierBorder}`, borderRadius: 4, padding: '0.4rem', resize: 'vertical', boxSizing: 'border-box' }}
                              />
                            </div>
                          );
                        })}
                        <div style={{ display: 'flex', gap: 8, marginTop: '0.4rem' }}>
                          <button
                            type="button"
                            onClick={handleSaveCriteria}
                            disabled={assessCriteriaSaving || buildCriteriaText(assessCriteriaHigh, assessCriteriaLow, assessCriteriaMedium, criteriaHeaders) === assessCriteria}
                            style={{ fontSize: '0.8rem' }}
                          >
                            {assessCriteriaSaving ? tP('supplyView.assessment.criteriaSaving') : tP('supplyView.assessment.criteriaSave')}
                          </button>
                          <button
                            type="button"
                            className="secondary"
                            onClick={() => {
                              const parts = parseCriteriaParts(assessCriteria);
                              setAssessCriteriaHigh(parts.high);
                              setAssessCriteriaLow(parts.low);
                              setAssessCriteriaMedium(parts.medium);
                              setAssessCriteriaOpen(false);
                            }}
                            style={{ fontSize: '0.8rem' }}
                          >
                            {tP('supplyView.assessment.criteriaCancel')}
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                  {caseSuppliesLoading && <p style={{ color: '#a1a1aa', fontSize: '0.85rem' }}>{tP('supplyView.loading')}</p>}
                  {caseSuppliesError && <p style={{ color: '#f87171', fontSize: '0.85rem' }}>{caseSuppliesError}</p>}
                  {!caseSuppliesLoading && !caseSuppliesError && (() => {
                    const q = planSupplyTextFilter.trim().toLowerCase();
                    let rows = planSupplyViewRows;
                    if (planSupplyHideDummy) rows = rows.filter((r) => !r.supplyId.toLowerCase().endsWith('_dummy'));
                    if (q) rows = rows.filter((r) => r.supplyId.toLowerCase().includes(q) || r.productId.toLowerCase().includes(q) || (r.locationId ?? '').toLowerCase().includes(q));
                    if (planSupplyUnusedOnly) rows = rows.filter((r) => r.peggedDemandCount === 0);
                    if (planSupplyPartialOnly) rows = rows.filter((r) => r.consumedQty > 0);

                    const totalInitial = planSupplyViewRows.reduce((s, r) => s + r.qty, 0);
                    const totalConsumed = planSupplyViewRows.reduce((s, r) => s + r.consumedQty, 0);
                    const overallUtil = totalInitial > 0 ? ((totalConsumed / totalInitial) * 100).toFixed(1) : null;

                    return (
                      <>
                        <p style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.5rem', marginTop: 0 }}>
                          {tP('supplyView.showing', { shown: qtyFmt(rows.length), total: qtyFmt(planSupplyViewRows.length) })}
                          {overallUtil != null && (
                            <span> {tP('supplyView.overallUtilization', { pct: overallUtil, consumed: qtyFmt(Number(totalConsumed)), initial: qtyFmt(Number(totalInitial)) })}</span>
                          )}
                        </p>
                        <SortFilterTable<PlanSupplyViewRow & { _key: string; productTotal: number; criticalityOrder: number; demandTotal: number; scarcityRatio: number; scarcityKind: 'direct' | 'bom' | 'none' }>
                          idKey="_key"
                          rows={rows.map((r, i) => {
                            const cs = supplyCriticalityMap[r.supplyId];
                            const autoSafe = !cs && r.consumedQty === 0;
                            const criticalityOrder = cs === 'critical' ? 0 : (cs === 'not_critical' || autoSafe) ? 1 : cs === 'error' ? 2 : cs === 'running' ? 3 : 4;
                            const productTotal = planSupplyProductTotalMap[r.productId] ?? 0;
                            // Hybrid material-level demand:
                            //   • End products with direct user demand → sum of requested_qty (can
                            //     exceed 100%, captures true requested pressure).
                            //   • Intermediates with no direct user demand → pegging-derived
                            //     consumption (BOM-explosion of upstream user demands, in this
                            //     material's units; capped at supply availability by physics).
                            // Mutually exclusive paths to avoid double-counting on products that are
                            // both directly demanded AND used as a BOM intermediate (rare but real).
                            const directDemand = productDemandTotalMap[r.productId] ?? 0;
                            const bomDerivedDemand = productConsumedTotalMap[r.productId] ?? 0;
                            const demandTotal = directDemand > 0 ? directDemand : bomDerivedDemand;
                            const scarcityKind: 'direct' | 'bom' | 'none' = directDemand > 0
                              ? 'direct'
                              : bomDerivedDemand > 0 ? 'bom' : 'none';
                            // Demand=0 → no demand pressure (rendered as "–"). Supply=0 with
                            // demand>0 → infinite scarcity (rendered as "∞"). Otherwise a ratio
                            // rendered as a percentage.
                            const scarcityRatio = productTotal > 0
                              ? demandTotal / productTotal
                              : (demandTotal > 0 ? Infinity : 0);
                            return { ...r, _key: `psv-${i}-${r.supplyId}`, productTotal, criticalityOrder, demandTotal, scarcityRatio, scarcityKind };
                          })}
                          filterKeys={[]}
                          defaultSortKey="supplyDate"
                          columns={[
                            { key: 'criticalityOrder', label: 'Criticality', sortable: true, render: (r) => {
                              const cs = supplyCriticalityMap[r.supplyId];
                              if (cs === 'running') return <span style={{ color: '#a1a1aa', fontSize: '0.75rem' }}>…</span>;
                              if (cs === 'error') return <span style={{ color: '#f59e0b', fontSize: '0.72rem' }}>err</span>;
                              if (cs === 'critical') return <span style={{ background: 'rgba(248,113,113,0.15)', color: '#f87171', border: '1px solid rgba(248,113,113,0.4)', borderRadius: 8, padding: '1px 8px', fontSize: '0.72rem', fontWeight: 700 }}>Critical</span>;
                              if (cs === 'not_critical') return <span style={{ background: 'rgba(52,211,153,0.12)', color: '#34d399', border: '1px solid rgba(52,211,153,0.35)', borderRadius: 8, padding: '1px 8px', fontSize: '0.72rem', fontWeight: 700 }}>Safe</span>;
                              // Auto-safe: zero consumption cannot impact any demand.
                              if (r.consumedQty === 0) return <span title="Zero consumption — cannot impact demands" style={{ background: 'rgba(52,211,153,0.08)', color: '#34d399', border: '1px solid rgba(52,211,153,0.25)', borderRadius: 8, padding: '1px 8px', fontSize: '0.72rem', fontWeight: 600, opacity: 0.85 }}>Safe</span>;
                              // No status yet AND util > 0 — offer per-row analysis.
                              return (
                                <button type="button" className="secondary"
                                  disabled={criticalityRunning}
                                  onClick={() => handleAnalyzeCriticalityForSupply(r.supplyId)}
                                  style={{ fontSize: '0.7rem', padding: '1px 8px' }}
                                  title="Analyze criticality for this supply">
                                  Analyze
                                </button>
                              );
                            }},
                            { key: 'supplyId', label: tP('supplyView.columns.supplyId'), sortable: true },
                            { key: 'productId', label: tP('supplyView.columns.product'), sortable: true },
                            { key: 'locationId', label: tP('supplyView.columns.location'), sortable: true, render: (r) => r.locationId ?? '–' },
                            { key: 'vendorId', label: tP('supplyView.columns.vendor'), sortable: true, render: (r) => r.vendorId ?? '–' },
                            { key: 'supplyDate', label: tP('supplyView.columns.supplyDate'), sortable: true, render: (r) => r.supplyDate ?? '–' },
                            { key: 'qty', label: tP('supplyView.columns.initialQty'), sortable: true, render: (r) => qtyFmt(Number(r.qty)) },
                            { key: 'productTotal', label: tP('supplyView.columns.productTotal'), sortable: true, render: (r) => r.productTotal > 0 ? qtyFmt(Number(r.productTotal)) : '–' },
                            { key: 'scarcityRatio', label: tP('supplyView.columns.scarcity'), sortable: true, render: (r) => {
                              if (r.demandTotal === 0) return <span style={{ color: '#52525b' }} title={tP('supplyView.scarcityNoneTooltip')}>–</span>;
                              if (!isFinite(r.scarcityRatio)) {
                                return <span style={{ color: '#f87171', fontWeight: 600 }} title={tP('supplyView.scarcityShortageTooltip', { demand: qtyFmt(r.demandTotal) })}>∞</span>;
                              }
                              const pct = r.scarcityRatio * 100;
                              const color = r.scarcityRatio > 1.0 ? '#f87171' : r.scarcityRatio >= 0.9 ? '#fbbf24' : '#34d399';
                              const tooltipKey = r.scarcityKind === 'bom'
                                ? 'supplyView.scarcityBomTooltip'
                                : 'supplyView.scarcityRatioTooltip';
                              // Subtle visual cue: dotted underline for BOM-derived rows so a user
                              // can see at a glance that the number isn't from direct user demand
                              // (and is committed-based, capped at 100%).
                              const decoration = r.scarcityKind === 'bom' ? 'underline dotted' : undefined;
                              return <span style={{ color, fontWeight: 600, textDecoration: decoration, textUnderlineOffset: '3px' }} title={tP(tooltipKey, { demand: qtyFmt(r.demandTotal), supply: qtyFmt(r.productTotal) })}>{pct.toFixed(0)}%</span>;
                            } },
                            { key: 'consumedQty', label: tP('supplyView.columns.consumed'), sortable: true, render: (r) => r.consumedQty > 0 ? <span style={{ color: '#a78bfa' }}>{qtyFmt(Number(r.consumedQty))}</span> : <span style={{ color: '#52525b' }}>0</span> },
                            { key: 'residualQty', label: tP('supplyView.columns.residual'), sortable: true, render: (r) => r.residualQty > 0 ? <span style={{ color: '#34d399' }}>{qtyFmt(Number(r.residualQty))}</span> : <span style={{ color: '#52525b' }}>0</span> },
                            { key: 'utilizationRate', label: tP('supplyView.columns.utilPct'), sortable: true, render: (r) => {
                              if (r.utilizationRate == null) return <span style={{ color: '#52525b' }}>–</span>;
                              const pct = (r.utilizationRate * 100).toFixed(1);
                              const color = r.utilizationRate > 1.0 ? '#f87171' : r.utilizationRate >= 0.9 ? '#34d399' : r.utilizationRate >= 0.5 ? '#f59e0b' : '#f87171';
                              const overAllocated = r.utilizationRate > 1.0;
                              return <span style={{ color }} title={overAllocated ? 'Over-allocated: consumed exceeds initial qty' : undefined}>{pct}%{overAllocated ? ' ⚠' : ''}</span>;
                            }},
                            { key: 'peggedDemandCount', label: tP('supplyView.columns.peggedDemands'), sortable: true, render: (r) => r.peggedDemandCount > 0 ? <span style={{ color: '#60a5fa' }}>{r.peggedDemandCount}</span> : <span style={{ color: '#52525b' }}>0</span> },
                            { key: 'totalPeggedQty', label: tP('supplyView.columns.totalPeggedQty'), sortable: true, render: (r) => r.totalPeggedQty > 0 ? qtyFmt(Number(r.totalPeggedQty)) : <span style={{ color: '#52525b' }}>0</span> },
                            { key: '_sup_override' as keyof (PlanSupplyViewRow & { _key: string }), label: tP('supplyView.columns.override'), sortable: false, render: (r) => {
                              const ov = r.override;
                              const eligible = r.peggedDemandCount >= 2;
                              if (ov) {
                                const n = ov.allocations.length;
                                const warn = !!ov.warning;
                                const overrideRecord = overrides.find((o) => o.entity_type === 'supply_split' && o.entity_key === r.supplyId);
                                return (
                                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: '0.75rem', whiteSpace: 'nowrap' }}>
                                    <span style={{ color: warn ? '#f87171' : '#fbbf24', fontWeight: 600 }}
                                          title={warn ? tP('supplyView.overrideWarn') : undefined}>
                                      {tP('supplyView.overrideActive', { n: String(n) })}{warn ? ' ⚠' : ''}
                                    </span>
                                    <button type="button" className="secondary"
                                      style={{ fontSize: '0.7rem', padding: '1px 6px' }}
                                      onClick={() => openSupplyOverrideDialog(r)}>
                                      {tP('supplyView.edit')}
                                    </button>
                                    {overrideRecord && (
                                      <button type="button" className="secondary"
                                        style={{ fontSize: '0.7rem', padding: '1px 6px', color: '#f87171', borderColor: 'rgba(248,113,113,0.4)' }}
                                        onClick={async () => {
                                          try { await deleteOverride(id, overrideRecord.id); await loadOverrides(); }
                                          catch (e) { setError(e instanceof Error ? e.message : 'Failed to clear override'); }
                                        }}>
                                        {tP('supplyView.clear')}
                                      </button>
                                    )}
                                  </span>
                                );
                              }
                              const infos = r.splitInfos;
                              // Chip's headline number is peggedDemandCount — the true count of
                              // user demands consuming this supply across every path (multi-demand
                              // groups + passthrough singletons + direct main-loop). Using a
                              // narrower per-group candidate count would disagree with the slide-in's
                              // Pegged Demands table and create the kind of "is it 41 or 13?"
                              // confusion that prompted this fix. Policy/colour come from the
                              // primary multi-demand group when one exists.
                              const chip = (r.peggedDemandCount > 0 || infos.length > 0) ? (() => {
                                const totalNeed = infos.reduce((s, i) => s + i.groupTotalNeed, 0);
                                const totalProduced = infos.reduce((s, i) => s + i.groupTotalProduced, 0);
                                const short = infos.length > 0 && totalProduced < totalNeed - 1e-6;
                                const primary = infos[0];
                                const modeColor = !primary
                                  ? '#a1a1aa'
                                  : primary.mode === 'fair' ? '#34d399'
                                  : primary.mode === 'proportional' ? '#60a5fa'
                                  : '#f59e0b';
                                const title = [
                                  `${r.peggedDemandCount} demand(s) consume this supply (total ${qtyFmt(Number(r.totalPeggedQty))})`,
                                  ...(infos.length > 0
                                    ? [
                                        '─── multi-demand consolidation group(s) ───',
                                        ...infos.map((info) => {
                                          const s = info.groupTotalProduced < info.groupTotalNeed - 1e-6;
                                          return [
                                            `Group: ${info.groupProductId} @ ${info.groupLocationId}`,
                                            `  Policy: ${info.mode}`,
                                            `  Candidates: ${info.candidateCount} demand(s)`,
                                            `  Need: ${qtyFmt(info.groupTotalNeed)}`,
                                            `  Produced: ${qtyFmt(info.groupTotalProduced)}`,
                                            s ? `  Shortage: ${qtyFmt(info.groupTotalNeed - info.groupTotalProduced)}` : '  No shortage',
                                          ].join('\n');
                                        }),
                                      ]
                                    : []),
                                ].join('\n');
                                return (
                                  <span title={title} style={{ fontSize: '0.75rem', whiteSpace: 'nowrap' }}>
                                    {primary && (
                                      <>
                                        <span style={{ color: modeColor, fontWeight: 600 }}>{primary.mode}</span>
                                        <span style={{ color: '#71717a' }}> · </span>
                                      </>
                                    )}
                                    <span style={{ color: '#a1a1aa' }}>{r.peggedDemandCount}d shared</span>
                                    {infos.length > 0 && (
                                      <>
                                        <span style={{ color: '#71717a' }}> · </span>
                                        <span style={{ color: short ? '#f87171' : '#a1a1aa' }}>
                                          {qtyFmt(totalProduced)}/{qtyFmt(totalNeed)}
                                        </span>
                                      </>
                                    )}
                                    {infos.length > 1 && (
                                      <>
                                        <span style={{ color: '#71717a' }}> · </span>
                                        <span style={{ color: '#a1a1aa' }}>{infos.length}g</span>
                                      </>
                                    )}
                                  </span>
                                );
                              })() : null;
                              return (
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}>
                                  {chip ?? <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>}
                                  {eligible && (
                                    <button type="button" className="secondary"
                                      style={{ fontSize: '0.7rem', padding: '1px 6px' }}
                                      onClick={() => openSupplyOverrideDialog(r)}>
                                      {tP('supplyView.override')}
                                    </button>
                                  )}
                                </span>
                              );
                            }},
                            { key: '_sup_explain' as keyof (PlanSupplyViewRow & { _key: string }), label: tP('supplyView.columns.breakdown'), sortable: false, render: (r) => {
                              const cs = supplyCriticalityMap[r.supplyId];
                              const hasCriticality = cs === 'critical' || cs === 'not_critical';
                              const eligible = r.peggedDemandCount > 0 || r.splitInfos.length > 0 || !!r.override || hasCriticality;
                              if (!eligible) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                              const k = `supply|${r.supplyId}`;
                              const isSelected = supExplainKey === k;
                              return (
                                <button
                                  type="button"
                                  className="secondary"
                                  style={isSelected ? { background: 'rgba(167,139,250,0.25)', borderColor: '#a78bfa' } : undefined}
                                  onClick={() => {
                                    if (isSelected) { setSupExplainOpen(false); setSupExplainKey(null); setSupExplainRow(null); }
                                    else { setSupExplainRow(r); setSupExplainKey(k); setSupExplainOpen(true); }
                                  }}
                                >{tc('show')}</button>
                              );
                            }},
                          ]}
                          rowStyle={(r) => {
                            // Highlight the row whose Explain or Pegging slide-in is currently open.
                            // Both buttons key on `supply|<supplyId>` so a single comparison covers both.
                            const k = `supply|${r.supplyId}`;
                            if (supExplainKey === k || woPeggingRowKey === k) {
                              return { background: 'rgba(56,189,248,0.12)', outline: '1px solid rgba(56,189,248,0.35)' };
                            }
                            return undefined;
                          }}
                        />
                      </>
                    );
                  })()}
                </div>
              )}
            </div>
          </>
        )}
      </section>
      )}
      {caseSection === 'bom-graph' && (
      <section>
        <h2>BOM Graph</h2>
        <BomGraphTab caseId={id} />
      </section>
      )}
      {caseSection === 'material' && (
      <section>
        <h2>{tSec('material')}</h2>

        {/* ── Assessment criteria editor (hidden in events-only subsection) ── */}
        {subsection !== 'events' && <div style={{ marginBottom: '1rem', borderBottom: '1px solid #3d3d40', paddingBottom: '0.75rem' }}>
          <button
            type="button"
            onClick={() => setAssessCriteriaOpen((o) => !o)}
            style={{ background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.8rem', padding: 0 }}
          >
            {assessCriteriaOpen ? '▾' : '▸'} {tP('supplyView.assessment.criteria')}
          </button>
          {assessCriteriaOpen && (
            <div style={{ marginTop: '0.5rem', maxWidth: 640 }}>
              {(['high', 'low', 'medium'] as const).map((tier) => {
                const tierColor = tier === 'high' ? '#f87171' : tier === 'low' ? '#34d399' : '#fbbf24';
                const tierBg = tier === 'high' ? 'rgba(248,113,113,0.12)' : tier === 'low' ? 'rgba(52,211,153,0.12)' : 'rgba(251,191,36,0.12)';
                const tierBorder = tier === 'high' ? 'rgba(248,113,113,0.35)' : tier === 'low' ? 'rgba(52,211,153,0.35)' : 'rgba(251,191,36,0.35)';
                const tierValue = tier === 'high' ? assessCriteriaHigh : tier === 'low' ? assessCriteriaLow : assessCriteriaMedium;
                const tierSetter = tier === 'high' ? setAssessCriteriaHigh : tier === 'low' ? setAssessCriteriaLow : setAssessCriteriaMedium;
                return (
                  <div key={tier} style={{ marginBottom: '0.6rem' }}>
                    <p style={{ fontSize: '0.75rem', margin: '0 0 3px', color: '#a1a1aa' }}>
                      {tP('supplyView.assessment.criteriaHeaderBefore')}
                      <span style={{ padding: '1px 7px', borderRadius: 10, background: tierBg, color: tierColor, border: `1px solid ${tierColor}`, fontWeight: 700, fontSize: '0.72rem' }}>{tier.toUpperCase()}</span>
                      {tP('supplyView.assessment.criteriaHeaderAfter')}
                    </p>
                    <textarea
                      value={tierValue}
                      onChange={(e) => tierSetter(e.target.value)}
                      rows={2}
                      placeholder={tP('supplyView.assessment.criteriaPlaceholderTier', { tier: tier.toUpperCase() })}
                      style={{ width: '100%', fontSize: '0.8rem', background: '#27272a', color: '#e4e4e7', border: `1px solid ${tierBorder}`, borderRadius: 4, padding: '0.4rem', resize: 'vertical', boxSizing: 'border-box' }}
                    />
                  </div>
                );
              })}
              <div style={{ display: 'flex', gap: 8, marginTop: '0.4rem' }}>
                <button
                  type="button"
                  onClick={handleSaveCriteria}
                  disabled={assessCriteriaSaving || buildCriteriaText(assessCriteriaHigh, assessCriteriaLow, assessCriteriaMedium, criteriaHeaders) === assessCriteria}
                  style={{ fontSize: '0.8rem' }}
                >
                  {assessCriteriaSaving ? tP('supplyView.assessment.criteriaSaving') : tP('supplyView.assessment.criteriaSave')}
                </button>
                <button
                  type="button"
                  className="secondary"
                  onClick={() => {
                    const parts = parseCriteriaParts(assessCriteria);
                    setAssessCriteriaHigh(parts.high);
                    setAssessCriteriaLow(parts.low);
                    setAssessCriteriaMedium(parts.medium);
                    setAssessCriteriaOpen(false);
                  }}
                  style={{ fontSize: '0.8rem' }}
                >
                  {tP('supplyView.assessment.criteriaCancel')}
                </button>
              </div>
            </div>
          )}
        </div>}

        {/* ── New event form ── */}
        {materialNewEvent === null ? (
          <button
            type="button"
            style={{ marginBottom: '1.25rem' }}
            onClick={() => setMaterialNewEvent({ supplyId: '', delayDays: 0, qtyDecreaseMode: 'pct', qtyDecreasePct: 0, qtyDecreaseAbs: 0, note: '' })}
          >
            + Add supply event
          </button>
        ) : (
          <div style={{ background: '#18181b', border: '1px solid #3d3d40', borderRadius: 8, padding: '1rem', marginBottom: '1.25rem', maxWidth: 640 }}>
            <h3 style={{ margin: '0 0 0.75rem', fontSize: '0.95rem', color: '#e4e4e7' }}>New supply event</h3>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', marginBottom: '0.75rem' }}>
              {/* Supply ID typeahead */}
              <div style={{ gridColumn: '1 / -1', position: 'relative' }}>
                <label style={{ display: 'block', fontSize: '0.8rem', color: '#a1a1aa', marginBottom: 3 }}>Supply ID</label>
                <input
                  type="text"
                  value={materialNewEvent.supplyId}
                  style={{ width: '100%', boxSizing: 'border-box' }}
                  placeholder="Type supply ID…"
                  autoComplete="off"
                  onChange={(e) => {
                    const val = e.target.value;
                    setMaterialNewEvent(ev => ev ? { ...ev, supplyId: val } : ev);
                    if (!val.trim()) {
                      setMaterialNewShowSuggestions(false);
                      setMaterialNewSupplySuggestions([]);
                      return;
                    }
                    if (materialNewDebounce.current) clearTimeout(materialNewDebounce.current);
                    materialNewDebounce.current = setTimeout(async () => {
                      try {
                        const res = await fetch(`/allocator/api/supplies?q=${encodeURIComponent(val.trim())}`);
                        if (res.ok) {
                          setMaterialNewSupplySuggestions(await res.json());
                          setMaterialNewShowSuggestions(true);
                        }
                      } catch { /* unreachable */ }
                    }, 200);
                  }}
                  onBlur={() => { setTimeout(() => setMaterialNewShowSuggestions(false), 150); }}
                />
                {materialNewShowSuggestions && materialNewSupplySuggestions.length > 0 && (
                  <div style={{ position: 'absolute', zIndex: 50, left: 0, right: 0, top: '100%', background: '#1c1c1e', border: '1px solid #3d3d40', borderRadius: 6, maxHeight: 200, overflowY: 'auto', boxShadow: '0 4px 16px rgba(0,0,0,0.4)' }}>
                    {materialNewSupplySuggestions.map((s) => (
                      <div
                        key={s.id}
                        style={{ padding: '6px 10px', cursor: 'pointer', fontSize: '0.82rem', color: '#e4e4e7', borderBottom: '1px solid #27272a' }}
                        onMouseDown={() => {
                          setMaterialNewEvent(ev => ev ? { ...ev, supplyId: s.id } : ev);
                          setMaterialNewShowSuggestions(false);
                          setMaterialNewSupplySuggestions([]);
                        }}
                      >
                        <span style={{ fontWeight: 600 }}>{s.id}</span>
                        {s.productId && <span style={{ marginLeft: 8, color: '#a1a1aa' }}>{s.productId}</span>}
                        {s.supplyDate && <span style={{ marginLeft: 8, color: '#71717a' }}>{s.supplyDate}</span>}
                        <span style={{ marginLeft: 8, color: '#71717a' }}>qty {qtyFmt(Number(s.qty))}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              {/* Delay days */}
              <div>
                <label style={{ display: 'block', fontSize: '0.8rem', color: '#a1a1aa', marginBottom: 3 }}>Delay (days)</label>
                <input
                  type="number"
                  min={0}
                  style={{ width: '100%', boxSizing: 'border-box' }}
                  value={materialNewEvent.delayDays}
                  onChange={(e) => setMaterialNewEvent(ev => ev ? { ...ev, delayDays: parseInt(e.target.value) || 0 } : ev)}
                />
              </div>
              {/* Qty decrease — segmented mode control + input */}
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 3 }}>
                  <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>Qty decrease</span>
                  <div style={{ display: 'flex', border: '1px solid #4c1d95', borderRadius: 4, overflow: 'hidden' }}>
                    <button type="button"
                      onClick={() => setMaterialNewEvent(ev => ev ? { ...ev, qtyDecreaseMode: 'pct' } : ev)}
                      style={{ fontSize: '0.72rem', padding: '1px 8px', background: materialNewEvent.qtyDecreaseMode === 'pct' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', cursor: 'pointer' }}>
                      %
                    </button>
                    <button type="button"
                      onClick={() => setMaterialNewEvent(ev => ev ? { ...ev, qtyDecreaseMode: 'abs' } : ev)}
                      style={{ fontSize: '0.72rem', padding: '1px 8px', background: materialNewEvent.qtyDecreaseMode === 'abs' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', borderLeft: '1px solid #4c1d95', cursor: 'pointer' }}>
                      qty
                    </button>
                  </div>
                </div>
                {materialNewEvent.qtyDecreaseMode === 'pct' ? (
                  <input type="number" min={0} max={100} step={0.1} style={{ width: '100%', boxSizing: 'border-box' }}
                    value={materialNewEvent.qtyDecreasePct}
                    onChange={(e) => setMaterialNewEvent(ev => ev ? { ...ev, qtyDecreasePct: parseFloat(e.target.value) || 0 } : ev)} />
                ) : (
                  <input type="number" min={0} step={1} style={{ width: '100%', boxSizing: 'border-box' }}
                    placeholder="Absolute qty reduction"
                    value={materialNewEvent.qtyDecreaseAbs}
                    onChange={(e) => setMaterialNewEvent(ev => ev ? { ...ev, qtyDecreaseAbs: parseFloat(e.target.value) || 0 } : ev)} />
                )}
              </div>
              {/* Note */}
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={{ display: 'block', fontSize: '0.8rem', color: '#a1a1aa', marginBottom: 3 }}>Note (optional)</label>
                <input
                  type="text"
                  style={{ width: '100%', boxSizing: 'border-box' }}
                  value={materialNewEvent.note}
                  onChange={(e) => setMaterialNewEvent(ev => ev ? { ...ev, note: e.target.value } : ev)}
                />
              </div>
            </div>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button
                type="button"
                disabled={materialNewSaving || !materialNewEvent.supplyId.trim()}
                onClick={async () => {
                  if (!materialNewEvent.supplyId.trim()) return;
                  setMaterialNewSaving(true);
                  try {
                    const created = await createMaterialEvent(id, {
                      supplyId: materialNewEvent.supplyId.trim(),
                      delayDays: materialNewEvent.delayDays,
                      qtyDecreasePct: materialNewEvent.qtyDecreaseMode === 'pct' ? materialNewEvent.qtyDecreasePct : 0,
                      qtyDecreaseAbs: materialNewEvent.qtyDecreaseMode === 'abs' ? materialNewEvent.qtyDecreaseAbs : null,
                      note: materialNewEvent.note || null,
                    });
                    setMaterialEvents(evs => [created, ...evs]);
                    setMaterialNewEvent(null);
                  } catch { /* ignore */ } finally {
                    setMaterialNewSaving(false);
                  }
                }}
              >
                {materialNewSaving ? 'Saving…' : 'Save'}
              </button>
              <button type="button" className="secondary" onClick={() => { setMaterialNewEvent(null); setMaterialNewShowSuggestions(false); }}>Cancel</button>
            </div>
          </div>
        )}

        {/* ── Events list ── */}
        {materialEventsLoading && <p style={{ color: '#71717a' }}>Loading…</p>}
        {!materialEventsLoading && materialEvents.length === 0 && (
          <p style={{ color: '#71717a' }}>No supply events recorded. Add one above to analyze impacts.</p>
        )}
        {materialEvents.map((ev) => {
          const editing = materialEventEditing[ev.id];
          const saving = materialEventSaving[ev.id] ?? false;
          const impact = materialImpacts[ev.id];
          const impactLoading = materialImpactLoading[ev.id] ?? false;
          const isCollapsed = materialEventCollapsed[ev.id] ?? false;
          return (
            <div key={ev.id} style={{ background: '#18181b', border: '1px solid #3d3d40', borderRadius: 8, padding: '1rem', marginBottom: '1rem' }}>
              {editing ? (
                /* ── Inline edit form ── */
                <div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem', marginBottom: '0.75rem' }}>
                    <div style={{ gridColumn: '1 / -1' }}>
                      <label style={{ display: 'block', fontSize: '0.8rem', color: '#a1a1aa', marginBottom: 3 }}>Supply ID</label>
                      <input type="text" style={{ width: '100%', boxSizing: 'border-box' }} value={editing.supplyId}
                        onChange={(e) => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], supplyId: e.target.value } }))} />
                    </div>
                    <div>
                      <label style={{ display: 'block', fontSize: '0.8rem', color: '#a1a1aa', marginBottom: 3 }}>Delay (days)</label>
                      <input type="number" min={0} style={{ width: '100%', boxSizing: 'border-box' }} value={editing.delayDays}
                        onChange={(e) => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], delayDays: parseInt(e.target.value) || 0 } }))} />
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 3 }}>
                        <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>Qty decrease</span>
                        <div style={{ display: 'flex', border: '1px solid #4c1d95', borderRadius: 4, overflow: 'hidden' }}>
                          <button type="button"
                            onClick={() => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], qtyDecreaseMode: 'pct' } }))}
                            style={{ fontSize: '0.72rem', padding: '1px 8px', background: editing.qtyDecreaseMode === 'pct' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', cursor: 'pointer' }}>
                            %
                          </button>
                          <button type="button"
                            onClick={() => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], qtyDecreaseMode: 'abs' } }))}
                            style={{ fontSize: '0.72rem', padding: '1px 8px', background: editing.qtyDecreaseMode === 'abs' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', borderLeft: '1px solid #4c1d95', cursor: 'pointer' }}>
                            qty
                          </button>
                        </div>
                      </div>
                      {editing.qtyDecreaseMode === 'pct' ? (
                        <input type="number" min={0} max={100} step={0.1} style={{ width: '100%', boxSizing: 'border-box' }} value={editing.qtyDecreasePct}
                          onChange={(e) => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], qtyDecreasePct: parseFloat(e.target.value) || 0 } }))} />
                      ) : (
                        <input type="number" min={0} step={1} style={{ width: '100%', boxSizing: 'border-box' }} placeholder="Absolute qty reduction" value={editing.qtyDecreaseAbs}
                          onChange={(e) => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], qtyDecreaseAbs: parseFloat(e.target.value) || 0 } }))} />
                      )}
                    </div>
                    <div style={{ gridColumn: '1 / -1' }}>
                      <label style={{ display: 'block', fontSize: '0.8rem', color: '#a1a1aa', marginBottom: 3 }}>Note</label>
                      <input type="text" style={{ width: '100%', boxSizing: 'border-box' }} value={editing.note}
                        onChange={(e) => setMaterialEventEditing(m => ({ ...m, [ev.id]: { ...m[ev.id], note: e.target.value } }))} />
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button type="button" disabled={saving}
                      onClick={async () => {
                        setMaterialEventSaving(m => ({ ...m, [ev.id]: true }));
                        try {
                          const updated = await updateMaterialEvent(id, ev.id, {
                            supplyId: editing.supplyId.trim(),
                            delayDays: editing.delayDays,
                            qtyDecreasePct: editing.qtyDecreaseMode === 'pct' ? editing.qtyDecreasePct : 0,
                            qtyDecreaseAbs: editing.qtyDecreaseMode === 'abs' ? editing.qtyDecreaseAbs : null,
                            note: editing.note || null,
                          });
                          setMaterialEvents(evs => evs.map(e => e.id === ev.id ? updated : e));
                          setMaterialEventEditing(m => { const n = { ...m }; delete n[ev.id]; return n; });
                          setMaterialImpacts(m => { const n = { ...m }; delete n[ev.id]; return n; });
                        } catch { /* ignore */ } finally {
                          setMaterialEventSaving(m => ({ ...m, [ev.id]: false }));
                        }
                      }}>{saving ? 'Saving…' : 'Save'}</button>
                    <button type="button" className="secondary"
                      onClick={() => setMaterialEventEditing(m => { const n = { ...m }; delete n[ev.id]; return n; })}>Cancel</button>
                  </div>
                </div>
              ) : (
                /* ── Read view ── */
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                    <div style={{ flex: 1, display: 'flex', alignItems: 'baseline', flexWrap: 'wrap', gap: 0, cursor: 'pointer' }}
                      onClick={() => setMaterialEventCollapsed(m => ({ ...m, [ev.id]: !(m[ev.id] ?? false) }))}>
                      <span style={{ marginRight: 6, color: '#71717a', fontSize: '0.75rem', userSelect: 'none' }}>{isCollapsed ? '▸' : '▾'}</span>
                      <span style={{ fontWeight: 600, color: '#e4e4e7', fontSize: '0.9rem' }}>{ev.supplyId}</span>
                      <span style={{ marginLeft: 10, background: ev.delayDays > 0 ? '#7c3aed' : '#3d3d40', color: '#fff', borderRadius: 6, padding: '2px 7px', fontSize: '0.75rem' }}>
                        {ev.delayDays > 0 ? `+${ev.delayDays}d delay` : 'no delay'}
                      </span>
                      {(ev.qtyDecreaseAbs != null && ev.qtyDecreaseAbs > 0) ? (
                        <span style={{ marginLeft: 6, background: '#b45309', color: '#fff', borderRadius: 6, padding: '2px 7px', fontSize: '0.75rem' }}>
                          −{qtyFmt(Number(ev.qtyDecreaseAbs))} qty
                        </span>
                      ) : ev.qtyDecreasePct > 0 ? (
                        <span style={{ marginLeft: 6, background: '#b45309', color: '#fff', borderRadius: 6, padding: '2px 7px', fontSize: '0.75rem' }}>
                          −{ev.qtyDecreasePct}% qty
                        </span>
                      ) : null}
                      {ev.note && <span style={{ marginLeft: 10, color: '#a1a1aa', fontSize: '0.8rem' }}>{ev.note}</span>}
                      <span style={{ marginLeft: 10, color: '#52525b', fontSize: '0.75rem' }}>{new Date(ev.createdAt).toLocaleString()}</span>
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                      <button type="button" className="secondary" style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                        onClick={() => {
                          setMaterialEventCollapsed(m => ({ ...m, [ev.id]: false }));
                          setMaterialEventEditing(m => ({ ...m, [ev.id]: { supplyId: ev.supplyId, delayDays: ev.delayDays, qtyDecreaseMode: ev.qtyDecreaseAbs != null && ev.qtyDecreaseAbs > 0 ? 'abs' : 'pct', qtyDecreasePct: ev.qtyDecreasePct, qtyDecreaseAbs: ev.qtyDecreaseAbs ?? 0, note: ev.note ?? '' } }));
                        }}>
                        Edit
                      </button>
                      <button type="button" className="secondary" style={{ fontSize: '0.8rem', padding: '3px 10px', color: '#f87171', borderColor: '#f87171' }}
                        onClick={async () => {
                          try {
                            await deleteMaterialEvent(id, ev.id);
                            setMaterialEvents(evs => evs.filter(e => e.id !== ev.id));
                            setMaterialImpacts(m => { const n = { ...m }; delete n[ev.id]; return n; });
                          } catch { /* ignore */ }
                        }}>
                        Delete
                      </button>
                      <button type="button" style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                        disabled={impactLoading}
                        onClick={async () => {
                          setMaterialImpactLoading(m => ({ ...m, [ev.id]: true }));
                          setMaterialImpacts(m => ({ ...m, [ev.id]: null }));
                          setMaterialImpactError(m => ({ ...m, [ev.id]: null }));
                          setMaterialImpactProgress(m => ({ ...m, [ev.id]: null }));
                          try {
                            const result = await analyzeMaterialImpact(
                              ev.supplyId, ev.delayDays, ev.qtyDecreasePct, true, ev.qtyDecreaseAbs,
                              (p) => setMaterialImpactProgress(m => ({ ...m, [ev.id]: p })),
                            );
                            setMaterialImpacts(m => ({ ...m, [ev.id]: result }));
                          } catch (e) {
                            setMaterialImpactError(m => ({ ...m, [ev.id]: e instanceof Error ? e.message : 'Impact analysis failed' }));
                          } finally {
                            setMaterialImpactLoading(m => ({ ...m, [ev.id]: false }));
                            setMaterialImpactProgress(m => ({ ...m, [ev.id]: null }));
                          }
                        }}>
                        {impactLoading ? 'Analyzing…' : 'Analyze impact'}
                      </button>
                      <button type="button" style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                        disabled={materialAssessmentLoading[ev.id] ?? false}
                        onClick={async () => {
                          setMaterialAssessmentLoading(m => ({ ...m, [ev.id]: true }));
                          setMaterialAssessmentError(m => ({ ...m, [ev.id]: null }));
                          try {
                            const result = await runAssessment(id, ev.supplyId, ev.delayDays, ev.qtyDecreasePct, undefined, impact, ev.qtyDecreaseAbs, locale);
                            setMaterialAssessments(m => ({ ...m, [ev.id]: result }));
                            const hist = await listAssessments(id, ev.supplyId);
                            setMaterialAssessmentHistory(m => ({ ...m, [ev.id]: hist }));
                            setMaterialAssessmentHistoryOpen(m => ({ ...m, [ev.id]: true }));
                          } catch (e) {
                            setMaterialAssessmentError(m => ({ ...m, [ev.id]: e instanceof Error ? e.message : 'Assessment failed' }));
                          } finally {
                            setMaterialAssessmentLoading(m => ({ ...m, [ev.id]: false }));
                          }
                        }}>
                        {(materialAssessmentLoading[ev.id] ?? false) ? 'Assessing…' : 'Assess impact'}
                      </button>
                    </div>
                  </div>

                  {impactLoading && materialImpactProgress[ev.id] && (materialImpactProgress[ev.id]?.total ?? 0) > 0 && (
                    <div style={{ marginTop: '0.5rem', maxWidth: 400 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.78rem', color: '#a1a1aa', marginBottom: '0.25rem' }}>
                        <span>
                          Re-planning: {materialImpactProgress[ev.id]?.current} / {materialImpactProgress[ev.id]?.total} demands
                          {materialImpactProgress[ev.id]?.iteration && materialImpactProgress[ev.id]?.iterations_max
                            ? ` (iter ${materialImpactProgress[ev.id]?.iteration}/${materialImpactProgress[ev.id]?.iterations_max})`
                            : ''}
                        </span>
                      </div>
                      <div style={{ height: 6, backgroundColor: '#27272a', borderRadius: 3, overflow: 'hidden' }}>
                        <div
                          style={{
                            height: '100%',
                            width: `${Math.min(100, 100 * (materialImpactProgress[ev.id]?.current ?? 0) / Math.max(1, materialImpactProgress[ev.id]?.total ?? 1))}%`,
                            backgroundColor: '#3b82f6',
                            transition: 'width 0.2s ease',
                          }}
                        />
                      </div>
                    </div>
                  )}
                  {materialImpactError[ev.id] && (
                    <p style={{ color: '#f87171', fontSize: '0.78rem', margin: '0.5rem 0 0' }}>{materialImpactError[ev.id]}</p>
                  )}

                  {!isCollapsed && (<>
                  {/* ── Impact results ── */}
                  {impact && (
                    <div style={{ marginTop: '0.875rem', borderTop: '1px solid #27272a', paddingTop: '0.875rem' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: '0.5rem', flexWrap: 'wrap' }}>
                        <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>
                          Supply: <b style={{ color: '#e4e4e7' }}>{impact.supply.productId}</b>
                          {impact.supply.locationId && <> @ <b style={{ color: '#e4e4e7' }}>{impact.supply.locationId}</b></>}
                          {' '}· qty {qtyFmt(Number(impact.supply.qty))}
                          {impact.supply.vendorId && <> · vendor {impact.supply.vendorId}</>}
                          {impact.supply.supplyDate && <> · {impact.supply.supplyDate}</>}
                        </span>
                        <span style={{ background: impact.impactedDemandCount > 0 ? '#991b1b' : '#166534', color: '#fff', borderRadius: 6, padding: '2px 8px', fontSize: '0.8rem', fontWeight: 600 }}>
                          {impact.impactedDemandCount} impacted demand{impact.impactedDemandCount !== 1 ? 's' : ''}
                        </span>
                        {impact.contingentPlanRunId != null && (
                          <span style={{ color: '#71717a', fontSize: '0.75rem' }}>
                            baseline run #{impact.planRunId} · contingent run #{impact.contingentPlanRunId}
                          </span>
                        )}
                        {impact.note && <span style={{ color: '#a1a1aa', fontSize: '0.8rem' }}>{impact.note}</span>}
                      </div>
                      {impact.impacts.length > 0 && (
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
                          <thead>
                            <tr style={{ color: '#71717a', borderBottom: '1px solid #27272a' }}>
                              <th style={{ textAlign: 'left', padding: '4px 8px', fontWeight: 500 }}>Demand ID</th>
                              <th style={{ textAlign: 'left', padding: '4px 8px', fontWeight: 500 }}>Product</th>
                              <th style={{ textAlign: 'left', padding: '4px 8px', fontWeight: 500 }}>Customer</th>
                              <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Due</th>
                              <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Req qty</th>
                              <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Baseline qty</th>
                              <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Contingent qty</th>
                              <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Shortfall</th>
                              <th style={{ textAlign: 'center', padding: '4px 8px', fontWeight: 500 }}>Status</th>
                            </tr>
                          </thead>
                          <tbody>
                            {impact.impacts.map((imp) => {
                              const baseQty = imp.baselineCommittedQty ?? imp.consumedSupplyQty;
                              const contQty = imp.contingentCommittedQty ?? 0;
                              const shortfall = baseQty - contQty;
                              const statusColor =
                                imp.status === 'newly_failed' ? '#991b1b' :
                                imp.status === 'qty_reduced'  ? '#b45309' :
                                imp.status === 'delayed'      ? '#7c3aed' : '#b45309';
                              return (
                                <tr key={imp.demandId} style={{ borderBottom: '1px solid #1f1f22' }}>
                                  <td style={{ padding: '5px 8px', color: '#e4e4e7', fontFamily: 'monospace' }}>{imp.demandId}</td>
                                  <td style={{ padding: '5px 8px', color: '#a1a1aa' }}>{imp.productId}</td>
                                  <td style={{ padding: '5px 8px', color: '#a1a1aa' }}>{imp.customerId}</td>
                                  <td style={{ padding: '5px 8px', color: '#a1a1aa', textAlign: 'right' }}>{imp.requestDueTime ?? '–'}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: '#e4e4e7' }}>{qtyFmt(Number(imp.requestedQty))}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: '#e4e4e7' }}>{qtyFmt(Number(baseQty))}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: contQty < baseQty ? '#f87171' : '#e4e4e7' }}>{qtyFmt(Number(contQty))}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: shortfall > 0 ? '#f87171' : '#a1a1aa' }}>
                                    {shortfall > 0 ? `-${qtyFmt(Number(shortfall))}` : '–'}
                                  </td>
                                  <td style={{ padding: '5px 8px', textAlign: 'center' }}>
                                    <span style={{ background: statusColor, color: '#fff', borderRadius: 5, padding: '2px 7px', fontSize: '0.73rem' }}>
                                      {imp.status}
                                    </span>
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      )}
                    </div>
                  )}

                  {/* ── Assessment result ── */}
                  {materialAssessmentError[ev.id] && (
                    <p style={{ color: '#f87171', fontSize: '0.78rem', margin: '0.5rem 0 0' }}>{materialAssessmentError[ev.id]}</p>
                  )}
                  {materialAssessments[ev.id] && (() => {
                    const ar = materialAssessments[ev.id]!;
                    return (
                      <div style={{ marginTop: '0.875rem', borderTop: '1px solid #27272a', paddingTop: '0.875rem' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: '0.25rem' }}>
                          <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>{tP('supplyView.assessment.ratingLabel')} </span>
                          <span style={{
                            display: 'inline-block',
                            padding: '1px 10px',
                            borderRadius: 12,
                            fontSize: '0.8rem',
                            fontWeight: 600,
                            letterSpacing: '0.05em',
                            background: ar.rating === 'LOW' ? 'rgba(52,211,153,0.15)' : ar.rating === 'HIGH' ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                            color: ar.rating === 'LOW' ? '#34d399' : ar.rating === 'HIGH' ? '#f87171' : '#fbbf24',
                            border: `1px solid ${ar.rating === 'LOW' ? '#34d399' : ar.rating === 'HIGH' ? '#f87171' : '#fbbf24'}`,
                          }}>{ar.rating}</span>
                        </div>
                        <p style={{ fontSize: '0.78rem', color: '#d4d4d8', margin: '0.3rem 0 0' }}>{ar.explanation}</p>
                      </div>
                    );
                  })()}

                  {/* ── Assessment history toggle ── */}
                  <div style={{ marginTop: '0.6rem' }}>
                    <button
                      type="button"
                      onClick={async () => {
                        if (!(materialAssessmentHistoryOpen[ev.id] ?? false)) {
                          try {
                            const hist = await listAssessments(id, ev.supplyId);
                            setMaterialAssessmentHistory(m => ({ ...m, [ev.id]: hist }));
                          } catch { /* best-effort */ }
                        }
                        setMaterialAssessmentHistoryOpen(m => ({ ...m, [ev.id]: !(m[ev.id] ?? false) }));
                      }}
                      style={{ background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.75rem', padding: 0 }}
                    >
                      {(materialAssessmentHistoryOpen[ev.id] ?? false) ? '▾' : '▸'} {tP('supplyView.assessment.history')}
                    </button>
                    {(materialAssessmentHistoryOpen[ev.id] ?? false) && (
                      <div style={{ marginTop: '0.5rem' }}>
                        {!(materialAssessmentHistory[ev.id]?.length) ? (
                          <p style={{ fontSize: '0.75rem', color: '#71717a', margin: 0 }}>{tP('supplyView.assessment.noHistory')}</p>
                        ) : (
                          <AssessmentHistoryTable rows={materialAssessmentHistory[ev.id]!} storageKey="event" />
                        )}
                      </div>
                    )}
                  </div>
                  </>)}
                </div>
              )}
            </div>
          );
        })}
      </section>
      )}
      {/* ── Knowledge Base slide-in ────────────────────────────────────────────── */}
      {bootstrapDialogOpen && typeof document !== 'undefined' && createPortal(
        <div
          style={{ position: 'fixed', inset: 0, zIndex: 9997, display: 'flex', justifyContent: 'flex-end' }}
          role="dialog"
          aria-label={tP('bootstrap.title')}
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.5)' }}
            onClick={closeBootstrapDialog}
            aria-hidden
          />
          <div
            style={{
              position: 'relative', zIndex: 10,
              width: bootstrapPanelWidth, maxWidth: '95vw', height: '100vh',
              display: 'flex', flexDirection: 'column',
              background: '#1c1c1e', color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)',
              padding: '1.25rem 1.5rem',
              overflowY: 'auto',
            }}
          >
            {/* Resize handle */}
            <div
              role="separator"
              aria-label="Resize panel"
              onMouseDown={(e) => { e.preventDefault(); bootstrapResizeRef.current = { startX: e.clientX, startW: bootstrapPanelWidth }; setBootstrapResizing(true); }}
              style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 6, cursor: 'col-resize', zIndex: 11 }}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <h3 style={{ margin: 0, color: '#fafafa' }}>{tP('bootstrap.title')}</h3>
              <button type="button" onClick={closeBootstrapDialog}
                style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>
                {tP('bootstrap.close')}
              </button>
            </div>
            <p style={{ margin: '0 0 0.75rem', fontSize: '0.85rem', color: '#a1a1aa' }}>
              {tP('bootstrap.intro')}
            </p>

            {/* Progress block (visible while a bootstrap job is running) */}
            {bootstrapJobStatus && (() => {
              const total = bootstrapJobStatus.total;
              const completed = bootstrapJobStatus.completed;
              const pct = total > 0 ? (completed / total) * 100 : 0;
              const isCancelled = bootstrapJobStatus.status === 'cancelled';
              const isCompleted = bootstrapJobStatus.status === 'completed';
              const isCancelInFlight = bootstrapJobStatus.cancelled === true && !isCancelled && !isCompleted;
              const accentBg = isCancelled ? '#3a1f0c' : '#0c1f3a';
              const accentBorder = isCancelled ? '#b45309' : '#1d4ed8';
              const accentText = isCancelled ? '#fdba74' : '#93c5fd';
              const barFill = isCancelled ? '#b45309' : isCancelInFlight ? '#fbbf24' : '#3b82f6';
              const barTrack = isCancelled ? '#451a03' : '#1e3a8a';
              return (
                <div style={{ marginBottom: '0.75rem', padding: '0.6rem 0.75rem', background: accentBg, border: `1px solid ${accentBorder}`, borderRadius: 6 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.35rem' }}>
                    <div style={{ fontWeight: 600, color: accentText, fontSize: '0.85rem' }}>
                      {isCancelled
                        ? tP('bootstrap.progressCancelled')
                        : isCompleted
                          ? tP('bootstrap.progressTitleDone')
                          : tP('bootstrap.progressTitle')}
                    </div>
                    {!isCompleted && !isCancelled && (
                      <button
                        type="button"
                        onClick={handleCancelBootstrap}
                        disabled={bootstrapCancelling || isCancelInFlight}
                        title={tP('bootstrap.interruptTooltip')}
                        style={{
                          fontSize: '0.72rem', padding: '2px 10px',
                          background: isCancelInFlight ? '#27272a' : 'transparent',
                          color: isCancelInFlight ? '#71717a' : '#fbbf24',
                          border: '1px solid #b45309', borderRadius: 4,
                          cursor: bootstrapCancelling || isCancelInFlight ? 'default' : 'pointer',
                          opacity: bootstrapCancelling || isCancelInFlight ? 0.7 : 1,
                        }}
                      >
                        {isCancelInFlight ? tP('bootstrap.interruptInflight') : tP('bootstrap.interrupt')}
                      </button>
                    )}
                  </div>
                  <div style={{ fontSize: '0.82rem', color: '#e4e4e7', marginBottom: '0.5rem' }}>
                    {isCancelled
                      ? tP('bootstrap.progressCancelledLine', { completed, total })
                      : isCompleted
                        ? tP('bootstrap.progressDone', { completed })
                        : tP('bootstrap.progressLine', {
                            completed,
                            total,
                            current: bootstrapJobStatus.current_preset_label,
                          })}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <div style={{ flex: 1, height: 12, background: barTrack, borderRadius: 6, overflow: 'hidden' }}>
                      <div style={{
                        height: '100%', background: barFill,
                        width: `${pct}%`,
                        transition: 'width 0.3s',
                      }} />
                    </div>
                    <span style={{ fontSize: '0.78rem', color: '#a1a1aa', minWidth: 56, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                      {`${completed}/${total} (${pct.toFixed(0)}%)`}
                    </span>
                  </div>
                  {isCancelInFlight && (
                    <div style={{ marginTop: '0.4rem', fontSize: '0.72rem', color: '#fbbf24' }}>
                      {tP('bootstrap.interruptHint')}
                    </div>
                  )}
                  {bootstrapJobStatus.errors.length > 0 && (
                    <div style={{ marginTop: '0.4rem', fontSize: '0.75rem', color: '#f87171' }}>
                      <div style={{ fontWeight: 600 }}>{tP('bootstrap.progressErrors', { n: bootstrapJobStatus.errors.length })}</div>
                      <ul style={{ margin: '0.2rem 0 0 1rem', padding: 0 }}>
                        {bootstrapJobStatus.errors.map((e, i) => (<li key={i}>{e}</li>))}
                      </ul>
                    </div>
                  )}
                </div>
              );
            })()}

            {/* Skipped-dup notice — server-side dedup at start time dropped
                some submitted presets because their config signature matched
                an existing KB record. */}
            {bootstrapSkippedNotice && bootstrapSkippedNotice.length > 0 && (
              <div style={{ marginBottom: '0.75rem', padding: '0.5rem 0.75rem', background: '#3a1f0c', border: '1px solid #b45309', borderRadius: 6, color: '#fdba74' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                  <div style={{ fontSize: '0.78rem' }}>
                    <div style={{ fontWeight: 600, marginBottom: 4 }}>{tP('bootstrap.skippedHeading', { n: bootstrapSkippedNotice.length })}</div>
                    <div style={{ fontSize: '0.72rem', color: '#fed7aa' }}>{tP('bootstrap.skippedHint')}</div>
                    <ul style={{ margin: '0.25rem 0 0 1rem', padding: 0, fontFamily: 'monospace', fontSize: '0.7rem', color: '#fed7aa' }}>
                      {bootstrapSkippedNotice.map((p) => (
                        <li key={p.preset_id}>{p.preset_label}</li>
                      ))}
                    </ul>
                  </div>
                  <button
                    type="button"
                    onClick={() => setBootstrapSkippedNotice(null)}
                    style={{ background: 'transparent', color: '#fdba74', border: '1px solid #b45309', borderRadius: 4, padding: '2px 8px', fontSize: '0.7rem', cursor: 'pointer' }}
                  >{tP('bootstrap.skippedDismiss')}</button>
                </div>
              </div>
            )}

            {/* Coverage + library-exhausted state */}
            {bootstrapPreview && (
              <p style={{ fontSize: '0.82rem', color: '#a1a1aa', margin: '0 0 0.75rem' }}>
                {bootstrapPreview.remaining_count === 0
                  ? tP('bootstrap.exhausted', { total: bootstrapPreview.library_size })
                  : tP('bootstrap.coverage', {
                      kbCount: bootstrapPreview.kb_record_count ?? 0,
                      covered: bootstrapPreview.already_run_count,
                      total: bootstrapPreview.library_size,
                    })}
              </p>
            )}

            {/* Batch size + Start. Always rendered — even when the library is
                fully in KB, the user can edit a preset to define a new variation. */}
            {bootstrapPreview && (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', marginBottom: '0.5rem', flexWrap: 'wrap' }}>
                  <label style={{ fontSize: '0.82rem', color: '#e4e4e7' }}>
                    {tP('bootstrap.batchSizeLabel')}:
                    <input
                      type="number" min={1} value={bootstrapBatchSize}
                      onChange={(e) => setBootstrapBatchSize(Math.max(1, Number(e.target.value) || 5))}
                      style={{
                        marginLeft: '0.5rem', width: 60, padding: '3px 6px',
                        background: '#27272a', color: '#e4e4e7',
                        border: '1px solid #52525b', borderRadius: 4, fontSize: '0.82rem',
                      }}
                    />
                  </label>
                  <label style={{ fontSize: '0.82rem', color: '#e4e4e7' }} title={tP('bootstrap.criterionTooltip')}>
                    {tP('bootstrap.criterionLabel')}:
                    <select
                      value={bootstrapCriterion}
                      onChange={(e) => setBootstrapCriterion(e.target.value as BootstrapCriterion)}
                      style={{
                        marginLeft: '0.5rem', padding: '3px 6px',
                        background: '#27272a', color: '#e4e4e7',
                        border: '1px solid #52525b', borderRadius: 4, fontSize: '0.82rem',
                      }}
                    >
                      <option value="fill_rate">{tP('bootstrap.criterionFillRate')}</option>
                      <option value="fairness">{tP('bootstrap.criterionFairness')}</option>
                      <option value="pareto">{tP('bootstrap.criterionPareto')}</option>
                    </select>
                  </label>
                  <span style={{ fontSize: '0.75rem', color: '#71717a' }}>{tP('bootstrap.batchSizeHint')}</span>
                </div>

                <details style={{ marginBottom: '0.5rem', fontSize: '0.75rem', color: '#71717a' }}>
                  <summary style={{ cursor: 'pointer' }}>
                    {tP('bootstrap.baselineHeading')}
                  </summary>
                  <div style={{ marginTop: '0.25rem', padding: '4px 8px', background: '#0a0a0a', borderRadius: 4, fontFamily: 'monospace', fontSize: '0.7rem', color: '#a1a1aa' }}>
                    {tP('bootstrap.baselineSummary')}
                  </div>
                </details>
                <div style={{ marginBottom: '0.5rem', fontSize: '0.82rem', color: '#a1a1aa', fontWeight: 600 }}>
                  {tP('bootstrap.nextBatchHeading')}
                </div>
                {/* Preset list: server suggests N single-knob variations
                    around the case's current best (or BASELINE on cold-start).
                    Each row is collapsed by default; expanding shows a
                    planning-page-style mini config form so users can alter
                    knobs before submitting. Edits get re-dedup'd at submit. */}
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem', marginBottom: '0.75rem' }}>
                  <tbody>
                    {bootstrapPreview.next_batch.map((p) => {
                      const editing = bootstrapEditingRows.has(p.preset_id);
                      const editedConfig = bootstrapEditedConfigs[p.preset_id];
                      const effectiveConfig = (editedConfig ?? p.config) as Record<string, unknown>;
                      const isEdited = editedConfig != null;
                      const summary = presetConfigSummary(effectiveConfig);
                      const ms = (effectiveConfig.method_selection ?? {}) as Record<string, unknown>;
                      const cs = (effectiveConfig.consolidation ?? {}) as Record<string, unknown>;
                      const sw = (ms.score_weights ?? {}) as Record<string, number>;
                      const elaborateOn = ms.mode === 'elaborate' || ms.elaborate === true;
                      const updateConfig = (mutator: (cfg: Record<string, unknown>) => void) => {
                        const next = JSON.parse(JSON.stringify(effectiveConfig)) as Record<string, unknown>;
                        mutator(next);
                        setBootstrapEditedConfigs((prev) => ({ ...prev, [p.preset_id]: next }));
                      };
                      const resetConfig = () => {
                        setBootstrapEditedConfigs((prev) => { const n = { ...prev }; delete n[p.preset_id]; return n; });
                      };
                      const toggleEditing = () => {
                        setBootstrapEditingRows((prev) => {
                          const n = new Set(prev);
                          if (n.has(p.preset_id)) n.delete(p.preset_id); else n.add(p.preset_id);
                          return n;
                        });
                      };
                      const inputStyle: React.CSSProperties = {
                        padding: '2px 6px', background: '#27272a', color: '#e4e4e7',
                        border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.78rem',
                      };
                      return (
                        <React.Fragment key={p.preset_id}>
                          <tr style={{ borderBottom: editing ? 'none' : '1px solid #27272a', background: isEdited ? 'rgba(251,146,60,0.06)' : undefined }}>
                            <td style={{ padding: '4px 6px', width: 18, cursor: 'pointer', color: '#71717a', verticalAlign: 'top' }}
                                onClick={toggleEditing}>
                              {editing ? '▾' : '▸'}
                            </td>
                            <td style={{ padding: '4px 6px', fontFamily: 'monospace', color: '#67e8f9', cursor: 'pointer', verticalAlign: 'top', whiteSpace: 'nowrap' }}
                                onClick={toggleEditing}>
                              {p.preset_label}
                              {isEdited && (
                                <span title={tP('bootstrap.editedTooltip')} style={{ marginLeft: 5, fontSize: '0.62rem', background: '#7c2d12', color: '#fed7aa', borderRadius: 4, padding: '0px 5px' }}>
                                  {tP('bootstrap.editedBadge')}
                                </span>
                              )}
                            </td>
                            <td style={{ padding: '4px 6px', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                              <span style={{ fontSize: '0.7rem', background: '#3f3f46', borderRadius: 4, padding: '1px 6px', color: '#a1a1aa' }}>{p.primary_axis}</span>
                            </td>
                            <td style={{ padding: '4px 6px', fontFamily: 'monospace', fontSize: '0.7rem', color: '#a1a1aa', verticalAlign: 'top' }}>
                              {summary}
                            </td>
                          </tr>
                          {editing && (
                            <tr style={{ borderBottom: '1px solid #27272a', background: '#0a0a0a' }}>
                              <td colSpan={4} style={{ padding: '8px 12px 12px 28px' }}>
                                {/* Method selection (supply side) */}
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', fontSize: '0.78rem', marginBottom: 6 }}>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editMaxMethods')}</span>
                                    <select value={Number(ms.max_methods ?? 1)}
                                      onChange={(e) => updateConfig((c) => {
                                        const m = (c.method_selection ?? {}) as Record<string, unknown>;
                                        m.max_methods = parseInt(e.target.value, 10) || 1;
                                        c.method_selection = m;
                                      })}
                                      style={inputStyle}>
                                      {[1,2,3,4,5,6,7,8].map((n) => (<option key={n} value={n}>{n}</option>))}
                                    </select>
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                    <input type="checkbox" checked={!!elaborateOn}
                                      onChange={(e) => updateConfig((c) => {
                                        const m = (c.method_selection ?? {}) as Record<string, unknown>;
                                        m.elaborate = e.target.checked;
                                        m.mode = e.target.checked ? 'elaborate' : 'preference';
                                        c.method_selection = m;
                                      })} />
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editElaborate')}</span>
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, opacity: elaborateOn ? 1 : 0.4 }}>
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editDepth')}</span>
                                    <input type="number" min={1} max={10} disabled={!elaborateOn}
                                      value={Number(ms.depth ?? 1)}
                                      onChange={(e) => updateConfig((c) => {
                                        const m = (c.method_selection ?? {}) as Record<string, unknown>;
                                        m.depth = Math.max(1, Math.min(10, parseInt(e.target.value, 10) || 1));
                                        c.method_selection = m;
                                      })}
                                      style={{ ...inputStyle, width: 56 }} />
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                    <input type="checkbox" checked={effectiveConfig.purchase_allowed === true}
                                      onChange={(e) => updateConfig((c) => { c.purchase_allowed = e.target.checked; })} />
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editPurchase')}</span>
                                  </label>
                                </div>
                                {elaborateOn && (
                                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', fontSize: '0.78rem', marginBottom: 6, paddingLeft: 8 }}>
                                    <span style={{ fontSize: '0.7rem', color: '#71717a' }}>{tP('bootstrap.editWeights')}</span>
                                    {(['commit_time','inventory_consumed','purchase'] as const).map((k) => (
                                      <label key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                        <span style={{ color: '#a1a1aa', fontSize: '0.72rem' }}>{k.replace('_',' ')}</span>
                                        <input type="number" step={0.05} min={0} max={1}
                                          value={Number(sw[k] ?? (k === 'commit_time' ? 0.4 : k === 'inventory_consumed' ? 0.35 : 0.25))}
                                          onChange={(e) => updateConfig((c) => {
                                            const m = (c.method_selection ?? {}) as Record<string, unknown>;
                                            const w = { ...((m.score_weights ?? {}) as Record<string, number>) };
                                            w[k] = Math.max(0, Math.min(1, parseFloat(e.target.value) || 0));
                                            m.score_weights = w; c.method_selection = m;
                                          })}
                                          style={{ ...inputStyle, width: 64 }} />
                                      </label>
                                    ))}
                                  </div>
                                )}
                                {/* Consolidation (demand side) */}
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', fontSize: '0.78rem', marginBottom: 6 }}>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                    <input type="checkbox" checked={cs.enabled !== false}
                                      onChange={(e) => updateConfig((c) => {
                                        const v = (c.consolidation ?? {}) as Record<string, unknown>;
                                        v.enabled = e.target.checked; c.consolidation = v;
                                      })} />
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editConsolidationEnabled')}</span>
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, opacity: cs.enabled !== false ? 1 : 0.4 }}>
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editScope')}</span>
                                    <select value={String(cs.scope ?? 'leaf-only')}
                                      disabled={cs.enabled === false}
                                      onChange={(e) => updateConfig((c) => {
                                        const v = (c.consolidation ?? {}) as Record<string, unknown>;
                                        v.scope = e.target.value; c.consolidation = v;
                                      })} style={inputStyle}>
                                      <option value="leaf-only">leaf-only</option>
                                      <option value="all">all</option>
                                    </select>
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, opacity: cs.enabled !== false ? 1 : 0.4 }}>
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editPeriodDays')}</span>
                                    <input type="number" min={0} max={365}
                                      disabled={cs.enabled === false}
                                      value={Number(cs.period_days ?? 0)}
                                      onChange={(e) => updateConfig((c) => {
                                        const v = (c.consolidation ?? {}) as Record<string, unknown>;
                                        v.period_days = Math.max(0, Math.min(365, parseInt(e.target.value, 10) || 0));
                                        c.consolidation = v;
                                      })}
                                      style={{ ...inputStyle, width: 64 }} />
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, opacity: cs.enabled !== false ? 1 : 0.4 }}>
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editAllocation')}</span>
                                    <select value={String(cs.allocation_mode ?? 'fair')}
                                      disabled={cs.enabled === false}
                                      onChange={(e) => updateConfig((c) => {
                                        const v = (c.consolidation ?? {}) as Record<string, unknown>;
                                        v.allocation_mode = e.target.value; c.consolidation = v;
                                      })} style={inputStyle}>
                                      <option value="fair">fair</option>
                                      <option value="proportional">proportional</option>
                                      <option value="priority_first">priority_first</option>
                                    </select>
                                  </label>
                                </div>
                                {isEdited && (
                                  <div style={{ marginTop: 6 }}>
                                    <button type="button" onClick={resetConfig}
                                      style={{ fontSize: '0.7rem', padding: '2px 8px', background: 'transparent', color: '#a1a1aa', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer' }}>
                                      {tP('bootstrap.editReset')}
                                    </button>
                                  </div>
                                )}
                              </td>
                            </tr>
                          )}
                        </React.Fragment>
                      );
                    })}
                  </tbody>
                </table>

                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem', marginTop: '0.5rem' }}>
                  <button type="button" onClick={closeBootstrapDialog}
                    style={{ padding: '5px 12px', background: 'transparent', color: '#a1a1aa', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>
                    {tP('bootstrap.cancel')}
                  </button>
                  <button type="button" onClick={handleStartBootstrap}
                    disabled={bootstrapStarting || bootstrapJobStatus?.status === 'running' || bootstrapPreviewLoading}
                    style={{
                      padding: '5px 14px', background: '#16a34a', color: '#fff',
                      border: 'none', borderRadius: 6, cursor: 'pointer',
                      fontWeight: 600, opacity: bootstrapStarting || bootstrapJobStatus?.status === 'running' ? 0.6 : 1,
                    }}>
                    {(() => {
                      if (bootstrapStarting) return tP('bootstrap.starting');
                      // Count actual runs: sum of values across checked axes.
                      // The server has already filtered duplicates; every
                      // suggestion in next_batch becomes a run on Start.
                      const willRun = bootstrapPreview.next_batch.length;
                      return tP('bootstrap.start', { n: willRun });
                    })()}
                  </button>
                </div>

                {bootstrapPreview.already_run.length > 0 && (() => {
                  // KPI columns: 'asc' direction = lower-is-better (gini, starvation),
                  // 'desc' = higher-is-better. Default sort dir on first click matches `better`.
                  const kpiCols: Array<{ key: keyof BootstrapPreset; label: string; better: 'asc' | 'desc'; fmt: (v: number) => string }> = [
                    { key: 'fill_rate_pct',                label: tP('bootstrap.kpi.fill'),        better: 'desc', fmt: (v) => `${v.toFixed(1)}%` },
                    { key: 'gini',                         label: tP('bootstrap.kpi.gini'),        better: 'asc',  fmt: (v) => v.toFixed(2) },
                    { key: 'p10_fill_ratio',               label: tP('bootstrap.kpi.p10'),         better: 'desc', fmt: (v) => v.toFixed(2) },
                    { key: 'on_time_count',                label: tP('bootstrap.kpi.onTime'),      better: 'desc', fmt: (v) => String(v) },
                    { key: 'manufacturing_total_quantity', label: tP('bootstrap.kpi.mfg'),         better: 'desc', fmt: (v) => qtyFmt(v) },
                    { key: 'inventory_consumed_total',     label: tP('bootstrap.kpi.invConsumed'), better: 'desc', fmt: (v) => qtyFmt(v) },
                  ];
                  const filtered = bootstrapKbSoundOnly
                    ? bootstrapPreview.already_run.filter((p) => p.soundness_status === 'sound')
                    : bootstrapPreview.already_run;
                  const sorted = bootstrapKbSort
                    ? [...filtered].sort((a, b) => {
                        const k = bootstrapKbSort.key as keyof BootstrapPreset;
                        const av = a[k];
                        const bv = b[k];
                        if (av == null && bv == null) return 0;
                        if (av == null) return 1;   // missing always sinks
                        if (bv == null) return -1;
                        const cmp = Number(av) - Number(bv);
                        return bootstrapKbSort.dir === 'asc' ? cmp : -cmp;
                      })
                    : filtered;
                  const handleSortClick = (key: string, defaultDir: 'asc' | 'desc') => {
                    setBootstrapKbSort((prev) => {
                      if (!prev || prev.key !== key) return { key, dir: defaultDir };
                      if (prev.dir === defaultDir) return { key, dir: defaultDir === 'asc' ? 'desc' : 'asc' };
                      return null;
                    });
                  };
                  const sortIndicator = (key: string) => {
                    if (!bootstrapKbSort || bootstrapKbSort.key !== key) return '';
                    return bootstrapKbSort.dir === 'asc' ? ' ↑' : ' ↓';
                  };
                  return (
                    <details style={{ marginTop: '1rem', fontSize: '0.78rem', color: '#71717a' }} open>
                      <summary style={{ cursor: 'pointer' }}>
                        {tP('bootstrap.alreadyRunHeading', { n: bootstrapPreview.already_run.length })}
                      </summary>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', margin: '0.5rem 0', flexWrap: 'wrap' }}>
                        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.72rem', color: '#a1a1aa', cursor: 'pointer' }}>
                          <input
                            type="checkbox"
                            checked={bootstrapKbSoundOnly}
                            onChange={(e) => setBootstrapKbSoundOnly(e.target.checked)}
                          />
                          <span>{tP('bootstrap.kbSoundOnly')}</span>
                        </label>
                        {bootstrapKbSort && (
                          <button
                            type="button"
                            onClick={() => setBootstrapKbSort(null)}
                            style={{ fontSize: '0.7rem', padding: '1px 8px', background: 'transparent', color: '#a1a1aa', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer' }}
                          >{tP('bootstrap.kbClearSort')}</button>
                        )}
                        <span style={{ fontSize: '0.7rem', color: '#52525b', marginLeft: 'auto' }}>
                          {tP('bootstrap.kbHint')}
                        </span>
                      </div>
                      <div style={{ overflowX: 'auto' }}>
                        <table style={{ width: '100%', marginTop: '0.25rem', borderCollapse: 'collapse', fontSize: '0.74rem' }}>
                          <thead>
                            <tr style={{ color: '#a1a1aa', borderBottom: '1px solid #3d3d40' }}>
                              <th style={{ width: 18, padding: '4px 6px' }} />
                              <th style={{ padding: '4px 6px', textAlign: 'left', whiteSpace: 'nowrap' }}>{tP('bootstrap.kbCol.preset')}</th>
                              <th style={{ padding: '4px 6px', textAlign: 'left' }}>{tP('bootstrap.kbCol.axis')}</th>
                              <th style={{ padding: '4px 6px', textAlign: 'left' }}>{tP('bootstrap.kbCol.diff')}</th>
                              {kpiCols.map((c) => (
                                <th key={String(c.key)} style={{ padding: '4px 6px', textAlign: 'right', whiteSpace: 'nowrap' }}>
                                  <button
                                    type="button"
                                    onClick={() => handleSortClick(String(c.key), c.better)}
                                    title={c.better === 'asc' ? tP('bootstrap.kpi.lowerBetter') : tP('bootstrap.kpi.higherBetter')}
                                    style={{ background: 'none', border: 'none', color: bootstrapKbSort?.key === c.key ? '#e4e4e7' : '#a1a1aa', cursor: 'pointer', padding: 0, fontSize: '0.72rem', fontWeight: bootstrapKbSort?.key === c.key ? 600 : 400 }}
                                  >{c.label}{sortIndicator(String(c.key))}</button>
                                </th>
                              ))}
                              <th style={{ padding: '4px 6px' }} />
                              <th style={{ padding: '4px 6px' }} />
                            </tr>
                          </thead>
                          <tbody>
                            {sorted.map((p) => {
                              const expanded = bootstrapExpandedPresets.has(p.preset_id);
                              // Self-describing config summary (no baseline framing) — see
                              // presetConfigSummary above. Storage is full JSON; this is
                              // purely how it's rendered in the row.
                              const summary = presetConfigSummary(p.config);
                              const renderKpi = (k: keyof BootstrapPreset, fmt: (v: number) => string) => {
                                const v = p[k];
                                if (v == null) return <span style={{ color: '#52525b' }}>–</span>;
                                return <span>{fmt(Number(v))}</span>;
                              };
                              return (
                                <React.Fragment key={p.preset_id}>
                                  <tr style={{ borderBottom: expanded ? 'none' : '1px solid #27272a' }}>
                                    <td style={{ padding: '4px 6px', width: 18, cursor: 'pointer', color: '#71717a', verticalAlign: 'top' }}
                                        onClick={() => toggleBootstrapPresetExpanded(p.preset_id)}>
                                      {expanded ? '▾' : '▸'}
                                    </td>
                                    <td style={{ padding: '4px 6px', fontFamily: 'monospace', color: '#a1a1aa', cursor: 'pointer', verticalAlign: 'top', whiteSpace: 'nowrap' }}
                                        onClick={() => toggleBootstrapPresetExpanded(p.preset_id)}>
                                      {p.preset_label}
                                      {(() => {
                                        // Axis-diff count vs the static baseline (cfg() defaults). The
                                        // single-axis property is what the agent uses for clean
                                        // comparative pairs; surface it as a glanceable badge so users
                                        // can quickly find diagnostic-quality rows.
                                        const diffStr = presetDiffSummary(p.config);
                                        const axisCount = diffStr ? diffStr.split(' · ').length : 0;
                                        const tone = axisCount === 0
                                          ? { bg: '#1e3a8a', fg: '#bfdbfe' }   // baseline itself
                                          : axisCount === 1
                                            ? { bg: '#14532d', fg: '#bbf7d0' } // single-axis (curated quality)
                                            : { bg: '#3f3f46', fg: '#a1a1aa' }; // multi-axis
                                        return (
                                          <span title={tP('bootstrap.axisDiffTooltip', { n: axisCount })}
                                            style={{ marginLeft: 4, fontSize: '0.62rem', background: tone.bg, color: tone.fg, borderRadius: 4, padding: '0px 5px' }}>
                                            {tP('bootstrap.axisDiffBadge', { n: axisCount })}
                                          </span>
                                        );
                                      })()}
                                      {p.source_plan_run_deleted && (
                                        <span title={tP('bootstrap.kbSourceDeleted')} style={{ marginLeft: 4, fontSize: '0.62rem', color: '#71717a', fontStyle: 'italic' }}>(orphan)</span>
                                      )}
                                    </td>
                                    <td style={{ padding: '4px 6px', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                                      <span style={{ fontSize: '0.66rem', background: '#3f3f46', borderRadius: 4, padding: '1px 6px', color: '#a1a1aa' }}>{p.primary_axis}</span>
                                    </td>
                                    <td style={{ padding: '4px 6px', fontFamily: 'monospace', fontSize: '0.68rem', color: '#a1a1aa', verticalAlign: 'top' }}>
                                      {summary}
                                    </td>
                                    {kpiCols.map((c) => (
                                      <td key={String(c.key)} style={{ padding: '4px 6px', color: '#a78bfa', textAlign: 'right', verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                                        {renderKpi(c.key, c.fmt)}
                                      </td>
                                    ))}
                                    <td style={{ padding: '4px 6px', verticalAlign: 'top' }}>
                                      {p.soundness_status === 'sound' && <span style={{ color: '#34d399', fontSize: '0.7rem' }}>✓</span>}
                                      {p.soundness_status === 'unsound' && <span style={{ color: '#f87171', fontSize: '0.7rem' }}>✗</span>}
                                      {p.soundness_status === 'unchecked' && <span style={{ color: '#71717a', fontSize: '0.7rem' }}>—</span>}
                                      {p.soundness_status === 'error' && <span style={{ color: '#fbbf24', fontSize: '0.7rem' }}>err</span>}
                                    </td>
                                    <td style={{ padding: '4px 6px', textAlign: 'right', verticalAlign: 'top' }}>
                                      {p.kb_record_id != null && (
                                        <button type="button"
                                          onClick={() => handleDeleteKbRecord(p.kb_record_id!)}
                                          title={tP('bootstrap.kbDeleteTooltip')}
                                          style={{
                                            fontSize: '0.7rem', padding: '1px 6px',
                                            background: 'transparent', color: '#f87171',
                                            border: '1px solid rgba(248,113,113,0.4)', borderRadius: 4,
                                            cursor: 'pointer',
                                          }}>
                                          {tc('delete')}
                                        </button>
                                      )}
                                    </td>
                                  </tr>
                                  {expanded && (
                                    <tr style={{ borderBottom: '1px solid #27272a' }}>
                                      <td colSpan={6 + kpiCols.length} style={{ padding: '0 6px 6px 26px' }}>
                                        <pre style={{
                                          margin: 0, fontSize: '0.66rem', color: '#a1a1aa',
                                          background: '#0a0a0a', padding: '6px 8px', borderRadius: 4,
                                          overflowX: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                                        }}>
                                          {JSON.stringify(p.config, null, 2)}
                                        </pre>
                                      </td>
                                    </tr>
                                  )}
                                </React.Fragment>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                    </details>
                  );
                })()}
              </>
            )}
          </div>
        </div>,
        document.body
      )}
      {planRunHistoryOpen && typeof document !== 'undefined' && createPortal(
        <div style={{ position: 'fixed', inset: 0, zIndex: 9996, display: 'flex', justifyContent: 'flex-end' }} role="dialog" aria-label="Plan run history">
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.5)' }} onClick={() => setPlanRunHistoryOpen(false)} aria-hidden />
          <div style={{ position: 'relative', zIndex: 10, width: planRunHistoryPanelWidth, maxWidth: '90vw', height: '100vh', display: 'flex', flexDirection: 'column', background: '#1c1c1e', color: '#e4e4e7', boxShadow: '-4px 0 24px rgba(0,0,0,0.4)' }}>
            {/* Resize handle */}
            <div
              role="separator"
              aria-label="Resize panel"
              onMouseDown={(e) => { e.preventDefault(); planRunHistoryResizeRef.current = { startX: e.clientX, startW: planRunHistoryPanelWidth }; setPlanRunHistoryResizing(true); }}
              style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 6, cursor: 'col-resize', zIndex: 11 }}
            />
            <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h3 style={{ margin: 0, fontSize: '1rem' }}>{tP('runHistory.panelTitle')}</h3>
              <button type="button" onClick={() => setPlanRunHistoryOpen(false)} style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tP('runHistory.close')}</button>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {planRunHistoryLoading && <p style={{ color: '#71717a' }}>{tP('runHistory.loading')}</p>}
              {!planRunHistoryLoading && planRunHistory.length === 0 && <p style={{ color: '#71717a' }}>{tP('runHistory.empty')}</p>}
              {!planRunHistoryLoading && planRunHistory.length > 0 && (() => {
                const kpiOptions: Array<{ key: keyof PlanRun; label: string; better: 'asc' | 'desc' }> = [
                  { key: 'fill_rate_pct',                label: tP('bootstrap.kpi.fill'),        better: 'desc' },
                  { key: 'gini',                         label: tP('bootstrap.kpi.gini'),        better: 'asc'  },
                  { key: 'p10_fill_ratio',               label: tP('bootstrap.kpi.p10'),         better: 'desc' },
                  { key: 'on_time_count',                label: tP('bootstrap.kpi.onTime'),      better: 'desc' },
                  { key: 'manufacturing_total_quantity', label: tP('bootstrap.kpi.mfg'),         better: 'desc' },
                  { key: 'inventory_consumed_total',     label: tP('bootstrap.kpi.invConsumed'), better: 'desc' },
                ];
                return (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.75rem', flexWrap: 'wrap', fontSize: '0.72rem', color: '#a1a1aa' }}>
                    <span>{tP('runHistory.sortBy')}</span>
                    {kpiOptions.map((opt) => {
                      const k = String(opt.key);
                      const active = planRunHistorySort?.key === k;
                      const ind = active ? (planRunHistorySort?.dir === 'asc' ? ' ↑' : ' ↓') : '';
                      return (
                        <button
                          key={k}
                          type="button"
                          onClick={() => setPlanRunHistorySort((prev) => {
                            if (!prev || prev.key !== k) return { key: k, dir: opt.better };
                            if (prev.dir === opt.better) return { key: k, dir: opt.better === 'asc' ? 'desc' : 'asc' };
                            return null;
                          })}
                          title={opt.better === 'asc' ? tP('bootstrap.kpi.lowerBetter') : tP('bootstrap.kpi.higherBetter')}
                          style={{
                            fontSize: '0.7rem', padding: '1px 8px',
                            background: active ? '#27272a' : 'transparent',
                            color: active ? '#e4e4e7' : '#a1a1aa',
                            border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer',
                            fontWeight: active ? 600 : 400,
                          }}
                        >{opt.label}{ind}</button>
                      );
                    })}
                    <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: '0.5rem', cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={planRunHistorySoundOnly}
                        onChange={(e) => setPlanRunHistorySoundOnly(e.target.checked)}
                      />
                      <span>{tP('bootstrap.kbSoundOnly')}</span>
                    </label>
                    {planRunHistorySort && (
                      <button
                        type="button"
                        onClick={() => setPlanRunHistorySort(null)}
                        style={{ fontSize: '0.7rem', padding: '1px 8px', background: 'transparent', color: '#a1a1aa', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer' }}
                      >{tP('bootstrap.kbClearSort')}</button>
                    )}
                  </div>
                );
              })()}
              {!planRunHistoryLoading && (() => {
                const filtered = planRunHistorySoundOnly
                  ? planRunHistory.filter((r) => r.soundness_status === 'sound')
                  : planRunHistory;
                const sorted = planRunHistorySort
                  ? [...filtered].sort((a, b) => {
                      const k = planRunHistorySort.key as keyof PlanRun;
                      const av = a[k];
                      const bv = b[k];
                      if (av == null && bv == null) return 0;
                      if (av == null) return 1;
                      if (bv == null) return -1;
                      const cmp = Number(av) - Number(bv);
                      return planRunHistorySort.dir === 'asc' ? cmp : -cmp;
                    })
                  : filtered;
                return sorted;
              })().map((run) => {
                const editing = planRunEditing[run.id];
                const isSavingEdit = !!planRunEditSaving[run.id];
                const isActive = run.is_active === true;
                const isInitial = run.is_initial === true;
                const isDesignated = run.is_active_designated === true;
                const isDesignating = !!planRunDesignating[run.id];
                const isOptimal = typeof run.chosen_depth === 'number';
                // Left border priority: green (active) > blue (initial) > amber (optimal-depth).
                // Optimal-depth runs get an amber accent so they're distinguishable at a glance
                // without overriding the active/initial cues, which carry stronger meaning.
                const accent = isActive ? '#4ade80' : isInitial ? '#60a5fa' : isOptimal ? '#f59e0b' : 'transparent';
                const expandedTab = planRunExpandedTab[run.id] ?? null;
                const detail = planRunDetailCache[run.id];
                const detailLoading = !!planRunDetailLoading[run.id];
                return (
                <div key={run.id} style={{
                  borderBottom: '1px solid #27272a',
                  borderLeft: `3px solid ${accent}`,
                  paddingLeft: '0.75rem',
                  paddingBottom: '0.75rem',
                  marginBottom: '0.75rem',
                  background: isActive ? 'rgba(74,222,128,0.04)' : isInitial ? 'rgba(96,165,250,0.04)' : isOptimal ? 'rgba(245,158,11,0.04)' : 'transparent',
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 6 }}>
                      <span style={{ fontSize: '0.85rem', fontWeight: 600, color: run.status === 'success' ? '#4ade80' : run.status === 'failed' ? '#f87171' : run.status === 'contingent' ? '#a78bfa' : '#fbbf24' }}>
                        {run.status}
                      </span>
                      {run.id === currentPlanRunId && (
                        <span title={tP('runHistory.chips.viewingTitle')} style={{ background: '#1e40af', color: '#dbeafe', borderRadius: 8, padding: '1px 7px', fontSize: '0.7rem', fontWeight: 600 }}>
                          {tP('runHistory.chips.viewing')}
                        </span>
                      )}
                      {isInitial && (
                        <span title={tP('runHistory.chips.initialTitle')} style={{ background: '#1e3a8a', color: '#bfdbfe', borderRadius: 8, padding: '1px 7px', fontSize: '0.7rem', fontWeight: 600 }}>
                          {tP('runHistory.chips.initial')}
                        </span>
                      )}
                      {run.metadata && (run.metadata as { bootstrap?: unknown }).bootstrap === true && (
                        <span
                          title={(run.metadata as { preset_label?: string; primary_axis?: string }).preset_label
                            ? `bootstrap · ${(run.metadata as { preset_label?: string; primary_axis?: string }).preset_label} (${(run.metadata as { preset_label?: string; primary_axis?: string }).primary_axis ?? '—'})`
                            : 'bootstrap'}
                          style={{ background: '#3b0764', color: '#e9d5ff', borderRadius: 8, padding: '1px 7px', fontSize: '0.7rem', fontWeight: 600 }}
                        >
                          📚 {tP('bootstrap.badge')}
                        </span>
                      )}
                      {isActive && (
                        <span
                          title={isDesignated ? tP('runHistory.chips.activeDesignatedTitle') : tP('runHistory.chips.activeLatestTitle')}
                          style={{ background: '#14532d', color: '#bbf7d0', borderRadius: 8, padding: '1px 7px', fontSize: '0.7rem', fontWeight: 600 }}
                        >
                          {isDesignated ? tP('runHistory.chips.activeDesignated') : tP('runHistory.chips.active')}
                        </span>
                      )}
                      {typeof run.chosen_depth === 'number' && (
                        <span
                          title={tP('runHistory.chosenDepthTitle')}
                          style={{
                            fontSize: '0.75rem',
                            fontWeight: 600,
                            color: '#fef3c7',
                            background: '#92400e',
                            border: '1px solid #f59e0b',
                            borderRadius: 8,
                            padding: '1px 8px',
                          }}
                        >
                          {tP('runHistory.chosenDepthChip', { depth: run.chosen_depth })}
                        </span>
                      )}
                      <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>
                        {new Date(run.created_at).toLocaleString()}
                      </span>
                      {typeof run.duration_ms === 'number' && run.duration_ms >= 0 && (() => {
                        const attempts = run.attempts ?? null;
                        const hasAttempts = Array.isArray(attempts) && attempts.length > 0;
                        const elapsedTitle = hasAttempts
                          ? [
                              tP('runHistory.elapsedBreakdownTitle'),
                              ...attempts.map((a) => `  depth ${a.depth}: ${formatElapsedMs(a.duration_ms)}`),
                              tP('runHistory.elapsedBreakdownTotal', {
                                ms: formatElapsedMs(attempts.reduce((s, a) => s + a.duration_ms, 0)),
                              }),
                            ].join('\n')
                          : tP('runHistory.elapsedTitle');
                        return (
                          <span
                            title={elapsedTitle}
                            style={{ fontSize: '0.75rem', color: '#a1a1aa', background: '#27272a', borderRadius: 8, padding: '1px 7px' }}
                          >
                            {formatElapsedMs(run.duration_ms)}
                          </span>
                        );
                      })()}
                      {run.override_count > 0 && (
                        <span style={{ background: '#7c3aed', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.72rem' }}>
                          {tP('runHistory.overrideCount', { n: run.override_count })}
                        </span>
                      )}
                      {run.soundness_status && run.soundness_status !== 'unchecked' && (() => {
                        const s = run.soundness_status;
                        const palette: Record<string, { bg: string; fg: string; label: string }> = {
                          checking: { bg: '#1e40af', fg: '#dbeafe', label: 'checking…' },
                          sound:    { bg: '#14532d', fg: '#bbf7d0', label: 'sound' },
                          unsound:  { bg: '#7f1d1d', fg: '#fecaca', label: 'unsound' },
                          error:    { bg: '#78350f', fg: '#fed7aa', label: 'check error' },
                        };
                        const p = palette[s] ?? { bg: '#27272a', fg: '#a1a1aa', label: s };
                        const clickable = s === 'sound' || s === 'unsound';
                        return (
                          <span
                            title={run.soundness_checked_at ? `Soundness ${s} (checked ${new Date(run.soundness_checked_at).toLocaleString()})` : `Soundness: ${s}`}
                            onClick={async () => {
                              if (!clickable || !id) return;
                              try {
                                const report = await checkPlanRunSoundness(id, run.id, { deep_check: true });
                                setSoundnessReportOpen({ runId: run.id, report });
                              } catch {/* noop */}
                            }}
                            style={{ background: p.bg, color: p.fg, borderRadius: 8, padding: '1px 7px', fontSize: '0.72rem', fontWeight: 600, cursor: clickable ? 'pointer' : 'default' }}
                          >
                            {p.label}
                          </span>
                        );
                      })()}
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                      {(run.status === 'success' || run.status === 'contingent') && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          disabled={planRunLoadingId === run.id}
                          onClick={() => handleRestorePlanRun(run.id)}
                        >
                          {planRunLoadingId === run.id ? tP('runHistory.actions.loading') : tP('runHistory.load')}
                        </button>
                      )}
                      {(run.status === 'success' || run.status === 'contingent') && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          disabled={!!soundnessChecking[run.id] || run.soundness_status === 'checking'}
                          title="Validate this plan run's pegging trees against the spec.md soundness rules"
                          onClick={async () => {
                            if (!id) return;
                            setSoundnessChecking((prev) => ({ ...prev, [run.id]: true }));
                            try {
                              const report = await checkPlanRunSoundness(id, run.id, { deep_check: true });
                              setSoundnessReportOpen({ runId: run.id, report });
                              // Refresh history list so the badge reflects the new state.
                              const updated = await listPlanRuns(id);
                              setPlanRunHistory(updated);
                            } catch (e) {
                              setSoundnessReportOpen({ runId: run.id, report: { overall_sound: false, demand_count: 0, sound_count: 0, deep_check: true, demands: [], cross_demand_violations: [{ rule: 'check_failed', node_path: '', message: e instanceof Error ? e.message : 'Soundness check failed.' }] } });
                            } finally {
                              setSoundnessChecking((prev) => { const n = { ...prev }; delete n[run.id]; return n; });
                            }
                          }}
                        >
                          {soundnessChecking[run.id] ? 'checking…' : 'check soundness'}
                        </button>
                      )}
                      {/* Set active: only for successful runs that aren't already active */}
                      {run.status === 'success' && !isActive && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          disabled={isDesignating}
                          onClick={() => handleDesignateActive(run.id)}
                          title={tP('runHistory.actions.setActiveTitle')}
                        >
                          {isDesignating ? tP('runHistory.actions.working') : tP('runHistory.actions.setActive')}
                        </button>
                      )}
                      {/* Unpin: only when this run is the pinned (designated) active */}
                      {isDesignated && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          disabled={isDesignating}
                          onClick={() => handleUnpinActive(run.id)}
                          title={tP('runHistory.actions.unpinTitle')}
                        >
                          {isDesignating ? tP('runHistory.actions.working') : tP('runHistory.actions.unpin')}
                        </button>
                      )}
                      {!editing && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          onClick={() => setPlanRunEditing((prev) => ({ ...prev, [run.id]: { name: run.name ?? '', notes: run.notes ?? '' } }))}
                        >
                          {tP('runHistory.edit')}
                        </button>
                      )}
                      <button
                        type="button"
                        className="secondary"
                        style={{ fontSize: '0.8rem', padding: '3px 10px', color: '#f87171', borderColor: '#f87171' }}
                        onClick={() => handleDeletePlanRun(run.id)}
                      >
                        {tP('runHistory.delete')}
                      </button>
                    </div>
                  </div>
                  {/* KPI strip — compact summary of the headline metrics from the run's
                      result snapshot. Only shown when the run has KPI data. */}
                  {(run.fill_rate_pct != null || run.gini != null || run.on_time_count != null) && (
                    <div style={{ display: 'flex', gap: '0.75rem', marginTop: 4, fontSize: '0.72rem', color: '#a1a1aa', flexWrap: 'wrap' }}>
                      {run.fill_rate_pct != null && (
                        <span><span style={{ color: '#71717a' }}>{tP('bootstrap.kpi.fill')}</span> <span style={{ color: '#a78bfa' }}>{run.fill_rate_pct.toFixed(1)}%</span></span>
                      )}
                      {run.gini != null && (
                        <span><span style={{ color: '#71717a' }}>{tP('bootstrap.kpi.gini')}</span> <span style={{ color: '#a78bfa' }}>{run.gini.toFixed(2)}</span></span>
                      )}
                      {run.p10_fill_ratio != null && (
                        <span><span style={{ color: '#71717a' }}>{tP('bootstrap.kpi.p10')}</span> <span style={{ color: '#a78bfa' }}>{run.p10_fill_ratio.toFixed(2)}</span></span>
                      )}
                      {run.on_time_count != null && (
                        <span><span style={{ color: '#71717a' }}>{tP('bootstrap.kpi.onTime')}</span> <span style={{ color: '#a78bfa' }}>{run.on_time_count}</span></span>
                      )}
                      {run.manufacturing_total_quantity != null && (
                        <span><span style={{ color: '#71717a' }}>{tP('bootstrap.kpi.mfg')}</span> <span style={{ color: '#a78bfa' }}>{qtyFmt(run.manufacturing_total_quantity)}</span></span>
                      )}
                      {run.inventory_consumed_total != null && (
                        <span><span style={{ color: '#71717a' }}>{tP('bootstrap.kpi.invConsumed')}</span> <span style={{ color: '#a78bfa' }}>{qtyFmt(run.inventory_consumed_total)}</span></span>
                      )}
                    </div>
                  )}
                  {/* Name display / edit */}
                  {editing ? (
                    <div style={{ marginTop: '0.5rem', display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <input
                        type="text"
                        placeholder={tP('runHistory.namePlaceholder')}
                        value={editing.name}
                        onChange={(e) => setPlanRunEditing((prev) => ({ ...prev, [run.id]: { ...prev[run.id], name: e.target.value } }))}
                        style={{ padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
                      />
                      <textarea
                        placeholder={tP('runHistory.notesPlaceholder')}
                        value={editing.notes}
                        rows={2}
                        onChange={(e) => setPlanRunEditing((prev) => ({ ...prev, [run.id]: { ...prev[run.id], notes: e.target.value } }))}
                        style={{ padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem', resize: 'vertical' }}
                      />
                      <div style={{ display: 'flex', gap: 6 }}>
                        <button
                          type="button"
                          disabled={isSavingEdit}
                          onClick={async () => {
                            if (!id) return;
                            setPlanRunEditSaving((prev) => ({ ...prev, [run.id]: true }));
                            try {
                              await updatePlanRun(id, run.id, { name: editing.name.trim() || undefined, notes: editing.notes.trim() || undefined });
                              setPlanRunHistory((prev) => prev.map((r) => r.id === run.id ? { ...r, name: editing.name.trim() || null, notes: editing.notes.trim() || null } : r));
                              setPlanRunEditing((prev) => { const n = { ...prev }; delete n[run.id]; return n; });
                            } catch {
                              // keep edit open on error
                            } finally {
                              setPlanRunEditSaving((prev) => { const n = { ...prev }; delete n[run.id]; return n; });
                            }
                          }}
                          style={{ padding: '3px 10px', background: '#3b82f6', color: '#fff', border: 'none', borderRadius: 4, fontSize: '0.8rem', cursor: isSavingEdit ? 'wait' : 'pointer' }}
                        >
                          {isSavingEdit ? tP('runHistory.saving') : tP('runHistory.save')}
                        </button>
                        <button
                          type="button"
                          onClick={() => setPlanRunEditing((prev) => { const n = { ...prev }; delete n[run.id]; return n; })}
                          style={{ padding: '3px 10px', background: 'transparent', color: '#a1a1aa', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.8rem', cursor: 'pointer' }}
                        >
                          {tP('runHistory.cancel')}
                        </button>
                      </div>
                    </div>
                  ) : (run.name || run.notes) ? (
                    <div style={{ marginTop: '0.3rem' }}>
                      {run.name && <p style={{ margin: 0, fontSize: '0.85rem', color: '#e4e4e7', fontWeight: 500 }}>{run.name}</p>}
                      {run.notes && <p style={{ margin: '0.15rem 0 0', fontSize: '0.78rem', color: '#a1a1aa', whiteSpace: 'pre-wrap' }}>{run.notes}</p>}
                    </div>
                  ) : null}
                  <div style={{ fontSize: '0.75rem', color: '#71717a', marginTop: '0.35rem' }}>
                    {tP('runHistory.runLabel', { id: run.id })}{run.job_id ? tP('runHistory.jobSuffix', { job: run.job_id.slice(0, 8) }) : ''}
                  </div>
                  {/* Tabbed expansion: Config / Overrides / Events */}
                  <div style={{ marginTop: '0.4rem', display: 'flex', gap: 4 }}>
                    {(['config', 'overrides', 'events'] as const).map((tab) => {
                      const open = expandedTab === tab;
                      const label = tab === 'config' ? tP('runHistory.tabs.config')
                        : tab === 'overrides' ? (run.override_count ? tP('runHistory.tabs.overridesWithCount', { count: run.override_count }) : tP('runHistory.tabs.overrides'))
                        : tP('runHistory.tabs.events');
                      return (
                        <button
                          key={tab}
                          type="button"
                          onClick={() => handleTogglePlanRunTab(run.id, tab)}
                          style={{
                            fontSize: '0.72rem',
                            padding: '2px 8px',
                            background: open ? '#3d3d40' : 'transparent',
                            color: open ? '#e4e4e7' : '#a1a1aa',
                            border: '1px solid #3d3d40',
                            borderRadius: 4,
                            cursor: 'pointer',
                          }}
                        >
                          {label}
                        </button>
                      );
                    })}
                  </div>
                  {expandedTab === 'config' && (
                    run.config && Object.keys(run.config).length > 0 ? (
                      <pre style={{ margin: '0.4rem 0 0', fontSize: '0.7rem', color: '#a1a1aa', whiteSpace: 'pre-wrap', background: '#111113', padding: '0.5rem', borderRadius: 4 }}>{JSON.stringify(run.config, null, 2)}</pre>
                    ) : (
                      <p style={{ margin: '0.4rem 0 0', fontSize: '0.72rem', color: '#71717a' }}>{tP('runHistory.details.noConfig')}</p>
                    )
                  )}
                  {expandedTab === 'overrides' && (
                    detailLoading ? (
                      <p style={{ margin: '0.4rem 0 0', fontSize: '0.72rem', color: '#71717a' }}>{tP('runHistory.loading')}</p>
                    ) : (() => {
                      const snap = detail?.override_snapshot ?? null;
                      if (!snap || snap.length === 0) return <p style={{ margin: '0.4rem 0 0', fontSize: '0.72rem', color: '#71717a' }}>{tP('runHistory.details.noOverrides')}</p>;
                      return (
                        <div style={{ marginTop: '0.4rem', background: '#111113', padding: '0.5rem', borderRadius: 4, overflowX: 'auto' }}>
                          <table style={{ width: '100%', fontSize: '0.7rem', color: '#a1a1aa', borderCollapse: 'collapse' }}>
                            <thead>
                              <tr style={{ textAlign: 'left', borderBottom: '1px solid #3d3d40' }}>
                                <th style={{ padding: '2px 6px' }}>{tP('runHistory.details.overrideHeaderType')}</th>
                                <th style={{ padding: '2px 6px' }}>{tP('runHistory.details.overrideHeaderKey')}</th>
                                <th style={{ padding: '2px 6px' }}>{tP('runHistory.details.overrideHeaderPayload')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {snap.map((o) => (
                                <tr key={o.id} style={{ borderBottom: '1px solid #1f1f22' }}>
                                  <td style={{ padding: '2px 6px', whiteSpace: 'nowrap' }}>{o.entity_type}</td>
                                  <td style={{ padding: '2px 6px', whiteSpace: 'nowrap' }}>{o.entity_key}</td>
                                  <td style={{ padding: '2px 6px', fontFamily: 'monospace', fontSize: '0.68rem' }}>{JSON.stringify(o.payload)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      );
                    })()
                  )}
                  {expandedTab === 'events' && (
                    detailLoading ? (
                      <p style={{ margin: '0.4rem 0 0', fontSize: '0.72rem', color: '#71717a' }}>{tP('runHistory.loading')}</p>
                    ) : (() => {
                      const events: PlanRunEvent[] = detail?.events ?? [];
                      if (events.length === 0) return <p style={{ margin: '0.4rem 0 0', fontSize: '0.72rem', color: '#71717a' }}>{tP('runHistory.details.noEvents')}</p>;
                      const kindLabel = (k: string) =>
                        k === 'created' ? tP('runHistory.events.created')
                        : k === 'saved' ? tP('runHistory.events.saved')
                        : k === 'renamed' ? tP('runHistory.events.renamed')
                        : k === 'promoted' ? tP('runHistory.events.promoted')
                        : k === 'designated_active' ? tP('runHistory.events.designatedActive')
                        : k === 'undesignated' ? tP('runHistory.events.undesignated')
                        : k === 'overridden' ? tP('runHistory.events.overridden')
                        : k;
                      return (
                        <ol style={{ margin: '0.4rem 0 0', padding: 0, listStyle: 'none' }}>
                          {events.map((ev) => (
                            <li key={ev.id} style={{ fontSize: '0.72rem', color: '#a1a1aa', padding: '2px 0', borderLeft: '2px solid #3d3d40', paddingLeft: 8, marginLeft: 2 }}>
                              <span style={{ color: '#e4e4e7', fontWeight: 500 }}>{kindLabel(ev.kind)}</span>
                              <span style={{ marginLeft: 6, color: '#71717a' }}>{new Date(ev.created_at).toLocaleString()}</span>
                              {ev.payload && Object.keys(ev.payload).length > 0 && (
                                <div style={{ marginTop: 2, fontFamily: 'monospace', fontSize: '0.68rem', color: '#6b7280' }}>
                                  {JSON.stringify(ev.payload)}
                                </div>
                              )}
                            </li>
                          ))}
                        </ol>
                      );
                    })()
                  )}
                </div>
                );
              })}
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ── Soundness report slide-in ─────────────────────────────────────────── */}
      {soundnessReportOpen && typeof document !== 'undefined' && createPortal(
        <div style={{ position: 'fixed', inset: 0, zIndex: 9997, display: 'flex', justifyContent: 'flex-end' }} role="dialog" aria-label="Soundness report">
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.5)' }} onClick={() => setSoundnessReportOpen(null)} aria-hidden />
          <div style={{ position: 'relative', zIndex: 10, width: 720, maxWidth: '90vw', height: '100vh', display: 'flex', flexDirection: 'column', background: '#1c1c1e', color: '#e4e4e7', boxShadow: '-4px 0 24px rgba(0,0,0,0.4)' }}>
            <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <h3 style={{ margin: 0, fontSize: '1rem' }}>
                  Soundness — Run #{soundnessReportOpen.runId}
                  <span style={{
                    marginLeft: 10,
                    fontSize: '0.78rem',
                    fontWeight: 600,
                    padding: '2px 8px',
                    borderRadius: 8,
                    background: soundnessReportOpen.report.overall_sound ? '#14532d' : '#7f1d1d',
                    color: soundnessReportOpen.report.overall_sound ? '#bbf7d0' : '#fecaca',
                  }}>
                    {soundnessReportOpen.report.overall_sound ? 'sound' : 'unsound'}
                  </span>
                </h3>
                <p style={{ margin: '4px 0 0', fontSize: '0.78rem', color: '#a1a1aa' }}>
                  {soundnessReportOpen.report.sound_count} / {soundnessReportOpen.report.demand_count} demands sound
                  {soundnessReportOpen.report.deep_check && ' · deep check'}
                  {soundnessReportOpen.report.cross_demand_violations.length > 0 && ` · ${soundnessReportOpen.report.cross_demand_violations.length} cross-demand violation(s)`}
                </p>
              </div>
              <button type="button" onClick={() => setSoundnessReportOpen(null)} style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {soundnessReportOpen.report.cross_demand_violations.length > 0 && (
                <div style={{ marginBottom: '1rem' }}>
                  <h4 style={{ margin: '0 0 0.5rem', fontSize: '0.85rem', color: '#fca5a5' }}>Cross-demand violations</h4>
                  {soundnessReportOpen.report.cross_demand_violations.map((v, i) => (
                    <div key={i} style={{ background: '#2d1818', border: '1px solid #7f1d1d', borderRadius: 6, padding: '8px 10px', marginBottom: 6, fontSize: '0.8rem' }}>
                      <div style={{ fontWeight: 600, color: '#fca5a5' }}>{v.rule} · <span style={{ color: '#a1a1aa', fontWeight: 400 }}>{v.node_path}</span></div>
                      <div style={{ color: '#e4e4e7', marginTop: 2 }}>{v.message}</div>
                      {(v.expected !== undefined || v.actual !== undefined) && (
                        <div style={{ color: '#a1a1aa', marginTop: 2, fontSize: '0.74rem' }}>
                          expected: {String(v.expected)} · actual: {String(v.actual)}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
              <h4 style={{ margin: '0 0 0.5rem', fontSize: '0.85rem' }}>Per-demand</h4>
              {soundnessReportOpen.report.demands.map((d) => (
                <div key={d.demand_id} style={{
                  borderLeft: `3px solid ${d.sound ? '#4ade80' : '#f87171'}`,
                  paddingLeft: '0.6rem',
                  marginBottom: '0.6rem',
                  background: d.sound ? 'transparent' : 'rgba(248,113,113,0.06)',
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: '0.78rem', fontWeight: 600, color: d.sound ? '#4ade80' : '#f87171' }}>
                      {d.sound ? '✓' : '✗'}
                    </span>
                    <span style={{ fontSize: '0.85rem' }}>{d.demand_id}</span>
                    {!d.sound && <span style={{ fontSize: '0.74rem', color: '#a1a1aa' }}>· {d.violations.length} violation(s)</span>}
                  </div>
                  {d.violations.map((v, i) => (
                    <div key={i} style={{ marginLeft: '1rem', marginTop: 4, padding: '6px 8px', background: '#27272a', borderRadius: 4, fontSize: '0.78rem' }}>
                      <div style={{ color: '#fca5a5', fontWeight: 600 }}>{v.rule} · <span style={{ color: '#71717a', fontWeight: 400 }}>{v.node_path}</span></div>
                      <div style={{ color: '#d4d4d8', marginTop: 2 }}>{v.message}</div>
                      {(v.expected !== undefined || v.actual !== undefined) && (
                        <div style={{ color: '#a1a1aa', marginTop: 2, fontSize: '0.72rem' }}>
                          expected: {String(v.expected)} · actual: {String(v.actual)}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ── Override dialog ────────────────────────────────────────────────────── */}
      {overrideDialogOpen && (overrideDialogWo || overrideDialogSupply) && typeof document !== 'undefined' && createPortal(
        <div style={{ position: 'fixed', inset: 0, zIndex: 10000, display: 'flex', alignItems: 'center', justifyContent: 'center' }} role="dialog" aria-label="Override dialog">
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.6)' }} onClick={() => setOverrideDialogOpen(false)} aria-hidden />
          <div style={{ position: 'relative', zIndex: 10, width: overrideDialogType === 'supply_split' ? 640 : 480, maxWidth: '92vw', maxHeight: '90vh', background: '#1c1c1e', color: '#e4e4e7', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,0.5)', padding: '1.5rem', boxSizing: 'border-box', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
            <div style={{ flexShrink: 0 }}>
              <h3 style={{ margin: '0 0 0.25rem', fontSize: '1rem' }}>
                {overrideDialogType === 'method_selection' ? tP('overrideDialog.titleMethod')
                  : overrideDialogType === 'supply_split' ? tP('supplyView.overrideSplitTitle')
                  : tP('overrideDialog.titleComponentSplit')}
              </h3>
              {overrideDialogType === 'supply_split' && overrideDialogSupply ? (
                <p style={{ margin: '0 0 1rem', fontSize: '0.8rem', color: '#a1a1aa' }}>
                  <strong>{overrideDialogSupply.supplyId}</strong> · {overrideDialogSupply.productId} @ {overrideDialogSupply.locationId}
                  {overrideDialogSupply.supplyDate && <> · {overrideDialogSupply.supplyDate}</>}
                  {' · '}{tP('woExplain.qtyLabel')}{' '}{qtyFmt(Number(overrideDialogSupply.qty))}
                </p>
              ) : overrideDialogWo && (
                <p style={{ margin: '0 0 1rem', fontSize: '0.8rem', color: '#a1a1aa' }}>
                  <strong>{overrideDialogWo.product_id}</strong> @ {overrideDialogWo.location_id}
                  {overrideDialogWo.demand_id && <> · {tP('woExplain.peggedDemand')} {overrideDialogWo.demand_id}</>}
                  {overrideDialogWo.start_time && <> · {overrideDialogWo.start_time.slice(0, 10)}</>}
                </p>
              )}
            </div>
            {/* Scrollable body — accommodates long demand lists in component/supply split forms. */}
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', margin: '0 -1.5rem', padding: '0 1.5rem' }}>
            {/* ── Method selection form ─── */}
            {overrideDialogType === 'method_selection' && overrideDialogWo && (
              <>
                {overrideDialogWo.wo_explanation_method && (
                  <div style={{ marginBottom: '1rem', padding: '0.6rem 0.75rem', background: '#27272a', borderRadius: 6, borderLeft: '3px solid #a78bfa' }}>
                    <div style={{ fontSize: '0.7rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '0.3rem' }}>{tP('overrideDialog.autoSelectedReason')}</div>
                    <p style={{ margin: 0, fontSize: '0.8rem', color: '#d4d4d8', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{overrideDialogWo.wo_explanation_method}</p>
                  </div>
                )}
                <label style={{ display: 'block', fontSize: '0.85rem', color: '#e4e4e7', marginBottom: '0.4rem' }}>{tP('overrideDialog.forceMethod')}</label>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  {(overrideDialogWo?.wo_explanation_method
                    ? Array.from(new Set(Array.from(overrideDialogWo.wo_explanation_method.matchAll(/\b(make|move|buy)\b/gi), (match) => match[1].toLowerCase())))
                    : ['make', 'move', 'buy']
                  ).map((m) => (
                    <label key={m} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '0.5rem 0.9rem', borderRadius: 6, border: `1px solid ${overrideMethodValue === m ? '#a78bfa' : '#3d3d40'}`, background: overrideMethodValue === m ? 'rgba(167,139,250,0.12)' : '#27272a', cursor: 'pointer', fontSize: '0.85rem' }}>
                      <input type="radio" name="override_method" value={m} checked={overrideMethodValue === m} onChange={() => setOverrideMethodValue(m)} style={{ accentColor: '#a78bfa' }} />
                      {m}
                    </label>
                  ))}
                </div>
                <p style={{ fontSize: '0.75rem', color: '#71717a', marginTop: '0.5rem' }}>{tP('overrideDialog.methodOverrideHelpPre')}<strong>{overrideDialogWo.product_id} @ {overrideDialogWo.location_id}</strong>{tP('overrideDialog.methodOverrideHelpPost')}</p>
              </>
            )}
            {/* ── Component split form ─── */}
            {overrideDialogType === 'component_split' && overrideDialogWo && (() => {
              const totalPlanned = overrideDialogWo.wo_consolidation_total_planned ?? 0;
              const sumQty = overrideSplitRows.reduce((s, r) => s + (Number(r.qty) || 0), 0);
              const over = sumQty > totalPlanned + 0.001;
              return (
                <>
                  <div style={{ marginBottom: '0.75rem', display: 'flex', gap: '1.5rem', fontSize: '0.8rem', color: '#a1a1aa' }}>
                    <span>{tP('overrideDialog.totalPlanned')}: <strong style={{ color: '#e4e4e7' }}>{qtyFmt(totalPlanned)}</strong></span>
                    <span>{tP('overrideDialog.splitMode')}: <strong style={{ color: '#e4e4e7' }}>{overrideDialogWo.wo_consolidation_split_mode === 'proportional' ? tP('overrideDialog.splitProportional') : overrideDialogWo.wo_consolidation_split_mode === 'priority_first' ? tP('overrideDialog.splitPriorityFirst') : tP('overrideDialog.splitFair')}</strong></span>
                  </div>
                  <table style={{ width: '100%', fontSize: '0.82rem', borderCollapse: 'collapse', marginBottom: '0.5rem' }}>
                    <thead>
                      <tr style={{ color: '#71717a', textAlign: 'left', borderBottom: '1px solid #3d3d40' }}>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem' }}>{tP('overrideDialog.colDemand')}</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem' }}>{tP('overrideDialog.colParentProduct')}</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem', textAlign: 'right' }}>{tP('overrideDialog.colPri')}</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem', textAlign: 'right' }}>{tP('overrideDialog.colRequested')}</th>
                        <th style={{ paddingBottom: '0.3rem', textAlign: 'right' }}>{tP('overrideDialog.colOverrideQty')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overrideSplitRows.map((row, i) => (
                        <tr key={i} style={{ borderTop: '1px solid #27272a' }}>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', color: '#d4d4d8' }}>{row.demand_id ?? '–'}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', color: '#a1a1aa', fontSize: '0.78rem' }}>{row.parent_product}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', textAlign: 'right' }}>{row.priority}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', textAlign: 'right', color: '#71717a' }}>{qtyFmt(row.requested_qty)}</td>
                          <td style={{ padding: '0.3rem 0', textAlign: 'right' }}>
                            <input
                              type="number"
                              min={0}
                              step="any"
                              value={row.qty}
                              onChange={(e) => setOverrideSplitRows((prev) => prev.map((r, j) => j === i ? { ...r, qty: parseFloat(e.target.value) || 0 } : r))}
                              style={{ width: 80, padding: '2px 6px', background: '#1c1c1e', border: `1px solid ${over ? '#f87171' : '#3d3d40'}`, borderRadius: 4, color: '#fafafa', fontSize: '0.82rem', textAlign: 'right' }}
                            />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr style={{ borderTop: '2px solid #3d3d40' }}>
                        <td colSpan={4} style={{ paddingTop: '0.3rem', color: '#a1a1aa', fontSize: '0.78rem', textAlign: 'right', paddingRight: '0.5rem' }}>{tP('overrideDialog.sum')}</td>
                        <td style={{ paddingTop: '0.3rem', textAlign: 'right', fontWeight: 600, color: over ? '#f87171' : sumQty <= totalPlanned + 0.001 ? '#4ade80' : '#e4e4e7' }}>
                          {qtyFmt(sumQty)}
                          {over && <span style={{ marginLeft: 4, fontSize: '0.7rem', color: '#f87171' }}>{tP('overrideDialog.exceedsTotal')}</span>}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                  <p style={{ fontSize: '0.75rem', color: '#71717a', margin: '0.25rem 0 0' }}>{tP('overrideDialog.componentSplitHelp')}</p>
                </>
              );
            })()}
            {/* ── Supply split form ─── */}
            {overrideDialogType === 'supply_split' && overrideDialogSupply && (() => {
              const supplyQty = overrideDialogSupply.qty ?? 0;
              const sumQty = overrideSupplyRows.reduce((s, r) => s + (Number(r.qty) || 0), 0);
              const over = sumQty > supplyQty + 0.001;
              const warn = !!overrideDialogSupply.override?.warning;
              return (
                <>
                  <p style={{ fontSize: '0.78rem', color: '#a1a1aa', margin: '0 0 0.6rem' }}>
                    {tP('supplyView.overrideInfo')}
                  </p>
                  {warn && (
                    <div style={{ marginBottom: '0.75rem', padding: '0.5rem 0.75rem', background: 'rgba(248,113,113,0.12)', border: '1px solid rgba(248,113,113,0.4)', borderRadius: 6 }}>
                      <span style={{ color: '#f87171', fontSize: '0.78rem' }}>{tP('supplyView.overrideWarnBanner')}</span>
                    </div>
                  )}
                  <table style={{ width: '100%', fontSize: '0.82rem', borderCollapse: 'collapse', marginBottom: '0.5rem', tableLayout: 'fixed' }}>
                    <colgroup>
                      <col style={{ width: '32%' }} />
                      <col style={{ width: '22%' }} />
                      <col style={{ width: '14%' }} />
                      <col style={{ width: '14%' }} />
                      <col style={{ width: '18%' }} />
                    </colgroup>
                    <thead>
                      <tr style={{ color: '#71717a', textAlign: 'left', borderBottom: '1px solid #3d3d40' }}>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem' }}>{tP('overrideDialog.colDemand')}</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem' }}>{tP('overrideDialog.colCustomer')}</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem', textAlign: 'right' }}>{tP('overrideDialog.colRequested')}</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem', textAlign: 'right' }}>{tP('overrideDialog.colCurrent')}</th>
                        <th style={{ paddingBottom: '0.3rem', textAlign: 'right' }}>{tP('overrideDialog.colOverrideQty')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overrideSupplyRows.map((row, i) => (
                        <tr key={row.demand_id} style={{ borderTop: '1px solid #27272a' }}>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', color: '#d4d4d8', overflowWrap: 'anywhere', wordBreak: 'break-all' }}>{row.demand_id}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', color: '#a1a1aa', fontSize: '0.78rem', overflowWrap: 'anywhere' }}>{row.customer ?? '–'}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', textAlign: 'right', color: '#71717a' }}>{row.requested_qty > 0 ? qtyFmt(row.requested_qty) : '–'}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', textAlign: 'right', color: '#71717a' }}>{qtyFmt(row.consumed_qty)}</td>
                          <td style={{ padding: '0.3rem 0', textAlign: 'right' }}>
                            <input
                              type="number"
                              min={0}
                              step="any"
                              value={row.qty}
                              onChange={(e) => setOverrideSupplyRows((prev) => prev.map((r, j) => j === i ? { ...r, qty: parseFloat(e.target.value) || 0 } : r))}
                              style={{ width: '100%', boxSizing: 'border-box', padding: '2px 6px', background: '#1c1c1e', border: `1px solid ${over ? '#f87171' : '#3d3d40'}`, borderRadius: 4, color: '#fafafa', fontSize: '0.82rem', textAlign: 'right' }}
                            />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr style={{ borderTop: '2px solid #3d3d40' }}>
                        <td colSpan={4} style={{ paddingTop: '0.3rem', color: '#a1a1aa', fontSize: '0.78rem', textAlign: 'right', paddingRight: '0.5rem' }}>{tP('overrideDialog.sumSupplyLabel')}</td>
                        <td style={{ paddingTop: '0.3rem', textAlign: 'right', fontWeight: 600, color: over ? '#f87171' : '#e4e4e7' }}>
                          {qtyFmt(sumQty)} / {qtyFmt(supplyQty)}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                  <p style={{ fontSize: '0.75rem', color: '#71717a', margin: '0.25rem 0 0' }}>{tP('overrideDialog.supplySplitHelp')}</p>
                </>
              );
            })()}
            </div>
            {/* Pinned footer: error message + reset banner + Cancel/Save buttons. */}
            <div style={{ flexShrink: 0, paddingTop: '0.75rem' }}>
            {overrideDialogError && <p style={{ color: '#f87171', fontSize: '0.8rem', marginTop: '0.4rem' }}>{overrideDialogError}</p>}
            {(() => {
              if (!overrideDialogType) return null;
              let entityKey: string;
              if (overrideDialogType === 'supply_split') {
                if (!overrideDialogSupply) return null;
                entityKey = overrideDialogSupply.supplyId;
              } else {
                if (!overrideDialogWo) return null;
                const productId = overrideDialogWo.product_id ?? '';
                const locationId = overrideDialogWo.location_id ?? '';
                const demandId = overrideDialogWo.demand_id ?? '';
                entityKey = overrideDialogType === 'component_split'
                  ? (overrideDialogWo.start_time ? `${productId}|${locationId}|${overrideDialogWo.start_time.slice(0, 10)}` : `${productId}|${locationId}`)
                  : (demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`);
              }
              const existingOverride = overrides.find((o) => o.entity_type === overrideDialogType && o.entity_key === entityKey) ?? null;
              if (!existingOverride) return null;
              return (
                <div style={{ marginTop: '0.75rem', padding: '0.5rem 0.75rem', background: 'rgba(180,83,9,0.12)', border: '1px solid rgba(180,83,9,0.4)', borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                  <span style={{ fontSize: '0.8rem', color: '#fbbf24' }}>{tP('overrideDialog.alreadySaved')}</span>
                  <button
                    type="button"
                    className="secondary"
                    disabled={overrideDialogSaving}
                    style={{ fontSize: '0.8rem', padding: '3px 10px', color: '#f87171', borderColor: 'rgba(248,113,113,0.4)' }}
                    onClick={async () => {
                      setOverrideDialogSaving(true);
                      setOverrideDialogError(null);
                      try {
                        await deleteOverride(id, existingOverride.id);
                        await loadOverrides();
                        setOverrideDialogOpen(false);
                        setOverrideDialogWo(null);
                        setOverrideDialogSupply(null);
                        setOverrideDialogType(null);
                      } catch (e) {
                        setOverrideDialogError(e instanceof Error ? e.message : tP('overrideDialog.failedReset'));
                      } finally {
                        setOverrideDialogSaving(false);
                      }
                    }}
                  >
                    {tP('overrideDialog.resetToAuto')}
                  </button>
                </div>
              );
            })()}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: '1rem' }}>
              <button type="button" className="secondary" onClick={() => setOverrideDialogOpen(false)}>{tP('overrideDialog.cancel')}</button>
              <button type="button" disabled={overrideDialogSaving} onClick={handleSaveOverride}>
                {overrideDialogSaving ? tP('overrideDialog.saving') : tP('overrideDialog.save')}
              </button>
            </div>
            </div>
          </div>
        </div>,
        document.body
      )}

      {woExplainOpen && woExplainRow && typeof document !== 'undefined' && createPortal(
        <div
          style={{ position: 'fixed', inset: 0, zIndex: 9997, display: 'flex', justifyContent: 'flex-end', pointerEvents: 'none' }}
          role="dialog"
          aria-label="Work order explanation"
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.4)', pointerEvents: 'auto' }}
            onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); setWoExplainRow(null); }}
            aria-hidden
          />
          <div
            style={{
              position: 'relative', zIndex: 10, width: woExplainPanelWidth, maxWidth: '90vw', height: '100vh',
              display: 'flex', flexDirection: 'column', background: '#1c1c1e', color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)', pointerEvents: 'auto',
            }}
          >
            {/* Resize handle */}
            <div
              role="separator"
              aria-label="Resize panel"
              onMouseDown={(e) => { e.preventDefault(); woExplainResizeRef.current = { startX: e.clientX, startW: woExplainPanelWidth }; setWoExplainResizing(true); }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(99, 102, 241, 0.5)'; }}
              onMouseLeave={(e) => { if (!woExplainResizing) e.currentTarget.style.background = 'transparent'; }}
              style={{
                position: 'absolute', left: 0, top: 0, bottom: 0, width: 8, cursor: 'col-resize', zIndex: 11,
                background: woExplainResizing ? 'rgba(99, 102, 241, 0.5)' : 'transparent',
                transition: 'background-color 120ms',
              }}
            />
            {/* Header */}
            <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
                <h3 style={{ margin: 0, color: '#fafafa', fontSize: '1rem' }}>{tP('woExplain.title')}</h3>
                <button type="button" onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); setWoExplainRow(null); }} style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tP('woExplain.close')}</button>
              </div>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#a1a1aa' }}>
                <strong>{woExplainRow.product_id}</strong> @ {woExplainRow.location_id}
                {woExplainRow.method && <> · <span style={{ color: '#67e8f9' }}>{woExplainRow.method}</span></>}
                {woExplainRow.prod_area && woExplainRow.prod_area.toLowerCase() !== 'dummy' && <> · {woExplainRow.prod_area}</>}
              </p>
            </div>
            {/* Body */}
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {/* Method choice */}
              {woExplainRow.wo_explanation_method && (
                <section style={{ marginBottom: '1.25rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.4rem' }}>
                    <h4 style={{ margin: 0, color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('woExplain.methodSelection')}</h4>
                    {woExplainRow.multi_supply_available && (
                      <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 8px' }}
                        onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); openOverrideDialog('method_selection', woExplainRow); }}>
                        {tP('woExplain.override')}
                      </button>
                    )}
                  </div>
                  <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{woExplainRow.wo_explanation_method}</p>
                </section>
              )}
              {/* Pegged demand(s) — answers "which final demand(s) does this WO contribute to?".
                  For consolidated WOs the existing Consolidation split section already breaks
                  it down, so we only show this for single-demand WOs. */}
              {woExplainRow.demand_id && !(woExplainRow.wo_consolidation_split_details && woExplainRow.wo_consolidation_split_details.length > 1) && (() => {
                const customer = (planResult?.committed_demands ?? []).find(d => d.demand_id === woExplainRow.demand_id)?.customer ?? null;
                return (
                  <section style={{ marginBottom: '1.25rem' }}>
                    <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('woExplain.peggedDemand')}</h4>
                    <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: 1.6 }}>
                      {tP('woExplain.producesForDemand')} <strong>{woExplainRow.demand_id}</strong>
                      {customer && <> · {tP('woExplain.customerLabel')} <strong style={{ color: '#a1a1aa' }}>{customer}</strong></>}
                      {woExplainRow.quantity != null && <> · {tP('woExplain.qtyLabel')} <strong>{qtyFmt(Number(woExplainRow.quantity))}</strong></>}
                    </p>
                  </section>
                );
              })()}
              {/* Demand competition */}
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('woExplain.demandCompetition')}</h4>
                {(woExplainRow.wo_competing_demands?.length ?? 0) <= 1 ? (
                  <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>{tP('woExplain.oneDemand')}</p>
                ) : (
                  <>
                    <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                      <strong>{woExplainRow.wo_competing_demands!.length}</strong> {tP('woExplain.multipleDemands')}
                    </p>
                    <ul style={{ margin: 0, paddingLeft: '1.25rem', fontSize: '0.8rem', color: '#d4d4d8', lineHeight: 1.6 }}>
                      {woExplainRow.wo_competing_demands!.map((d) => <li key={d}>{d}</li>)}
                    </ul>
                  </>
                )}
              </section>
              {/* Consolidation split */}
              {woExplainRow.wo_consolidation_split_details && woExplainRow.wo_consolidation_split_details.length > 1 && (
                <section style={{ marginBottom: '1.25rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.4rem' }}>
                    <h4 style={{ margin: 0, color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('woExplain.consolidationSplit')}</h4>
                    <button
                      type="button"
                      className="secondary"
                      style={{ fontSize: '0.72rem', padding: '2px 8px' }}
                      onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); openOverrideDialog('component_split', woExplainRow); }}
                    >
                      {tP('woExplain.overrideSplit')}
                    </button>
                  </div>
                  <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                    {tP('woExplain.consolidatedFor')} <strong>{woExplainRow.wo_consolidation_split_details.length}</strong> {tP('woExplain.demandsTotal')} <strong>{qtyFmt(woExplainRow.wo_consolidation_total_planned ?? 0)}</strong>).
                    {' '}{tP('woExplain.splitMode')} <strong>{woExplainRow.wo_consolidation_split_mode === 'proportional' ? tP('woExplain.proportional') : woExplainRow.wo_consolidation_split_mode === 'priority_first' ? tP('woExplain.priorityFirst') : tP('woExplain.splitFair')}</strong>.
                  </p>
                  {(() => {
                    const exp = splitPolicyExplanation(woExplainRow.wo_consolidation_split_mode, tP);
                    const overrideActive = woExplainRow.consolidation_override_active === true;
                    return (
                      <div style={{ margin: '0 0 0.6rem', padding: '0.55rem 0.75rem', background: '#27272a', borderRadius: 6, borderLeft: '3px solid #67e8f9' }}>
                        <div style={{ fontSize: '0.78rem', color: '#67e8f9', marginBottom: '0.25rem', fontWeight: 600 }}>{exp.headline}</div>
                        <p style={{ margin: 0, fontSize: '0.78rem', color: '#a1a1aa', lineHeight: 1.5 }}>{exp.detail}</p>
                        <p style={{ margin: '0.4rem 0 0', fontSize: '0.72rem', color: '#71717a', fontStyle: 'italic' }}>
                          {overrideActive
                            ? <>{tP('woExplain.policySourceOverridePre')} <code style={{ background: '#1c1c1e', padding: '0 4px', borderRadius: 3 }}>component_split</code> {tP('woExplain.policySourceOverridePost')}</>
                            : <>{tP('woExplain.policySourceConfigPre')} <code style={{ background: '#1c1c1e', padding: '0 4px', borderRadius: 3 }}>consolidation.allocation_mode</code> {tP('woExplain.policySourceConfigMid')} <em>{tP('woExplain.overrideSplit')}</em> {tP('woExplain.policySourceConfigSuffix')}</>}
                        </p>
                      </div>
                    );
                  })()}
                  <table style={{ width: '100%', fontSize: '0.78rem', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ color: '#a1a1aa', textAlign: 'left' }}>
                        <th style={{ paddingBottom: '0.2rem' }}>{tP('woExplain.columns.demand')}</th>
                        <th style={{ paddingBottom: '0.2rem' }}>{tP('woExplain.columns.parentProduct')}</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>{tP('woExplain.columns.priority')}</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>{tP('woExplain.columns.requested')}</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>{tP('woExplain.columns.allocated')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {woExplainRow.wo_consolidation_split_details.map((row, i) => (
                        <tr key={i} style={{ borderTop: '1px solid #3d3d40' }}>
                          <td style={{ padding: '0.2rem 0.4rem 0.2rem 0' }}>{row.demand_id ?? '–'}</td>
                          <td style={{ padding: '0.2rem 0.4rem 0.2rem 0', color: '#a1a1aa' }}>{row.parent_product}</td>
                          <td style={{ padding: '0.2rem 0', textAlign: 'right' }}>{row.priority}</td>
                          <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right' }}>{qtyFmt(row.requested_qty)}</td>
                          <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right', color: row.allocated_qty < row.requested_qty - 0.01 ? '#f87171' : '#4ade80' }}>
                            {qtyFmt(row.allocated_qty)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </section>
              )}
              {/* Supply alternatives */}
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('woExplain.supplyAlts')}</h4>
                {woExplainRow.multi_supply_available ? (
                  <p style={{ margin: 0, fontSize: '0.875rem' }}>
                    {tP('woExplain.multipleSupply')}
                    {woExplainRow.wo_explanation_method
                      ? <> {tP('woExplain.seeMethodSelection')} <em>{tP('woExplain.methodSelectionRef')}</em> {tP('woExplain.aboveForChoice')}</>
                      : <> {tP('woExplain.noMethodExplain')}</>}
                  </p>
                ) : (
                  <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>{tP('woExplain.oneSupply')}</p>
                )}
              </section>
              {/* No explanation available */}
              {!woExplainRow.wo_explanation_method && (
                <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>
                  {tP('woExplain.noExplain')}
                </p>
              )}
            </div>
          </div>
        </div>,
        document.body
      )}
      {supExplainOpen && supExplainRow && typeof document !== 'undefined' && createPortal(
        <div
          style={{ position: 'fixed', inset: 0, zIndex: 9997, display: 'flex', justifyContent: 'flex-end', pointerEvents: 'none' }}
          role="dialog"
          aria-label="Supply explanation"
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.4)', pointerEvents: 'auto' }}
            onClick={() => { setSupExplainOpen(false); setSupExplainKey(null); setSupExplainRow(null); }}
            aria-hidden
          />
          <div
            style={{
              position: 'relative', zIndex: 10, width: supExplainPanelWidth, maxWidth: '90vw', height: '100vh',
              display: 'flex', flexDirection: 'column', background: '#1c1c1e', color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)', pointerEvents: 'auto',
            }}
          >
            <div
              role="separator"
              aria-label="Resize panel"
              onMouseDown={(e) => { e.preventDefault(); supExplainResizeRef.current = { startX: e.clientX, startW: supExplainPanelWidth }; setSupExplainResizing(true); }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(99, 102, 241, 0.5)'; }}
              onMouseLeave={(e) => { if (!supExplainResizing) e.currentTarget.style.background = 'transparent'; }}
              style={{
                position: 'absolute', left: 0, top: 0, bottom: 0, width: 8, cursor: 'col-resize', zIndex: 11,
                background: supExplainResizing ? 'rgba(99, 102, 241, 0.5)' : 'transparent',
                transition: 'background-color 120ms',
              }}
            />
            <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
                <h3 style={{ margin: 0, color: '#fafafa', fontSize: '1rem' }}>{tP('supExplain.title')}</h3>
                <button type="button" onClick={() => { setSupExplainOpen(false); setSupExplainKey(null); setSupExplainRow(null); }} style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tP('supExplain.close')}</button>
              </div>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#a1a1aa' }}>
                <strong>{supExplainRow.supplyId}</strong> · {supExplainRow.productId} @ {supExplainRow.locationId ?? '–'}
                {supExplainRow.supplyDate && <> · {supExplainRow.supplyDate}</>}
                {' · '}{tP('woExplain.qtyLabel')} {qtyFmt(Number(supExplainRow.qty))}
              </p>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {(() => {
                const cs = supplyCriticalityMap[supExplainRow.supplyId];
                const csLabel = cs === 'critical' ? tP('supExplain.critical') : cs === 'not_critical' ? tP('supExplain.safe') : (supExplainRow.consumedQty === 0 ? tP('supExplain.safeZero') : null);
                if (!csLabel) return null;
                return (
                  <section style={{ marginBottom: '1.25rem' }}>
                    <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('supExplain.criticalityHeading')}</h4>
                    <p style={{ margin: 0, fontSize: '0.875rem' }}>
                      <strong style={{ color: cs === 'critical' ? '#f87171' : '#34d399' }}>{csLabel}</strong>
                      {cs === 'critical' && <> {tP('supExplain.criticalDetail')}</>}
                      {(cs === 'not_critical' || (!cs && supExplainRow.consumedQty === 0)) && <> {tP('supExplain.safeDetail')}</>}
                    </p>
                  </section>
                );
              })()}
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('supExplain.utilizationHeading')}</h4>
                <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: 1.6 }}>
                  {tP('supExplain.utilInitial')} <strong>{qtyFmt(Number(supExplainRow.qty))}</strong>
                  {' · '}{tP('supExplain.utilConsumed')} <strong style={{ color: '#a78bfa' }}>{qtyFmt(Number(supExplainRow.consumedQty))}</strong>
                  {' · '}{tP('supExplain.utilResidual')} <strong style={{ color: '#34d399' }}>{qtyFmt(Number(supExplainRow.residualQty))}</strong>
                  {supExplainRow.utilizationRate != null && (
                    <> {' · '}<strong>{(supExplainRow.utilizationRate * 100).toFixed(1)}%</strong></>
                  )}
                </p>
              </section>
              {/* ── Assessment UI (impact what-if) ────────────────────────── */}
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('supplyView.assessment.heading')}</h4>
                <div style={{ padding: '0.6rem 0.75rem', background: '#1c1c1e', borderRadius: 6, border: '1px solid #3d3d40' }}>
                  <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
                    <label style={{ fontSize: '0.78rem', color: '#a1a1aa', display: 'flex', alignItems: 'center', gap: 4 }}>
                      {tP('supplyView.assessment.delayDays')}
                      <input
                        type="number" min={0}
                        value={assessDelayDays}
                        onChange={(e) => setAssessDelayDays(Math.max(0, Number(e.target.value)))}
                        style={{ width: 60, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                      />
                    </label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                      <span style={{ fontSize: '0.78rem', color: '#a1a1aa' }}>{tP('supplyView.assessment.qtyDecreasePct')}</span>
                      <div style={{ display: 'flex', border: '1px solid #4c1d95', borderRadius: 4, overflow: 'hidden' }}>
                        <button type="button"
                          onClick={() => setAssessQtyDecreaseMode('pct')}
                          style={{ fontSize: '0.72rem', padding: '1px 8px', background: assessQtyDecreaseMode === 'pct' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', cursor: 'pointer' }}>
                          %
                        </button>
                        <button type="button"
                          onClick={() => setAssessQtyDecreaseMode('abs')}
                          style={{ fontSize: '0.72rem', padding: '1px 8px', background: assessQtyDecreaseMode === 'abs' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', borderLeft: '1px solid #4c1d95', cursor: 'pointer' }}>
                          qty
                        </button>
                      </div>
                      {assessQtyDecreaseMode === 'pct' ? (
                        <input
                          type="number" min={0} max={100}
                          value={assessQtyDecreasePct}
                          onChange={(e) => setAssessQtyDecreasePct(Math.max(0, Math.min(100, Number(e.target.value))))}
                          style={{ width: 60, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                        />
                      ) : (
                        <input
                          type="number" min={0} step={1}
                          placeholder={tP('supplyView.assessment.qtyDecreaseUnits')}
                          value={assessQtyDecreaseAbs}
                          onChange={(e) => setAssessQtyDecreaseAbs(Math.max(0, Number(e.target.value)))}
                          style={{ width: 80, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                        />
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={handleAssess}
                      disabled={assessmentRunning}
                      style={{ fontSize: '0.8rem' }}
                    >
                      {assessmentRunning ? tP('supplyView.assessment.assessing') : tP('supplyView.assessment.assess')}
                    </button>
                  </div>
                  {assessError && (
                    <p style={{ color: '#f87171', fontSize: '0.75rem', margin: '0 0 0.4rem' }}>{assessError}</p>
                  )}
                  {assessmentResult && (
                    <div style={{ marginBottom: '0.4rem' }}>
                      <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>{tP('supplyView.assessment.ratingLabel')} </span>
                      <span style={{
                        display: 'inline-block',
                        padding: '1px 10px',
                        borderRadius: 12,
                        fontSize: '0.8rem',
                        fontWeight: 600,
                        letterSpacing: '0.05em',
                        background: assessmentResult.rating === 'LOW' ? 'rgba(52,211,153,0.15)' : assessmentResult.rating === 'HIGH' ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                        color: assessmentResult.rating === 'LOW' ? '#34d399' : assessmentResult.rating === 'HIGH' ? '#f87171' : '#fbbf24',
                        border: `1px solid ${assessmentResult.rating === 'LOW' ? '#34d399' : assessmentResult.rating === 'HIGH' ? '#f87171' : '#fbbf24'}`,
                      }}>{assessmentResult.rating}</span>
                      <p style={{ fontSize: '0.78rem', color: '#d4d4d8', margin: '0.35rem 0 0' }}>{assessmentResult.explanation}</p>
                    </div>
                  )}
                  <button
                    type="button"
                    onClick={handleLoadHistory}
                    style={{ background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.75rem', padding: 0 }}
                  >
                    {assessmentHistoryOpen ? '▾' : '▸'} {tP('supplyView.assessment.history')}
                  </button>
                  {assessmentHistoryOpen && (
                    <div style={{ marginTop: '0.5rem' }}>
                      {assessmentHistory.length === 0 ? (
                        <p style={{ fontSize: '0.75rem', color: '#71717a', margin: 0 }}>{tP('supplyView.assessment.noHistory')}</p>
                      ) : (
                        <AssessmentHistoryTable rows={assessmentHistory} storageKey="supExplain" />
                      )}
                    </div>
                  )}
                </div>
              </section>
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('supExplain.peggedHeading')}</h4>
                {supExplainRow.peggedDemands.length === 0 ? (
                  <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>{tP('supExplain.peggedEmpty')}</p>
                ) : (
                  <>
                    <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                      <strong>{supExplainRow.peggedDemandCount}</strong> {tP('supExplain.peggedConsumedSuffix')}
                      ({' '}{tP('supExplain.peggedTotal')} <strong>{qtyFmt(Number(supExplainRow.totalPeggedQty))}</strong>{' '}):
                    </p>
                    {/* New "Path" column annotates each pegged demand with which consolidation
                        group it flowed through (or "direct" for main-loop / passthrough). This
                        makes the row counts of the two tables on this slide-in semantically
                        reconcile: every pegged demand reveals its provenance, and the user
                        can see how the totals line up across paths. */}
                    <table style={{ width: '100%', fontSize: '0.78rem', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
                      <colgroup>
                        <col style={{ width: '28%' }} />
                        <col style={{ width: '24%' }} />
                        <col style={{ width: '18%' }} />
                        <col style={{ width: '15%' }} />
                        <col style={{ width: '15%' }} />
                      </colgroup>
                      <thead>
                        <tr style={{ color: '#a1a1aa', textAlign: 'left' }}>
                          <th style={{ paddingBottom: '0.2rem' }}>{tP('supExplain.peggedColDemand')}</th>
                          <th style={{ paddingBottom: '0.2rem' }}>{tP('supExplain.peggedColCustomer')}</th>
                          <th style={{ paddingBottom: '0.2rem' }}>{tP('supExplain.peggedColPath') /* 'Path' / '路径' */}</th>
                          <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>{tP('supExplain.peggedColQty')}</th>
                          <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>{tP('supExplain.peggedColShare')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {supExplainRow.peggedDemands.map((d, i) => {
                          const total = Number(supExplainRow.totalPeggedQty) || 0;
                          const share = total > 1e-9 ? (Number(d.qtyConsumed) / total) * 100 : 0;
                          // Path label covers BOTH multi-demand consolidation groups AND
                          // passthrough singletons; absent = main-loop direct consumption.
                          const groupLabel = supExplainRow.demandPath[d.demandId] ?? null;
                          // Make the demand id clickable so the user can jump straight to that
                          // demand's pegging tree. Only active when the corresponding
                          // committed_demand row exists (i.e. it's a real user-level demand we
                          // can render a tree for). This restores the demand-hyperlink behavior
                          // that the deleted Impact column used to provide.
                          const demandRow = planResult?.committed_demands.find((cd) => cd.demand_id === d.demandId);
                          const demandKey = demandRow ? `demand|${demandRow.demand_id ?? ''}|${demandRow.product_id}|${demandRow.location_id}` : null;
                          return (
                            <tr key={`${d.demandId}-${i}`} style={{ borderTop: '1px solid #3d3d40' }}>
                              <td style={{ padding: '0.2rem 0.4rem 0.2rem 0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {demandRow && demandKey ? (
                                  <button
                                    type="button"
                                    className="secondary"
                                    style={{ fontSize: '0.74rem', padding: '1px 6px', fontFamily: 'monospace' }}
                                    title={tP('supExplain.openDemandPegging')}
                                    onClick={() => {
                                      // Switch to the planPegging slide-in for this demand.
                                      // Capture the current supExplain row so the planPegging
                                      // slide-in can render a "← back to <supplyId>" link that
                                      // restores the Breakdown view.
                                      setPreviousSupExplainRow(supExplainRow);
                                      setPlanPeggingContext({ type: 'demand', row: demandRow });
                                      setPlanPeggingOpen(true);
                                      setWoPeggingRowKey(demandKey);
                                      setSupExplainOpen(false);
                                      setSupExplainKey(null);
                                      setSupExplainRow(null);
                                    }}
                                  >{d.demandId}</button>
                                ) : (
                                  <span>{d.demandId}</span>
                                )}
                              </td>
                              <td style={{ padding: '0.2rem 0.4rem 0.2rem 0', color: '#a1a1aa', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.customer ?? '–'}</td>
                              <td style={{ padding: '0.2rem 0.4rem 0.2rem 0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '0.74rem' }}>
                                {groupLabel ? (
                                  <span style={{ color: '#67e8f9' }} title={`via consolidation group ${groupLabel}`}>{groupLabel}</span>
                                ) : (
                                  <span style={{ color: '#a1a1aa', fontStyle: 'italic' }} title="Direct main-loop / passthrough consumption (no consolidation split)">{tP('supExplain.peggedPathDirect') /* 'direct' / '直接' */}</span>
                                )}
                              </td>
                              <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right' }}>{qtyFmt(Number(d.qtyConsumed))}</td>
                              <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right', color: '#a1a1aa' }}>{share.toFixed(1)}%</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                    <p style={{ margin: '0.4rem 0 0', fontSize: '0.75rem', color: '#71717a', lineHeight: 1.5 }}>
                      {tP('supExplain.peggedShareNote')}
                    </p>
                  </>
                )}
              </section>
              {/* CONSOLIDATION SPLIT sections were collapsed into the Pegged Demands table
                  above (the "Path" column annotates each demand with its consolidation
                  group), and the policy/totals chip in the supply-table row carries the
                  per-group summary. One view per shared component, no duplication. */}
              {supExplainRow.override && (
                <section style={{ marginBottom: '1.25rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.4rem' }}>
                    <h4 style={{ margin: 0, color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('supExplain.overrideHeading')}</h4>
                    <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 8px' }}
                      onClick={() => { setSupExplainOpen(false); setSupExplainKey(null); openSupplyOverrideDialog(supExplainRow); }}>
                      {tP('supExplain.overrideEdit')}
                    </button>
                  </div>
                  <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                    <strong>{supExplainRow.override.allocations.length}</strong> {tP('supExplain.overrideSummarySuffix')}
                    {supExplainRow.override.warning && <> {' · '}<strong style={{ color: '#f87171' }}>{tP('supExplain.overrideWarn')}</strong></>}
                  </p>
                  <table style={{ width: '100%', fontSize: '0.78rem', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ color: '#a1a1aa', textAlign: 'left' }}>
                        <th style={{ paddingBottom: '0.2rem' }}>{tP('supExplain.overrideColDemand')}</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>{tP('supExplain.overrideColQty')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {supExplainRow.override.allocations.map((a, i) => (
                        <tr key={`${a.demand_id}-${i}`} style={{ borderTop: '1px solid #3d3d40' }}>
                          <td style={{ padding: '0.2rem 0.4rem 0.2rem 0' }}>{a.demand_id}</td>
                          <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right' }}>{qtyFmt(Number(a.qty))}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </section>
              )}
            </div>
          </div>
        </div>,
        document.body
      )}
      {copilotOpen && typeof document !== 'undefined' && createPortal(
        <div
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 9998,
            display: 'flex',
            justifyContent: 'flex-end',
            pointerEvents: 'auto',
          }}
          role="dialog"
          aria-label={tP('copilot.title')}
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', zIndex: 0, pointerEvents: 'auto' }}
            onClick={() => setCopilotOpen(false)}
            aria-hidden
          />
          <div
            onMouseDown={(e) => e.stopPropagation()}
            onClick={(e) => e.stopPropagation()}
            style={{
              position: 'relative',
              zIndex: 10,
              width: copilotPanelWidth,
              maxWidth: '90vw',
              minWidth: 320,
              height: '100vh',
              display: 'flex',
              flexDirection: 'column',
              background: '#1c1c1e',
              color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)',
              pointerEvents: 'auto',
            }}
          >
            <div
              role="separator"
              aria-label="Resize agent panel"
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                copilotResizeRef.current = { startX: e.clientX, startW: copilotPanelWidth };
                setCopilotResizing(true);
              }}
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                bottom: 0,
                width: 6,
                cursor: 'col-resize',
                zIndex: 11,
              }}
            />
            <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <h3 style={{ margin: 0, color: '#fafafa' }}>{tP('copilot.title')}</h3>
                <button type="button" onClick={() => setCopilotOpen(false)} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tP('copilot.close')}</button>
              </div>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#a1a1aa' }}>
                <strong>{tP('copilot.methods')}</strong> {planningConfig.method_selection?.multiple === true
                  ? tP('copilot.equalSplit')
                  : planningConfig.method_selection?.elaborate === true
                    ? tP('copilot.oneByScoreWithDepth', { depth: planningConfig.method_selection?.depth ?? 1 })
                    : tP('copilot.oneByPreference')}.{' '}
                <strong>{tP('copilot.purchase')}</strong> {planningConfig.purchase_allowed === false ? tP('copilot.disabled') : tP('copilot.allowed')}.{' '}
                <strong>{tP('copilot.consolidation')}</strong> {planningConfig.consolidation?.enabled === true
                  ? tP('copilot.consolidationOnDetail', {
                      days: planningConfig.consolidation.period_days ?? 0,
                      split: planningConfig.consolidation.allocation_mode === 'proportional'
                        ? tP('copilot.splitProportional')
                        : planningConfig.consolidation.allocation_mode === 'priority_first'
                          ? tP('copilot.splitPriorityFirst')
                          : tP('copilot.splitFair'),
                    })
                  : tP('copilot.off')}. {tP('copilot.naturalLangInfo')}
              </p>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {copilotMessages.length === 0 && (
                <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>
                  {tP('copilot.examplesTitle')} <strong>{tP('copilot.examplesBody')}</strong> {tP('copilot.examplesMethod')} {tP('copilot.examplesRest')}
                </p>
              )}
              {copilotMessages.map((m, i) => (
                <div key={i} style={{ marginBottom: '0.75rem' }}>
                  <span style={{ fontWeight: 600, color: m.role === 'user' ? '#a78bfa' : '#67e8f9', fontSize: '0.8rem' }}>{m.role === 'user' ? tP('copilot.roleUser') : tP('copilot.roleCopilot')}: </span>
                  <span style={{ whiteSpace: 'pre-wrap', fontSize: '0.875rem' }}>{m.text.replace(/\*\*(.*?)\*\*/g, '$1')}</span>
                  {m.role === 'assistant' && m.steps && m.steps.length > 0 && (
                    <div style={{ marginTop: '0.4rem', marginLeft: '0.75rem', borderLeft: '2px solid #3d3d40', paddingLeft: '0.75rem' }}>
                      {m.steps.map((s, si) => (
                        <div key={si} style={{ fontSize: '0.75rem', color: '#a1a1aa', fontStyle: 'italic', marginBottom: '0.15rem' }}>
                          <span style={{ color: '#71717a', fontFamily: 'monospace' }}>{s.tool}</span>
                          {' · '}
                          {s.result_summary}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
              {(copilotLoading || copilotPendingJobId) && (
                <div style={{ margin: '0.25rem 0', fontSize: '0.875rem', color: '#a1a1aa' }}>
                  {copilotLoading && <p style={{ margin: 0 }}>{tP('copilot.thinking')}</p>}
                  {copilotActiveJob && (() => {
                    const cur = copilotActiveJob.progress?.current ?? 0;
                    const tot = copilotActiveJob.progress?.total ?? 0;
                    const pct = tot > 0 ? Math.min(100, (cur / tot) * 100) : 0;
                    return (
                      <div style={{ marginTop: '0.4rem' }}>
                        <div style={{ fontSize: '0.75rem', color: '#71717a', marginBottom: 4, fontFamily: 'monospace' }}>
                          plan {cur}/{tot} demands ({pct.toFixed(1)}%)
                          {!copilotLoading && copilotPendingJobId && ` — ${tP('copilot.runningInBackground')}`}
                        </div>
                        <div style={{ height: 6, background: '#27272a', border: '1px solid #3d3d40', borderRadius: 3, overflow: 'hidden' }}>
                          <div style={{ height: '100%', width: `${pct}%`, background: '#0ea5e9', transition: 'width 0.3s ease' }} />
                        </div>
                      </div>
                    );
                  })()}
                </div>
              )}
              <div ref={copilotMessagesEndRef} />
            </div>
            <form
              style={{ padding: '1rem 1.25rem', borderTop: '1px solid #3d3d40', flexShrink: 0 }}
              onSubmit={async (e) => {
                e.preventDefault();
                const text = copilotInput.trim();
                if (!text || copilotLoading) return;
                setCopilotMessages((prev) => [...prev, { role: 'user', text }]);
                setCopilotInput('');
                setCopilotLoading(true);
                // Apply config_update + reply to chat in a shared shape.
                const applyConfig = (cu: PlanningConfig | null) => {
                  if (!cu) return;
                  setPlanningConfig((prev) => ({
                    ...prev,
                    ...cu,
                    method_selection: cu.method_selection ? { ...prev.method_selection, ...cu.method_selection } : prev.method_selection,
                    purchase_allowed: 'purchase_allowed' in cu ? cu.purchase_allowed : prev.purchase_allowed,
                    consolidation: cu.consolidation ? { ...prev.consolidation, ...cu.consolidation } : prev.consolidation,
                  }));
                };
                try {
                  // Try the full agent first; fall back to copilot if it 5xxs (e.g. OPENAI key missing).
                  const res = await planningAgent(id, text, planningConfig, copilotMessages);
                  applyConfig(res.config_update);
                  setCopilotMessages((prev) => [
                    ...prev,
                    { role: 'assistant', text: res.reply, steps: res.steps, fresh_run_id: res.fresh_run_id },
                  ]);
                  // If the agent ran a plan, refresh the run-history list so the user sees it.
                  if (res.fresh_run_id != null && id != null) {
                    listPlanRuns(id).then(setPlanRunHistory).catch(() => { /* ignore */ });
                  }
                  // If the plan exceeded the agent's 25s wait window, the
                  // backend hands the job_id back here; mode-2 polling above
                  // takes over and posts the completion message itself.
                  if (res.pending_job_id) {
                    setCopilotPendingJobId(res.pending_job_id);
                  }
                } catch {
                  // Agent unavailable — fall back to the copilot route, then to local rule-based parser.
                  try {
                    const res = await planningCopilot(id, text, planningConfig, copilotMessages);
                    applyConfig(res.config_update);
                    setCopilotMessages((prev) => [...prev, { role: 'assistant', text: res.reply }]);
                  } catch {
                    const { reply, configUpdate } = parseCopilotIntent(text, planningConfig);
                    if (configUpdate) setPlanningConfig((prev) => ({
                      ...prev,
                      ...configUpdate,
                      method_selection: configUpdate.method_selection ? { ...prev.method_selection, ...configUpdate.method_selection } : prev.method_selection,
                      consolidation: configUpdate.consolidation ? { ...prev.consolidation, ...configUpdate.consolidation } : prev.consolidation,
                    }));
                    setCopilotMessages((prev) => [...prev, { role: 'assistant', text: reply }]);
                  }
                } finally {
                  setCopilotLoading(false);
                }
              }}
            >
              <input
                type="text"
                value={copilotInput}
                onChange={(e) => setCopilotInput(e.target.value)}
                placeholder={tP('copilot.inputPlaceholder')}
                disabled={copilotLoading}
                style={{ width: '100%', padding: '8px 12px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 6, color: '#fafafa' }}
                aria-label={tP('copilot.title')}
              />
              <button type="submit" disabled={copilotLoading} className="secondary" style={{ marginTop: '0.5rem' }}>{tP('copilot.send')}</button>
            </form>
          </div>
        </div>,
        document.body
      )}
      {!copilotOpen && typeof document !== 'undefined' && createPortal(
        <button
          type="button"
          onClick={() => setCopilotOpen(true)}
          aria-label={tP('copilot.title')}
          title={tP('copilot.title')}
          style={{
            position: 'fixed',
            right: 0,
            top: '50%',
            transform: 'translateY(-50%)',
            zIndex: 9990,
            padding: '14px 8px',
            background: '#3b82f6',
            color: '#fff',
            border: 'none',
            borderTopLeftRadius: 8,
            borderBottomLeftRadius: 8,
            boxShadow: '-2px 0 10px rgba(0,0,0,0.35)',
            cursor: 'pointer',
            writingMode: 'vertical-rl',
            textOrientation: 'mixed',
            fontSize: '0.85rem',
            fontWeight: 600,
            letterSpacing: '0.04em',
          }}
        >
          {tP('agentLauncher')}
        </button>,
        document.body
      )}
      {planPeggingOpen && planPeggingContext && typeof document !== 'undefined' && (planPeggingContext.type === 'demand' ? !!planResult : true) && createPortal(
        <div
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 9998,
            display: 'flex',
            justifyContent: 'flex-end',
            pointerEvents: 'auto',
          }}
          role="dialog"
          aria-label="Planning pegging"
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', zIndex: 0, pointerEvents: 'auto' }}
            onClick={() => { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); }}
            aria-hidden
          />
          <div
            onMouseDown={(e) => e.stopPropagation()}
            onClick={(e) => e.stopPropagation()}
            style={{
              position: 'relative',
              zIndex: 10,
              width: planPeggingPanelWidth,
              maxWidth: '90vw',
              minWidth: 320,
              maxHeight: '100vh',
              overflow: 'auto',
              background: '#1c1c1e',
              color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)',
              padding: '1.25rem',
              pointerEvents: 'auto',
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <div
              role="separator"
              aria-label="Resize planning pegging panel"
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                planPeggingResizeRef.current = { startX: e.clientX, startW: planPeggingPanelWidth };
                setPlanPeggingResizing(true);
              }}
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                bottom: 0,
                width: 6,
                cursor: 'col-resize',
                zIndex: 11,
              }}
            />
            {previousPeggingContext && (
              <div style={{ marginBottom: '0.5rem' }}>
                <button
                  type="button"
                  onClick={() => {
                    setPlanPeggingContext(previousPeggingContext);
                    setWoPeggingRowKey(`supply|${previousPeggingContext.supplyId}`);
                    setPreviousPeggingContext(null);
                  }}
                  style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.78rem', padding: 0, display: 'flex', alignItems: 'center', gap: '0.3rem' }}
                >
                  ← {previousPeggingContext.supplyId}
                </button>
              </div>
            )}
            {previousSupExplainRow && (
              <div style={{ marginBottom: '0.5rem' }}>
                <button
                  type="button"
                  onClick={() => {
                    // Restore the Breakdown slide-in for the supply we came from,
                    // close the planPegging slide-in. This is the inverse of the
                    // demand-id click in supExplain.
                    setSupExplainRow(previousSupExplainRow);
                    setSupExplainKey(`supply|${previousSupExplainRow.supplyId}`);
                    setSupExplainOpen(true);
                    setPlanPeggingOpen(false);
                    setPlanPeggingContext(null);
                    setWoPeggingRowKey(null);
                    setPreviousSupExplainRow(null);
                  }}
                  style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.78rem', padding: 0, display: 'flex', alignItems: 'center', gap: '0.3rem' }}
                >
                  ← {previousSupExplainRow.supplyId}
                </button>
              </div>
            )}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
              <h3 style={{ margin: 0, color: '#fafafa' }}>
                {planPeggingContext.type === 'supply'
                  ? tP('supplyView.peggingPanel.title', { supplyId: planPeggingContext.supplyId })
                  : planPeggingContext.type === 'demand'
                    ? tP('peggingPanel.titleDemand', { label: planPeggingContext.row.demand_id ?? planPeggingContext.row.product_id ?? '' })
                    : tP('peggingPanel.titleWorkOrder', { product: planPeggingContext.row.product_id ?? '', location: planPeggingContext.row.location_id ?? '' })}
              </h3>
              <button type="button" onClick={() => { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); }} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tc('close')}</button>
            </div>
            <p style={{ margin: 0, marginBottom: '0.5rem', fontSize: '0.8rem', color: '#71717a' }}>
              {planPeggingContext.type === 'supply'
                ? tP('supplyView.peggingPanel.description')
                : planPeggingContext.type === 'work_order'
                  ? tP('peggingPanel.descriptionWorkOrder')
                  : tP('peggingPanel.descriptionDemand')}
              {planPeggingContext.type !== 'supply' && (
                <>{' '}{tP('peggingPanel.descriptionOrSiblings')}</>
              )}
            </p>
            {planPeggingContext.type === 'supply' && (() => {
              const ctx = planPeggingContext;
              return (
                <div>
                  <p style={{ fontSize: '0.8rem', color: '#71717a', margin: '0 0 0.75rem' }}>
                    {tP('supplyView.peggingPanel.initialQty')} <strong style={{ color: '#e4e4e7' }}>{qtyFmt(Number(ctx.initialQty))}</strong>
                    {' · '}{tP('supplyView.peggingPanel.consumed')} <strong style={{ color: '#a78bfa' }}>{qtyFmt(Number(ctx.consumedQty))}</strong>
                    {' · '}{tP('supplyView.peggingPanel.demandCount', { count: ctx.peggedDemands.length })}
                  </p>
                  {/* ── Assessment UI ──────────────────────────────────────── */}
                  <div style={{ marginBottom: '1rem', padding: '0.6rem 0.75rem', background: '#1c1c1e', borderRadius: 6, border: '1px solid #3d3d40' }}>
                    {/* Input row */}
                    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
                      <label style={{ fontSize: '0.78rem', color: '#a1a1aa', display: 'flex', alignItems: 'center', gap: 4 }}>
                        {tP('supplyView.assessment.delayDays')}
                        <input
                          type="number" min={0}
                          value={assessDelayDays}
                          onChange={(e) => setAssessDelayDays(Math.max(0, Number(e.target.value)))}
                          style={{ width: 60, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                        />
                      </label>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <span style={{ fontSize: '0.78rem', color: '#a1a1aa' }}>{tP('supplyView.assessment.qtyDecreasePct')}</span>
                        <div style={{ display: 'flex', border: '1px solid #4c1d95', borderRadius: 4, overflow: 'hidden' }}>
                          <button type="button"
                            onClick={() => setAssessQtyDecreaseMode('pct')}
                            style={{ fontSize: '0.72rem', padding: '1px 8px', background: assessQtyDecreaseMode === 'pct' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', cursor: 'pointer' }}>
                            %
                          </button>
                          <button type="button"
                            onClick={() => setAssessQtyDecreaseMode('abs')}
                            style={{ fontSize: '0.72rem', padding: '1px 8px', background: assessQtyDecreaseMode === 'abs' ? '#4c1d95' : '#27272a', color: '#e4e4e7', border: 'none', borderLeft: '1px solid #4c1d95', cursor: 'pointer' }}>
                            qty
                          </button>
                        </div>
                        {assessQtyDecreaseMode === 'pct' ? (
                          <input
                            type="number" min={0} max={100}
                            value={assessQtyDecreasePct}
                            onChange={(e) => setAssessQtyDecreasePct(Math.max(0, Math.min(100, Number(e.target.value))))}
                            style={{ width: 60, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                          />
                        ) : (
                          <input
                            type="number" min={0} step={1}
                            placeholder="units"
                            value={assessQtyDecreaseAbs}
                            onChange={(e) => setAssessQtyDecreaseAbs(Math.max(0, Number(e.target.value)))}
                            style={{ width: 80, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                          />
                        )}
                      </div>
                      <button
                        type="button"
                        onClick={handleAssess}
                        disabled={assessmentRunning}
                        style={{ fontSize: '0.8rem' }}
                      >
                        {assessmentRunning ? tP('supplyView.assessment.assessing') : tP('supplyView.assessment.assess')}
                      </button>
                    </div>
                    {/* Error */}
                    {assessError && (
                      <p style={{ color: '#f87171', fontSize: '0.75rem', margin: '0 0 0.4rem' }}>{assessError}</p>
                    )}
                    {/* Rating result */}
                    {assessmentResult && (
                      <div style={{ marginBottom: '0.4rem' }}>
                        <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>{tP('supplyView.assessment.ratingLabel')} </span>
                        <span style={{
                          display: 'inline-block',
                          padding: '1px 10px',
                          borderRadius: 12,
                          fontSize: '0.8rem',
                          fontWeight: 600,
                          letterSpacing: '0.05em',
                          background: assessmentResult.rating === 'LOW' ? 'rgba(52,211,153,0.15)' : assessmentResult.rating === 'HIGH' ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                          color: assessmentResult.rating === 'LOW' ? '#34d399' : assessmentResult.rating === 'HIGH' ? '#f87171' : '#fbbf24',
                          border: `1px solid ${assessmentResult.rating === 'LOW' ? '#34d399' : assessmentResult.rating === 'HIGH' ? '#f87171' : '#fbbf24'}`,
                        }}>{assessmentResult.rating}</span>
                        <p style={{ fontSize: '0.78rem', color: '#d4d4d8', margin: '0.35rem 0 0' }}>{assessmentResult.explanation}</p>
                      </div>
                    )}
                    {/* History toggle */}
                    <button
                      type="button"
                      onClick={handleLoadHistory}
                      style={{ background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.75rem', padding: 0 }}
                    >
                      {assessmentHistoryOpen ? '▾' : '▸'} {tP('supplyView.assessment.history')}
                    </button>
                    {assessmentHistoryOpen && (
                      <div style={{ marginTop: '0.5rem' }}>
                        {assessmentHistory.length === 0 ? (
                          <p style={{ fontSize: '0.75rem', color: '#71717a', margin: 0 }}>{tP('supplyView.assessment.noHistory')}</p>
                        ) : (
                          <AssessmentHistoryTable rows={assessmentHistory} storageKey="main" />
                        )}
                      </div>
                    )}
                  </div>
                  {/* ── Pegged demand table ────────────────────────────────── */}
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.82rem' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa' }}>
                        <th style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 500 }}>{tP('supplyView.peggingPanel.columns.demandId')}</th>
                        <th style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 500 }}>{tP('supplyView.peggingPanel.columns.customer')}</th>
                        <th style={{ textAlign: 'right', padding: '4px 6px', fontWeight: 500 }}>{tP('supplyView.peggingPanel.columns.qtyConsumed')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {[...ctx.peggedDemands]
                        .sort((a, b) => b.qtyConsumed - a.qtyConsumed)
                        .map((d) => {
                          const demandRow = planResult?.committed_demands.find((cd) => cd.demand_id === d.demandId);
                          const demandKey = demandRow ? `demand|${demandRow.demand_id ?? ''}|${demandRow.product_id}|${demandRow.location_id}` : null;
                          const isActive = demandKey != null && woPeggingRowKey === demandKey;
                          return (
                          <tr key={d.demandId} style={{ borderBottom: '1px solid #27272a' }}>
                            <td style={{ padding: '4px 6px', fontFamily: 'monospace' }}>
                              {demandRow ? (
                                <button
                                  type="button"
                                  className="secondary"
                                  style={{ fontSize: '0.78rem', padding: '1px 6px', fontFamily: 'monospace', ...(isActive ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : {}) }}
                                  onClick={() => {
                                    if (isActive) {
                                      setPlanPeggingContext({ type: 'supply', supplyId: ctx.supplyId, peggedDemands: ctx.peggedDemands, initialQty: ctx.initialQty, consumedQty: ctx.consumedQty });
                                      setWoPeggingRowKey(`supply|${ctx.supplyId}`);
                                      setPreviousPeggingContext(null);
                                    } else {
                                      setPreviousPeggingContext({ type: 'supply', supplyId: ctx.supplyId, peggedDemands: ctx.peggedDemands, initialQty: ctx.initialQty, consumedQty: ctx.consumedQty });
                                      setPlanPeggingContext({ type: 'demand', row: demandRow });
                                      setWoPeggingRowKey(demandKey);
                                    }
                                  }}
                                >{d.demandId}</button>
                              ) : (
                                <span style={{ color: '#60a5fa' }}>{d.demandId || '–'}</span>
                              )}
                            </td>
                            <td style={{ padding: '4px 6px', color: '#e4e4e7' }}>{d.customer ?? '–'}</td>
                            <td style={{ padding: '4px 6px', textAlign: 'right', color: '#a78bfa' }}>{qtyFmt(Number(d.qtyConsumed))}</td>
                          </tr>
                          );
                        })}
                    </tbody>
                  </table>
                </div>
              );
            })()}
            {planPeggingContext.type === 'work_order' && (() => {
              const row = planPeggingContext.row;
              // "Shared" means the planner consolidated multiple demands into one WO (demand_id=null).
              // Demand-specific WOs serve one demand each — not shared even if the same product@location
              // has sibling WOs for other demands.
              const sharedDemands = row.demand_id == null
                ? (row.wo_consolidation_split_details ?? [])
                    .map((d) => d.demand_id)
                    .filter((d): d is string => d != null && d !== '')
                : [];
              if (sharedDemands.length <= 1) return null;
              const active = woPeggingActiveDemandId ?? (sharedDemands[0] ?? '');
              return (
                <div style={{ marginBottom: '0.75rem' }}>
                  <span style={{ fontSize: '0.75rem', color: '#a1a1aa', marginRight: 6 }}>
                    Consolidated for {sharedDemands.length} demands — viewing:
                  </span>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 4 }}>
                    {sharedDemands.map((did) => (
                      <button
                        key={did}
                        type="button"
                        className="secondary"
                        title={did}
                        style={{
                          fontSize: '0.72rem', padding: '2px 8px', maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          background: active === did ? 'rgba(167,139,250,0.18)' : undefined,
                          borderColor: active === did ? '#a78bfa' : undefined,
                          color: active === did ? '#a78bfa' : undefined,
                        }}
                        onClick={() => setWoPeggingActiveDemandId(did)}
                      >
                        {did}
                      </button>
                    ))}
                  </div>
                </div>
              );
            })()}
            {planPeggingContext.type === 'work_order' && (() => {
              const row = planPeggingContext.row as WorkOrder;
              // For demand-specific WOs (not consolidated), show a single demand note
              // analogous to the consolidated tabs, so the UI is consistent.
              if (row.demand_id == null) return null;
              return (
                <div style={{ marginBottom: '0.75rem', fontSize: '0.75rem', color: '#a1a1aa' }}>
                  Serving demand: <span style={{ color: '#e4e4e7', fontWeight: 500 }}>{row.demand_id}</span>
                </div>
              );
            })()}
            {planPeggingContext.type !== 'supply' && (() => {
              let tree: PlanningPeggingNode | null = null;
              const isWoPeggingView = planPeggingContext.type === 'work_order';
              const woPeggingDemandId = isWoPeggingView ? (planPeggingContext.row as WorkOrder).demand_id ?? null : null;
              // The demand whose pegging we're viewing — suppress redundant "(demand …)" labels on all nodes that carry this id.
              const contextDemandId = isWoPeggingView ? woPeggingDemandId : (planPeggingContext.row as { demand_id?: string | null }).demand_id ?? null;
              if (planPeggingContext.type === 'work_order') {
                if (planWorkOrderPeggingLoading === woPeggingKey || (woPeggingKey && !planWorkOrderPeggingCache[woPeggingKey] && !planWorkOrderPeggingError)) {
                  return (
                    <div style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>
                      <p>Loading pegging…</p>
                      <p style={{ fontSize: '0.75rem', marginTop: '0.5rem', color: '#71717a' }}>
                        If this hangs: run plan first (backend stores result per case), then open pegging. Check browser console (F12) and backend logs for errors.
                      </p>
                    </div>
                  );
                }
                if (planWorkOrderPeggingError) {
                  return <p style={{ color: '#f87171', fontSize: '0.9rem' }}>{planWorkOrderPeggingError}</p>;
                }
                tree = woPeggingKey ? planWorkOrderPeggingCache[woPeggingKey] ?? null : null;
                if (!tree) {
                  return <p style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>No pegging for this work order. Run plan first.</p>;
                }
              } else {
                if (!planResult) {
                  return <p style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>No plan result.</p>;
                }
                const demandIdNorm = String(planPeggingContext.row.demand_id ?? '').trim();
                // Use the LAST matching entry: planning_pegging = consolidatedPegging + planningPegging,
                // so the last entry for a demand_id is the main planning tree (not a consolidation component sub-tree).
                const matchingEntries = planResult.planning_pegging?.filter((e) => String(e.demand_id ?? '').trim() === demandIdNorm) ?? [];
                const entry = matchingEntries.length > 0 ? matchingEntries[matchingEntries.length - 1] : undefined;
                tree = entry?.tree ?? null;
                if (!tree) {
                  return <p style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>No pegging tree for this demand. Re-run plan to get planning_pegging.</p>;
                }
              }

              const runSearch = (query: string) => {
                const q = query.trim().toLowerCase();
                if (!q || !tree) {
                  setPlanPeggingMatchPaths([]);
                  setPlanPeggingMatchPath(null);
                  setPlanPeggingMatchIndex(0);
                  return;
                }
                const matches: string[] = [];
                const ancestors = new Set<string>();
                const nodeMatches = (n: PlanningPeggingNode): boolean => {
                  const fields = [n.product_id, n.demand_id, n.supply_id, n.location_id, n.method];
                  return fields.some((f) => typeof f === 'string' && f.toLowerCase().includes(q));
                };
                const walk = (n: PlanningPeggingNode, path: string, chain: string[]): void => {
                  const nextChain = [...chain, path];
                  if (nodeMatches(n)) {
                    matches.push(path);
                    chain.forEach((p) => ancestors.add(p));
                  }
                  (n.children ?? []).forEach((c, i) => walk(c, `${path}-${i}`, nextChain));
                };
                walk(tree, '0', []);
                setPlanPeggingMatchPaths(matches);
                setPlanPeggingMatchIndex(0);
                setPlanPeggingMatchPath(matches[0] ?? null);
                if (matches.length > 0) {
                  setPlanPeggingExpanded((prev) => {
                    const next = new Set(prev);
                    ancestors.forEach((p) => next.add(p));
                    // Also expand the first match itself so its children are visible
                    next.add(matches[0]);
                    return next;
                  });
                }
              };
              const stepMatch = (delta: number) => {
                if (planPeggingMatchPaths.length === 0) return;
                const nextIdx = (planPeggingMatchIndex + delta + planPeggingMatchPaths.length) % planPeggingMatchPaths.length;
                setPlanPeggingMatchIndex(nextIdx);
                const nextPath = planPeggingMatchPaths[nextIdx];
                setPlanPeggingMatchPath(nextPath);
                // Make sure ancestors of the new match are expanded
                setPlanPeggingExpanded((prev) => {
                  const next = new Set(prev);
                  const parts = nextPath.split('-');
                  for (let i = 1; i <= parts.length; i++) next.add(parts.slice(0, i).join('-'));
                  next.add(nextPath);
                  return next;
                });
              };

              function renderNode(node: PlanningPeggingNode, path: string, depth: number, xlink = false) {
                const rawChildren = node.children ?? [];
                // A child "contributed" if it has any committed_qty (demand) or quantity (other).
                const childContrib = (c: PlanningPeggingNode): number => {
                  const cc = (c as { committed_qty?: number | null }).committed_qty;
                  return Number((cc != null ? cc : c.quantity) ?? 0);
                };
                let childrenList = rawChildren;
                // Blocked work_order with no `failed` marker — collapse the subtree.
                // The slot's method_choice_explanation already names the deepest
                // bottleneck, so an empty/legacy 0-qty subtree below would add
                // noise without information. WOs marked `failed: true` come from
                // the AND-bottleneck blocked branch and carry the partial pegging
                // tree (under-allocated children, deeper child_failed cascades) —
                // those stay expandable so operators can inspect *why* the method
                // was blocked.
                const isLegacyBlockedWo = node.type === 'work_order'
                  && !node.failed
                  && Number(node.quantity ?? 0) <= 1e-9
                  && rawChildren.length > 0;
                if (isLegacyBlockedWo) {
                  childrenList = [];
                }
                // Fix: under OR-relation, hide siblings that contributed nothing when at
                // least one DID contribute. OR semantics is "any one path supplies the
                // parent" — failed alternatives are dead weight.
                if (!isLegacyBlockedWo && node.children_relation === 'or' && childrenList.length > 1) {
                  const contribCount = childrenList.reduce((n, c) => n + (childContrib(c) > 1e-9 ? 1 : 0), 0);
                  if (contribCount > 0 && contribCount < childrenList.length) {
                    childrenList = childrenList.filter((c) => childContrib(c) > 1e-9);
                  }
                }

                // Consolidated supply nodes: find the original supply trees from non-last
                // planning_pegging entries for the embedded demand_id.
                // supply_id format: "consolidated_${demandId}_${productId}"
                let consolidatedSourceTrees: PlanningPeggingNode[] = [];
                if (!xlink && node.type === 'supply' && node.supply_id?.startsWith('consolidated_')) {
                  const withoutPrefix = node.supply_id.slice('consolidated_'.length);
                  const pid = node.product_id ?? '';
                  // Strip the trailing _${productId} to recover the embedded demandId
                  const embeddedDemandId = pid && withoutPrefix.endsWith(`_${pid}`)
                    ? withoutPrefix.slice(0, -(pid.length + 1))
                    : withoutPrefix;
                  const allEntries = (planResult?.planning_pegging ?? []).filter(
                    (e) => String(e.demand_id ?? '').trim() === embeddedDemandId
                  );
                  consolidatedSourceTrees = allEntries
                    .slice(0, -1)
                    .map((e) => e.tree)
                    .filter((t): t is PlanningPeggingNode =>
                      t != null && t.product_id === pid
                    );
                }

                const hasChildren = childrenList.length > 0 || consolidatedSourceTrees.length > 0;
                const isRoot = path === '0';
                const expandable = hasChildren || isRoot;
                const isExpanded = planPeggingExpanded.has(path);
                const toggle = () => setPlanPeggingExpanded((prev) => {
                  const next = new Set(prev);
                  if (next.has(path)) next.delete(path);
                  else next.add(path);
                  return next;
                });
                const isDemand = node.type === 'demand';
                const isWorkOrder = node.type === 'work_order';
                const icon = !isWorkOrder ? '▢' : '⚙';
                const typeLabel = isDemand ? 'Need'
                  : node.type === 'supply' ? 'Supply'
                  : node.type === 'purchase' ? 'Purchase'
                  : 'Work order';
                const typeColor = isDemand ? '#60a5fa'
                  : isWorkOrder ? '#34d399'
                  : '#a78bfa';
                // When viewing work-order pegging, root node quantity must match the table row the user clicked
                const woRowQty = planPeggingContext?.type === 'work_order' && isRoot && path === '0'
                  ? Number((planPeggingContext.row as WorkOrder).quantity ?? 0)
                  : null;
                const label = node.type === 'demand'
                  ? (() => {
                      const reqQty = Number(node.quantity ?? 0);
                      const committedRaw = (node as { committed_qty?: number | null }).committed_qty;
                      const commQty = committedRaw == null ? reqQty : Number(committedRaw);
                      const qtyLabel = commQty < reqQty - 1e-6
                        ? `${qtyFmt(commQty)} / ${qtyFmt(reqQty)}`
                        : qtyFmt(reqQty);
                      return `${node.product_id ?? node.demand_id ?? '–'} · ${qtyLabel} @ ${node.location_id ?? '–'}${node.demand_id && node.product_id !== node.demand_id && node.demand_id !== contextDemandId ? ` (demand ${node.demand_id})` : ''}`;
                    })()
                  : node.type === 'work_order'
                    ? (() => {
                        const qty = woRowQty ?? Number(node.quantity ?? 0);
                        const lotCount = (node as { lot_count?: number | null }).lot_count ?? null;
                        const maxLotSize = (node as { max_lot_size?: number | null }).max_lot_size ?? null;
                        const lotPart =
                          lotCount && lotCount > 1 && maxLotSize
                            ? ` · ${lotCount} lots of up to ${qtyFmt(Number(maxLotSize))}`
                            : '';
                        return `${node.method} ${node.product_id} @ ${node.location_id ?? '–'} · ${qtyFmt(qty)}${node.end_time ? ` · end ${node.end_time}` : ''}${lotPart}`;
                      })()
                    : node.type === 'supply'
                      ? `${node.product_id} @ ${node.location_id ?? '–'} · ${qtyFmt(Number(node.quantity ?? 0))}${node.supply_id ? ` · ${node.supply_id}` : ''}`
                      : `${node.product_id} @ ${node.location_id ?? '–'} · ${qtyFmt(Number(node.quantity ?? 0))}`;
                const indentPx = 12;
                let childGroupLabel: string | null = null;
                let childGroupKind: 'and' | 'or' | null = null;
                const relation = node.children_relation as 'and' | 'or' | undefined;
                if (relation === 'or' && hasChildren && childrenList.length > 1) {
                  childGroupKind = 'or';
                  childGroupLabel = 'ANY of the inventories / work orders below can supply this node (OR).';
                } else if (relation === 'and' && hasChildren && childrenList.length > 1) {
                  childGroupKind = 'and';
                  childGroupLabel = 'ALL of the inventories / work orders below are required together (AND).';
                } else if (!relation && hasChildren && childrenList.length > 1) {
                  // No explicit relation set. Default by parent type:
                  //   - work_order children are BOM components → AND (all required).
                  //   - demand children are independent supply paths (waterfall slots,
                  //     inventory buckets, alternative methods) → OR. Exception:
                  //     same-product FIFO buckets — neither AND nor OR, no label.
                  //   - supply/purchase nodes shouldn't normally have multiple children.
                  if (node.type === 'work_order') {
                    childGroupKind = 'and';
                    childGroupLabel = 'ALL of the inventories / work orders below are required together (AND).';
                  } else if (node.type === 'demand') {
                    const sameProductBucketsOnly = childrenList.every((c: typeof node) =>
                      (c.type === 'supply' || c.type === 'purchase') &&
                      c.product_id === node.product_id,
                    );
                    if (!sameProductBucketsOnly) {
                      childGroupKind = 'or';
                      childGroupLabel = 'ANY of the inventories / work orders below can supply this node (OR).';
                    }
                  }
                }
                const isActiveMatch = planPeggingMatchPath === path;
                const isAnyMatch = planPeggingMatchPaths.includes(path);
                return (
                  <div
                    key={path}
                    style={{ marginBottom: 4 }}
                    ref={isActiveMatch ? ((el) => { if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }) : undefined}
                  >
                    <button
                      type="button"
                      onClick={toggle}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 6,
                        width: '100%',
                        textAlign: 'left',
                        padding: '4px 6px',
                        background: isActiveMatch
                          ? 'rgba(250, 204, 21, 0.28)'
                          : isAnyMatch
                            ? 'rgba(250, 204, 21, 0.12)'
                            : depth % 2 === 0 ? 'rgba(255,255,255,0.04)' : 'transparent',
                        border: isActiveMatch ? '1px solid #facc15' : 'none',
                        borderRadius: 4,
                        color: '#e4e4e7',
                        cursor: expandable ? 'pointer' : 'default',
                        fontSize: '0.85rem',
                      }}
                    >
                      <span style={{ width: 14, flexShrink: 0 }}>{expandable ? (isExpanded ? '▼' : '▶') : '·'}</span>
                      <span style={{ width: 18, flexShrink: 0, fontSize: '0.9em', color: typeColor }} title={typeLabel}>{icon}</span>
                      <span style={{ flex: 1, color: typeColor }}>{label}</span>
                    </button>
                    {node.type === 'work_order' && node.method_choice_explanation && (() => {
                      const explanationPath = `explain-${path}`;
                      const isExplanationOpen = planExplanationExpanded.has(explanationPath);
                      const toggleExplanation = () => setPlanExplanationExpanded((prev) => {
                        const next = new Set(prev);
                        if (next.has(explanationPath)) next.delete(explanationPath);
                        else next.add(explanationPath);
                        return next;
                      });
                      return (
                        <div style={{ marginTop: 4, marginLeft: 4, fontSize: '0.75rem', color: '#a1a1aa' }}>
                          <button
                            type="button"
                            onClick={toggleExplanation}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 4,
                              padding: '2px 0',
                              background: 'none',
                              border: 'none',
                              color: '#71717a',
                              cursor: 'pointer',
                              fontSize: '0.75rem',
                            }}
                          >
                            {isExplanationOpen ? '▼' : '▶'}
                            Why (method)
                          </button>
                          {isExplanationOpen && (
                            <div style={{ paddingLeft: 8, borderLeft: '2px solid #3d3d40', marginTop: 2 }}>
                              <p style={{ margin: 0, lineHeight: 1.35 }}><strong>Method:</strong> {node.method_choice_explanation}</p>
                            </div>
                          )}
                        </div>
                      );
                    })()}
                    {expandable && isExpanded && (
                      <div style={{ marginTop: 2, paddingLeft: indentPx }}>
                        {childGroupLabel && (
                          <div
                            style={{
                              marginBottom: 2,
                              display: 'inline-flex',
                              alignItems: 'center',
                              gap: 4,
                              fontSize: '0.7rem',
                              color: childGroupKind === 'and' ? '#f97316' : '#38bdf8',
                              backgroundColor: childGroupKind === 'and' ? 'rgba(249,115,22,0.12)' : 'rgba(56,189,248,0.12)',
                              borderRadius: 999,
                              padding: '1px 6px',
                            }}
                          >
                            <span style={{ fontWeight: 700 }}>{childGroupKind === 'and' ? 'AND' : 'OR'}</span>
                            <span>{childGroupLabel}</span>
                          </div>
                        )}
                        {childrenList.length > 0
                          ? childrenList.map((child, i) => renderNode(child, `${path}-${i}`, depth + 1, xlink))
                          : consolidatedSourceTrees.length > 0
                            ? null /* rendered below */
                            : node.type === 'demand'
                              ? <p style={{ margin: 0, fontSize: '0.8rem', color: '#f87171' }}>No work orders — planning could not fulfill this demand (no method or child failed).</p>
                              : node.type === 'work_order'
                                ? <p style={{ margin: 0, fontSize: '0.8rem', color: '#71717a' }}>No component breakdown (leaf work order or depth-limited).</p>
                                : null
                        }
                        {consolidatedSourceTrees.length > 0 && isExpanded && (
                          <>
                            <div style={{ marginBottom: 3, marginTop: 2, display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.7rem', color: '#fb923c', backgroundColor: 'rgba(251,146,60,0.12)', borderRadius: 999, padding: '1px 6px' }}>
                              ↑ original supplies consumed by this consolidation
                            </div>
                            {consolidatedSourceTrees.map((t, i) => renderNode(t, `${path}-cs${i}`, depth + 1, true))}
                          </>
                        )}
                      </div>
                    )}
                  </div>
                );
              }
              return (
                <>
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    marginTop: '0.25rem', marginBottom: '0.5rem',
                    padding: '4px 6px', background: '#1c1c1e',
                    border: '1px solid #3d3d40', borderRadius: 4,
                  }}>
                    <input
                      type="text"
                      value={planPeggingSearch}
                      onChange={(e) => setPlanPeggingSearch(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault();
                          if (planPeggingMatchPaths.length > 0) stepMatch(e.shiftKey ? -1 : 1);
                          else runSearch(planPeggingSearch);
                        } else if (e.key === 'Escape') {
                          setPlanPeggingSearch('');
                          setPlanPeggingMatchPaths([]);
                          setPlanPeggingMatchPath(null);
                          setPlanPeggingMatchIndex(0);
                        } else {
                          // Any edit invalidates prior matches; user presses Enter/Find to re-search.
                          if (planPeggingMatchPaths.length > 0) {
                            setPlanPeggingMatchPaths([]);
                            setPlanPeggingMatchPath(null);
                            setPlanPeggingMatchIndex(0);
                          }
                        }
                      }}
                      placeholder="Find in pegging (product / location / supply / demand id)…"
                      style={{ flex: 1, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem' }}
                    />
                    <button type="button" onClick={() => runSearch(planPeggingSearch)}
                      style={{ padding: '3px 8px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.78rem' }}>
                      Find
                    </button>
                    <button type="button" onClick={() => stepMatch(-1)} disabled={planPeggingMatchPaths.length === 0}
                      style={{ padding: '3px 8px', background: '#2d2d30', color: planPeggingMatchPaths.length === 0 ? '#52525b' : '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: planPeggingMatchPaths.length === 0 ? 'default' : 'pointer', fontSize: '0.78rem' }}>
                      ↑
                    </button>
                    <button type="button" onClick={() => stepMatch(1)} disabled={planPeggingMatchPaths.length === 0}
                      style={{ padding: '3px 8px', background: '#2d2d30', color: planPeggingMatchPaths.length === 0 ? '#52525b' : '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: planPeggingMatchPaths.length === 0 ? 'default' : 'pointer', fontSize: '0.78rem' }}>
                      ↓
                    </button>
                    <span style={{ fontSize: '0.72rem', color: '#a1a1aa', minWidth: 60, textAlign: 'right' }}>
                      {planPeggingMatchPaths.length === 0
                        ? (planPeggingSearch.trim() ? 'no match' : '')
                        : `${planPeggingMatchIndex + 1} / ${planPeggingMatchPaths.length}`}
                    </span>
                  </div>
                  <div style={{ marginTop: '0.5rem' }}>{tree ? renderNode(tree, '0', 0) : null}</div>
                </>
              );
            })()}
          </div>
        </div>,
        document.body
      )}
      {(basketSlideInRow != null || basketShowingFinal) && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 50,
            display: 'flex',
            justifyContent: 'flex-end',
          }}
          role="dialog"
          aria-label={basketShowingFinal ? 'Basket at end of loaded view' : 'Basket after step'}
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)' }}
            onClick={() => { setBasketSlideInRow(null); setBasketShowingFinal(false); }}
          />
          <div
            style={{
              width: 'min(380px, 100vw)',
              maxHeight: '100vh',
              overflow: 'auto',
              background: '#1c1c1e',
              color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)',
              padding: '1.25rem',
              position: 'relative',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
              <h3 style={{ margin: 0, color: '#fafafa' }}>
                {basketShowingFinal ? 'Basket at end of loaded view' : `Basket after step ${(basketSlideInRow?.basket_step_index ?? 0) + 1}`}
              </h3>
              <button type="button" onClick={() => { setBasketSlideInRow(null); setBasketShowingFinal(false); }} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
            </div>
            {!basketShowingFinal && basketSlideInRow != null && (
              <>
                <p style={{ margin: 0, color: '#a1a1aa', fontSize: '0.9rem' }}>
                  Row {basketSlideInRow.step ?? '–'} · From {basketSlideInRow.from_inventory_display ?? '–'}
                </p>
                {(basketSlideInRow.candidates?.length ?? 0) > 0 && (
                  <>
                    <h4 style={{ margin: '1rem 0 0.5rem', fontSize: '0.9rem', color: '#a78bfa' }}>Target candidates</h4>
                    <ul style={{ marginTop: 0, paddingLeft: '1.25rem', listStyle: 'disc' }}>
                      {basketSlideInRow.candidates!.map((c, j) => (
                        <li key={j} style={{ marginBottom: 4 }}>
                          <code style={{ background: '#2d2d30', padding: '2px 6px', borderRadius: 4 }}>{c.to_inventory_display}</code>
                          <span style={{ marginLeft: 8, fontWeight: 600 }}>{c.qty}</span>
                          {c.edge_type ? <span style={{ marginLeft: 6, fontSize: '0.8em', color: '#71717a' }}>({c.edge_type})</span> : null}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </>
            )}
            <h4 style={{ margin: '1rem 0 0.5rem', fontSize: '0.9rem', color: '#a1a1aa' }}>Basket (scarcity order)</h4>
            <p style={{ margin: '0 0 0.5rem', fontSize: '0.8rem', color: '#71717a' }}>
              {basketShowingFinal ? 'Final basket after all allocation steps. Consumed supplies are removed.' : 'Scarcity = total quantity per component; scarcest first. Basket = initial supplies (minus consumed) + produced in this run. Produced output is added after its step, so it appears at the beginning of the next step.'}
            </p>
            {basketDeltas.length === 0 ? (
              <p style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>{basketLoading ? 'Loading basket…' : 'Basket not loaded yet.'}</p>
            ) : (
            <ul style={{ marginTop: 0, paddingLeft: '1.25rem', listStyle: 'disc' }}>
              {(() => {
                const items = basketShowingFinal && basketFinal
                  ? basketFinal.map((b) => ({ ...b, fromInitialSupply: basketInitialKeys.has(b.key) }))
                  : computeBasketAfterStep(basketSlideInRow?.basket_step_index ?? 0);
                return items.length === 0 ? (
                  <li style={{ color: '#71717a' }}>No inventory in basket</li>
                ) : (
                  items.map((b, i) => (
                    <li key={i} style={{ marginBottom: 6 }}>
                      <span style={{ marginRight: 6, color: '#a1a1aa' }}>{i + 1}.</span>
                      <code style={{ background: '#2d2d30', padding: '2px 6px', borderRadius: 4 }}>{b.display}</code>
                      <span style={{ marginLeft: 8, fontWeight: 600 }}>{b.qty}</span>
                      {b.fromInitialSupply ? (
                        <span style={{ marginLeft: 6, fontSize: '0.75rem', color: '#71717a' }}>(initial supply)</span>
                      ) : (
                        <span style={{ marginLeft: 6, fontSize: '0.75rem', color: '#a78bfa' }}>(produced this run)</span>
                      )}
                    </li>
                  ))
                );
              })()}
            </ul>
            )}
          </div>
        </div>
      )}
      {explanationOpen && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 50,
            display: 'flex',
            justifyContent: 'flex-end',
          }}
          role="dialog"
          aria-label="Split explanation"
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)' }}
            onClick={() => setExplanationOpen(false)}
          />
          <div
            style={{
              width: 'min(420px, 100vw)',
              maxHeight: '100vh',
              overflow: 'auto',
              background: '#1c1c1e',
              color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)',
              padding: '1.25rem',
              position: 'relative',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
              <h3 style={{ margin: 0, color: '#fafafa' }}>Split explanation</h3>
              <button type="button" onClick={() => setExplanationOpen(false)} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
            </div>
            {explanationLoading && <p style={{ color: '#a1a1aa' }}>Loading…</p>}
            {!explanationLoading && explanationData && (
              <div style={{ fontSize: '0.9rem', color: '#e4e4e7' }}>
                <p><strong>Critical component</strong>: <code style={{ background: '#2d2d30', padding: '2px 6px', borderRadius: 4 }}>{explanationData.component_display}</code></p>
                <p style={{ marginTop: '0.75rem' }}><strong>Total supply for this component</strong> (initial): {explanationData.total_supply_for_component}</p>
                {explanationData.available_during_run != null && explanationData.available_during_run !== explanationData.total_supply_for_component && (
                  <p style={{ marginTop: '0.25rem', color: '#a1a1aa' }}><strong>Available during run</strong> (initial + produced): {explanationData.available_during_run}</p>
                )}
                {explanationData.supply_note && (
                  <p style={{ marginTop: '0.5rem', padding: '0.5rem', background: '#3d3d40', color: '#a1a1aa', borderRadius: 6, fontSize: '0.85rem' }}>
                    {explanationData.supply_note}
                  </p>
                )}
                {explanationData.weight_formula && (
                  <p style={{ marginTop: '0.75rem', padding: '0.5rem', background: '#2d2d30', color: '#a1a1aa', borderRadius: 6, border: '1px solid #3d3d40', fontSize: '0.85rem' }}>
                    <strong>How weights are calculated</strong>: {explanationData.weight_formula}
                  </p>
                )}
                <p style={{ marginTop: '0.75rem' }}><strong>Candidate targets</strong> (edges that consume this component){explanationData.total_candidate_weight != null ? ` · total weight = ${explanationData.total_candidate_weight}` : ''}:</p>
                <ul style={{ margin: '0.25rem 0', paddingLeft: '1.25rem' }}>
                  {explanationData.candidate_targets.map((t) => (
                    <li key={t.variant_key}>
                      <code style={{ background: '#2d2d30', padding: '2px 6px', borderRadius: 4 }}>{t.variant_display}</code> ({t.edge_type})
                      {t.target_weight != null && <span style={{ color: '#a1a1aa', marginLeft: 6 }}>weight = {t.target_weight}</span>}
                      {t.weight_calculation && <div style={{ fontSize: '0.8rem', color: '#71717a', marginTop: 2 }}>{t.weight_calculation}</div>}
                    </li>
                  ))}
                </ul>
                {explanationData.candidate_targets.length === 0 && <p style={{ margin: 0, color: '#71717a' }}>None</p>}
                <p style={{ marginTop: '0.75rem' }}><strong>Usage in this run</strong> (available before → qty allocated):</p>
                <ul style={{ margin: '0.25rem 0', paddingLeft: '1.25rem' }}>
                  {explanationData.steps.map((s, i) => (
                    <li key={i}>
                      <code style={{ background: '#2d2d30', padding: '2px 6px', borderRadius: 4 }}>{s.to_variant_display}</code> ({s.edge_type}): available before = {s.available_before} → qty = {s.qty}
                    </li>
                  ))}
                </ul>
                {explanationData.steps.length === 0 && <p style={{ margin: 0, color: '#71717a' }}>None</p>}
                <p style={{ marginTop: '1rem', padding: '0.5rem', background: '#2d2d30', color: '#a1a1aa', borderRadius: 6, border: '1px solid #3d3d40' }}>{explanationData.reason}</p>
              </div>
            )}
            {!explanationLoading && !explanationData && <p style={{ color: '#a1a1aa' }}>Could not load explanation.</p>}
          </div>
        </div>
      )}
      {peggingOpen && typeof document !== 'undefined' && createPortal(
        <div
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 9999,
            display: 'flex',
            justifyContent: 'flex-end',
            pointerEvents: 'auto',
          }}
          role="dialog"
          aria-label="Pegging"
        >
          <div
            style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', zIndex: 0, pointerEvents: 'auto' }}
            onClick={() => setPeggingOpen(false)}
            aria-hidden
          />
          <div
            onMouseDown={(e) => e.stopPropagation()}
            onClick={(e) => e.stopPropagation()}
            style={{
              position: 'relative',
              zIndex: 10,
              width: peggingPanelWidth,
              maxWidth: '90vw',
              minWidth: 320,
              maxHeight: '100vh',
              overflow: 'auto',
              background: '#1c1c1e',
              color: '#e4e4e7',
              boxShadow: '-4px 0 24px rgba(0,0,0,0.4)',
              padding: '1.25rem',
              pointerEvents: 'auto',
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <div
              role="separator"
              aria-label="Resize pegging panel"
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
                peggingResizeRef.current = { startX: e.clientX, startW: peggingPanelWidth };
                setPeggingResizing(true);
              }}
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                bottom: 0,
                width: 6,
                cursor: 'col-resize',
                zIndex: 11,
              }}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <h3 style={{ margin: 0, color: '#fafafa' }}>{peggingTitle}</h3>
              <button type="button" onClick={() => setPeggingOpen(false)} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tc('close')}</button>
            </div>
            {peggingData?.direction === 'demand-to-supply' && (peggingData.demand_allocated_qty != null || peggingData.demand_requested_qty != null) && (
              <>
                <p style={{ margin: 0, marginBottom: '0.25rem', color: '#a1a1aa', fontSize: '0.9rem' }}>
                  {tP('peggingPanel.allocated')} <strong style={{ color: '#fafafa' }}>{qtyFmt(Number(peggingData.demand_allocated_qty ?? 0))}</strong>
                  {peggingData.demand_requested_qty != null && (
                    <> {tP('peggingPanel.requested')}{qtyFmt(Number(peggingData.demand_requested_qty))})</>
                  )}
                </p>
                <p style={{ margin: 0, marginBottom: '1rem', color: '#71717a', fontSize: '0.8rem' }}>
                  {tP('peggingPanel.info')}
                </p>
              </>
            )}
            {peggingLoading && <p style={{ color: '#a1a1aa' }}>{tc('loading')}</p>}
            {!peggingLoading && peggingData && !peggingTreeReady && (
              <div style={{ fontSize: '0.9rem' }}>
                <p><strong>{tP('peggingPanel.nodes')}:</strong> {(Array.isArray(peggingData.nodes) ? peggingData.nodes : []).length} &nbsp; <strong>{tP('peggingPanel.edges')}:</strong> {(Array.isArray(peggingData.edges) ? peggingData.edges : []).length}</p>
                <p style={{ color: '#a1a1aa' }}>{tP('peggingPanel.buildingTree')}</p>
              </div>
            )}
            {!peggingLoading && peggingData && peggingTreeReady && peggingGraph && (
              <PeggingTree
                graph={peggingGraph}
                expanded={peggingExpanded}
                onExpand={(pathKey, isExpanding) => {
                  setPeggingExpanded((prev) => {
                    const next = new Set(prev);
                    if (next.has(pathKey)) next.delete(pathKey);
                    else next.add(pathKey);
                    return next;
                  });
                  if (isExpanding) {
                    setPeggingChildrenAllowedFor((prev) => new Set(prev).add(pathKey));
                  }
                }}
                childrenAllowedFor={peggingChildrenAllowedFor}
                direction={peggingData.direction}
                servedDemandIds={peggingData.direction === 'supply-to-demand' && peggingData.critical_paths_by_demand?.paths_by_demand
                  ? peggingData.critical_paths_by_demand.paths_by_demand.filter((p: { path?: string[] }) => p.path?.length).map((p: { demand_id: string }) => p.demand_id)
                  : []}
              />
            )}
            {!peggingLoading && !peggingData && <p style={{ color: '#a1a1aa' }}>Could not load pegging.</p>}
          </div>
        </div>,
        document.body
      )}
    </div>
  );
}
