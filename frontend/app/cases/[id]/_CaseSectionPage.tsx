'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
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
  listPlanRuns,
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
  listMaterialEvents,
  createMaterialEvent,
  updateMaterialEvent,
  deleteMaterialEvent,
  analyzeMaterialImpact,
  type CaseSupplyRow,
  type PeggedDemandEntry,
  type PlanSupplyViewRow,
  getCaseSupplies,
  type AssessmentSummary,
  type AssessmentResponse,
  getAssessmentCriteria,
  setAssessmentCriteria,
  listAssessments,
  runAssessment,
  savePlanRun,
  updatePlanRun,
} from '@/lib/api';

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

/** True if any supply node in the pegging tree has a supply_id containing the given substring (case-insensitive). */
function peggingTreeContainsSupply(node: PlanningPeggingNode, supplySubstr: string): boolean {
  if (node.type === 'supply') {
    const sid = (node.supply_id ?? '').trim().toLowerCase();
    if (sid && sid.includes(supplySubstr)) return true;
  }
  for (const child of node.children ?? []) {
    if (peggingTreeContainsSupply(child, supplySubstr)) return true;
  }
  return false;
}

/** Renders Plan KPIs when plan result exists; builds kpis from backend plan_kpis or derives from committed_demands/work_orders. */
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
      inventory: inv,
      procurement: byMethod('purchase'),
      manufacturing: byMethod('make'),
      logistics: byMethod('move'),
    };
  }
  const d = kpis.delivery ?? {};
  const inv = kpis.inventory ?? {};
  const proc = kpis.procurement ?? {};
  const mfg = kpis.manufacturing ?? {};
  const log = kpis.logistics ?? {};
  const card = (title: string, items: { label: string; value: string }[], accent?: string) => (
    <div key={title} style={{ flex: '1 1 160px', minWidth: 140, padding: '0.75rem 1rem', background: 'rgba(255,255,255,0.04)', borderRadius: 8, border: '1px solid #3d3d40' }}>
      <div style={{ fontSize: '0.7rem', color: '#a1a1aa', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '0.5rem', fontWeight: 600 }}>{title}</div>
      {items.map(({ label, value }) => (
        <div key={label} style={{ marginTop: 4 }}>
          <span style={{ fontSize: '0.75rem', color: '#71717a' }}>{label}</span>
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
          { label: tK('committedRequested'), value: `${Number(d.total_committed ?? 0).toLocaleString()} / ${Number(d.total_requested ?? 0).toLocaleString()}` },
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
        {card(tK('inventory'), [
          { label: tK('consumptionRate'), value: inv.consumption_rate != null ? `${(Number(inv.consumption_rate) * 100).toFixed(1)}%` : '–' },
          { label: tK('consumed'), value: Number(inv.consumed_total ?? 0).toLocaleString() },
          { label: tK('initialSupply'), value: Number(inv.initial_total ?? 0).toLocaleString() },
        ], '#a78bfa')}
        {card(tK('procurement'), [
          { label: tK('orders'), value: String(proc.order_count ?? 0) },
          { label: tK('totalQuantity'), value: Number(proc.total_quantity ?? 0).toLocaleString() },
        ], '#f59e0b')}
        {card(tK('manufacturing'), [
          { label: tK('orders'), value: String(mfg.order_count ?? 0) },
          { label: tK('totalQuantity'), value: Number(mfg.total_quantity ?? 0).toLocaleString() },
        ], '#3b82f6')}
        {card(tK('logistics'), [
          { label: tK('orders'), value: String(log.order_count ?? 0) },
          { label: tK('totalQuantity'), value: Number(log.total_quantity ?? 0).toLocaleString() },
        ], '#06b6d4')}
      </div>
    </div>
  );
}

import { SortFilterTable } from '@/app/components/SortFilterTable';

/** Extract a human-readable message from an API error.
 *  The backend often returns JSON bodies like {"detail":"..."} or {"error":"..."}.
 *  If the raw string is valid JSON with one of those fields, return that field's value;
 *  otherwise return the raw string as-is. */
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
import { PeggingTree, pathKeyFromPath, type PeggingGraph } from '@/app/components/PeggingTree';
import BomGraphTab from '@/app/components/BomGraphTab';

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

export function CaseDetail({ section: sectionProp = 'planning', subsection }: { section?: string; subsection?: string }) {
  const tNav = useTranslations('nav');
  const tSec = useTranslations('sections');
  const tA = useTranslations('allocation');
  const tP = useTranslations('planning');
  const tc = useTranslations('common');
  // Locale-aware section headers used when assembling / saving criteria text
  const criteriaHeaders = {
    high:   tP('assessment.criteriaHeader', { tier: 'HIGH' }),
    low:    tP('assessment.criteriaHeader', { tier: 'LOW' }),
    medium: tP('assessment.criteriaHeader', { tier: 'MEDIUM' }),
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
  const [planExplanationExpanded, setPlanExplanationExpanded] = useState<Set<string>>(new Set());
  const [planPeggingPanelWidth, setPlanPeggingPanelWidth] = useState(420);
  const [planWorkOrderPeggingCache, setPlanWorkOrderPeggingCache] = useState<Record<string, PlanningPeggingNode>>({});
  // Active demand for WO pegging panel; null = use the row's own demand_id (default)
  const [woPeggingActiveDemandId, setWoPeggingActiveDemandId] = useState<string | null>(null);
  // When drilling from supply pegging panel into a demand tree, remembers the supply context for the back link.
  const [previousPeggingContext, setPreviousPeggingContext] = useState<{
    type: 'supply'; supplyId: string; peggedDemands: PeggedDemandEntry[]; initialQty: number; consumedQty: number;
  } | null>(null);
  const [planWorkOrderPeggingLoading, setPlanWorkOrderPeggingLoading] = useState<string | null>(null);
  const [planWorkOrderPeggingError, setPlanWorkOrderPeggingError] = useState<string | null>(null);
  const planPeggingResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [planPeggingResizing, setPlanPeggingResizing] = useState(false);
  const [planResultTab, setPlanResultTab] = useState<'demands' | 'work_orders' | 'supplies'>('demands');
  // ID of the plan run currently loaded in planResult; null = freshly-run (not from history)
  const [currentPlanRunId, setCurrentPlanRunId] = useState<number | null>(null);
  // DB run ID for the current fresh (unsaved) plan result; null once saved or when loading from history
  const [freshPlanRunId, setFreshPlanRunId] = useState<number | null>(null);
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
  const [planWoPivot, setPlanWoPivot] = useState<'none' | 'prod_area' | 'location' | 'nested'>('none');
  const [planWoPivotExpanded, setPlanWoPivotExpanded] = useState<Set<string>>(new Set());
  const [planWoPivotSubExpanded, setPlanWoPivotSubExpanded] = useState<Set<string>>(new Set());
  const [woExplainOpen, setWoExplainOpen] = useState(false);
  const [woExplainRow, setWoExplainRow] = useState<WorkOrder | null>(null);
  const [woExplainKey, setWoExplainKey] = useState<string | null>(null);
  const [woPeggingRowKey, setWoPeggingRowKey] = useState<string | null>(null);
  const [bomRealPairs, setBomRealPairs] = useState<[string, string][] | null>(null);
  const [realMoveTriples, setRealMoveTriples] = useState<[string, string, string][] | null>(null);
  const [planningConfig, setPlanningConfig] = useState<PlanningConfig>({ consolidation: { enabled: true }, purchase_allowed: false });
  const [planJobId, setPlanJobId] = useState<string | null>(null);
  const [planProgress, setPlanProgress] = useState<{ current: number; total: number } | null>(null);
  const planPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [copilotOpen, setCopilotOpen] = useState(false);
  const [copilotMessages, setCopilotMessages] = useState<PlanningCopilotMessage[]>([]);
  const [copilotInput, setCopilotInput] = useState('');
  const [copilotLoading, setCopilotLoading] = useState(false);
  const [copilotPanelWidth, setCopilotPanelWidth] = useState(440);
  const copilotResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [copilotResizing, setCopilotResizing] = useState(false);
  const copilotMessagesEndRef = useRef<HTMLDivElement | null>(null);

  // ── Supply criticality analysis state ─────────────────────────────────────
  const [supplyCriticalityMap, setSupplyCriticalityMap] = useState<Record<string, 'critical' | 'not_critical' | 'running' | 'error'>>({});
  const [criticalityRunning, setCriticalityRunning] = useState(false);
  const [criticalityProgress, setCriticalityProgress] = useState<{ done: number; total: number } | null>(null);
  // Whether to auto-run criticality scan after planning (config option, default: off)
  const [analyzeCriticalityEnabled, setAnalyzeCriticalityEnabled] = useState(false);
  // Set to true after plan completes with analyzeCriticalityEnabled; cleared once caseSupplies loads and analysis starts
  const [autoCriticalityPending, setAutoCriticalityPending] = useState(false);

  // ── Plan run history state ──────────────────────────────────────────────────
  const [planRunHistory, setPlanRunHistory] = useState<PlanRun[]>([]);
  const currentRunIsContingent = currentPlanRunId != null &&
    planRunHistory.find(r => r.id === currentPlanRunId)?.status === 'contingent';
  const [planRunHistoryOpen, setPlanRunHistoryOpen] = useState(false);
  const [planRunHistoryLoading, setPlanRunHistoryLoading] = useState(false);
  const [planRunLoadingId, setPlanRunLoadingId] = useState<number | null>(null);

  // ── Override dialog state ───────────────────────────────────────────────────
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [overrideDialogType, setOverrideDialogType] = useState<'method_selection' | 'variant_selection' | 'component_split' | null>(null);
  const [overrideDialogWo, setOverrideDialogWo] = useState<WorkOrder | null>(null);
  const [overrideDialogSaving, setOverrideDialogSaving] = useState(false);
  const [overrideDialogError, setOverrideDialogError] = useState<string | null>(null);
  // Structured override form state (type-specific; avoids raw JSON editing)
  const [overrideMethodValue, setOverrideMethodValue] = useState<string>('make');
  const [overrideVariantValue, setOverrideVariantValue] = useState('');
  const [overrideSplitRows, setOverrideSplitRows] = useState<Array<{
    demand_id: string | null;
    qty: number;
    requested_qty: number;
    priority: number;
    parent_product: string;
  }>>([]);

  // Panel resize state
  const [woExplainPanelWidth, setWoExplainPanelWidth] = useState(420);
  const woExplainResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [woExplainResizing, setWoExplainResizing] = useState(false);
  const [planRunHistoryPanelWidth, setPlanRunHistoryPanelWidth] = useState(520);
  const planRunHistoryResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [planRunHistoryResizing, setPlanRunHistoryResizing] = useState(false);

  /** Rule-based intent: map user message to config updates and a reply for variant, method, and consolidation selection. */
  function parseCopilotIntent(message: string, currentConfig: PlanningConfig): { reply: string; configUpdate?: PlanningConfig } {
    const t = message.trim().toLowerCase();
    const vs = currentConfig.variant_selection ?? {};
    const ms = currentConfig.method_selection ?? {};
    const cs = currentConfig.consolidation ?? {};
    const multi = vs.multiple;

    if (!t) return { reply: 'You can configure variant selection, method selection, or shared-component consolidation. Say "show config" to see current settings.' };

    if (/show|current|what('s| is)? (my )?config|settings|config/.test(t)) {
      const variantMode = multi === false ? 'single best variant' : 'all feasible variants (equal split)';
      const methodMode = ms.multiple === true ? 'equal split across methods' : ms.elaborate === true ? 'one by score (elaborate)' : 'one by preference (cascade — tries preferred first, falls back to next if children fail)';
      const purchaseMode = currentConfig.purchase_allowed === false ? 'disabled' : 'allowed';
      const consolidationMode = cs.enabled
        ? `on · ${cs.period_days ?? 7}d bucket · ${cs.allocation_mode === 'proportional' ? 'proportional' : 'priority-first'} split`
        : 'off';
      return { reply: `Variant selection: **${variantMode}**. Method selection: **${methodMode}**. Purchase: **${purchaseMode}**. Consolidation: **${consolidationMode}**.` };
    }

    if (/single|one variant|only one|best variant|use one/.test(t)) {
      return {
        reply: 'Set variant selection to **single best variant**. The planner will pick one best BOM/variant per demand (by score: earliest commit, most inventory consumed, least purchase). Re-run plan to apply.',
        configUpdate: { variant_selection: { ...vs, multiple: false } },
      };
    }

    if (/all variants|multiple variants|every variant|equal split.*variant|divide (across|among).*variant/.test(t)) {
      return {
        reply: 'Set variant selection to **all feasible variants** with equal split. Demand will be divided among all feasible BOM/variants. Re-run plan to apply.',
        configUpdate: { variant_selection: { ...vs, multiple: true } },
      };
    }

    if (/equal.?split.?method|split.?method.?equal|split across method|multiple method|use all method/.test(t)) {
      return {
        reply: 'Set method selection to **equal split across methods**. When multiple make/move/buy methods can fulfill a demand, quantity is divided equally among them. Re-run plan to apply.',
        configUpdate: { method_selection: { ...ms, multiple: true, elaborate: false } },
      };
    }

    if (/elaborate method|simulate method|score method|method by score/.test(t)) {
      return {
        reply: 'Set method selection to **elaborate (score by simulation)**. The planner simulates each method\'s child materials and picks the one with earliest commit, most inventory consumed, and least purchase. Slower. Re-run plan to apply.',
        configUpdate: { method_selection: { ...ms, elaborate: true, multiple: false } },
      };
    }

    if (/by preference|prefer method|cascade method|preferred method|one by preference/.test(t)) {
      return {
        reply: 'Set method selection to **by preference (cascade)**. The planner tries the most preferred method first; if its child materials cannot be planned, it falls back to the next preferred method. Re-run plan to apply.',
        configUpdate: { method_selection: { ...ms, multiple: false, elaborate: false } },
      };
    }

    if (/no purchase|disable purchase|disallow purchase|no buy|exclude buy|without purchase/.test(t)) {
      return {
        reply: 'Purchase (buy method) **disabled**. The planner will not use buy methods; demands will be fulfilled from inventory, make, or move only. Re-run plan to apply.',
        configUpdate: { purchase_allowed: false },
      };
    }

    if (/allow purchase|enable purchase|purchase allowed|include buy|with purchase/.test(t)) {
      return {
        reply: 'Purchase (buy method) **allowed**. The planner will use buy methods when available. Re-run plan to apply.',
        configUpdate: { purchase_allowed: true },
      };
    }

    if (/enable consolidat|turn on consolidat|consolidate demand|group demand|shared.?component/.test(t)) {
      return {
        reply: `Enabled **shared-component consolidation**. Demands within the same time bucket (currently ${cs.period_days ?? 7} days) that share a component will be grouped into one work order, then output is split by ${cs.allocation_mode === 'proportional' ? 'proportional qty' : 'priority order'}. Re-run plan to apply.`,
        configUpdate: { consolidation: { ...cs, enabled: true } },
      };
    }

    if (/disable consolidat|turn off consolidat|no consolidat/.test(t)) {
      return {
        reply: 'Disabled consolidation. Each demand will plan its components independently. Re-run plan to apply.',
        configUpdate: { consolidation: { ...cs, enabled: false } },
      };
    }

    const periodMatch = t.match(/(\d+)\s*(?:-\s*)?day(?:s)?\s*(?:bucket|period|window)/);
    if (periodMatch || /bucket.*(\d+)|period.*(\d+)/.test(t)) {
      const m2 = t.match(/(\d+)/);
      const days = Math.max(1, Math.min(365, parseInt(periodMatch?.[1] ?? m2?.[1] ?? '7', 10)));
      return {
        reply: `Set consolidation time bucket to **${days} day${days === 1 ? '' : 's'}**. Re-run plan to apply.`,
        configUpdate: { consolidation: { ...cs, period_days: days } },
      };
    }

    if (/proportional|split by (qty|quantity|share)|by share/.test(t)) {
      return {
        reply: 'Set consolidation split policy to **proportional** — output is divided in proportion to each demand\'s requested quantity. Re-run plan to apply.',
        configUpdate: { consolidation: { ...cs, allocation_mode: 'proportional' } },
      };
    }

    if (/priority.?first|fill highest priority|by priority|priority order/.test(t)) {
      return {
        reply: 'Set consolidation split policy to **priority-first** — highest-priority demands are filled first from consolidated output. Re-run plan to apply.',
        configUpdate: { consolidation: { ...cs, allocation_mode: 'priority_first' } },
      };
    }

    if (/reset|default|clear/.test(t)) {
      return {
        reply: 'Reset to defaults: all feasible variants (equal split), by preference (cascade) method selection, purchase allowed, consolidation off. Re-run plan to apply.',
        configUpdate: { variant_selection: { multiple: true }, method_selection: { multiple: false, elaborate: false }, purchase_allowed: true, consolidation: { enabled: false } },
      };
    }

    return {
      reply: 'I handle variant selection, method selection, purchase, and shared-component consolidation. Try: "single best variant", "all variants", "by preference", "elaborate method", "equal split methods", "no purchase", "allow purchase", "enable consolidation", "set 14 day bucket", "proportional split", or "show config".',
    };
  }

  useEffect(() => {
    copilotMessagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [copilotMessages]);

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
          if (analyzeCriticalityEnabled && freshId && id != null) {
            // Auto-persist so impact/criticality analysis can use the persisted run.
            try {
              await savePlanRun(Number(id), freshId);
              setCurrentPlanRunId(freshId);
              setFreshPlanRunId(null);
            } catch {
              // Auto-save failed — leave as unsaved; user can save manually.
              setCurrentPlanRunId(null);
              setFreshPlanRunId(freshId);
            }
            setAutoCriticalityPending(true);
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
      } catch {
        // keep polling on transient errors
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

  // Reset assessment result/history when a different supply is opened in the pegging panel
  const currentPeggingSupplyId = planPeggingContext?.type === 'supply' ? planPeggingContext.supplyId : null;
  useEffect(() => {
    if (currentPeggingSupplyId) {
      setAssessmentResult(null);
      setAssessmentHistory([]);
      setAssessmentHistoryOpen(false);
      setAssessError(null);
    }
  }, [currentPeggingSupplyId]);

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
          setAssessCriteriaHigh(tP('assessment.criteriaDefaultHigh'));
          setAssessCriteriaLow(tP('assessment.criteriaDefaultLow'));
          setAssessCriteriaMedium(tP('assessment.criteriaDefaultMedium'));
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
    getSupplyView(id, runId)
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
        if (full.config) setPlanningConfig(full.config as PlanningConfig);
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
      return s.has(`method_selection|${methodKey}`) || s.has(`variant_selection|${methodKey}`) || s.has(`component_split|${splitKey}`);
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

  /**
   * Invert demand-centric planning_pegging trees into a supply-centric map.
   * Key: supply_id. Value: { totalPeggedQty, demands[] }
   * Consolidated synthetic IDs (consolidated_*) are keyed but won't match any CaseSupplyRow.
   */
  const supplyPeggingMap = useMemo(() => {
    const map = new Map<string, { totalPeggedQty: number; demands: PeggedDemandEntry[] }>();
    if (!planResult?.planning_pegging) return map;

    const demandCustomerMap = new Map<string, string | null>();
    for (const d of planResult.committed_demands ?? []) {
      if (d.demand_id) demandCustomerMap.set(d.demand_id, d.customer ?? null);
    }

    // activeDemandId: the demand currently in scope as we descend the tree.
    // For non-consolidated entries it's the entry-level demand_id throughout.
    // For consolidated entries (entry demand_id = null), demand-type nodes inside
    // the tree carry their own demand_id — we pick it up as we descend.
    function walk(node: PlanningPeggingNode, activeDemandId: string | null) {
      // When we step into a demand node, switch to its own demand_id.
      const effectiveDemandId =
        node.type === 'demand' && node.demand_id ? node.demand_id : activeDemandId;

      if (node.type === 'supply' && node.supply_id && effectiveDemandId) {
        const sid = node.supply_id;
        const qty = Number(node.quantity ?? 0);
        const existing = map.get(sid);
        if (existing) {
          const existingForDemand = existing.demands.find((d) => d.demandId === effectiveDemandId);
          if (existingForDemand) {
            existingForDemand.qtyConsumed += qty;
          } else {
            existing.demands.push({ demandId: effectiveDemandId, customer: demandCustomerMap.get(effectiveDemandId) ?? null, qtyConsumed: qty });
          }
          existing.totalPeggedQty += qty;
        } else {
          map.set(sid, {
            totalPeggedQty: qty,
            demands: [{ demandId: effectiveDemandId, customer: demandCustomerMap.get(effectiveDemandId) ?? null, qtyConsumed: qty }],
          });
        }
      }
      for (const child of node.children ?? []) walk(child, effectiveDemandId);
    }

    for (const entry of planResult.planning_pegging) {
      if (entry.passthrough) continue;
      walk(entry.tree, entry.demand_id ?? null);
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

  /** Join caseSupplies rows with supplyPeggingMap to produce the enriched supply view. */
  const planSupplyViewRows = useMemo((): PlanSupplyViewRow[] => {
    return caseSupplies.map((s) => {
      const pegging = supplyPeggingMap.get(s.supplyId);
      const consumedQty = pegging?.totalPeggedQty ?? 0;
      const residualQty = Math.max(0, s.qty - consumedQty);
      const utilizationRate = s.qty > 0 ? consumedQty / s.qty : null;
      return {
        ...s,
        consumedQty,
        residualQty,
        utilizationRate,
        peggedDemandCount: pegging?.demands.length ?? 0,
        totalPeggedQty: pegging?.totalPeggedQty ?? 0,
        peggedDemands: pegging?.demands ?? [],
      };
    });
  }, [caseSupplies, supplyPeggingMap]);

  /** Sum of qty per productId across all plan supply view rows (unfiltered). */
  const planSupplyProductTotalMap = useMemo((): Record<string, number> => {
    const m: Record<string, number> = {};
    for (const r of planSupplyViewRows) {
      const pid = r.productId ?? '';
      if (pid) m[pid] = (m[pid] ?? 0) + (Number(r.qty) || 0);
    }
    return m;
  }, [planSupplyViewRows]);

  const handleAnalyzeCriticality = async () => {
    const all = planSupplyViewRows.filter(r => !r.supplyId.toLowerCase().endsWith('_dummy'));
    if (all.length === 0) return;

    // Supplies with zero consumption are trivially safe — skip re-plan for them
    const trivialSafe = all.filter(r => r.consumedQty === 0);
    const needsAnalysis = all.filter(r => r.consumedQty > 0);

    setCriticalityRunning(true);
    setCriticalityProgress({ done: trivialSafe.length, total: all.length });
    setSupplyCriticalityMap(prev => {
      const next = { ...prev };
      for (const r of trivialSafe) next[r.supplyId] = 'not_critical';
      for (const r of needsAnalysis) next[r.supplyId] = 'running';
      return next;
    });

    const BATCH = 5;
    let done = trivialSafe.length;
    for (let i = 0; i < needsAnalysis.length; i += BATCH) {
      const batch = needsAnalysis.slice(i, i + BATCH);
      await Promise.all(batch.map(async (r) => {
        try {
          const result = await analyzeMaterialImpact(r.supplyId, 0, 100, false);
          setSupplyCriticalityMap(prev => ({ ...prev, [r.supplyId]: result.impactedDemandCount > 0 ? 'critical' : 'not_critical' }));
        } catch {
          setSupplyCriticalityMap(prev => ({ ...prev, [r.supplyId]: 'error' }));
        }
        done++;
        setCriticalityProgress({ done, total: all.length });
      }));
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
    setPeggingTitle(`Pegging: Supply ${row.component_key.replace('|', '@')}`);
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
    setPeggingTitle(`Pegging: Demand ${row.demand_id}`);
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

  const handleRestorePlanRun = async (runId: number) => {
    setPlanRunLoadingId(runId);
    try {
      const full = await getPlanRun(id, runId);
      if (full.result) {
        setPlanResult(full.result as typeof planResult);
        setCurrentPlanRunId(runId);
        setFreshPlanRunId(null);
        setPlanRunSaveError(null);
        setPlanWorkOrderPeggingCache({});
        if (full.config) setPlanningConfig(full.config as PlanningConfig);
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

  const openOverrideDialog = (type: 'method_selection' | 'variant_selection' | 'component_split', wo: WorkOrder) => {
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
    } else if (type === 'variant_selection') {
      const productId = wo.product_id ?? '';
      const locationId = wo.location_id ?? '';
      const demandId = wo.demand_id ?? '';
      const entityKey = demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`;
      const existing = overrides.find((o) => o.entity_type === 'variant_selection' && o.entity_key === entityKey);
      const savedAltGroup = existing ? (existing.payload as Record<string, unknown>).alt_group as string | undefined : undefined;
      setOverrideVariantValue(savedAltGroup ?? '');
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
    if (!overrideDialogType || !overrideDialogWo) return;
    setOverrideDialogSaving(true);
    setOverrideDialogError(null);
    try {
      const productId = overrideDialogWo.product_id ?? '';
      const locationId = overrideDialogWo.location_id ?? '';
      const demandId = overrideDialogWo.demand_id ?? '';

      let payload: Record<string, unknown>;
      if (overrideDialogType === 'method_selection') {
        if (!overrideMethodValue.trim()) { setOverrideDialogError('Select a method'); setOverrideDialogSaving(false); return; }
        payload = { method: overrideMethodValue.trim() };
      } else if (overrideDialogType === 'variant_selection') {
        if (!overrideVariantValue.trim()) { setOverrideDialogError('Enter an ALT_GROUP value'); setOverrideDialogSaving(false); return; }
        payload = { alt_group: overrideVariantValue.trim() };
      } else {
        // component_split
        const allocations = overrideSplitRows.map((r) => ({ demand_id: r.demand_id, qty: Number(r.qty) }));
        payload = { allocations };
      }

      let entityKey: string;
      if (overrideDialogType === 'component_split') {
        entityKey = overrideDialogWo.start_time
          ? `${productId}|${locationId}|${overrideDialogWo.start_time.slice(0, 10)}`
          : `${productId}|${locationId}`;
      } else {
        entityKey = demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`;
      }
      await upsertOverride(id, overrideDialogType, entityKey, payload);
      await loadOverrides();
      setOverrideDialogOpen(false);
      setOverrideDialogWo(null);
      setOverrideDialogType(null);
    } catch (e) {
      setOverrideDialogError(e instanceof Error ? e.message : 'Failed to save override');
    } finally {
      setOverrideDialogSaving(false);
    }
  };

  // ── Assessment handlers ─────────────────────────────────────────────────────

  const handleAssess = async () => {
    if (!planPeggingContext || planPeggingContext.type !== 'supply') return;
    setAssessmentRunning(true);
    setAssessError(null);
    try {
      const result = await runAssessment(
        id,
        planPeggingContext.supplyId,
        assessDelayDays,
        assessQtyDecreaseMode === 'pct' ? assessQtyDecreasePct : 0,
        currentPlanRunId,
        undefined,
        assessQtyDecreaseMode === 'abs' ? assessQtyDecreaseAbs : null,
      );
      setAssessmentResult(result);
      const hist = await listAssessments(id, planPeggingContext.supplyId);
      setAssessmentHistory(hist);
      setAssessmentHistoryOpen(true);
    } catch (e) {
      setAssessError(e instanceof Error ? e.message : 'Assessment failed');
    } finally {
      setAssessmentRunning(false);
    }
  };

  const handleLoadHistory = async () => {
    if (!planPeggingContext || planPeggingContext.type !== 'supply') return;
    if (!assessmentHistoryOpen) {
      try {
        const hist = await listAssessments(id, planPeggingContext.supplyId);
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
              <span>{tA('progress.steps')} {(allocationProgress.steps ?? 0).toLocaleString()} / {(allocationProgress.max_steps ?? 0).toLocaleString()}</span>
              <span>{tA('progress.basket')} {allocationProgress.basket_keys ?? 0} {tA('progress.items')}, {(Number(allocationProgress.basket_total_qty) ?? 0).toLocaleString()} {tA('progress.qty')}</span>
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
                    One row per supply record. If you expect more rows, re-import supply CSV for this case.
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
                      Overall utilization: <strong>{totalConsumed.toLocaleString()}</strong> / <strong>{totalInitial.toLocaleString()}</strong> initial = <strong>{overallUtil.toFixed(1)}%</strong>
                    </p>
                  );
                })()}
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, marginBottom: '0.5rem' }} title="Uncheck to see all supplies, including those with 0 consumed">
                  <input
                    type="checkbox"
                    checked={supplyFilterConsumedOnly}
                    onChange={(e) => setSupplyFilterConsumedOnly(e.target.checked)}
                  />
                  Only consumed (consumed qty &gt; 0) — uncheck to see all supplies
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
                  { key: 'supply_id', label: 'Supply ID', sortable: true },
                  { key: 'supply_date', label: 'Time', sortable: true, render: (r) => r.supply_date ?? '–' },
                  { key: 'product_id', label: 'Product', sortable: true },
                  { key: 'location_id', label: 'Location', sortable: true },
                  { key: 'initial_qty', label: 'Initial qty', sortable: true },
                  { key: 'product_total', label: 'Total per product', sortable: true, render: (r) => r.product_total > 0 ? r.product_total.toLocaleString() : '–' },
                  { key: 'consumed_qty', label: 'Consumed qty', sortable: true },
                  { key: 'residual_qty', label: 'Residual qty', sortable: true },
                  { key: 'utilization_rate', label: 'Utilization', sortable: true, render: (r) => r.utilization_rate != null ? `${(Number(r.utilization_rate) * 100).toFixed(1)}%` : '–' },
                  { key: '_pegging', label: 'Pegging', sortable: false, render: (r) => <button type="button" className="secondary" onClick={() => handleSupplyPeggingClick(r)}>Show</button> },
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
                      Showing raw allocation steps (lightweight, paginated). {allocationActionsTotal > 0 && `Total: ${allocationActionsTotal.toLocaleString()} steps.`}
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
                            {allocationActionsOffset + 1}–{allocationActionsOffset + allocationActions.length} of {allocationActionsTotal.toLocaleString()}
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
                    Showing {allocationView.length.toLocaleString()} of {allocationViewTruncated.total_steps.toLocaleString()} rows
                    {allocationViewTruncated.total_actions > allocationViewTruncated.limit && ` (${allocationViewTruncated.total_actions.toLocaleString()} steps)`}.
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
                    Run progress: <strong>{(allocationProgress.steps ?? 0).toLocaleString()} steps</strong>, basket <strong>{allocationProgress.basket_keys ?? 0} items</strong>. Table is ordered by scarcity; each row’s basket is after the step in <strong>After step</strong> (view may show fewer steps until it refreshes).
                  </p>
                )}
                {(basketDeltas.length > 0 || allocationView.length > 0) && (
                  <p style={{ fontSize: '0.875rem', color: '#64748b', marginBottom: '0.5rem' }}>
                    View has data through step <strong>{(basketDeltas.length || allocationView.length).toLocaleString()}</strong>
                    {allocationViewTruncated && allocationView.length < allocationViewTruncated.total_steps ? (
                      <> (first chunk loaded; use Load more for rest).</>
                    ) : (
                      <>.</>
                    )}{' '}
                    {basketDeltas.length > 0 ? (
                      <>
                        Basket after last loaded step: <strong>{(basketFinal?.length ?? computeBasketAfterStep(basketDeltas.length - 1).length).toLocaleString()} items</strong>
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
                                  {Number(s.total_consumed_qty).toLocaleString()}
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
                            { key: 'consumed_qty', label: 'Consumed qty', sortable: true, render: (r) => Number(r.consumed_qty).toLocaleString() },
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
                        Overall fulfillment: <strong>{totalAllocated.toLocaleString()}</strong> / <strong>{totalRequested.toLocaleString()}</strong> requested = <strong>{overallRate.toFixed(1)}%</strong>
                      </p>
                      {Object.keys(byCustomer).length > 1 && (
                        <details style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                          <summary style={{ cursor: 'pointer' }}>Rollup by customer</summary>
                          <ul style={{ marginTop: '0.25rem', paddingLeft: '1.25rem' }}>
                            {Object.entries(byCustomer).map(([cust, { requested, allocated }]) => {
                              const rate = requested > 0 ? (allocated / requested) * 100 : 0;
                              return (
                                <li key={cust}>
                                  <strong>{cust}</strong>: {allocated.toLocaleString()} / {requested.toLocaleString()} = {rate.toFixed(1)}%
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
                  suggested_revision: f.suggested_revision ?? (f.status === 'fulfilled' ? 'Fulfilled' : f.allocated_qty > 0 ? `Reduce to ${f.allocated_qty}` : 'Unfulfilled (0 allocated)'),
                }))}
                rowId={(r) => `demand-${r.demand_id}`}
                onRowClick={handleDemandPeggingClick}
                filterKeys={['demand_id', 'customer', 'customer_id', 'product_id', 'status', 'suggested_revision', 'request_due_time', 'revised_time', 'fulfillment_rate']}
                defaultSortKey="fulfillment_rate"
                columns={[
                  { key: 'demand_id', label: 'Demand ID', sortable: true },
                  { key: 'customer', label: 'Customer', sortable: true, render: (r) => r.customer ?? r.customer_id ?? '–' },
                  { key: 'request_due_time', label: 'Time', sortable: true, render: (r) => r.request_due_time ?? '–' },
                  { key: 'revised_time', label: 'Revised time', sortable: true, render: (r) => r.revised_time ?? '–' },
                  { key: 'product_id', label: 'Product', sortable: true },
                  { key: 'requested_qty', label: 'Requested', sortable: true },
                  { key: 'allocated_qty', label: 'Allocated', sortable: true },
                  { key: 'fulfillment_rate', label: 'Fulfillment', sortable: true, render: (r) => r.fulfillment_rate != null ? `${(Number(r.fulfillment_rate) * 100).toFixed(1)}%` : '–' },
                  { key: 'status', label: 'Status', sortable: true },
                  { key: 'suggested_revision', label: 'Suggested revision', sortable: true },
                  { key: '_pegging', label: 'Pegging', sortable: false, render: (r) => <button type="button" className="secondary" onClick={() => handleDemandPeggingClick(r)}>Show</button> },
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
        <p style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
          {tP('info')} <strong>{tP('infoConfigureLink')}</strong> {tP('infoSuffix')}
        </p>

        {/* ── Overrides management panel ─────────────────────────────────────── */}
        <details style={{ marginBottom: '1rem', border: '1px solid #3d3d40', borderRadius: 6, padding: '0.5rem 0.75rem' }}>
          <summary style={{ cursor: 'pointer', fontSize: '0.9rem', fontWeight: 600, color: '#e4e4e7', userSelect: 'none' }}>
            {tP('overridesPanel.title')} {overrides.length > 0 && <span style={{ marginLeft: 6, background: '#3b82f6', color: '#fff', borderRadius: 10, padding: '1px 7px', fontSize: '0.75rem' }}>{overrides.length}</span>}
          </summary>
          <div style={{ marginTop: '0.75rem' }}>
            <p style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.75rem' }}>
              {tP('overridesPanel.info')}
            </p>
            {overrides.length === 0 && (
              <p style={{ fontSize: '0.85rem', color: '#52525b', marginBottom: '0.5rem' }}>{tP('overridesPanel.noOverrides')}</p>
            )}
            {overrides.length > 0 && (
              <table style={{ width: '100%', fontSize: '0.8rem', borderCollapse: 'collapse', marginBottom: '0.75rem' }}>
                <thead>
                  <tr style={{ color: '#a1a1aa', textAlign: 'left', borderBottom: '1px solid #3d3d40' }}>
                    <th style={{ paddingBottom: '0.3rem', paddingRight: '0.75rem' }}>{tP('overridesPanel.columns.type')}</th>
                    <th style={{ paddingBottom: '0.3rem', paddingRight: '0.75rem' }}>{tP('overridesPanel.columns.entityKey')}</th>
                    <th style={{ paddingBottom: '0.3rem', paddingRight: '0.75rem' }}>{tP('overridesPanel.columns.payload')}</th>
                    <th style={{ paddingBottom: '0.3rem' }}></th>
                  </tr>
                </thead>
                <tbody>
                  {overrides.map((ov) => (
                    <tr key={ov.id} style={{ borderTop: '1px solid #27272a' }}>
                      <td style={{ padding: '0.3rem 0.75rem 0.3rem 0', color: '#67e8f9' }}>{ov.entity_type}</td>
                      <td style={{ padding: '0.3rem 0.75rem 0.3rem 0', color: '#d4d4d8' }}>{ov.entity_key}</td>
                      <td style={{ padding: '0.3rem 0.75rem 0.3rem 0', color: '#a1a1aa', maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {JSON.stringify(ov.payload)}
                      </td>
                      <td style={{ padding: '0.3rem 0', whiteSpace: 'nowrap' }}>
                        <button type="button" className="secondary" style={{ fontSize: '0.75rem', padding: '2px 8px' }} onClick={() => handleDeleteOverride(ov.id)}>{tc('remove')}</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {/* Manual JSON override form */}
            <details style={{ fontSize: '0.8rem' }}>
              <summary style={{ cursor: 'pointer', color: '#71717a' }}>{tP('overridesPanel.addManually')}</summary>
              <div style={{ marginTop: '0.5rem', display: 'flex', gap: '0.5rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <select
                  value={overrideForm.entity_type}
                  onChange={(e) => setOverrideForm({ ...overrideForm, entity_type: e.target.value })}
                  style={{ padding: '4px 8px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem' }}
                >
                  <option value="method_selection">method_selection</option>
                  <option value="variant_selection">variant_selection</option>
                  <option value="component_split">component_split</option>
                </select>
                <input
                  placeholder={tP('overridesPanel.entityKeyPlaceholder')}
                  value={overrideForm.entity_key}
                  onChange={(e) => setOverrideForm({ ...overrideForm, entity_key: e.target.value })}
                  style={{ padding: '4px 8px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem', minWidth: 240 }}
                />
                <input
                  placeholder={tP('overridesPanel.payloadPlaceholder')}
                  value={overrideForm.payload}
                  onChange={(e) => setOverrideForm({ ...overrideForm, payload: e.target.value })}
                  style={{ padding: '4px 8px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem', minWidth: 200 }}
                />
                <button type="button" className="secondary" style={{ fontSize: '0.8rem' }} onClick={handleAddOverride}>{tc('add')}</button>
              </div>
            </details>
          </div>
        </details>
        <div style={{ marginBottom: '0.75rem' }}>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', marginRight: '1rem', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={planningConfig.variant_selection?.multiple === false}
              onChange={(e) => setPlanningConfig((c) => ({
                ...c,
                variant_selection: { ...c.variant_selection, multiple: e.target.checked ? false : undefined },
              }))}
            />
            <span>{tP('config.singleBestVariant')}</span>
          </label>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', marginRight: '1rem', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={planningConfig.method_selection?.multiple === true}
              onChange={(e) => setPlanningConfig((c) => ({
                ...c,
                method_selection: { ...c.method_selection, multiple: e.target.checked },
              }))}
            />
            <span>{tP('config.equalSplitMethods')}</span>
          </label>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', marginRight: '1rem', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={planningConfig.method_selection?.elaborate === true}
              onChange={(e) => setPlanningConfig((c) => ({
                ...c,
                method_selection: { ...c.method_selection, elaborate: e.target.checked },
              }))}
            />
            <span>{tP('config.elaborateMethod')}</span>
          </label>
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
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={analyzeCriticalityEnabled}
                onChange={(e) => setAnalyzeCriticalityEnabled(e.target.checked)}
              />
              <span style={{ fontSize: '0.875rem' }}>Analyze Criticality</span>
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', opacity: planningConfig.consolidation?.enabled === true ? 1 : 0.4 }}>
              <span style={{ color: '#a1a1aa' }}>{tP('config.bucketDays')}</span>
              <input
                type="number"
                min={1}
                max={365}
                disabled={planningConfig.consolidation?.enabled !== true}
                value={planningConfig.consolidation?.period_days ?? 7}
                onChange={(e) => {
                  const v = Math.max(1, Math.min(365, parseInt(e.target.value, 10) || 7));
                  setPlanningConfig((c) => ({ ...c, consolidation: { ...c.consolidation, period_days: v } }));
                }}
                style={{ width: 64, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
              />
            </label>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', opacity: planningConfig.consolidation?.enabled === true ? 1 : 0.4 }}>
              <span style={{ color: '#a1a1aa' }}>{tP('config.splitPolicy')}</span>
              <select
                disabled={planningConfig.consolidation?.enabled !== true}
                value={planningConfig.consolidation?.allocation_mode ?? 'priority_first'}
                onChange={(e) => setPlanningConfig((c) => ({
                  ...c,
                  consolidation: { ...c.consolidation, allocation_mode: e.target.value as 'priority_first' | 'proportional' },
                }))}
                style={{ padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
              >
                <option value="priority_first">{tP('config.priorityFirst')}</option>
                <option value="proportional">{tP('config.proportional')}</option>
              </select>
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
            onClick={() => { loadPlanRunHistory(); setPlanRunHistoryOpen(true); }}
            style={{ padding: '6px 12px' }}
          >
            {tP('planRunHistory')}
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
              <span>{tP('planProgress')} {planProgress.current} / {planProgress.total} {tP('demands')}</span>
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
            {currentRunIsContingent && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#2e1065', border: '1px solid #7c3aed', borderRadius: 6, padding: '6px 12px', marginBottom: '0.75rem', fontSize: '0.82rem', color: '#ddd6fe' }}>
                <span style={{ fontWeight: 700, color: '#a78bfa' }}>Contingent run #{currentPlanRunId}</span>
                <span>— what-if branch. Run a fresh plan to create a new baseline.</span>
              </div>
            )}
            {currentPlanRunId === null && freshPlanRunId !== null && (
              <div style={{ marginBottom: '0.75rem', padding: '0.6rem 0.75rem', background: '#1a2e1a', border: '1px solid #166534', borderRadius: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: '0.5rem' }}>
                  <span style={{ fontSize: '0.78rem', color: '#86efac', fontWeight: 600 }}>Unsaved</span>
                  <span style={{ fontSize: '0.75rem', color: '#71717a' }}>— results are in memory only</span>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap' }}>
                  <input
                    type="text"
                    placeholder="Run name (optional)"
                    value={freshRunName}
                    onChange={(e) => setFreshRunName(e.target.value)}
                    style={{ flex: '1 1 180px', minWidth: 0, padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
                  />
                  <input
                    type="text"
                    placeholder="Notes (optional)"
                    value={freshRunNotes}
                    onChange={(e) => setFreshRunNotes(e.target.value)}
                    style={{ flex: '2 1 240px', minWidth: 0, padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
                  />
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
                    {planRunSaving ? 'Saving…' : 'Save run'}
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
                {tP('tabs.supplies', { count: planSupplyViewRows.length.toLocaleString() })}
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
                    // ALL trees per demand_id — consolidation entries contain original supply IDs
                    // that the main tree replaces with consolidated_* synthetic IDs.
                    const allPeggingTreesByDemandId = (planResult.planning_pegging ?? []).reduce<Record<string, PlanningPeggingNode[]>>((acc, e) => {
                      if (e.demand_id && e.tree) {
                        if (!acc[e.demand_id]) acc[e.demand_id] = [];
                        acc[e.demand_id].push(e.tree);
                      }
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
                    if (planDemandSupplyFilter.trim()) {
                      const substr = planDemandSupplyFilter.trim().toLowerCase();
                      list = list.filter((r) => {
                        const demandId = r.demand_id ?? (r as unknown as { demand_id?: string }).demand_id;
                        const trees = demandId ? allPeggingTreesByDemandId[demandId] : null;
                        if (!trees?.length) return false;
                        return trees.some(t => peggingTreeContainsSupply(t, substr));
                      });
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
                            No demands in this plan have a work order with method &quot;purchase&quot; in their pegging tree.
                          </p>
                        )}
                        {planDemandRealMoveOnly && list.length === 0 && realMoveTriples !== null && realMoveTriples.length > 0 && (
                          <p style={{ fontSize: '0.8rem', color: '#71717a', marginBottom: '0.5rem' }}>
                            No demands in this plan have a move work order with TRANSIT_TIME &gt; 0 in their pegging tree.
                          </p>
                        )}
                        {hasMultipleCustomers && (
                          <details style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '0.5rem' }}>
                            <summary style={{ cursor: 'pointer' }}>Rollup by customer (filtered set)</summary>
                            <ul style={{ marginTop: '0.25rem', paddingLeft: '1.25rem' }}>
                              {Object.entries(byCustomer).map(([cust, qty]) => (
                                <li key={cust}><strong>{cust}</strong>: {Number(qty).toLocaleString()} committed</li>
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
                          filterPlaceholder="Filter by demand ID, customer, product, location…"
                          defaultSortKey="commit_time"
                          stickyHeader
                          rowStyle={(r) => {
                            const k = `demand|${r.demand_id ?? ''}|${r.product_id}|${r.location_id}`;
                            if (r.is_failed) return { background: 'rgba(248,113,113,0.08)', outline: '1px solid rgba(248,113,113,0.3)' };
                            if (woPeggingRowKey === k) return { background: 'rgba(56,189,248,0.12)', outline: '1px solid rgba(56,189,248,0.35)' };
                            return undefined;
                          }}
                          columns={[
                            { key: 'demand_id', label: 'Demand ID', sortable: true, render: (r) => r.demand_id ?? '–' },
                            { key: '_customer', label: 'Customer', sortable: true, render: (r) => (r as { _customer?: string })._customer || (r.customer ?? r.customer_id ?? '–') },
                            { key: 'product_id', label: 'Product', sortable: true },
                            { key: 'location_id', label: 'Location', sortable: true },
                            { key: 'requested_qty', label: 'Requested', sortable: true, render: (r) => r.requested_qty != null ? Number(r.requested_qty).toLocaleString() : '–' },
                            { key: 'quantity', label: 'Committed', sortable: true, render: (r) => r.is_failed
                              ? <span style={{ color: '#f87171', fontWeight: 600, fontSize: '0.78rem', background: 'rgba(248,113,113,0.15)', padding: '1px 6px', borderRadius: 4 }}>FAILED</span>
                              : String(r.quantity) },
                            { key: 'shortage', label: 'Shortage', sortable: true, render: (r) => {
                              const s = r.shortage ?? 0;
                              return s > 0.01
                                ? <span style={{ color: '#f87171', fontWeight: 600 }}>{Number(s).toLocaleString()}</span>
                                : <span style={{ color: '#4ade80' }}>0</span>;
                            }},
                            { key: 'request_time', label: 'Request time', sortable: true, render: (r) => r.request_time ?? '–' },
                            { key: 'commit_time', label: 'Commit time', sortable: true, render: (r) => r.commit_time ?? '–' },
                            { key: 'commit_reason', label: 'Commit reason', sortable: true, render: (r) => {
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
                            { key: '_pegging', label: 'Pegging', sortable: false, render: (r) => {
                              const k = `demand|${r.demand_id ?? ''}|${r.product_id}|${r.location_id}`;
                              const isSelected = woPeggingRowKey === k;
                              return (
                                <button
                                  type="button"
                                  className="secondary"
                                  style={isSelected ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : undefined}
                                  onClick={() => {
                                    if (isSelected) { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); }
                                    else { setPlanPeggingContext({ type: 'demand', row: r }); setPlanPeggingOpen(true); setWoPeggingRowKey(k); setPreviousPeggingContext(null); }
                                  }}
                                >Show</button>
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
                            <li key={area}><strong>{area}</strong>: {Number(qty).toLocaleString()} quantity</li>
                          ))}
                        </ul>
                      </details>
                    ) : null;
                  })()}
                  {(() => {
                    let workOrderRows = planWorkOrderHideDummyProdArea
                      ? planResult.work_orders.filter((r) => (r.prod_area ?? '').trim().toLowerCase() !== 'dummy')
                      : planResult.work_orders;
                    // Build set of demand IDs with shortage > 0 for short-supply filter
                    const shortDemandIds = planDemandShortOnly
                      ? new Set(planResult.committed_demands.filter((d) => (d.shortage ?? 0) > 0.01).map((d) => d.demand_id ?? ''))
                      : null;
                    // Filters refer to work-order pegging (each WO's supplies subtree), not demand pegging.
                    const anyPeggingFilter = planDemandRealMakeOnly || planDemandBuyOnly || planDemandRealMoveOnly || planWoDemandedByMultiple || planWoMultiSupply || planWoPurchaseOnly || planWoMoveOnly || planDemandShortOnly || planWoHasOverride;
                    if (anyPeggingFilter) {
                      workOrderRows = workOrderRows.filter((r) => {
                        if (planDemandRealMakeOnly && !(r.pegging_includes_real_make === true)) return false;
                        if (planDemandBuyOnly && !(r.pegging_includes_buy === true)) return false;
                        if (planDemandRealMoveOnly && !(r.pegging_includes_real_move === true)) return false;
                        if (planWoDemandedByMultiple && !(r.demanded_by_multiple === true)) return false;
                        if (planWoMultiSupply && !(r.multi_supply_available === true)) return false;
                        if (planWoPurchaseOnly) { const m = (r.method ?? '').toLowerCase(); if (m !== 'buy' && m !== 'purchase') return false; }
                        if (planWoMoveOnly && (r.method ?? '').toLowerCase() !== 'move') return false;
                        if (shortDemandIds) {
                          const matchesDirect = shortDemandIds.has(r.demand_id ?? '');
                          const matchesConsolidated = !r.demand_id && (r.wo_consolidation_split_details ?? []).some((d) => shortDemandIds.has(d.demand_id ?? ''));
                          if (!matchesDirect && !matchesConsolidated) return false;
                        }
                        if (planWoHasOverride && !woHasSavedOverride(r)) return false;
                        return true;
                      });
                    }
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
                    // Build demand_id → requested_qty / shortage lookups (mirrors demands-tab pattern)
                    const demandRequestedMap = new Map<string, number>();
                    const demandShortageMap = new Map<string, number>();
                    for (const d of planResult.committed_demands) {
                      const id = d.demand_id ?? '';
                      if (!id) continue;
                      if (d.requested_qty != null) demandRequestedMap.set(id, d.requested_qty);
                      if ((d.shortage ?? 0) > 0) demandShortageMap.set(id, d.shortage ?? 0);
                    }
                    const dummyHiddenCount = planResult.work_orders.filter((r) => (r.prod_area ?? '').trim().toLowerCase() === 'dummy').length;
                    const woRows: WoEnrichedRow[] = groupedRows.map((r, i) => {
                      const splitDemandIds = (r.wo_consolidation_split_details ?? [])
                        .map((d) => d.demand_id)
                        .filter((d): d is string => d != null && d !== '');
                      const demandLabel = r.demand_id
                        ? r.demand_id
                        : splitDemandIds.length > 0
                          ? splitDemandIds.join(', ')
                          : undefined;
                      const requested = r.demand_id
                        ? (demandRequestedMap.get(r.demand_id) ?? undefined)
                        : splitDemandIds.reduce((s, did) => s + (demandRequestedMap.get(did) ?? 0), 0) || undefined;
                      const shortage = r.demand_id
                        ? (demandShortageMap.get(r.demand_id) ?? 0)
                        : splitDemandIds.reduce((s, did) => s + (demandShortageMap.get(did) ?? 0), 0);
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
                    const woColumns: { key: string; label: string; sortable?: boolean; render?: (r: WoEnrichedRow) => React.ReactNode }[] = [
                      { key: 'product_id', label: 'Product', sortable: true },
                      { key: 'location_id', label: 'Location', sortable: true },
                      { key: '_prod_area', label: 'PROD_AREA', sortable: true, render: (r) => r._prod_area || r.prod_area || '–' },
                      { key: '_requested_qty', label: 'Requested', sortable: true, render: (r) =>
                        r._requested_qty != null ? Number(r._requested_qty).toLocaleString() : '–'
                      },
                      { key: 'quantity', label: 'Committed', sortable: true, render: (r) => Number(r.quantity).toLocaleString() },
                      { key: 'start_time', label: 'Start time', sortable: true, render: (r) => r.start_time ?? '–' },
                      { key: 'end_time', label: 'End time', sortable: true, render: (r) => r.end_time ?? '–' },
                      { key: 'method', label: 'Method', sortable: true, render: (r) => (
                        <span>
                          {r.method ?? '–'}
                          {(r.override_active || r.consolidation_override_active) && (
                            <span title={r.override_active ? 'User override active (method/variant)' : 'User override active (consolidation split)'} style={{ marginLeft: 5, background: r.override_active ? '#7c3aed' : '#0891b2', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.7rem', verticalAlign: 'middle' }}>
                              override
                            </span>
                          )}
                          {!r.override_active && !r.consolidation_override_active && woHasSavedOverride(r) && (
                            <span title="Saved override — re-run plan to apply" style={{ marginLeft: 5, background: '#b45309', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.7rem', verticalAlign: 'middle' }}>
                              pending
                            </span>
                          )}
                        </span>
                      ) },
                      { key: '_demand_label', label: 'Demand', sortable: true, render: (r) => {
                        const ids = r._demand_ids ?? [];
                        const label = r._demand_label;
                        if (!label) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        if (ids.length <= 1) return <span>{label}</span>;
                        const preview = ids.length <= 3 ? label : `${ids.slice(0, 2).join(', ')} +${ids.length - 2} more`;
                        return (
                          <span title={label} style={{ cursor: 'default' }}>
                            {preview}
                            <span style={{ marginLeft: 5, background: '#0891b2', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.7rem', verticalAlign: 'middle' }}>
                              shared
                            </span>
                          </span>
                        );
                      } },
                      { key: '_shortage', label: 'Shortage', sortable: true, render: (r) => {
                        const s = r._shortage;
                        if (!s) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        return <span style={{ color: '#f87171' }}>{Number(s).toLocaleString()}</span>;
                      } },
                      { key: 'location_source', label: 'Location source', sortable: true, render: (r) => r.location_source ?? '–' },
                      { key: '_peg_order', label: 'Pegging', sortable: true, render: (r) => {
                        const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                        const isSelected = woPeggingRowKey === k;
                        return (
                          <button
                            type="button"
                            className="secondary"
                            style={isSelected ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : undefined}
                            onClick={(e) => {
                              e.stopPropagation();
                              if (isSelected) { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); }
                              else { setPlanPeggingContext({ type: 'work_order', row: r }); setPlanPeggingOpen(true); setWoPeggingRowKey(k); setPreviousPeggingContext(null); }
                            }}
                          >Show</button>
                        );
                      } },
                      { key: '_explain', label: 'Explain', sortable: false, render: (r) => {
                        const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                        const isSelected = woExplainKey === k;
                        if (!(r.wo_explanation_method || r.wo_explanation_variant || (r.wo_competing_demands?.length ?? 0) > 0 || (r.wo_consolidation_split_details?.length ?? 0) > 1))
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
                          >Why</button>
                        );
                      }},
                      { key: '_override', label: 'Override', sortable: false, render: (r) => {
                        const hasMethod = r.multi_supply_available === true && r.wo_explanation_method != null;
                        const hasVariant = r.wo_explanation_variant != null;
                        const hasSplit = (r.wo_consolidation_split_details?.length ?? 0) > 1;
                        if (!hasMethod && !hasVariant && !hasSplit) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        return (
                          <div style={{ display: 'flex', gap: 4 }}>
                            {hasMethod && <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 6px' }} onClick={() => openOverrideDialog('method_selection', r)}>Method</button>}
                            {hasVariant && <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 6px' }} onClick={() => openOverrideDialog('variant_selection', r)}>Variant</button>}
                            {hasSplit && <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 6px' }} onClick={() => openOverrideDialog('component_split', r)}>Split</button>}
                          </div>
                        );
                      }},
                    ];
                    const pivotGroups = (planWoPivot === 'prod_area' || planWoPivot === 'location') ? buildWoPivotGroups(woRows, planWoPivot) : [];
                    const nestedGroups = planWoPivot === 'nested' ? buildWoNestedPivotGroups(woRows) : [];
                    const woRowStyle = (r: WoEnrichedRow) => {
                      const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                      if (woExplainKey === k) return { background: 'rgba(167,139,250,0.15)', outline: '1px solid rgba(167,139,250,0.4)' };
                      if (woPeggingRowKey === k) return { background: 'rgba(56,189,248,0.12)', outline: '1px solid rgba(56,189,248,0.35)' };
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
                        <td style={{ textAlign: 'right' }}>{group.qty_total.toLocaleString()}</td>
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
                          Showing {groupedRows.length.toLocaleString()} work order{groupedRows.length !== 1 ? 's' : ''}
                          {planWorkOrderHideDummyProdArea && dummyHiddenCount > 0
                            ? ` (${dummyHiddenCount.toLocaleString()} with PROD_AREA = dummy hidden)`
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
                        ) : (
                          /* ── Flat view (unchanged) ── */
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
                        ? `Analyzing… (${criticalityProgress?.done ?? 0}/${criticalityProgress?.total ?? 0})`
                        : 'Analyze Criticality'}
                    </button>
                    {!criticalityRunning && Object.keys(supplyCriticalityMap).length > 0 && (
                      <button
                        type="button"
                        className="secondary"
                        onClick={() => setSupplyCriticalityMap({})}
                        style={{ fontSize: '0.8rem' }}
                      >Clear</button>
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
                      {assessCriteriaOpen ? '▾' : '▸'} {tP('assessment.criteria')}
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
                                {tP('assessment.criteriaHeaderBefore')}
                                <span style={{ padding: '1px 7px', borderRadius: 10, background: tierBg, color: tierColor, border: `1px solid ${tierColor}`, fontWeight: 700, fontSize: '0.72rem' }}>{tier.toUpperCase()}</span>
                                {tP('assessment.criteriaHeaderAfter')}
                              </p>
                              <textarea
                                value={tierValue}
                                onChange={(e) => tierSetter(e.target.value)}
                                rows={2}
                                placeholder={tP('assessment.criteriaPlaceholderTier', { tier: tier.toUpperCase() })}
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
                            {assessCriteriaSaving ? tP('assessment.criteriaSaving') : tP('assessment.criteriaSave')}
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
                            {tP('assessment.criteriaCancel')}
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
                    if (planSupplyPartialOnly) rows = rows.filter((r) => r.residualQty > 0 && r.consumedQty > 0);

                    const totalInitial = planSupplyViewRows.reduce((s, r) => s + r.qty, 0);
                    const totalConsumed = planSupplyViewRows.reduce((s, r) => s + r.consumedQty, 0);
                    const overallUtil = totalInitial > 0 ? ((totalConsumed / totalInitial) * 100).toFixed(1) : null;

                    return (
                      <>
                        <p style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.5rem', marginTop: 0 }}>
                          {tP('supplyView.showing', { shown: rows.length.toLocaleString(), total: planSupplyViewRows.length.toLocaleString() })}
                          {overallUtil != null && (
                            <span> {tP('supplyView.overallUtilization', { pct: overallUtil, consumed: Number(totalConsumed).toLocaleString(), initial: Number(totalInitial).toLocaleString() })}</span>
                          )}
                        </p>
                        <SortFilterTable<PlanSupplyViewRow & { _key: string; productTotal: number; criticalityOrder: number }>
                          idKey="_key"
                          rows={rows.map((r, i) => {
                            const cs = supplyCriticalityMap[r.supplyId];
                            const criticalityOrder = cs === 'critical' ? 0 : cs === 'not_critical' ? 1 : cs === 'error' ? 2 : cs === 'running' ? 3 : 4;
                            return { ...r, _key: `psv-${i}-${r.supplyId}`, productTotal: planSupplyProductTotalMap[r.productId] ?? 0, criticalityOrder };
                          })}
                          filterKeys={[]}
                          defaultSortKey="supplyDate"
                          columns={[
                            { key: 'criticalityOrder', label: 'Criticality', sortable: true, render: (r) => {
                              const cs = supplyCriticalityMap[r.supplyId];
                              if (!cs) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                              if (cs === 'running') return <span style={{ color: '#a1a1aa', fontSize: '0.75rem' }}>…</span>;
                              if (cs === 'error') return <span style={{ color: '#f59e0b', fontSize: '0.72rem' }}>err</span>;
                              if (cs === 'critical') return <span style={{ background: 'rgba(248,113,113,0.15)', color: '#f87171', border: '1px solid rgba(248,113,113,0.4)', borderRadius: 8, padding: '1px 8px', fontSize: '0.72rem', fontWeight: 700 }}>Critical</span>;
                              return <span style={{ background: 'rgba(52,211,153,0.12)', color: '#34d399', border: '1px solid rgba(52,211,153,0.35)', borderRadius: 8, padding: '1px 8px', fontSize: '0.72rem', fontWeight: 700 }}>Safe</span>;
                            }},
                            { key: 'supplyId', label: tP('supplyView.columns.supplyId'), sortable: true },
                            { key: 'productId', label: tP('supplyView.columns.product'), sortable: true },
                            { key: 'locationId', label: tP('supplyView.columns.location'), sortable: true, render: (r) => r.locationId ?? '–' },
                            { key: 'vendorId', label: tP('supplyView.columns.vendor'), sortable: true, render: (r) => r.vendorId ?? '–' },
                            { key: 'supplyDate', label: tP('supplyView.columns.supplyDate'), sortable: true, render: (r) => r.supplyDate ?? '–' },
                            { key: 'qty', label: tP('supplyView.columns.initialQty'), sortable: true, render: (r) => Number(r.qty).toLocaleString() },
                            { key: 'productTotal', label: tP('supplyView.columns.productTotal'), sortable: true, render: (r) => r.productTotal > 0 ? Number(r.productTotal).toLocaleString() : '–' },
                            { key: 'consumedQty', label: tP('supplyView.columns.consumed'), sortable: true, render: (r) => r.consumedQty > 0 ? <span style={{ color: '#a78bfa' }}>{Number(r.consumedQty).toLocaleString()}</span> : <span style={{ color: '#52525b' }}>0</span> },
                            { key: 'residualQty', label: tP('supplyView.columns.residual'), sortable: true, render: (r) => r.residualQty > 0 ? <span style={{ color: '#34d399' }}>{Number(r.residualQty).toLocaleString()}</span> : <span style={{ color: '#52525b' }}>0</span> },
                            { key: 'utilizationRate', label: tP('supplyView.columns.utilPct'), sortable: true, render: (r) => {
                              if (r.utilizationRate == null) return <span style={{ color: '#52525b' }}>–</span>;
                              const pct = (r.utilizationRate * 100).toFixed(1);
                              const color = r.utilizationRate > 1.0 ? '#f87171' : r.utilizationRate >= 0.9 ? '#34d399' : r.utilizationRate >= 0.5 ? '#f59e0b' : '#f87171';
                              const overAllocated = r.utilizationRate > 1.0;
                              return <span style={{ color }} title={overAllocated ? 'Over-allocated: consumed exceeds initial qty' : undefined}>{pct}%{overAllocated ? ' ⚠' : ''}</span>;
                            }},
                            { key: 'peggedDemandCount', label: tP('supplyView.columns.peggedDemands'), sortable: true, render: (r) => r.peggedDemandCount > 0 ? <span style={{ color: '#60a5fa' }}>{r.peggedDemandCount}</span> : <span style={{ color: '#52525b' }}>0</span> },
                            { key: 'totalPeggedQty', label: tP('supplyView.columns.totalPeggedQty'), sortable: true, render: (r) => r.totalPeggedQty > 0 ? Number(r.totalPeggedQty).toLocaleString() : <span style={{ color: '#52525b' }}>0</span> },
                            { key: '_sup_pegging' as keyof (PlanSupplyViewRow & { _key: string }), label: tP('supplyView.columns.pegging'), sortable: false, render: (r) => {
                              if (r.peggedDemandCount === 0) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                              const k = `supply|${r.supplyId}`;
                              const isSelected = woPeggingRowKey === k;
                              return (
                                <button
                                  type="button"
                                  className="secondary"
                                  style={isSelected ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : undefined}
                                  onClick={() => {
                                    if (isSelected) {
                                      setPlanPeggingOpen(false);
                                      setPlanPeggingContext(null);
                                      setWoPeggingRowKey(null);
                                      setPreviousPeggingContext(null);
                                    } else {
                                      setPlanPeggingContext({ type: 'supply', supplyId: r.supplyId, peggedDemands: r.peggedDemands, initialQty: r.qty, consumedQty: r.consumedQty });
                                      setPlanPeggingOpen(true);
                                      setWoPeggingRowKey(k);
                                      setPreviousPeggingContext(null);
                                    }
                                  }}
                                >Show</button>
                              );
                            }},
                          ]}
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
            {assessCriteriaOpen ? '▾' : '▸'} {tP('assessment.criteria')}
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
                      {tP('assessment.criteriaHeaderBefore')}
                      <span style={{ padding: '1px 7px', borderRadius: 10, background: tierBg, color: tierColor, border: `1px solid ${tierColor}`, fontWeight: 700, fontSize: '0.72rem' }}>{tier.toUpperCase()}</span>
                      {tP('assessment.criteriaHeaderAfter')}
                    </p>
                    <textarea
                      value={tierValue}
                      onChange={(e) => tierSetter(e.target.value)}
                      rows={2}
                      placeholder={tP('assessment.criteriaPlaceholderTier', { tier: tier.toUpperCase() })}
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
                  {assessCriteriaSaving ? tP('assessment.criteriaSaving') : tP('assessment.criteriaSave')}
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
                  {tP('assessment.criteriaCancel')}
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
                        <span style={{ marginLeft: 8, color: '#71717a' }}>qty {Number(s.qty).toLocaleString()}</span>
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
                          −{Number(ev.qtyDecreaseAbs).toLocaleString()} qty
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
                          try {
                            const result = await analyzeMaterialImpact(ev.supplyId, ev.delayDays, ev.qtyDecreasePct, true, ev.qtyDecreaseAbs);
                            setMaterialImpacts(m => ({ ...m, [ev.id]: result }));
                          } catch { /* ignore */ } finally {
                            setMaterialImpactLoading(m => ({ ...m, [ev.id]: false }));
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
                            const result = await runAssessment(id, ev.supplyId, ev.delayDays, ev.qtyDecreasePct, undefined, impact, ev.qtyDecreaseAbs);
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

                  {!isCollapsed && (<>
                  {/* ── Impact results ── */}
                  {impact && (
                    <div style={{ marginTop: '0.875rem', borderTop: '1px solid #27272a', paddingTop: '0.875rem' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: '0.5rem', flexWrap: 'wrap' }}>
                        <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>
                          Supply: <b style={{ color: '#e4e4e7' }}>{impact.supply.productId}</b>
                          {impact.supply.locationId && <> @ <b style={{ color: '#e4e4e7' }}>{impact.supply.locationId}</b></>}
                          {' '}· qty {Number(impact.supply.qty).toLocaleString()}
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
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: '#e4e4e7' }}>{Number(imp.requestedQty).toLocaleString()}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: '#e4e4e7' }}>{Number(baseQty).toLocaleString()}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: contQty < baseQty ? '#f87171' : '#e4e4e7' }}>{Number(contQty).toLocaleString()}</td>
                                  <td style={{ padding: '5px 8px', textAlign: 'right', color: shortfall > 0 ? '#f87171' : '#a1a1aa' }}>
                                    {shortfall > 0 ? `-${Number(shortfall).toLocaleString()}` : '–'}
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
                          <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>{tP('assessment.ratingLabel')} </span>
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
                      {(materialAssessmentHistoryOpen[ev.id] ?? false) ? '▾' : '▸'} {tP('assessment.history')}
                    </button>
                    {(materialAssessmentHistoryOpen[ev.id] ?? false) && (
                      <div style={{ marginTop: '0.5rem' }}>
                        {!(materialAssessmentHistory[ev.id]?.length) ? (
                          <p style={{ fontSize: '0.75rem', color: '#71717a', margin: 0 }}>{tP('assessment.noHistory')}</p>
                        ) : (
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.75rem' }}>
                            <thead>
                              <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa' }}>
                                <th style={{ textAlign: 'left', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.date')}</th>
                                <th style={{ textAlign: 'center', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.rating')}</th>
                                <th style={{ textAlign: 'right', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.delay')}</th>
                                <th style={{ textAlign: 'right', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.qtyPct')}</th>
                                <th style={{ textAlign: 'left', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.explanation')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {materialAssessmentHistory[ev.id]!.map((h) => (
                                <tr key={h.id} style={{ borderBottom: '1px solid #27272a' }}>
                                  <td style={{ padding: '3px 5px', color: '#71717a', whiteSpace: 'nowrap' }}>{h.createdAt.slice(0, 10)}</td>
                                  <td style={{ padding: '3px 5px', textAlign: 'center' }}>
                                    <span style={{
                                      padding: '0 6px',
                                      borderRadius: 10,
                                      fontWeight: 600,
                                      fontSize: '0.72rem',
                                      background: h.rating === 'LOW' ? 'rgba(52,211,153,0.15)' : h.rating === 'HIGH' ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                                      color: h.rating === 'LOW' ? '#34d399' : h.rating === 'HIGH' ? '#f87171' : '#fbbf24',
                                    }}>{h.rating}</span>
                                  </td>
                                  <td style={{ padding: '3px 5px', textAlign: 'right', color: '#e4e4e7' }}>{h.deliveryDelayDays}</td>
                                  <td style={{ padding: '3px 5px', textAlign: 'right', color: '#e4e4e7' }}>{h.quantityDecreasePct}</td>
                                  <td style={{ padding: '3px 5px', color: '#a1a1aa', maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.explanation}>{h.explanation}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
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
      {/* ── Plan run history slide-in ──────────────────────────────────────────── */}
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
              <h3 style={{ margin: 0, fontSize: '1rem' }}>Plan run history</h3>
              <button type="button" onClick={() => setPlanRunHistoryOpen(false)} style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {planRunHistoryLoading && <p style={{ color: '#71717a' }}>Loading…</p>}
              {!planRunHistoryLoading && planRunHistory.length === 0 && <p style={{ color: '#71717a' }}>No persisted plan runs found. Run a plan with async mode to persist results.</p>}
              {!planRunHistoryLoading && planRunHistory.map((run) => {
                const editing = planRunEditing[run.id];
                const isSavingEdit = !!planRunEditSaving[run.id];
                return (
                <div key={run.id} style={{ borderBottom: '1px solid #27272a', paddingBottom: '0.75rem', marginBottom: '0.75rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <div>
                      <span style={{ fontSize: '0.85rem', fontWeight: 600, color: run.status === 'success' ? '#4ade80' : run.status === 'failed' ? '#f87171' : run.status === 'contingent' ? '#a78bfa' : '#fbbf24' }}>
                        {run.status}
                      </span>
                      <span style={{ marginLeft: 8, fontSize: '0.8rem', color: '#a1a1aa' }}>
                        {new Date(run.created_at).toLocaleString()}
                      </span>
                      {run.override_count > 0 && (
                        <span style={{ marginLeft: 8, background: '#7c3aed', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.72rem' }}>
                          {run.override_count} override{run.override_count !== 1 ? 's' : ''}
                        </span>
                      )}
                    </div>
                    <div style={{ display: 'flex', gap: 6 }}>
                      {(run.status === 'success' || run.status === 'contingent') && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          disabled={planRunLoadingId === run.id}
                          onClick={() => handleRestorePlanRun(run.id)}
                        >
                          {planRunLoadingId === run.id ? 'Loading…' : 'Load'}
                        </button>
                      )}
                      {!editing && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.8rem', padding: '3px 10px' }}
                          onClick={() => setPlanRunEditing((prev) => ({ ...prev, [run.id]: { name: run.name ?? '', notes: run.notes ?? '' } }))}
                        >
                          Edit
                        </button>
                      )}
                      <button
                        type="button"
                        className="secondary"
                        style={{ fontSize: '0.8rem', padding: '3px 10px', color: '#f87171', borderColor: '#f87171' }}
                        onClick={() => handleDeletePlanRun(run.id)}
                      >
                        Delete
                      </button>
                    </div>
                  </div>
                  {/* Name display / edit */}
                  {editing ? (
                    <div style={{ marginTop: '0.5rem', display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <input
                        type="text"
                        placeholder="Name (optional)"
                        value={editing.name}
                        onChange={(e) => setPlanRunEditing((prev) => ({ ...prev, [run.id]: { ...prev[run.id], name: e.target.value } }))}
                        style={{ padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
                      />
                      <textarea
                        placeholder="Notes (optional)"
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
                          {isSavingEdit ? 'Saving…' : 'Save'}
                        </button>
                        <button
                          type="button"
                          onClick={() => setPlanRunEditing((prev) => { const n = { ...prev }; delete n[run.id]; return n; })}
                          style={{ padding: '3px 10px', background: 'transparent', color: '#a1a1aa', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.8rem', cursor: 'pointer' }}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  ) : (run.name || run.notes) ? (
                    <div style={{ marginTop: '0.3rem' }}>
                      {run.name && <p style={{ margin: 0, fontSize: '0.85rem', color: '#e4e4e7', fontWeight: 500 }}>{run.name}</p>}
                      {run.notes && <p style={{ margin: '0.15rem 0 0', fontSize: '0.78rem', color: '#a1a1aa', whiteSpace: 'pre-wrap' }}>{run.notes}</p>}
                    </div>
                  ) : null}
                  <div style={{ fontSize: '0.75rem', color: '#71717a', marginTop: '0.25rem' }}>
                    Run #{run.id}{run.job_id ? ` · job ${run.job_id.slice(0, 8)}…` : ''}
                    {run.config && Object.keys(run.config).length > 0 && (
                      <details style={{ display: 'inline-block', marginLeft: 8 }}>
                        <summary style={{ cursor: 'pointer' }}>config</summary>
                        <pre style={{ margin: '0.25rem 0', fontSize: '0.7rem', color: '#a1a1aa', whiteSpace: 'pre-wrap' }}>{JSON.stringify(run.config, null, 2)}</pre>
                      </details>
                    )}
                  </div>
                </div>
                );
              })}
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ── Override dialog ────────────────────────────────────────────────────── */}
      {overrideDialogOpen && overrideDialogWo && typeof document !== 'undefined' && createPortal(
        <div style={{ position: 'fixed', inset: 0, zIndex: 10000, display: 'flex', alignItems: 'center', justifyContent: 'center' }} role="dialog" aria-label="Override dialog">
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.6)' }} onClick={() => setOverrideDialogOpen(false)} aria-hidden />
          <div style={{ position: 'relative', zIndex: 10, width: 480, maxWidth: '92vw', background: '#1c1c1e', color: '#e4e4e7', borderRadius: 10, boxShadow: '0 8px 32px rgba(0,0,0,0.5)', padding: '1.5rem' }}>
            <h3 style={{ margin: '0 0 0.25rem', fontSize: '1rem' }}>
              {overrideDialogType === 'method_selection' ? 'Override method selection'
                : overrideDialogType === 'variant_selection' ? 'Override BOM variant selection'
                : 'Override component split allocation'}
            </h3>
            <p style={{ margin: '0 0 1rem', fontSize: '0.8rem', color: '#a1a1aa' }}>
              <strong>{overrideDialogWo.product_id}</strong> @ {overrideDialogWo.location_id}
              {overrideDialogWo.demand_id && <> · demand {overrideDialogWo.demand_id}</>}
              {overrideDialogWo.start_time && <> · {overrideDialogWo.start_time.slice(0, 10)}</>}
            </p>
            {/* ── Method selection form ─── */}
            {overrideDialogType === 'method_selection' && overrideDialogWo && (
              <>
                {overrideDialogWo.wo_explanation_method && (
                  <div style={{ marginBottom: '1rem', padding: '0.6rem 0.75rem', background: '#27272a', borderRadius: 6, borderLeft: '3px solid #a78bfa' }}>
                    <div style={{ fontSize: '0.7rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '0.3rem' }}>Auto-selected reason</div>
                    <p style={{ margin: 0, fontSize: '0.8rem', color: '#d4d4d8', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{overrideDialogWo.wo_explanation_method}</p>
                  </div>
                )}
                <label style={{ display: 'block', fontSize: '0.85rem', color: '#e4e4e7', marginBottom: '0.4rem' }}>Force method</label>
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
                <p style={{ fontSize: '0.75rem', color: '#71717a', marginTop: '0.5rem' }}>This overrides the planner&apos;s auto-selected method for <strong>{overrideDialogWo.product_id} @ {overrideDialogWo.location_id}</strong>. Re-run plan to apply.</p>
              </>
            )}
            {/* ── Variant selection form ─── */}
            {overrideDialogType === 'variant_selection' && overrideDialogWo && (
              <>
                {overrideDialogWo.wo_explanation_variant && (
                  <div style={{ marginBottom: '1rem', padding: '0.6rem 0.75rem', background: '#27272a', borderRadius: 6, borderLeft: '3px solid #a78bfa' }}>
                    <div style={{ fontSize: '0.7rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '0.3rem' }}>Auto-selected reason</div>
                    <p style={{ margin: 0, fontSize: '0.8rem', color: '#d4d4d8', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{overrideDialogWo.wo_explanation_variant}</p>
                  </div>
                )}
                <label style={{ display: 'block', fontSize: '0.85rem', color: '#e4e4e7', marginBottom: '0.4rem' }}>Force ALT_GROUP</label>
                <input
                  type="text"
                  placeholder="Type the ALT_GROUP name to force (e.g. GROUP1)"
                  value={overrideVariantValue}
                  onChange={(e) => setOverrideVariantValue(e.target.value)}
                  style={{ width: '100%', padding: '0.5rem 0.75rem', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 6, color: '#fafafa', fontSize: '0.85rem', boxSizing: 'border-box' }}
                  autoFocus
                />
                <p style={{ fontSize: '0.75rem', color: '#71717a', marginTop: '0.5rem' }}>Read the reason above to see which ALT_GROUP was chosen and its alternatives. Re-run plan to apply.</p>
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
                    <span>Total planned: <strong style={{ color: '#e4e4e7' }}>{totalPlanned.toLocaleString(undefined, { maximumFractionDigits: 1 })}</strong></span>
                    <span>Split mode: <strong style={{ color: '#e4e4e7' }}>{overrideDialogWo.wo_consolidation_split_mode === 'proportional' ? 'Proportional' : 'Priority first'}</strong></span>
                  </div>
                  <table style={{ width: '100%', fontSize: '0.82rem', borderCollapse: 'collapse', marginBottom: '0.5rem' }}>
                    <thead>
                      <tr style={{ color: '#71717a', textAlign: 'left', borderBottom: '1px solid #3d3d40' }}>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem' }}>Demand</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem' }}>Parent product</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem', textAlign: 'right' }}>Pri</th>
                        <th style={{ paddingBottom: '0.3rem', paddingRight: '0.5rem', textAlign: 'right' }}>Requested</th>
                        <th style={{ paddingBottom: '0.3rem', textAlign: 'right' }}>Override qty</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overrideSplitRows.map((row, i) => (
                        <tr key={i} style={{ borderTop: '1px solid #27272a' }}>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', color: '#d4d4d8' }}>{row.demand_id ?? '–'}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', color: '#a1a1aa', fontSize: '0.78rem' }}>{row.parent_product}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', textAlign: 'right' }}>{row.priority}</td>
                          <td style={{ padding: '0.3rem 0.5rem 0.3rem 0', textAlign: 'right', color: '#71717a' }}>{row.requested_qty.toLocaleString(undefined, { maximumFractionDigits: 1 })}</td>
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
                        <td colSpan={4} style={{ paddingTop: '0.3rem', color: '#a1a1aa', fontSize: '0.78rem', textAlign: 'right', paddingRight: '0.5rem' }}>Sum</td>
                        <td style={{ paddingTop: '0.3rem', textAlign: 'right', fontWeight: 600, color: over ? '#f87171' : sumQty <= totalPlanned + 0.001 ? '#4ade80' : '#e4e4e7' }}>
                          {sumQty.toLocaleString(undefined, { maximumFractionDigits: 1 })}
                          {over && <span style={{ marginLeft: 4, fontSize: '0.7rem', color: '#f87171' }}>exceeds total</span>}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                  <p style={{ fontSize: '0.75rem', color: '#71717a', margin: '0.25rem 0 0' }}>If the sum exceeds the total planned qty the backend will scale all quantities down proportionally. Re-run plan to apply.</p>
                </>
              );
            })()}
            {overrideDialogError && <p style={{ color: '#f87171', fontSize: '0.8rem', marginTop: '0.4rem' }}>{overrideDialogError}</p>}
            {(() => {
              if (!overrideDialogWo || !overrideDialogType) return null;
              const productId = overrideDialogWo.product_id ?? '';
              const locationId = overrideDialogWo.location_id ?? '';
              const demandId = overrideDialogWo.demand_id ?? '';
              const entityKey = overrideDialogType === 'component_split'
                ? (overrideDialogWo.start_time ? `${productId}|${locationId}|${overrideDialogWo.start_time.slice(0, 10)}` : `${productId}|${locationId}`)
                : (demandId ? `${productId}|${locationId}|${demandId}` : `${productId}|${locationId}`);
              const existingOverride = overrides.find((o) => o.entity_type === overrideDialogType && o.entity_key === entityKey) ?? null;
              if (!existingOverride) return null;
              return (
                <div style={{ marginTop: '0.75rem', padding: '0.5rem 0.75rem', background: 'rgba(180,83,9,0.12)', border: '1px solid rgba(180,83,9,0.4)', borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                  <span style={{ fontSize: '0.8rem', color: '#fbbf24' }}>Override already saved — re-run plan to apply, or reset to auto.</span>
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
                        setOverrideDialogType(null);
                      } catch (e) {
                        setOverrideDialogError(e instanceof Error ? e.message : 'Failed to reset');
                      } finally {
                        setOverrideDialogSaving(false);
                      }
                    }}
                  >
                    Reset to auto
                  </button>
                </div>
              );
            })()}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: '1rem' }}>
              <button type="button" className="secondary" onClick={() => setOverrideDialogOpen(false)}>Cancel</button>
              <button type="button" disabled={overrideDialogSaving} onClick={handleSaveOverride}>
                {overrideDialogSaving ? 'Saving…' : 'Save override'}
              </button>
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
              style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 6, cursor: 'col-resize', zIndex: 11 }}
            />
            {/* Header */}
            <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
                <h3 style={{ margin: 0, color: '#fafafa', fontSize: '1rem' }}>Work order explanation</h3>
                <button type="button" onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); setWoExplainRow(null); }} style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
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
                    <h4 style={{ margin: 0, color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Method selection</h4>
                    {woExplainRow.multi_supply_available && (
                      <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 8px' }}
                        onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); openOverrideDialog('method_selection', woExplainRow); }}>
                        Override
                      </button>
                    )}
                  </div>
                  <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{woExplainRow.wo_explanation_method}</p>
                </section>
              )}
              {/* Variant choice */}
              {woExplainRow.wo_explanation_variant && (
                <section style={{ marginBottom: '1.25rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.4rem' }}>
                    <h4 style={{ margin: 0, color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>BOM variant selection</h4>
                    <button type="button" className="secondary" style={{ fontSize: '0.72rem', padding: '2px 8px' }}
                      onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); openOverrideDialog('variant_selection', woExplainRow); }}>
                      Override
                    </button>
                  </div>
                  <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>{woExplainRow.wo_explanation_variant}</p>
                </section>
              )}
              {/* Demand competition */}
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Demand competition (BOM graph)</h4>
                {(woExplainRow.wo_competing_demands?.length ?? 0) <= 1 ? (
                  <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>Only one demand product requires this component — no competition.</p>
                ) : (
                  <>
                    <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                      <strong>{woExplainRow.wo_competing_demands!.length}</strong> demand product(s) require this component per the BOM graph:
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
                    <h4 style={{ margin: 0, color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Consolidation split</h4>
                    <button
                      type="button"
                      className="secondary"
                      style={{ fontSize: '0.72rem', padding: '2px 8px' }}
                      onClick={() => { setWoExplainOpen(false); setWoExplainKey(null); openOverrideDialog('component_split', woExplainRow); }}
                    >
                      Override split
                    </button>
                  </div>
                  <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                    This work order was consolidated for <strong>{woExplainRow.wo_consolidation_split_details.length}</strong> demands
                    (total planned: <strong>{(woExplainRow.wo_consolidation_total_planned ?? 0).toLocaleString(undefined, { maximumFractionDigits: 1 })}</strong>).
                    Split mode: <strong>{woExplainRow.wo_consolidation_split_mode === 'proportional' ? 'Proportional' : 'Priority first'}</strong>.
                  </p>
                  <table style={{ width: '100%', fontSize: '0.78rem', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ color: '#a1a1aa', textAlign: 'left' }}>
                        <th style={{ paddingBottom: '0.2rem' }}>Demand</th>
                        <th style={{ paddingBottom: '0.2rem' }}>Parent product</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>Priority</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>Requested</th>
                        <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}>Allocated</th>
                      </tr>
                    </thead>
                    <tbody>
                      {woExplainRow.wo_consolidation_split_details.map((row, i) => (
                        <tr key={i} style={{ borderTop: '1px solid #3d3d40' }}>
                          <td style={{ padding: '0.2rem 0.4rem 0.2rem 0' }}>{row.demand_id ?? '–'}</td>
                          <td style={{ padding: '0.2rem 0.4rem 0.2rem 0', color: '#a1a1aa' }}>{row.parent_product}</td>
                          <td style={{ padding: '0.2rem 0', textAlign: 'right' }}>{row.priority}</td>
                          <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right' }}>{row.requested_qty.toLocaleString(undefined, { maximumFractionDigits: 1 })}</td>
                          <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right', color: row.allocated_qty < row.requested_qty - 0.01 ? '#f87171' : '#4ade80' }}>
                            {row.allocated_qty.toLocaleString(undefined, { maximumFractionDigits: 1 })}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </section>
              )}
              {/* Supply alternatives */}
              <section style={{ marginBottom: '1.25rem' }}>
                <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Supply alternatives (BOM graph)</h4>
                {woExplainRow.multi_supply_available ? (
                  <p style={{ margin: 0, fontSize: '0.875rem' }}>
                    Multiple supply methods are available for this component in the BOM graph.
                    {woExplainRow.wo_explanation_method
                      ? <> See <em>Method selection</em> above for the choice made.</>
                      : <> No method selection explanation was recorded (this may be a pre-existing inventory supply).</>}
                  </p>
                ) : (
                  <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>Only one supply method available for this component — no alternatives.</p>
                )}
              </section>
              {/* No explanation available */}
              {!woExplainRow.wo_explanation_method && !woExplainRow.wo_explanation_variant && (
                <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>
                  No method or variant explanation available for this work order. This may occur for consolidated work orders or when only one option existed.
                </p>
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
          aria-label="Planning copilot"
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
              aria-label="Resize copilot panel"
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
                <h3 style={{ margin: 0, color: '#fafafa' }}>Planning copilot</h3>
                <button type="button" onClick={() => setCopilotOpen(false)} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
              </div>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#a1a1aa' }}>
                <strong>Variants:</strong> {planningConfig.variant_selection?.multiple === false ? 'single best' : 'all feasible (equal split)'}.{' '}
                <strong>Methods:</strong> {planningConfig.method_selection?.multiple === true ? 'equal split' : planningConfig.method_selection?.elaborate === true ? 'one by score (elaborate)' : 'one by preference'}.{' '}
                <strong>Purchase:</strong> {planningConfig.purchase_allowed === false ? 'disabled' : 'allowed'}.{' '}
                <strong>Consolidation:</strong> {planningConfig.consolidation?.enabled === true
                  ? `on · ${planningConfig.consolidation.period_days ?? 7}d · ${planningConfig.consolidation.allocation_mode === 'proportional' ? 'proportional' : 'priority-first'}`
                  : 'off'}. Express your requirements in natural language; the system may ask follow-up questions to clarify.
              </p>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
              {copilotMessages.length === 0 && (
                <p style={{ margin: 0, fontSize: '0.875rem', color: '#71717a' }}>
                  Ask how to configure <strong>variant</strong> and <strong>method</strong> selection. Examples: &quot;use only one variant per demand&quot;, &quot;split demand across all feasible variants&quot;, &quot;equal split across methods&quot;, &quot;show current config&quot;. With an LLM enabled, you can use natural language and the assistant may ask clarifying questions.
                </p>
              )}
              {copilotMessages.map((m, i) => (
                <div key={i} style={{ marginBottom: '0.75rem' }}>
                  <span style={{ fontWeight: 600, color: m.role === 'user' ? '#a78bfa' : '#67e8f9', fontSize: '0.8rem' }}>{m.role === 'user' ? 'You' : 'Copilot'}: </span>
                  <span style={{ whiteSpace: 'pre-wrap', fontSize: '0.875rem' }}>{m.text.replace(/\*\*(.*?)\*\*/g, '$1')}</span>
                </div>
              ))}
              {copilotLoading && <p style={{ margin: 0, fontSize: '0.875rem', color: '#a1a1aa' }}>Thinking…</p>}
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
                try {
                  const res = await planningCopilot(id, text, planningConfig, copilotMessages);
                  if (res.config_update) setPlanningConfig((prev) => ({
                    ...prev,
                    ...res.config_update!,
                    variant_selection: res.config_update!.variant_selection ? { ...prev.variant_selection, ...res.config_update!.variant_selection } : prev.variant_selection,
                    method_selection: res.config_update!.method_selection ? { ...prev.method_selection, ...res.config_update!.method_selection } : prev.method_selection,
                    purchase_allowed: 'purchase_allowed' in res.config_update! ? res.config_update!.purchase_allowed : prev.purchase_allowed,
                    consolidation: res.config_update!.consolidation ? { ...prev.consolidation, ...res.config_update!.consolidation } : prev.consolidation,
                  }));
                  setCopilotMessages((prev) => [...prev, { role: 'assistant', text: res.reply }]);
                } catch {
                  const { reply, configUpdate } = parseCopilotIntent(text, planningConfig);
                  if (configUpdate) setPlanningConfig((prev) => ({
                    ...prev,
                    ...configUpdate,
                    variant_selection: configUpdate.variant_selection ? { ...prev.variant_selection, ...configUpdate.variant_selection } : prev.variant_selection,
                    method_selection: configUpdate.method_selection ? { ...prev.method_selection, ...configUpdate.method_selection } : prev.method_selection,
                    consolidation: configUpdate.consolidation ? { ...prev.consolidation, ...configUpdate.consolidation } : prev.consolidation,
                  }));
                  setCopilotMessages((prev) => [...prev, { role: 'assistant', text: reply }]);
                } finally {
                  setCopilotLoading(false);
                }
              }}
            >
              <input
                type="text"
                value={copilotInput}
                onChange={(e) => setCopilotInput(e.target.value)}
                placeholder="e.g. I want to use a single best variant"
                disabled={copilotLoading}
                style={{ width: '100%', padding: '8px 12px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 6, color: '#fafafa' }}
                aria-label="Chat with planning copilot"
              />
              <button type="submit" disabled={copilotLoading} className="secondary" style={{ marginTop: '0.5rem' }}>Send</button>
            </form>
          </div>
        </div>,
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
            onClick={() => { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); }}
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
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
              <h3 style={{ margin: 0, color: '#fafafa' }}>
                {planPeggingContext.type === 'supply'
                  ? tP('supplyView.peggingPanel.title', { supplyId: planPeggingContext.supplyId })
                  : planPeggingContext.type === 'demand'
                    ? `Pegging: ${planPeggingContext.row.demand_id ?? planPeggingContext.row.product_id}`
                    : `Pegging: ${planPeggingContext.row.product_id} @ ${planPeggingContext.row.location_id}`}
              </h3>
              <button type="button" onClick={() => { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setPreviousPeggingContext(null); }} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
            </div>
            <p style={{ margin: 0, marginBottom: '0.5rem', fontSize: '0.8rem', color: '#71717a' }}>
              {planPeggingContext.type === 'supply'
                ? tP('supplyView.peggingPanel.description')
                : planPeggingContext.type === 'work_order'
                  ? 'Root = this work order; below it are the supplies that fulfill it (inventory, child work orders, purchase) at all levels.'
                  : '▢ blue = demand target, ▢ purple = supply / purchase inventory, ⚙ green = work order (transformation). Root = target demand; leaves = supply or purchase inventory.'}
              {planPeggingContext.type !== 'supply' && (
                <>{' '}When multiple inventories or work orders appear as siblings under the same parent, they are alternative paths <strong>OR</strong> that can each supply flow to that parent.</>
              )}
            </p>
            {planPeggingContext.type === 'supply' && (() => {
              const ctx = planPeggingContext;
              return (
                <div>
                  <p style={{ fontSize: '0.8rem', color: '#71717a', margin: '0 0 0.75rem' }}>
                    {tP('supplyView.peggingPanel.initialQty')} <strong style={{ color: '#e4e4e7' }}>{Number(ctx.initialQty).toLocaleString()}</strong>
                    {' · '}{tP('supplyView.peggingPanel.consumed')} <strong style={{ color: '#a78bfa' }}>{Number(ctx.consumedQty).toLocaleString()}</strong>
                    {' · '}{tP('supplyView.peggingPanel.demandCount', { count: ctx.peggedDemands.length })}
                  </p>
                  {/* ── Assessment UI ──────────────────────────────────────── */}
                  <div style={{ marginBottom: '1rem', padding: '0.6rem 0.75rem', background: '#1c1c1e', borderRadius: 6, border: '1px solid #3d3d40' }}>
                    {/* Input row */}
                    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
                      <label style={{ fontSize: '0.78rem', color: '#a1a1aa', display: 'flex', alignItems: 'center', gap: 4 }}>
                        {tP('assessment.delayDays')}
                        <input
                          type="number" min={0}
                          value={assessDelayDays}
                          onChange={(e) => setAssessDelayDays(Math.max(0, Number(e.target.value)))}
                          style={{ width: 60, fontSize: '0.78rem', padding: '2px 4px', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 3 }}
                        />
                      </label>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <span style={{ fontSize: '0.78rem', color: '#a1a1aa' }}>{tP('assessment.qtyDecreasePct')}</span>
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
                        {assessmentRunning ? tP('assessment.assessing') : tP('assessment.assess')}
                      </button>
                    </div>
                    {/* Error */}
                    {assessError && (
                      <p style={{ color: '#f87171', fontSize: '0.75rem', margin: '0 0 0.4rem' }}>{assessError}</p>
                    )}
                    {/* Rating result */}
                    {assessmentResult && (
                      <div style={{ marginBottom: '0.4rem' }}>
                        <span style={{ fontSize: '0.82rem', color: '#a1a1aa' }}>{tP('assessment.ratingLabel')} </span>
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
                      {assessmentHistoryOpen ? '▾' : '▸'} {tP('assessment.history')}
                    </button>
                    {assessmentHistoryOpen && (
                      <div style={{ marginTop: '0.5rem' }}>
                        {assessmentHistory.length === 0 ? (
                          <p style={{ fontSize: '0.75rem', color: '#71717a', margin: 0 }}>{tP('assessment.noHistory')}</p>
                        ) : (
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.75rem' }}>
                            <thead>
                              <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa' }}>
                                <th style={{ textAlign: 'left', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.date')}</th>
                                <th style={{ textAlign: 'center', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.rating')}</th>
                                <th style={{ textAlign: 'right', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.delay')}</th>
                                <th style={{ textAlign: 'right', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.qtyPct')}</th>
                                <th style={{ textAlign: 'left', padding: '3px 5px', fontWeight: 500 }}>{tP('assessment.historyColumns.explanation')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {assessmentHistory.map((h) => (
                                <tr key={h.id} style={{ borderBottom: '1px solid #27272a' }}>
                                  <td style={{ padding: '3px 5px', color: '#71717a', whiteSpace: 'nowrap' }}>{h.createdAt.slice(0, 10)}</td>
                                  <td style={{ padding: '3px 5px', textAlign: 'center' }}>
                                    <span style={{
                                      padding: '0 6px',
                                      borderRadius: 10,
                                      fontWeight: 600,
                                      fontSize: '0.72rem',
                                      background: h.rating === 'LOW' ? 'rgba(52,211,153,0.15)' : h.rating === 'HIGH' ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                                      color: h.rating === 'LOW' ? '#34d399' : h.rating === 'HIGH' ? '#f87171' : '#fbbf24',
                                    }}>{h.rating}</span>
                                  </td>
                                  <td style={{ padding: '3px 5px', textAlign: 'right', color: '#e4e4e7' }}>{h.deliveryDelayDays}</td>
                                  <td style={{ padding: '3px 5px', textAlign: 'right', color: '#e4e4e7' }}>{h.quantityDecreasePct}</td>
                                  <td style={{ padding: '3px 5px', color: '#a1a1aa', maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={h.explanation}>{h.explanation}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
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
                            <td style={{ padding: '4px 6px', textAlign: 'right', color: '#a78bfa' }}>{Number(d.qtyConsumed).toLocaleString()}</td>
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

              function renderNode(node: PlanningPeggingNode, path: string, depth: number, xlink = false) {
                const childrenList = node.children ?? [];

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
                  ? `${node.product_id ?? node.demand_id ?? '–'} · ${Number(node.quantity ?? 0).toLocaleString()} @ ${node.location_id ?? '–'}${node.demand_id && node.product_id !== node.demand_id && node.demand_id !== contextDemandId ? ` (demand ${node.demand_id})` : ''}`
                  : node.type === 'work_order'
                    ? (() => {
                        const qty = woRowQty ?? Number(node.quantity ?? 0);
                        const lotCount = (node as { lot_count?: number | null }).lot_count ?? null;
                        const maxLotSize = (node as { max_lot_size?: number | null }).max_lot_size ?? null;
                        const lotPart =
                          lotCount && lotCount > 1 && maxLotSize
                            ? ` · ${lotCount} lots of up to ${Number(maxLotSize).toLocaleString()}`
                            : '';
                        return `${node.method} ${node.product_id} @ ${node.location_id ?? '–'} · ${qty.toLocaleString()}${node.end_time ? ` · end ${node.end_time}` : ''}${lotPart}`;
                      })()
                    : node.type === 'supply'
                      ? `${node.product_id} @ ${node.location_id ?? '–'} · ${Number(node.quantity ?? 0).toLocaleString()}${node.supply_id ? ` · ${node.supply_id}` : ''}`
                      : `${node.product_id} @ ${node.location_id ?? '–'} · ${Number(node.quantity ?? 0).toLocaleString()}`;
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
                } else if (!relation && hasChildren && childrenList.length > 1 && node.type !== 'work_order') {
                  // Fallback: multiple inbound options into an inventory/demand node behave as OR.
                  childGroupKind = 'or';
                  childGroupLabel = 'ANY of the inventories / work orders below can supply this node (OR).';
                }
                return (
                  <div key={path} style={{ marginBottom: 4 }}>
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
                        background: depth % 2 === 0 ? 'rgba(255,255,255,0.04)' : 'transparent',
                        border: 'none',
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
                    {node.type === 'work_order' && (node.method_choice_explanation || node.variant_choice_explanation) && (() => {
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
                            Why (method / variant)
                          </button>
                          {isExplanationOpen && (
                            <div style={{ paddingLeft: 8, borderLeft: '2px solid #3d3d40', marginTop: 2 }}>
                              {node.method_choice_explanation && (
                                <p style={{ margin: '0 0 4px', lineHeight: 1.35 }}><strong>Method:</strong> {node.method_choice_explanation}</p>
                              )}
                              {node.variant_choice_explanation && (
                                <p style={{ margin: 0, lineHeight: 1.35 }}><strong>Variant:</strong> {node.variant_choice_explanation}</p>
                              )}
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
              return <div style={{ marginTop: '0.5rem' }}>{tree ? renderNode(tree, '0', 0) : null}</div>;
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
              <button type="button" onClick={() => setPeggingOpen(false)} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>Close</button>
            </div>
            {peggingData?.direction === 'demand-to-supply' && (peggingData.demand_allocated_qty != null || peggingData.demand_requested_qty != null) && (
              <>
                <p style={{ margin: 0, marginBottom: '0.25rem', color: '#a1a1aa', fontSize: '0.9rem' }}>
                  Allocated: <strong style={{ color: '#fafafa' }}>{Number(peggingData.demand_allocated_qty ?? 0).toLocaleString()}</strong>
                  {peggingData.demand_requested_qty != null && (
                    <> (requested: {Number(peggingData.demand_requested_qty).toLocaleString()})</>
                  )}
                </p>
                <p style={{ margin: 0, marginBottom: '1rem', color: '#71717a', fontSize: '0.8rem' }}>
                  First level below = direct contributors; their edge qtys sum to Allocated above. Deeper levels: qty = flow along that edge (· = leaf, no further expansion).
                </p>
              </>
            )}
            {peggingLoading && <p style={{ color: '#a1a1aa' }}>Loading…</p>}
            {!peggingLoading && peggingData && !peggingTreeReady && (
              <div style={{ fontSize: '0.9rem' }}>
                <p><strong>Nodes:</strong> {(Array.isArray(peggingData.nodes) ? peggingData.nodes : []).length} &nbsp; <strong>Edges:</strong> {(Array.isArray(peggingData.edges) ? peggingData.edges : []).length}</p>
                <p style={{ color: '#a1a1aa' }}>Building tree…</p>
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
