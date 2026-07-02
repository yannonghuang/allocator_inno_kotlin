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
  getPeggingSaveStatus,
  type PeggingSaveStatus,
  planningAgent,
  listActivePlanJobs,
  type ActivePlanJob,
  listPlanRuns,
  // Post-response polling: chat reuses getPlanStatus to track plans that
  // exceeded the agent's 25s blocking window.
  getPlanRun,
  getPlanRunPegging,
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
  getPurchasableRawMaterials,
  type PurchasableRawMaterial,
  getConstraintOptions,
  type ConstraintOptions,
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
import { computeHorizon, ScheduleBar, ScheduleHorizonRuler, methodColor } from './_workOrderSchedule';
import { WoScheduleImpactPanel, WoScheduleQuickModal } from './_woScheduleImpact';
import type { PlanResult } from '../../../lib/api';

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

// commit - request, in whole days. Both inputs may be null (unfulfilled demand or
// missing request date); returns null in that case so the cell renders as "—" and
// the "Late delivery" filter doesn't pick the row up.
function computeLatenessDays(requestDueTime: string | null | undefined, revisedTime: string | null | undefined): number | null {
  if (!requestDueTime || !revisedTime) return null;
  const req = new Date(requestDueTime.slice(0, 10));
  const rev = new Date(revisedTime.slice(0, 10));
  if (Number.isNaN(req.getTime()) || Number.isNaN(rev.getTime())) return null;
  return Math.round((rev.getTime() - req.getTime()) / 86_400_000);
}

// Inline markdown renderer for the planning-agent chat panel. Handles:
//   1. GFM-style pipe tables (header row + `|---|---|` separator + body rows)
//   2. `**bold**` spans
// Everything else is rendered as pre-wrap text. Mirrors the scheduling-agent
// renderer (schub-openclaw Agent.tsx) so the same Markdown produced by Kotlin
// renders identically in both UIs. We hand-roll this to avoid pulling in
// react-markdown + remark-gfm for two features.
function renderCopilotInline(text: string, keyPrefix: string): React.ReactNode {
  // Split on **bold** spans, keep the markers in the result so we can detect them.
  const parts = text.split(/(\*\*[^*\n]+\*\*)/g);
  return parts.map((p, i) => (
    p.startsWith('**') && p.endsWith('**') && p.length >= 4
      ? <strong key={`${keyPrefix}-b${i}`}>{p.slice(2, -2)}</strong>
      : <React.Fragment key={`${keyPrefix}-t${i}`}>{p}</React.Fragment>
  ));
}

function renderCopilotText(text: string): React.ReactNode[] {
  const lines = text.split('\n');
  const out: React.ReactNode[] = [];
  let bufStart = 0;
  let i = 0;
  const isPipeRow = (s: string) => /^\s*\|.+\|\s*$/.test(s);
  const isSep = (s: string) => /^\s*\|[-:\s|]+\|\s*$/.test(s);
  const flushText = (untilExclusive: number) => {
    if (untilExclusive <= bufStart) return;
    const chunk = lines.slice(bufStart, untilExclusive).join('\n');
    if (chunk.length > 0) {
      out.push(
        <span key={`p${bufStart}`} style={{ whiteSpace: 'pre-wrap' }}>
          {renderCopilotInline(chunk, `p${bufStart}`)}
        </span>
      );
    }
  };
  while (i < lines.length) {
    if (i + 1 < lines.length && isPipeRow(lines[i]) && isSep(lines[i + 1])) {
      flushText(i);
      const header = lines[i].trim().slice(1, -1).split('|').map((s) => s.trim());
      let j = i + 2;
      const body: string[][] = [];
      while (j < lines.length && isPipeRow(lines[j]) && !isSep(lines[j])) {
        body.push(lines[j].trim().slice(1, -1).split('|').map((s) => s.trim()));
        j++;
      }
      out.push(
        <table key={`tbl${i}`} style={{ borderCollapse: 'collapse', margin: '0.4rem 0', fontSize: '0.85rem' }}>
          <thead>
            <tr>{header.map((h, hi) => (
              <th key={hi} style={{ textAlign: 'left', padding: '0.25rem 0.9rem 0.25rem 0', color: '#a1a1aa', fontWeight: 600, borderBottom: '1px solid #3d3d40' }}>
                {renderCopilotInline(h, `th${i}-${hi}`)}
              </th>
            ))}</tr>
          </thead>
          <tbody>
            {body.map((row, ri) => (
              <tr key={ri}>{row.map((c, ci) => (
                <td key={ci} style={{ padding: '0.2rem 0.9rem 0.2rem 0', verticalAlign: 'top' }}>
                  {renderCopilotInline(c, `td${i}-${ri}-${ci}`)}
                </td>
              ))}</tr>
            ))}
          </tbody>
        </table>
      );
      i = j;
      bufStart = j;
    } else {
      i++;
    }
  }
  flushText(lines.length);
  return out;
}

/**
 * Searchable, multi-valued picker for the "selective purchase" whitelist. Shared by
 * the plan-conditions config panel and the copilot `/raw` slash command. Filters on
 * product_id / description / vendor / SKU series. An empty selection means "all raw
 * materials are purchasable" (the default).
 */
function RawMaterialPicker({
  options,
  selected,
  onChange,
  initialFilter,
  defaultCollapsed,
  tP,
}: {
  options: PurchasableRawMaterial[];
  selected: string[];
  onChange: (next: string[]) => void;
  initialFilter?: string;
  defaultCollapsed?: boolean;
  tP: (k: string) => string;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [filter, setFilter] = useState(initialFilter ?? '');
  const sel = new Set(selected);
  const f = filter.trim().toLowerCase();
  // Wildcard-aware match: a query containing `*` is treated as a glob anchored at the
  // start of product_id (e.g. `160-*` → every 160- series id). Otherwise substring match
  // across id / description / vendor / sku series (the original behavior).
  const matches = (o: PurchasableRawMaterial): boolean => {
    if (!f) return true;
    if (f.includes('*')) {
      const rx = new RegExp('^' + f.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*'), 'i');
      return rx.test(o.product_id);
    }
    return o.product_id.toLowerCase().includes(f) ||
      (o.description ?? '').toLowerCase().includes(f) ||
      (o.vendor_id ?? '').toLowerCase().includes(f) ||
      (o.sku_pattern ?? '').toLowerCase().includes(f);
  };
  const shown = f ? options.filter(matches) : options;
  const shownIds = shown.map((o) => o.product_id);
  const toggle = (id: string) => {
    const next = new Set(sel);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onChange(Array.from(next));
  };
  const selectIds = (ids: string[]) => { const next = new Set(sel); ids.forEach((i) => next.add(i)); onChange(Array.from(next)); };
  const deselectIds = (ids: string[]) => { const next = new Set(sel); ids.forEach((i) => next.delete(i)); onChange(Array.from(next)); };
  const btnStyle: React.CSSProperties = { fontSize: '0.7rem', color: '#d4d4d8', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '2px 7px', cursor: 'pointer' };
  return (
    <div style={{ marginTop: '0.4rem' }}>
      {/* Toggle button — show/hide the full list; the selection count stays visible either way.
          The ⓘ explains the (surprising) whitelist semantics: empty ≡ all selected. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '3px 9px', color: '#d4d4d8', fontSize: '0.72rem', cursor: 'pointer' }}
        >
          <span>{collapsed ? '▸' : '▾'}</span>
          <span>{collapsed ? tP('config.purchasableShowList') : tP('config.purchasableHideList')}</span>
          <span style={{ color: sel.size === 0 ? '#fbbf24' : '#a1a1aa' }}>
            {`(${sel.size} / ${options.length} ${tP('config.purchasableSelected')})`}
            {sel.size === 0 && ` — ${tP('config.purchasableAllHint')}`}
          </span>
        </button>
        <span
          title={tP('config.purchasableSemantics')}
          style={{ fontSize: '0.78rem', color: '#71717a', cursor: 'help', border: '1px solid #52525b', borderRadius: '50%', width: 15, height: 15, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }}
        >
          i
        </span>
      </div>
      {!collapsed && (
        <div style={{ marginTop: 4 }}>
          <input
            type="text"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={tP('config.purchasableSearchPlaceholder')}
            style={{ width: '100%', padding: '4px 8px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem', marginBottom: 4 }}
          />
          {/* Bulk actions. Select all / Clear always apply to the WHOLE list; the wildcard
              pair (shown only when a filter is active) applies to the matched set — so
              `160-*` + Deselect matching removes just that series. The two are complementary:
              e.g. Select all, then filter 160-* → Deselect matching, filter 283-* → Deselect
              matching ⇒ everything except those series. */}
          <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 4 }}>
            <button type="button" style={btnStyle} onClick={() => onChange(options.map((o) => o.product_id))}>
              {tP('config.purchasableSelectAll')}
            </button>
            <button type="button" style={btnStyle} onClick={() => onChange([])}>
              {tP('config.purchasableClear')}
            </button>
            {f && (
              <>
                <span style={{ color: '#52525b' }}>|</span>
                <button type="button" style={btnStyle} onClick={() => selectIds(shownIds)}>
                  {`${tP('config.purchasableSelectShown')} (${shown.length})`}
                </button>
                <button type="button" style={btnStyle} onClick={() => deselectIds(shownIds)}>
                  {`${tP('config.purchasableDeselectShown')} (${shown.length})`}
                </button>
              </>
            )}
          </div>
          <div style={{ maxHeight: 160, overflowY: 'auto', border: '1px solid #3f3f46', borderRadius: 4, padding: '2px 4px' }}>
            {shown.length === 0 && (
              <div style={{ fontSize: '0.75rem', color: '#71717a', padding: '4px' }}>{tP('config.purchasableNone')}</div>
            )}
            {shown.map((o) => (
              <label key={o.product_id}
                style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '2px 0', cursor: 'pointer', fontSize: '0.78rem' }}>
                <input type="checkbox" checked={sel.has(o.product_id)} onChange={() => toggle(o.product_id)} />
                <span style={{ fontFamily: 'monospace', color: '#e4e4e7' }}>{o.product_id}</span>
                {o.description && <span style={{ color: '#a1a1aa' }}>— {o.description}</span>}
              </label>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

type ConstraintRule = { customer: string; parent: string; location: string; child: string };

/** Builder for customer-specific BOM-alternative constraints: four cascading dropdowns
 *  (customer → parent → location → child) + Add, with a removable list of added rules. */
/** Single-select dropdown with a type-to-filter input — for long option lists (customers,
 *  parent products) in the constraint editor. */
function SearchableSelect({ value, onChange, options, placeholder, disabled, width, tP }: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  placeholder: string;
  disabled?: boolean;
  width?: number;
  tP: (k: string) => string;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const w = width ?? 220;
  const selectedLabel = options.find((o) => o.value === value)?.label ?? '';
  const q = query.trim().toLowerCase();
  const shown = q ? options.filter((o) => o.value.toLowerCase().includes(q) || o.label.toLowerCase().includes(q)) : options;
  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <input
        type="text"
        disabled={disabled}
        value={open ? query : selectedLabel}
        placeholder={placeholder}
        onChange={(e) => { setQuery(e.target.value); if (!open) setOpen(true); }}
        onFocus={() => { setOpen(true); setQuery(''); }}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        style={{ padding: '3px 18px 3px 6px', background: disabled ? '#1f1f22' : '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.78rem', width: w }}
      />
      {value && !open && !disabled && (
        <button type="button" title={tP('config.constraintRemove')} onMouseDown={(e) => { e.preventDefault(); onChange(''); }}
          style={{ position: 'absolute', right: 4, top: 2, background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.85rem', lineHeight: 1, padding: 0 }}>×</button>
      )}
      {open && !disabled && (
        <div style={{ position: 'absolute', zIndex: 30, top: '100%', left: 0, width: w, maxHeight: 220, overflowY: 'auto', background: '#1f1f22', border: '1px solid #3f3f46', borderRadius: 4, marginTop: 2 }}>
          {shown.length === 0 && <div style={{ padding: '4px 6px', fontSize: '0.75rem', color: '#71717a' }}>{tP('config.constraintNoMatch')}</div>}
          {shown.slice(0, 300).map((o) => (
            <div key={o.value} onMouseDown={(e) => { e.preventDefault(); onChange(o.value); setOpen(false); setQuery(''); }}
              style={{ padding: '3px 6px', fontSize: '0.78rem', color: '#e4e4e7', cursor: 'pointer', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', background: o.value === value ? '#3730a3' : 'transparent' }}>
              {o.label}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ConstraintPicker({
  options,
  constraints,
  onChange,
  defaultCollapsed,
  tP,
}: {
  options: ConstraintOptions;
  constraints: ConstraintRule[];
  onChange: (next: ConstraintRule[]) => void;
  defaultCollapsed?: boolean;
  tP: (k: string) => string;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [customer, setCustomer] = useState('');
  const [parent, setParent] = useState('');
  const [location, setLocation] = useState('*');
  const [child, setChild] = useState('');

  const parentOpt = options.parents.find((p) => p.parent === parent);
  const locationOpts = parentOpt?.locations ?? [];
  const childOpts = parentOpt?.children ?? [];
  const canAdd = !!(customer && parent && child);

  const selStyle: React.CSSProperties = { padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.78rem', maxWidth: 240 };
  const btnStyle: React.CSSProperties = { fontSize: '0.7rem', color: '#d4d4d8', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '3px 9px', cursor: 'pointer' };
  const anyLoc = (l: string) => (l === '*' || !l ? tP('config.constraintLocationAny') : l);
  const custLabel = (cid: string) => { const c = options.customers.find((x) => x.customer_id === cid); return c?.description ? `${cid} — ${c.description}` : cid; };

  const addRule = () => {
    if (!canAdd) return;
    const rule: ConstraintRule = { customer, parent, location: location || '*', child };
    if (constraints.some((r) => r.customer === rule.customer && r.parent === rule.parent && r.location === rule.location && r.child === rule.child)) return;
    onChange([...constraints, rule]);
    setChild('');   // keep customer/parent so the user can add sibling rules quickly
  };

  return (
    <div style={{ marginTop: '0.4rem' }}>
      <button type="button" onClick={() => setCollapsed((c) => !c)}
        style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', background: 'none', border: 'none', padding: 0, color: 'inherit', fontSize: '0.875rem', cursor: 'pointer' }}>
        <span style={{ fontSize: '0.7rem', color: '#a1a1aa' }}>{collapsed ? '▸' : '▾'}</span>
        <span>{collapsed ? tP('config.constraintShow') : tP('config.constraintHide')}</span>
        <span style={{ color: '#a1a1aa' }}>{`(${constraints.length})`}</span>
      </button>
      {!collapsed && (
        <div style={{ marginTop: '0.35rem', marginLeft: '1.5rem' }}>
          <div style={{ fontSize: '0.7rem', color: '#71717a', marginBottom: 4 }}>{tP('config.constraintHint')}</div>
          {options.parents.length === 0 ? (
            <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{tP('config.constraintNoAlternatives')}</div>
          ) : (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', marginBottom: 6 }}>
              <SearchableSelect
                value={customer}
                onChange={setCustomer}
                options={options.customers.map((c) => ({ value: c.customer_id, label: custLabel(c.customer_id) }))}
                placeholder={`${tP('config.constraintCustomer')}…`}
                width={220}
                tP={tP}
              />
              <SearchableSelect
                value={parent}
                onChange={(v) => { setParent(v); setLocation('*'); setChild(''); }}
                options={options.parents.map((p) => ({ value: p.parent, label: p.parent }))}
                placeholder={`${tP('config.constraintParent')}…`}
                width={260}
                tP={tP}
              />
              <select value={location} onChange={(e) => setLocation(e.target.value)} disabled={!parent} style={selStyle}>
                <option value="*">{tP('config.constraintLocationAny')}</option>
                {locationOpts.map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
              <select value={child} onChange={(e) => setChild(e.target.value)} disabled={!parent} style={selStyle}>
                <option value="">{tP('config.constraintChild')}…</option>
                {childOpts.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
              <button type="button" onClick={addRule} disabled={!canAdd} style={{ ...btnStyle, opacity: canAdd ? 1 : 0.4, cursor: canAdd ? 'pointer' : 'default' }}>{tP('config.constraintAdd')}</button>
            </div>
          )}
          {constraints.length === 0 ? (
            <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{tP('config.constraintEmpty')}</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {constraints.map((r, i) => (
                <div key={`${r.customer}|${r.parent}|${r.location}|${r.child}`} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.76rem' }}>
                  <span style={{ fontFamily: 'monospace', color: '#e4e4e7' }}>
                    {r.customer} · {r.parent} @ {anyLoc(r.location)} ⇒ {r.child}
                  </span>
                  <button type="button" title={tP('config.constraintRemove')} onClick={() => onChange(constraints.filter((_, j) => j !== i))}
                    style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer', fontSize: '0.95rem', lineHeight: 1, padding: 0 }}>×</button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
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

import { PeggingTree, pathKeyFromPath, type PeggingGraph } from '@/app/components/PeggingTree';
import BomGraphTab from '@/app/components/BomGraphTab';
import { ResourceUtilizationView } from '@/app/components/ResourceUtilizationView';
import { PlanningPeggingTreeView } from '@/app/components/PlanningPeggingTreeView';
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
  _peg_depth?: number;     // BFS depth from woPegHighlightRow (0=self, 1=direct component, 2=deeper); set when Pegged-only is active
  _demand_label?: string;
  _demand_ids?: string[];
  _requested_qty?: number;
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
    // Skip failed=true subtrees and ~0-qty make subtrees: rolled-back / blocked branches whose
    // purchases never happen. The backend WO flatten skips them too, so this keeps the supplies
    // expand and the pegged-requirement totals consistent with the (real) committed quantities.
    if ((node as { failed?: boolean }).failed) return;
    if (node.type === 'work_order' && (node.method ?? '').toLowerCase() === 'make' && (Number(node.quantity) || 0) < 1e-6) return;
    if (node.type === 'work_order') {
      const key = `${demandId ?? ''}|${node.product_id ?? ''}|${node.location_id ?? ''}|${node.method ?? ''}`;
      // Accumulate this WO's own committed quantity for the key. node.quantity is the WO's
      // specific allocation in this pegging context — it's already net of any OR-split inventory
      // contribution. Using parentDemand.quantity here would overstate Requested when the demand
      // is partially fulfilled by inventory (OR split): the demand qty includes the inventory
      // share, but Requested should only reflect what this WO actually provides.
      // For non-OR-split cases node.quantity == parentDemand.quantity, so no regression.
      if (parentDemand?.type === 'demand' && node.quantity != null) {
        peggedQtyMap.set(key, (peggedQtyMap.get(key) ?? 0) + node.quantity);
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

function normalizePlanningConfig(cfg: PlanningConfig): PlanningConfig {
  const cs = cfg.consolidation;
  if (!cs) return cfg;
  const globalFb = cs.wo_batch_scale ?? 'weekly';
  return {
    ...cfg,
    consolidation: {
      ...cs,
      make_batch_scale:     cs.make_batch_scale     ?? globalFb,
      move_batch_scale:     cs.move_batch_scale     ?? globalFb,
      purchase_batch_scale: cs.purchase_batch_scale ?? globalFb,
    },
  };
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
  const [demandLateOnly, setDemandLateOnly] = useState(false);
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
    work_orders_native?: WorkOrder[];
    planning_pegging: PlanningPeggingEntry[];
    supply_allocations?: PlanSupplyAllocation[];
    supply_level_allocations?: SupplyLevelAllocation[];
    supply_summary?: { initial_total: number; consumed_total: number; consumption_rate: number | null };
    plan_kpis?: PlanKpis;
    /** Count of WO groups pushed by ResourceScheduler.arbitrate.
     *  0 when global scheduling is off or nothing was contended. */
    resource_contention_pushed_wos?: number;
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
  // Per-demand pegging cache — populated lazily when user opens a demand's pegging panel.
  // Keys are demand_id strings; values are the fetched PlanningPeggingEntry or 'loading'/'error'.
  const [demandPeggingCache, setDemandPeggingCache] = useState<Record<string, PlanningPeggingEntry | 'loading' | 'error'>>({});
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
  const [previousWoExplainRow, setPreviousWoExplainRow] = useState<WorkOrder | null>(null);
  // When navigating from the move manifest (work_order context) into a demand pegging tree,
  // stores the WO row so the "go back" button can restore the manifest view.
  const [previousManifestWoRow, setPreviousManifestWoRow] = useState<WorkOrder | null>(null);
  const [planWorkOrderPeggingLoading, setPlanWorkOrderPeggingLoading] = useState<string | null>(null);
  const [planWorkOrderPeggingError, setPlanWorkOrderPeggingError] = useState<string | null>(null);
  // Consolidated WO accordion: which demand sections are open + per-section tree expansion
  const [woConsolidatedOpenSections, setWoConsolidatedOpenSections] = useState<Set<string>>(new Set());
  const [woConsolidatedExpanded, setWoConsolidatedExpanded] = useState<Record<string, Set<string>>>({});
  const woConsolidatedFetchingRef = useRef<Set<string>>(new Set());
  const planPeggingResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [planPeggingResizing, setPlanPeggingResizing] = useState(false);
  const [planResultTab, setPlanResultTab] = useState<'demands' | 'work_orders' | 'supplies' | 'resourceUtilization'>('demands');
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
  // Which WO list the table shows: the consolidated procurement view, or the native per-demand
  // view (1:1 with the pegging). Kept as separate tables so aggregates never double-count.
  const [woTableTab, setWoTableTab] = useState<'consolidated' | 'native'>('consolidated');
  const activeWorkOrders = useMemo<WorkOrder[]>(() =>
    woTableTab === 'native'
      ? (planResult?.work_orders_native ?? planResult?.work_orders ?? [])
      : (planResult?.work_orders ?? []),
    [planResult, woTableTab]);

  // Effective WO badge counts: phantom-filtered + lot-grouped, so badge = shown + VirtualProduct_*-hidden.
  const woTabEffectiveCounts = useMemo(() => {
    const effectiveCount = (wos: WorkOrder[]): number => {
      // Count unique grouped rows using the same 7-field key the table uses, so badge = table
      // row count when no checkbox filters are active. Phantom filtering was removed from the
      // table (planning_pegging is lazy-loaded so backedSigs was always empty, silently
      // dropping purchase and move orders from the badge while the table showed them all).
      const woKey = (r: WorkOrder) => [
        String(r.demand_id ?? ''), String(r.product_id ?? ''), String(r.location_id ?? ''),
        String(r.method ?? ''), String(r.location_source ?? ''), String(r.prod_area ?? ''),
        String(r.wo_group_id ?? ''),
      ].join('|');
      return new Set(wos.map(woKey)).size;
    };

    return {
      consolidated: effectiveCount(planResult?.work_orders ?? []),
      native: effectiveCount(planResult?.work_orders_native ?? planResult?.work_orders ?? []),
    };
  }, [planResult]);
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
  const [planDemandLateOnly, setPlanDemandLateOnly] = useState(false);
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
  const [planWoMakeOnly, setPlanWoMakeOnly] = useState(false);
  const [planWoMoveOnly, setPlanWoMoveOnly] = useState(false);
  const [planWoHasOverride, setPlanWoHasOverride] = useState(false);
  const [planWoFilterDemandId, setPlanWoFilterDemandId] = useState('');
  const [planWoPivot, setPlanWoPivot] = useState<'none' | 'prod_area' | 'location' | 'nested' | 'demand'>('none');
  const [planWoLayoutMode, setPlanWoLayoutMode] = useState<'data' | 'split' | 'timeline'>('split');
  const [woPegHighlightRow, setWoPegHighlightRow] = useState<WoEnrichedRow | null>(null);
  const [planWoPivotExpanded, setPlanWoPivotExpanded] = useState<Set<string>>(new Set());
  const [planWoPivotSubExpanded, setPlanWoPivotSubExpanded] = useState<Set<string>>(new Set());
  const [woExpandedKeys, setWoExpandedKeys] = useState<Set<string>>(new Set());
  const [woExplainOpen, setWoExplainOpen] = useState(false);
  const [woExplainRow, setWoExplainRow] = useState<WorkOrder | null>(null);
  const [woExplainKey, setWoExplainKey] = useState<string | null>(null);
  const [manifestSortCol, setManifestSortCol] = useState<'demand' | 'comp' | 'qty'>('demand');
  const [manifestSortDir, setManifestSortDir] = useState<'asc' | 'desc'>('asc');
  const [woScheduleModalRow, setWoScheduleModalRow] = useState<WorkOrder | null>(null);
  const [supExplainOpen, setSupExplainOpen] = useState(false);
  const [supExplainRow, setSupExplainRow] = useState<PlanSupplyViewRow | null>(null);
  const [supExplainKey, setSupExplainKey] = useState<string | null>(null);
  const [woPeggingRowKey, setWoPeggingRowKey] = useState<string | null>(null);
  // When a node is selected inside the open pegging tree, this stores the
  // pathKey (for tree-row highlight) and the parsed product|location pair
  // (for matching back to the WO table row(s) so the user sees which WO row
  // corresponds to the tree element they picked). Cleared on pegging close.
  const [peggingSelectedPathKey, setPeggingSelectedPathKey] = useState<string | null>(null);
  const [peggingSelectedProductLoc, setPeggingSelectedProductLoc] = useState<{ product: string; location: string } | null>(null);
  const [bomRealPairs, setBomRealPairs] = useState<[string, string][] | null>(null);
  const [realMoveTriples, setRealMoveTriples] = useState<[string, string, string][] | null>(null);
  // Buyable raw materials for the selective-purchase whitelist dropdown + copilot /raw picker.
  const [purchasableOptions, setPurchasableOptions] = useState<PurchasableRawMaterial[]>([]);
  const [constraintOptions, setConstraintOptions] = useState<ConstraintOptions>({ customers: [], parents: [] });
  const [planningConfig, setPlanningConfig] = useState<PlanningConfig>({ consolidation: { enabled: true, period_days: 7, make_batch_scale: 'weekly', move_batch_scale: 'weekly', purchase_batch_scale: 'weekly' }, purchase_allowed: false, purchasable_materials: [], constraints: [], analyze_criticality: false, check_soundness: true, enable_global_scheduling: true });
  const [planJobId, setPlanJobId] = useState<string | null>(null);
  const [planProgress, setPlanProgress] = useState<{ current: number; total: number; iteration?: number; iterations_max?: number } | null>(null);
  const planPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [peggingSaveStatus, setPeggingSaveStatus] = useState<PeggingSaveStatus | null>(null);
  const peggingSavePollRef = useRef<ReturnType<typeof setInterval> | null>(null);
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
    const diffs: string[] = [];
    if (ms.mode !== 'preference') diffs.push(`mode: preference → ${ms.mode}`);
    if (Number(ms.max_methods) !== 1) diffs.push(`max_methods: 1 → ${ms.max_methods}`);
    if (Number(ms.depth) !== 1) diffs.push(`depth: 1 → ${ms.depth}`);
    if (ms.max_bom_depth != null && Number(ms.max_bom_depth) !== 3) diffs.push(`max_bom_depth: 3 → ${ms.max_bom_depth}`);
    const defaultScale = 'weekly';
    const globalFb = (cs.wo_batch_scale as string) ?? defaultScale;
    (['make', 'move', 'purchase'] as const).forEach((k) => {
      const cur = ((cs as Record<string, unknown>)[`${k}_batch_scale`] as string) ?? globalFb;
      if (cur !== defaultScale) diffs.push(`${k}-batch: weekly → ${cur}`);
    });
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
    parts.push(`bom=${ms.max_bom_depth ?? 3}`);
    if (sw && (sw.commit_time != null || sw.inventory_consumed != null || sw.purchase != null)) {
      parts.push(`w=(${Number(sw.commit_time ?? 0)}, ${Number(sw.inventory_consumed ?? 0)}, ${Number(sw.purchase ?? 0)})`);
    }
    parts.push(`wo_batch=${cs.enabled === false ? 'off' : 'on'}`);
    const globalFb = (cs.wo_batch_scale as string) ?? 'weekly';
    const mScale  = ((cs as Record<string, unknown>).make_batch_scale as string) ?? globalFb;
    const mvScale = ((cs as Record<string, unknown>).move_batch_scale as string) ?? globalFb;
    const pScale  = ((cs as Record<string, unknown>).purchase_batch_scale as string) ?? globalFb;
    parts.push(`batch=${mScale}/${mvScale}/${pScale}`);
    parts.push(`purch=${config.purchase_allowed === true ? 'on' : 'off'}`);
    return parts.join(' · ');
  };
  /** Canonical baseline config (mirrors the Kotlin cfg() defaults). */
  const makeBaselineConfig = (): Record<string, unknown> => ({
    method_selection: {
      mode: 'preference',
      depth: 1,
      multiple: false,
      max_methods: 1,
      max_bom_depth: 3,
    },
    consolidation: {
      enabled: true,
      period_days: 7,
      make_batch_scale: 'weekly',
      move_batch_scale: 'weekly',
      purchase_batch_scale: 'weekly',
    },
    variant_selection: { multiple: true },
    purchase_allowed: false,
    analyze_criticality: false,
    check_soundness: true,
    enable_global_scheduling: true,
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
      case 'consolidation_enabled': cs.enabled = value; break;
      case 'period_days': {
        cs.period_days = value;
        const snapped = value === 0 ? 'all' : value === 7 ? 'weekly' : value === 14 ? 'biweekly' : 'monthly';
        (cs as Record<string, unknown>).make_batch_scale = snapped;
        (cs as Record<string, unknown>).move_batch_scale = snapped;
        (cs as Record<string, unknown>).purchase_batch_scale = snapped;
        break;
      }
      case 'purchase_allowed':    cfg.purchase_allowed = value; break;
      case 'mode':
        ms.mode = value;
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
  const [peggedSort, setPeggedSort] = useState<{ key: 'demand' | 'customer' | 'requested' | 'allocated' | 'consumed' | 'share'; dir: 'asc' | 'desc' } | null>(null);
  const [peggedDemandFilter, setPeggedDemandFilter] = useState('');
  const [peggedCustomerFilter, setPeggedCustomerFilter] = useState('');
  const [planRunHistoryPanelWidth, setPlanRunHistoryPanelWidth] = useState(520);
  const planRunHistoryResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [planRunHistoryResizing, setPlanRunHistoryResizing] = useState(false);
  // KB slide-in panel resize state (mirrors planRunHistory).
  const [bootstrapPanelWidth, setBootstrapPanelWidth] = useState(720);
  const bootstrapResizeRef = useRef<{ startX: number; startW: number } | null>(null);
  const [bootstrapResizing, setBootstrapResizing] = useState(false);


  useEffect(() => {
    copilotMessagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [copilotMessages]);

  // Per-(case, run) chat history in localStorage so switching between runs
  // doesn't pollute the agent's context with the previous run's trace, and
  // navigating back to a run resumes its prior thread. Capped at 100
  // messages per thread. The pending_job_id flow is intentionally NOT
  // persisted — if the user navigates away while a plan is in flight, the
  // in-flight tracking dies with the page; the user can still inspect the
  // saved run in the run-history list when they return.
  //
  // One effect handles three branches based on whether the storage key
  // (chat-history-{caseId}-run-{runId} or -norun) changed since the last
  // run. `justLoadedRef` suppresses the redundant persist that would
  // otherwise fire right after a load (queued setCopilotMessages from the
  // load triggers this effect again with the loaded content).
  const COPILOT_HISTORY_LIMIT = 100;
  const copilotHistoryKey = (caseId: number, runId: number | null) =>
    runId != null ? `chat-history-${caseId}-run-${runId}` : `chat-history-${caseId}-norun`;
  const previousKeyRef = useRef<string | null>(null);
  const justLoadedRef = useRef(false);
  useEffect(() => {
    if (!Number.isFinite(id)) return;
    const key = copilotHistoryKey(id, currentPlanRunId);
    if (previousKeyRef.current === key) {
      if (justLoadedRef.current) {
        justLoadedRef.current = false;
        return;
      }
      try {
        const trimmed = copilotMessages.length > COPILOT_HISTORY_LIMIT
          ? copilotMessages.slice(-COPILOT_HISTORY_LIMIT)
          : copilotMessages;
        window.localStorage.setItem(key, JSON.stringify(trimmed));
      } catch {
        /* localStorage might be full or disabled — silently skip */
      }
    } else {
      // Key changed — save outgoing thread under previous key first so it
      // can be restored if the user navigates back, then load incoming.
      if (previousKeyRef.current) {
        try {
          const trimmed = copilotMessages.length > COPILOT_HISTORY_LIMIT
            ? copilotMessages.slice(-COPILOT_HISTORY_LIMIT)
            : copilotMessages;
          window.localStorage.setItem(previousKeyRef.current, JSON.stringify(trimmed));
        } catch {
          /* localStorage might be full or disabled — silently skip */
        }
      }
      try {
        const raw = window.localStorage.getItem(key);
        setCopilotMessages(raw ? (JSON.parse(raw) as PlanningCopilotMessage[]) : []);
      } catch {
        setCopilotMessages([]);
      }
      previousKeyRef.current = key;
      justLoadedRef.current = true;
    }
  }, [copilotMessages, id, currentPlanRunId]);

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
                  setPlanningConfig(normalizePlanningConfig({
                    ...cfg,
                    method_selection: {
                      ...cfg.method_selection,
                      depth: chosen ?? cfg.method_selection?.depth ?? 1,
                    },
                  }));
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

  // Poll background pegging save status. Starts when plan job finishes (planJobId cleared)
  // or when the page loads with a fresh run. Stops when the backend returns 204 (save done).
  useEffect(() => {
    if (!id) return;
    const poll = async () => {
      try {
        const status = await getPeggingSaveStatus(id);
        setPeggingSaveStatus(status);
        if (!status && peggingSavePollRef.current) {
          clearInterval(peggingSavePollRef.current);
          peggingSavePollRef.current = null;
        }
      } catch { /* transient — keep polling */ }
    };
    poll();
    if (peggingSavePollRef.current) clearInterval(peggingSavePollRef.current);
    peggingSavePollRef.current = setInterval(poll, 3000);
    return () => {
      if (peggingSavePollRef.current) { clearInterval(peggingSavePollRef.current); peggingSavePollRef.current = null; }
    };
  // Re-trigger when a plan finishes (planJobId goes null) or on mount.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, planJobId]);

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

  // Load the buyable raw-material catalog for the selective-purchase whitelist
  // dropdown + copilot /raw picker. Independent of plan results so the options
  // are ready before the first run.
  useEffect(() => {
    if (!id) {
      setPurchasableOptions([]);
      return;
    }
    let cancelled = false;
    getPurchasableRawMaterials(id)
      .then((res) => { if (!cancelled) setPurchasableOptions(res.materials ?? []); })
      .catch(() => { if (!cancelled) setPurchasableOptions([]); });
    getConstraintOptions(id)
      .then((res) => { if (!cancelled) setConstraintOptions({ customers: res.customers ?? [], parents: res.parents ?? [] }); })
      .catch(() => { if (!cancelled) setConstraintOptions({ customers: [], parents: [] }); });
    return () => { cancelled = true; };
  }, [id]);

  // Reset active-demand selection and consolidated accordion when the user opens pegging for a different WO
  useEffect(() => {
    setWoPeggingActiveDemandId(null);
    setWoConsolidatedOpenSections(new Set());
    setWoConsolidatedExpanded({});
  }, [planPeggingContext]);

  // When the pegging panel opens for a demand, auto-expand the critical-path
  // chain so the gold-tagged transit nodes are visible without the user
  // hunting through collapsed AND-children. Mirrors the Kotlin
  // `traceCriticalPath` algorithm.
  useEffect(() => {
    if (!planPeggingContext || planPeggingContext.type === 'supply' || !planResult) return;
    const demandIdNorm = String(planPeggingContext.row.demand_id ?? '').trim();
    if (!demandIdNorm) return;
    const matchingEntries = planResult.planning_pegging?.filter(
      (e) => String(e.demand_id ?? '').trim() === demandIdNorm,
    ) ?? [];
    const entry = matchingEntries.length > 0 ? matchingEntries[matchingEntries.length - 1] : undefined;
    const tree = entry?.tree;
    if (!tree) return;

    const hasFlaggedDescendant = (n: PlanningPeggingNode): boolean => {
      if (n.is_bottleneck || n.is_root_bottleneck) return true;
      return (n.children ?? []).some(hasFlaggedDescendant);
    };
    const ratio = (c: PlanningPeggingNode): number => {
      const q = Number(c.quantity ?? 0);
      const cq = Number((c as { committed_qty?: number | null }).committed_qty ?? q);
      return q < 1e-9 ? 0 : cq / q;
    };
    const contributed = (c: PlanningPeggingNode): boolean => {
      const q = Number(c.quantity ?? 0);
      const cq = Number((c as { committed_qty?: number | null }).committed_qty ?? q);
      return q > 1e-9 || cq > 1e-9;
    };
    const relationOf = (n: PlanningPeggingNode): 'and' | 'or' => {
      const explicit = (n as { children_relation?: string | null }).children_relation;
      if (explicit === 'and' || explicit === 'or') return explicit;
      return n.type === 'work_order' ? 'and' : 'or';
    };
    const paths = new Set<string>();
    const walk = (n: PlanningPeggingNode | null, p: string): void => {
      if (!n) return;
      paths.add(p);
      const kids = n.children ?? [];
      if (kids.length === 0) return;
      const contributingKids = kids
        .map((c, i) => ({ c, i }))
        .filter(({ c }) => contributed(c));
      if (contributingKids.length === 0) return;
      if (relationOf(n) === 'or') {
        contributingKids.forEach(({ c, i }) => walk(c, `${p}-${i}`));
        return;
      }
      const flagged = contributingKids.filter(({ c }) => c.is_bottleneck || c.is_root_bottleneck);
      let pick: { c: PlanningPeggingNode; i: number } | null = null;
      if (flagged.length > 0) {
        flagged.sort((a, b) => {
          const ra = ratio(a.c);
          const rb = ratio(b.c);
          if (Math.abs(ra - rb) > 1e-9) return ra - rb;
          return a.i - b.i;
        });
        pick = flagged[0];
      } else {
        const transit = contributingKids.filter(({ c }) => hasFlaggedDescendant(c));
        if (transit.length === 0) return;
        transit.sort((a, b) => {
          const ra = ratio(a.c);
          const rb = ratio(b.c);
          if (Math.abs(ra - rb) > 1e-9) return ra - rb;
          return a.i - b.i;
        });
        pick = transit[0];
      }
      walk(pick.c, `${p}-${pick.i}`);
    };
    walk(tree, '0');
    if (paths.size <= 1) return;
    setPlanPeggingExpanded((prev) => {
      const next = new Set(prev);
      paths.forEach((p) => next.add(p));
      return next;
    });
  }, [planPeggingContext, planResult]);

  // Lazily fetch per-demand pegging tree when user opens the pegging panel and the
  // tree is not in planResult.planning_pegging (bulk load is disabled — trees are too large).
  useEffect(() => {
    if (planPeggingContext?.type !== 'demand') return;
    const demandId = String(planPeggingContext.row.demand_id ?? '').trim();
    if (!demandId) return;
    const inResult = planResult?.planning_pegging?.some((e) => String(e.demand_id ?? '').trim() === demandId);
    if (inResult) return;
    if (demandPeggingCache[demandId]) return;
    const planRunId = currentPlanRunId ?? freshPlanRunId;
    if (!planRunId || !id) return;
    setDemandPeggingCache((prev) => ({ ...prev, [demandId]: 'loading' }));
    getPlanRunPegging(Number(id), planRunId, demandId)
      .then(({ planning_pegging }) => {
        const entry = planning_pegging[planning_pegging.length - 1] as PlanningPeggingEntry | undefined;
        if (entry) {
          setDemandPeggingCache((prev) => ({ ...prev, [demandId]: entry }));
          setPlanResult((prev) => prev ? { ...prev, planning_pegging: [...(prev.planning_pegging ?? []), entry] } : prev);
        } else {
          setDemandPeggingCache((prev) => ({ ...prev, [demandId]: 'error' }));
        }
      })
      .catch(() => setDemandPeggingCache((prev) => ({ ...prev, [demandId]: 'error' })));
  }, [planPeggingContext, planResult, demandPeggingCache, currentPlanRunId, freshPlanRunId, id]);

  // Lazily fetch pegging tree when user highlights a WO row (for predecessor/
  // successor display), mirroring the demand-panel lazy load above.
  useEffect(() => {
    if (!woPegHighlightRow) return;
    const demandId = String(woPegHighlightRow.demand_id ?? '').trim();
    if (!demandId) return;
    const inResult = planResult?.planning_pegging?.some((e) => String(e.demand_id ?? '').trim() === demandId);
    if (inResult) return;
    if (demandPeggingCache[demandId]) return;
    const planRunId = currentPlanRunId ?? freshPlanRunId;
    if (!planRunId || !id) return;
    setDemandPeggingCache((prev) => ({ ...prev, [demandId]: 'loading' }));
    getPlanRunPegging(Number(id), planRunId, demandId)
      .then(({ planning_pegging }) => {
        const entry = planning_pegging[planning_pegging.length - 1] as PlanningPeggingEntry | undefined;
        if (entry) {
          setDemandPeggingCache((prev) => ({ ...prev, [demandId]: entry }));
          setPlanResult((prev) => prev ? { ...prev, planning_pegging: [...(prev.planning_pegging ?? []), entry] } : prev);
        } else {
          setDemandPeggingCache((prev) => ({ ...prev, [demandId]: 'error' }));
        }
      })
      .catch(() => setDemandPeggingCache((prev) => ({ ...prev, [demandId]: 'error' })));
  }, [woPegHighlightRow, planResult, demandPeggingCache, currentPlanRunId, freshPlanRunId, id]);

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
          // All null-demand consolidated WOs (single or multi-demand) use per-demand accordion; no single woPeggingKey.
          if (row.demand_id == null && (row.consolidated_demand_ids?.length ?? 0) >= 1) return null;
          const isConsolidated = row.demand_id == null;
          const demandPart = isConsolidated ? '' : String(woPeggingActiveDemandId ?? row.demand_id ?? '').trim();
          // start_time is part of the cache key — different lots (same demand/
          // product/location/method, different start_time) get separate trees.
          return `${demandPart}|${String(row.product_id ?? '').trim()}|${String(row.location_id ?? '').trim()}|${String(row.method ?? '').trim()}|${String(row.start_time ?? '').trim()}`;
        })()
      : null;
  useEffect(() => {
    if (!woPeggingKey || !id || planPeggingContext?.type !== 'work_order') return;
    const row = planPeggingContext.row as WorkOrder;
    if (planWorkOrderPeggingCache[woPeggingKey]) return;
    if (planWorkOrderPeggingLoading === woPeggingKey) return;
    // Consolidated WO: always fetch with demand_id='' so backend searches consolidated trees.
    const demand_id = row.demand_id == null ? '' : String(woPeggingActiveDemandId ?? row.demand_id ?? '').trim();
    const location_id = String(row.location_id ?? '').trim();
    const method = String(row.method ?? '').trim();
    // Consolidated multi-product move WOs have product_id=null; use the first
    // component's product_id so the backend can locate the per-demand move node.
    const moveComps = (method === 'move' && !row.product_id) ? (row.move_components ?? []) : [];
    if (moveComps.length > 1) {
      // Multiple products in one shipment: no single pegging tree can represent all
      // of them. Surface a helpful message rather than an opaque error.
      setPlanWorkOrderPeggingError(
        `This shipment carries ${moveComps.length} products (${moveComps.map((c) => c.product_id).join(', ')}). Open each demand's pegging individually to trace a specific product.`
      );
      return;
    }
    const product_id = String(row.product_id ?? moveComps[0]?.product_id ?? '').trim();
    if (!product_id || !location_id || !method) {
      const msg = `Missing work-order params (product_id=${product_id ? 'set' : 'empty'}, location_id=${location_id ? 'set' : 'empty'}, method=${method ? 'set' : 'empty'}).`;
      if (typeof console !== 'undefined' && console.warn) console.warn('[WO pegging]', msg);
      setPlanWorkOrderPeggingError(msg);
      return;
    }
    setPlanWorkOrderPeggingError(null);
    setPlanWorkOrderPeggingLoading(woPeggingKey);
    if (typeof console !== 'undefined' && console.log) console.log('[WO pegging] Fetching', { caseId: id, demand_id, product_id, location_id, method });
    getWorkOrderPegging(Number(id), {
      demand_id,
      product_id,
      location_id,
      method,
      // Batched WO (demand_id null): forward its constituent demands and drop start_time (the
      // merged start won't match per-demand nodes) so the endpoint aggregates the original
      // per-demand pegging. Normal WO: pass start_time to pick the exact slot/lot.
      ...(row.demand_id == null && (row.consolidated_demand_ids?.length ?? 0) > 0
        ? { demand_ids: row.consolidated_demand_ids, win_start: row.wo_window_start, win_end: row.wo_window_end }
        : { start_time: row.start_time ?? undefined }),
      ...(currentPlanRunId != null ? { run_id: currentPlanRunId } : {}),
    })
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

  // Multi-demand consolidated WO: fetch one pegging tree per contributing logical WO.
  // Each fetch uses the native WO's start_time (found via consolidated_group_id linkage)
  // so the backend locates the exact per-demand WO node rather than the merged one.
  useEffect(() => {
    if (!planPeggingOpen || planPeggingContext?.type !== 'work_order' || !id || !planResult) return;
    const row = planPeggingContext.row as WorkOrder;
    if (row.demand_id != null) return;
    // Merge demand IDs from split_details (has per-demand qty) and consolidated_demand_ids
    // (populated even for virtual-demand WOs where split_details is empty).
    const splitDetails = (row.wo_consolidation_split_details ?? [])
      .filter((d) => !!d.demand_id && d.demand_id !== '') as Array<{ demand_id: string; allocated_qty: number }>;
    const splitIds = new Set(splitDetails.map((d) => d.demand_id));
    const allDemandIds = [
      ...splitDetails.map((d) => d.demand_id),
      ...(row.consolidated_demand_ids ?? []).filter((d) => !!d && d !== '' && !splitIds.has(d)),
    ];
    if (allDemandIds.length === 0) return;
    const product_id = String(row.product_id ?? '').trim();
    const location_id = String(row.location_id ?? '').trim();
    const method = String(row.method ?? '').trim();
    if (!product_id || !location_id || !method) return;
    // Open all sections on first open
    setWoConsolidatedOpenSections((prev) => {
      if (prev.size > 0) return prev;
      return new Set(allDemandIds);
    });
    for (const did of allDemandIds) {
      // Locate the native WO for this demand in this consolidated batch
      const nativeWo = (planResult.work_orders_native ?? [])
        .filter((w) => w.consolidated_group_id === row.wo_group_id && w.demand_id === did)
        .sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''))[0];
      const start_time = nativeWo?.start_time ?? undefined;
      const cacheKey = `${did}|${product_id}|${location_id}|${method}|${start_time ?? ''}`;
      if (planWorkOrderPeggingCache[cacheKey]) {
        setWoConsolidatedExpanded((prev) => prev[cacheKey] ? prev : { ...prev, [cacheKey]: new Set(['0']) });
        continue;
      }
      if (woConsolidatedFetchingRef.current.has(cacheKey)) continue;
      woConsolidatedFetchingRef.current.add(cacheKey);
      getWorkOrderPegging(Number(id), {
        demand_id: did, product_id, location_id, method,
        start_time: start_time ?? undefined,
        ...(currentPlanRunId != null ? { run_id: currentPlanRunId } : {}),
      })
        .then((res) => {
          setPlanWorkOrderPeggingCache((prev) => ({ ...prev, [cacheKey]: res.tree }));
          setWoConsolidatedExpanded((prev) => ({ ...prev, [cacheKey]: new Set(['0']) }));
        })
        .catch((err) => {
          if (typeof console !== 'undefined' && console.error)
            console.error('[WO consolidated pegging]', did, parseApiError(err));
        })
        .finally(() => { woConsolidatedFetchingRef.current.delete(cacheKey); });
    }
  }, [planPeggingContext, planPeggingOpen, planResult, id, currentPlanRunId]);

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
  // Clear any tree-row selection (and its WO-row cross-highlight) whenever
  // the pegging panel closes — every close path funnels through
  // setPeggingOpen(false), so this useEffect avoids patching each one.
  useEffect(() => {
    if (!peggingOpen) {
      setPeggingSelectedPathKey(null);
      setPeggingSelectedProductLoc(null);
    }
  }, [peggingOpen]);
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
    const svPlanRunId = currentPlanRunId ?? freshPlanRunId;
    getSupplyView(id, runId, svPlanRunId != null ? { plan_run_id: svPlanRunId } : undefined)
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

  const loadLatestPlanRun = async (caseDetail: CaseType | null, signal: { cancelled: boolean }) => {
    try {
      const runs = await listPlanRuns(id);
      if (signal.cancelled) return;
      setPlanRunHistory(runs);
      // Prefer the case's active plan run (designated → falls back to latest
      // success via the backend's resolveActiveRunId). The active id is
      // already in `caseDetail` from the shared init fetch — don't refetch
      // (parallel getCase calls can race and produce different UI defaults).
      const activeId: number | null = caseDetail?.active_plan_run_id ?? null;
      const target = activeId != null
        ? runs.find((r) => r.id === activeId && r.status === 'success')
        : null;
      const fallback = runs.find((r) => r.status === 'success');
      const chosen = target ?? fallback;
      if (!chosen) return;
      const full = await getPlanRun(id, chosen.id);
      if (signal.cancelled) return;
      if (full.result) {
        setPlanResult(full.result as typeof planResult);
        setCurrentPlanRunId(chosen.id);
        setPlanWorkOrderPeggingCache({});
        if (full.config) {
          const cfg = full.config as PlanningConfig;
          const chosenDepth = full.chosen_depth ?? null;
          setPlanningConfig(normalizePlanningConfig({
            ...cfg,
            method_selection: {
              ...cfg.method_selection,
              depth: chosenDepth ?? cfg.method_selection?.depth ?? 1,
            },
          }));
        }
        restoreCriticality(chosen.id);
      }
    } catch {
      // non-fatal — plan results simply won't be pre-loaded
    }
  };

  useEffect(() => {
    // Cancellation pattern: in React StrictMode dev (and HMR remounts), the
    // effect fires twice — setup, cleanup, setup again. The first setup's
    // async work is cancelled via signal.cancelled when the cleanup runs;
    // the second setup runs cleanly. Only the surviving invocation writes
    // state, so we don't race two parallel getCase / loadLatestPlanRun
    // chains that previously caused active-then-latest flicker.
    const signal = { cancelled: false };
    setLoading(true);
    setError(null);
    const timeoutId = setTimeout(() => setLoading(false), 20000);
    (async () => {
      // Single shared getCase fetch — used by both the case-detail panel and
      // the active-run resolver below. Avoids two parallel calls racing.
      const caseDetail = await getCase(id).catch((e) => {
        if (!signal.cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load case');
        }
        return null;
      });
      if (signal.cancelled) return;
      if (caseDetail) setC(caseDetail);
      await Promise.all([
        loadRuns(),
        loadOverrides(),
        loadLatestPlanRun(caseDetail, signal),
      ]);
    })().finally(() => {
      if (signal.cancelled) return;
      clearTimeout(timeoutId);
      setLoading(false);
    });
    return () => { signal.cancelled = true; };
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
    const svPlanRunId = currentPlanRunId ?? freshPlanRunId;
    getSupplyView(id, selectedRunId, svPlanRunId != null ? { plan_run_id: svPlanRunId } : undefined)
      .then((s) => setSupplyView(s.supply_view))
      .catch(() => setSupplyView([]))
      .finally(() => setSupplyViewLoading(false));
  }, [currentPlanRunId, freshPlanRunId, selectedRunId, id]);

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
        const res = await fetch(`/allocator/api/supplies?caseId=${id}&q=${encodeURIComponent(val.trim())}`);
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
    function addEdge(childK: string, parentK: string) {
      if (!parentMap.has(childK)) parentMap.set(childK, new Set());
      parentMap.get(childK)!.add(parentK);
      if (!childMap.has(parentK)) childMap.set(parentK, new Set());
      childMap.get(parentK)!.add(childK);
    }
    // Each work_order node emits two parallel edge sets:
    //   gid-keyed  ("gid:<wo_group_id>"): used by native WOs so two groups with the same
    //              product/location/method (different time windows) stay in separate BFS trees.
    //   4-part key (demand|pid|lid|method): kept for consolidated WO fallback, which uses
    //              demand-based keys because consolidated gids don't appear in planning_pegging.
    function visit(
      node: PlanningPeggingNode,
      ancestorGid: string | null,
      ancestor4: string | null,
      demandId: string,
    ) {
      const nodeDemand = node.demand_id ?? demandId;
      let myGid: string | null = null;
      let my4: string | null = null;
      if (node.type === 'work_order') {
        myGid = node.wo_group_id ? `gid:${node.wo_group_id}` : null;
        my4 = `${nodeDemand}|${node.product_id ?? ''}|${node.location_id ?? ''}|${node.method ?? ''}`;
        if (myGid && ancestorGid) addEdge(myGid, ancestorGid);
        if (ancestor4) addEdge(my4, ancestor4);
      }
      const nextGid = myGid ?? ancestorGid;
      const next4 = my4 ?? ancestor4;
      for (const child of node.children ?? []) visit(child, nextGid, next4, nodeDemand);
    }
    for (const entry of planResult?.planning_pegging ?? []) {
      visit(entry.tree, null, null, entry.demand_id ?? '');
    }
    return { parentMap, childMap };
  }, [planResult]);

  // ── Shared row identity / pegging-classification helpers ────────────────────
  // These are the single source of truth for "what counts as one row in the
  // work-order view" and "how does a row relate to the highlighted WO".
  // The pegged-row filter (in the table render) and the up/down-arrow counts
  // (in the toolbar) both go through `peggedRowKindFor` so they cannot disagree.
  type WoRowLike = {
    product_id?: string;
    location_id?: string | null;
    method?: string | null;
    demand_id?: string | null;
    location_source?: string | null;
    prod_area?: string | null;
    wo_group_id?: string | null;
    consolidated?: boolean;
    _demand_ids?: string[];
    _is_inventory?: boolean;
    wo_consolidation_split_details?: Array<{ demand_id?: string | null }> | null;
  };

  // 7-part key used to group lots into one logical work-order row.  Both the
  // count walk and the table's grouping derive their row identity from this.
  // wo_group_id is the 7th component so two native WO groups for the same
  // product/location/method (different time windows) remain distinct rows.
  const woRowGroupKey = useCallback((r: WoRowLike): string => [
    String(r.demand_id ?? ''),
    String(r.product_id ?? ''),
    String(r.location_id ?? ''),
    String(r.method ?? ''),
    String(r.location_source ?? ''),
    String(r.prod_area ?? ''),
    String(r.wo_group_id ?? ''),
  ].join('|'), []);

  // Demand-ids associated with a row.  Enriched rows carry `_demand_ids`
  // pre-populated; raw work_orders fall back to demand_id or
  // wo_consolidation_split_details.
  const woRowDemandIds = useCallback((r: WoRowLike): string[] => {
    if (r._demand_ids && r._demand_ids.length) return r._demand_ids;
    if (r.demand_id) return [r.demand_id];
    return (r.wo_consolidation_split_details ?? [])
      .map((d) => d.demand_id)
      .filter((d): d is string => d != null && d !== '');
  }, []);

  // Pegging keys for a row used to look up ancestor/descendant sets in woPegRelations.
  // Native WOs (non-consolidated, have wo_group_id) use a gid-prefixed key that maps
  // 1:1 to the pegging tree node — this keeps two WO groups for the same
  // product/location/method (different time windows) separate in the BFS traversal.
  // Consolidated WOs fall back to per-demand 4-part keys (the pegging graph is built
  // from native planning_pegging trees and stores 4-part edges for this path).
  const woRowPegKeys = useCallback((r: WoRowLike): string[] => {
    if (!r.consolidated && r.wo_group_id) return [`gid:${r.wo_group_id}`];
    const ids = woRowDemandIds(r);
    return (ids.length ? ids : ['']).map((d) =>
      `${d}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`);
  }, [woRowDemandIds]);

  // Single classifier used by both the "Pegged only" filter and the arrow
  // counts: returns 'self' if the row is the highlighted WO itself,
  // 'ancestor' if downstream (consumer side), 'descendant' if upstream
  // (supplier side), or null if unrelated.
  type PeggedRowKind = 'self' | 'ancestor' | 'descendant' | null;
  const peggedRowKindFor = useCallback((
    r: WoRowLike,
    highlightRow: WoRowLike | null,
    sets: { ancestors: Set<string>; descendants: Set<string> },
  ): PeggedRowKind => {
    if (!highlightRow) return null;
    if (woRowGroupKey(r) === woRowGroupKey(highlightRow)) return 'self';
    const keys = woRowPegKeys(r);
    if (keys.some((k) => sets.ancestors.has(k))) return 'ancestor';
    if (keys.some((k) => sets.descendants.has(k))) return 'descendant';
    return null;
  }, [woRowGroupKey, woRowPegKeys]);

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

  // Row-level counts that mirror the "Pegged only" filter exactly: walks the
  // raw work_orders, applies the same hide-VirtualProduct filter the table
  // applies pre-grouping, dedupes by woRowGroupKey, and classifies each group
  // via the shared peggedRowKindFor.  Used to display the ↓/↑ counts next to
  // the highlighted WO.
  const woPegRowCounts = useMemo(() => {
    if (!woPegHighlightRow || !planResult) return { ancestors: 0, descendants: 0 };
    const seenGroup = new Set<string>();
    let ancestors = 0;
    let descendants = 0;
    for (const r of activeWorkOrders) {
      // Mirror the table's pre-grouping filter: hide product_id starting with VirtualProduct_.
      if (planWorkOrderHideDummyProdArea && (r.product_id ?? '').trim().startsWith('VirtualProduct_')) continue;
      const gk = woRowGroupKey(r as WoRowLike);
      if (seenGroup.has(gk)) continue;
      seenGroup.add(gk);
      const kind = peggedRowKindFor(r as WoRowLike, woPegHighlightRow, woPegHighlightSets);
      if (kind === 'ancestor') ancestors++;
      else if (kind === 'descendant') descendants++;
    }
    return { ancestors, descendants };
  }, [planResult, woPegHighlightRow, woPegHighlightSets, peggedRowKindFor, woRowGroupKey, planWorkOrderHideDummyProdArea]);

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
      // Same failed=true skip as walk() — see rationale there.
      if (node.type === 'work_order' && (node as { failed?: boolean }).failed === true) return;
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
      // Skip subtrees rooted at failed=true work_orders. Those carry the
      // AND-bottleneck blocked-branch diagnostic snapshot — first-pass child
      // peggings whose inventory takes were rolled back at the planner
      // level. Their supply-leaf qtys never actually drew from inventory,
      // so attributing them as `qty_consumed` over-counts (one supply lot
      // reports 100% util while another reports 0%, and per-demand totals
      // double-count first-pass exploration). Mirrors the backend
      // extractSupplyAllocations + soundness checker filters.
      if (node.type === 'work_order' && (node as { failed?: boolean }).failed === true) return;

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

  const demandCustomerMap = useMemo(() => {
    const m = new Map<string, string | null>();
    for (const d of planResult?.committed_demands ?? []) {
      if (d.demand_id) m.set(d.demand_id, d.customer ?? null);
    }
    return m;
  }, [planResult?.committed_demands]);

  // lotId → demandId → qty — used by the supply-explain allocation heatmap.
  // supply_id → demand_id → qty_allocated (demand's proportional entitlement from this lot).
  // Uses qty_allocated when present (new runs); falls back to qty_consumed for older runs.
  const lotDemandAllocMap = useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const a of planResult?.supply_allocations ?? []) {
      if (!a.demand_id) continue;
      let dm = m.get(a.supply_id);
      if (!dm) { dm = new Map(); m.set(a.supply_id, dm); }
      const qty = a.qty_allocated != null ? a.qty_allocated : a.qty_consumed;
      dm.set(a.demand_id, (dm.get(a.demand_id) ?? 0) + qty);
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
          mode: fallbackMode ?? 'fair',
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

  // supplyView (from plan_supply_allocation via backend) keyed by supply_id — used as
  // fallback when supplyPeggingMap is empty (pegging trees too large to load in-memory).
  const supplyViewRowMap = useMemo(() => {
    const m = new Map<string, SupplyViewRow>();
    for (const r of supplyView) m.set(r.supply_id, r);
    return m;
  }, [supplyView]);

  /** Join caseSupplies rows with supplyPeggingMap to produce the enriched supply view. */
  const planSupplyViewRows = useMemo((): PlanSupplyViewRow[] => {
    return caseSupplies.map((s) => {
      const pegging = supplyPeggingMap.get(s.supplyId);
      const svRow = supplyViewRowMap.get(s.supplyId);
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
        peggedDemandCount: pegging?.demands.length ?? svRow?.pegged_demands ?? 0,
        totalPeggedQty: pegging?.totalPeggedQty ?? svRow?.total_pegged_qty ?? 0,
        peggedDemands: pegging?.demands.length
          ? pegging.demands
          : (() => {
              const dm = lotDemandAllocMap.get(s.supplyId);
              if (!dm) return [];
              return Array.from(dm.entries()).map(([demandId, qty]) => ({
                demandId,
                customer: demandCustomerMap.get(demandId) ?? null,
                qtyConsumed: qty,
              }));
            })(),
        splitInfos: supplySplitInfoMap.get(s.supplyId) ?? [],
        demandPath,
      };
    });
  }, [caseSupplies, supplyPeggingMap, supplyConsumedMap, lotDemandAllocMap, demandCustomerMap, supplySplitInfoMap, supplyDemandPathMap, supplyViewRowMap]);

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
    setPeggingSelectedPathKey(null);
    setPeggingSelectedProductLoc(null);
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
    setPeggingSelectedPathKey(null);
    setPeggingSelectedProductLoc(null);
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
          // Default the depth to the run's chosen_depth (legacy, set when the
          // retired optimal-depth search ran) when present; fall back to 1 so
          // the form starts from a sane baseline rather than carrying over
          // whatever depth was in the saved config snapshot.
          const cfg = full.config as PlanningConfig;
          const chosen = full.chosen_depth ?? null;
          setPlanningConfig(normalizePlanningConfig({
            ...cfg,
            method_selection: {
              ...cfg.method_selection,
              depth: chosen ?? 1,
            },
          }));
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
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem', marginBottom: '0.5rem', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={demandLateOnly}
                    onChange={(e) => setDemandLateOnly(e.target.checked)}
                  />
                  <span>{tA('demandView.lateOnly')}</span>
                </label>
                <SortFilterTable<FeasibleDemand & { suggested_revision?: string; lateness_days: number | null }>
                idKey="demand_id"
                rows={feasibleDemands
                  .map((f) => ({
                    ...f,
                    suggested_revision: f.suggested_revision ?? (f.status === 'fulfilled'
                      ? tA('demandView.fulfilled')
                      : f.allocated_qty > 0
                        ? `${tA('demandView.reduceTo')} ${f.allocated_qty}`
                        : tA('demandView.unfulfilled')),
                    lateness_days: computeLatenessDays(f.request_due_time, f.revised_time),
                  }))
                  .filter((r) => !demandLateOnly || (r.lateness_days != null && r.lateness_days > 0))}
                rowId={(r) => `demand-${r.demand_id}`}
                onRowClick={handleDemandPeggingClick}
                rowStyle={(r) => (r.lateness_days != null && r.lateness_days > 0)
                  ? { background: 'rgba(248, 113, 113, 0.08)' }
                  : undefined}
                filterKeys={['demand_id', 'customer', 'customer_id', 'product_id', 'status', 'suggested_revision', 'request_due_time', 'revised_time', 'fulfillment_rate']}
                defaultSortKey="fulfillment_rate"
                columns={[
                  { key: 'demand_id', label: tA('demandView.columns.demandId'), sortable: true },
                  { key: 'customer', label: tA('demandView.columns.customer'), sortable: true, render: (r) => r.customer ?? r.customer_id ?? '–' },
                  { key: 'request_due_time', label: tA('demandView.columns.time'), sortable: true, render: (r) => r.request_due_time ?? '–' },
                  { key: 'revised_time', label: tA('demandView.columns.revisedTime'), sortable: true, render: (r) => r.revised_time ?? '–' },
                  {
                    key: 'lateness_days',
                    label: tA('demandView.columns.lateness'),
                    sortable: true,
                    sortValue: (r, dir) => r.lateness_days ?? (dir === 'asc' ? Number.POSITIVE_INFINITY : Number.NEGATIVE_INFINITY),
                    render: (r) => {
                      if (r.lateness_days == null) return '–';
                      if (r.lateness_days > 0) return <span style={{ color: '#f87171', fontWeight: 600 }}>+{r.lateness_days}d</span>;
                      if (r.lateness_days < 0) return <span style={{ color: '#34d399' }}>{r.lateness_days}d</span>;
                      return '0d';
                    },
                  },
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
          {/* ── Group 1: Configurations (how methods are ranked + tried per demand) ── */}
          <fieldset style={{ border: '1px solid #3f3f46', borderRadius: 6, padding: '0.45rem 0.75rem 0.65rem', margin: '0 0 0.55rem' }}>
            <legend style={{ padding: '0 0.4rem', fontSize: '0.72rem', color: '#a1a1aa', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              {tP('config.groupMethodSelection')}
            </legend>
            {/* ── Method ── */}
            <div style={{ fontSize: '0.65rem', color: '#52525b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '0.3rem' }}>
              {tP('config.subheadMethod')}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
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
              <label
                style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.875rem' }}
                title={tP('config.maxBomDepthHint')}
              >
                <span style={{ color: '#a1a1aa' }}>{tP('config.maxBomDepth')}</span>
                <input
                  type="number"
                  min={1}
                  max={10}
                  value={planningConfig.method_selection?.max_bom_depth ?? 3}
                  onChange={(e) => {
                    const v = Math.max(1, Math.min(10, parseInt(e.target.value, 10) || 3));
                    setPlanningConfig((c) => ({ ...c, method_selection: { ...c.method_selection, max_bom_depth: v } }));
                  }}
                  style={{ width: 56, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
                />
              </label>
            </div>

            {/* ── Purchase ── */}
            <div style={{ marginTop: '0.65rem', borderTop: '1px solid #27272a', paddingTop: '0.4rem' }}>
              <div style={{ fontSize: '0.65rem', color: '#52525b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '0.3rem' }}>
                {tP('config.subheadPurchase')}
              </div>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={planningConfig.purchase_allowed !== false}
                  onChange={(e) => setPlanningConfig((c) => ({ ...c, purchase_allowed: e.target.checked }))}
                />
                <span style={{ fontSize: '0.875rem' }}>{tP('config.purchaseAllowed')}</span>
              </label>
              {planningConfig.purchase_allowed !== false && (
                <div style={{ marginTop: '0.35rem', marginLeft: '1.5rem' }}>
                  <div style={{ fontSize: '0.72rem', color: '#a1a1aa', marginBottom: 2 }}>
                    {tP('config.purchasableMaterials')}
                  </div>
                  {purchasableOptions.length === 0 ? (
                    <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{tP('config.purchasableNone')}</div>
                  ) : (
                    <RawMaterialPicker
                      options={purchasableOptions}
                      selected={planningConfig.purchasable_materials ?? []}
                      onChange={(next) => setPlanningConfig((c) => ({ ...c, purchasable_materials: next }))}
                      defaultCollapsed
                      tP={tP}
                    />
                  )}
                </div>
              )}
            </div>

            {/* ── Scheduling ── */}
            <div style={{ marginTop: '0.65rem', borderTop: '1px solid #27272a', paddingTop: '0.4rem' }}>
              <div style={{ fontSize: '0.65rem', color: '#52525b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '0.3rem' }}>
                {tP('config.subheadScheduling')}
              </div>
              <label
                style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}
                title={tP('config.enableGlobalSchedulingTooltip')}
              >
                <input
                  type="checkbox"
                  checked={planningConfig.enable_global_scheduling !== false}
                  onChange={(e) => setPlanningConfig((c) => ({ ...c, enable_global_scheduling: e.target.checked }))}
                />
                <span style={{ fontSize: '0.875rem' }}>{tP('config.enableGlobalScheduling')}</span>
              </label>
            </div>

            {/* ── Constraints ── */}
            <div style={{ marginTop: '0.65rem', borderTop: '1px solid #27272a', paddingTop: '0.4rem' }}>
              <div style={{ fontSize: '0.65rem', color: '#52525b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '0.3rem' }}>
                {tP('config.subheadConstraints')}
              </div>
              <ConstraintPicker
                options={constraintOptions}
                constraints={planningConfig.constraints ?? []}
                onChange={(next) => setPlanningConfig((c) => ({ ...c, constraints: next }))}
                defaultCollapsed
                tP={tP}
              />
            </div>
          </fieldset>

          {/* ── Group 2: Post-plan handling (run AFTER planning, do not affect planner) ── */}
          <fieldset style={{ border: '1px solid #3f3f46', borderRadius: 6, padding: '0.45rem 0.75rem 0.55rem', margin: '0 0 0.55rem' }}>
            <legend style={{ padding: '0 0.4rem', fontSize: '0.72rem', color: '#a1a1aa', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              {tP('config.groupPostPlan')}
            </legend>
            <div style={{ display: 'flex', alignItems: 'center', gap: '1.25rem', flexWrap: 'wrap' }}>
              {/* WO batch scales — grouped visually */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
                <span style={{ fontSize: '0.72rem', color: '#a1a1aa', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{tP('config.woBatchFrequency')}</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', padding: '4px 10px', border: '1px solid #3f3f46', borderRadius: 5 }}>
                {(['make', 'move', 'purchase'] as const).map((type) => {
                  const configKey = `${type}_batch_scale` as 'make_batch_scale' | 'move_batch_scale' | 'purchase_batch_scale';
                  const labelKey = `woBatch${type.charAt(0).toUpperCase() + type.slice(1)}` as 'woBatchMake' | 'woBatchMove' | 'woBatchPurchase';
                  const globalFb = planningConfig.consolidation?.wo_batch_scale ?? 'weekly';
                  const val = (planningConfig.consolidation?.[configKey] ?? globalFb) as string;
                  return (
                    <label key={type}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.875rem' }}
                      title={tP('config.woBatchScaleTooltip')}
                    >
                      <span style={{ color: '#a1a1aa' }}>{tP(`config.${labelKey}`)}</span>
                      <select
                        value={val}
                        onChange={(e) => {
                          const v = e.target.value as 'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';
                          const days = v === 'weekly' ? 7 : v === 'biweekly' ? 14 : v === 'monthly' ? 30 : 0;
                          setPlanningConfig((c) => ({ ...c, consolidation: { ...c.consolidation, [configKey]: v, period_days: days } }));
                        }}
                        style={{ padding: '3px 4px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.875rem' }}
                      >
                        <option value="none">{tP('config.woBatchNone')}</option>
                        <option value="weekly">{tP('config.woBatchWeekly')}</option>
                        <option value="biweekly">{tP('config.woBatchBiweekly')}</option>
                        <option value="monthly">{tP('config.woBatchMonthly')}</option>
                        <option value="all">{tP('config.woBatchAll')}</option>
                      </select>
                    </label>
                  );
                })}
              </div>
              </div>
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
          </fieldset>

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
                max_bom_depth: 3,
                score_weights: { commit_time: 0.4, inventory_consumed: 0.35, purchase: 0.25 },
              },
              purchase_allowed: false,
              constraints: [],
              consolidation: { enabled: true, period_days: 7, make_batch_scale: 'weekly', move_batch_scale: 'weekly', purchase_batch_scale: 'weekly' },
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
        {peggingSaveStatus && (
          <div style={{ marginTop: '0.5rem', maxWidth: 400 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', color: '#a16207', marginBottom: '0.25rem' }}>
              <span>Saving pegging data for run #{peggingSaveStatus.run_id} — {peggingSaveStatus.pct}%</span>
              <span style={{ color: '#71717a' }}>{peggingSaveStatus.chunks_done}/{peggingSaveStatus.chunks_total} chunks</span>
            </div>
            <div style={{ height: 5, backgroundColor: '#27272a', borderRadius: 4, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${peggingSaveStatus.pct}%`, backgroundColor: '#d97706', transition: 'width 0.4s ease' }} />
            </div>
          </div>
        )}
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
            {(planResult.resource_contention_pushed_wos ?? 0) > 0 && (
              <div
                style={{
                  marginTop: '0.4rem',
                  padding: '0.4rem 0.6rem',
                  background: 'rgba(250,204,21,0.08)',
                  border: '1px solid rgba(250,204,21,0.35)',
                  borderRadius: 6,
                  fontSize: '0.78rem',
                  color: '#facc15',
                }}
                title={tP('planResult.contentionPushedTooltip')}
              >
                {tP('planResult.contentionPushed', { count: planResult.resource_contention_pushed_wos ?? 0 })}
              </div>
            )}
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
              <button
                type="button"
                className={planResultTab === 'resourceUtilization' ? '' : 'secondary'}
                onClick={() => setPlanResultTab('resourceUtilization')}
              >
                {tP('tabs.resourceUtilization')}
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
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planDemandLateOnly}
                        onChange={(e) => setPlanDemandLateOnly(e.target.checked)}
                      />
                      <span>{tP('committedDemands.filterLateOnly')}</span>
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
                    if (planDemandLateOnly) {
                      list = list.filter((r) => {
                        const days = computeLatenessDays(r.request_time, r.commit_time);
                        return days != null && days > 0;
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
                            const isPegged = woPeggingRowKey === k;
                            // Pegging-selected wins over failed: when the user clicks Show on
                            // a failed demand, they need a clear visual anchor. We layer the
                            // sky-blue pegging accent ON TOP of the failed-row red wash so
                            // both signals remain readable.
                            if (isPegged) {
                              return r.is_failed
                                ? { background: 'rgba(56,189,248,0.22)', outline: '2px solid #38bdf8', borderLeft: '3px solid #f87171' }
                                : { background: 'rgba(56,189,248,0.22)', outline: '2px solid #38bdf8' };
                            }
                            if (r.is_failed) return { background: 'rgba(248,113,113,0.08)', outline: '1px solid rgba(248,113,113,0.3)' };
                            // Late delivery: amber tint (distinct from the red used for failed).
                            const latenessDays = computeLatenessDays(r.request_time, r.commit_time);
                            if (latenessDays != null && latenessDays > 0) {
                              return { background: 'rgba(251,146,60,0.08)' };
                            }
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
                            {
                              key: 'lateness',
                              label: tP('committedDemands.columns.lateness'),
                              sortable: true,
                              sortValue: (r, dir) => {
                                const v = computeLatenessDays(r.request_time, r.commit_time);
                                return v ?? (dir === 'asc' ? Number.POSITIVE_INFINITY : Number.NEGATIVE_INFINITY);
                              },
                              render: (r) => {
                                const v = computeLatenessDays(r.request_time, r.commit_time);
                                if (v == null) return '–';
                                if (v > 0) return <span style={{ color: '#fb923c', fontWeight: 600 }}>+{v}d</span>;
                                if (v < 0) return <span style={{ color: '#34d399' }}>{v}d</span>;
                                return '0d';
                              },
                            },
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
                                    if (isSelected) { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setPreviousWoExplainRow(null); setPreviousManifestWoRow(null); }
                                    else { setPlanPeggingContext({ type: 'demand', row: r }); setPlanPeggingOpen(true); setWoPeggingRowKey(k); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setPreviousWoExplainRow(null); setPreviousManifestWoRow(null); }
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
                  <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '0.75rem' }}>
                    <h4 style={{ margin: 0 }}>{tP('workOrders.heading')}</h4>
                    {/* ── Native vs Consolidated segment control ── */}
                    <div style={{ display: 'flex', border: '1px solid #3f3f46', borderRadius: 6, overflow: 'hidden' }}>
                      {(['consolidated', 'native'] as const).map((tab) => (
                        <button
                          key={tab}
                          type="button"
                          onClick={() => setWoTableTab(tab)}
                          style={{
                            fontSize: '0.82rem',
                            padding: '4px 14px',
                            borderRadius: 0,
                            border: 'none',
                            background: woTableTab === tab ? '#3f3f46' : 'transparent',
                            color: woTableTab === tab ? '#f4f4f5' : '#a1a1aa',
                            fontWeight: woTableTab === tab ? 600 : 400,
                            cursor: 'pointer',
                          }}
                        >
                          {tP(tab === 'consolidated' ? 'workOrders.tabConsolidated' : 'workOrders.tabNative')}
                          <span style={{ marginLeft: 6, opacity: 0.65, fontSize: '0.72rem' }}>
                            {tab === 'consolidated' ? woTabEffectiveCounts.consolidated : woTabEffectiveCounts.native}
                          </span>
                        </button>
                      ))}
                    </div>
                  </div>
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
                        checked={planWoMakeOnly}
                        onChange={(e) => setPlanWoMakeOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterMakeOnly')}</span>
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
                        checked={planWoPurchaseOnly}
                        onChange={(e) => setPlanWoPurchaseOnly(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterPurchaseOnly')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <input
                        type="checkbox"
                        checked={planWoHasOverride}
                        onChange={(e) => setPlanWoHasOverride(e.target.checked)}
                      />
                      <span>{tP('workOrders.filterHasOverride')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.8rem', color: '#e4e4e7' }}>
                      <span>Demand:</span>
                      <input
                        type="text"
                        list="plan-wo-demand-filter-list"
                        value={planWoFilterDemandId}
                        onChange={(e) => setPlanWoFilterDemandId(e.target.value)}
                        placeholder="demand_id"
                        style={{ width: '11rem', padding: '2px 6px', fontSize: '0.78rem', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 3, color: '#e4e4e7' }}
                        title="Show only WOs pegged to this demand (matches demand_id or any consolidated split-detail demand)"
                      />
                      {planWoFilterDemandId && (
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.7rem', padding: '1px 6px' }}
                          onClick={() => setPlanWoFilterDemandId('')}
                        >×</button>
                      )}
                      <datalist id="plan-wo-demand-filter-list">
                        {Array.from(new Set((planResult.committed_demands ?? [])
                          .map((d) => d.demand_id)
                          .filter((d): d is string => !!d)))
                          .slice(0, 200)
                          .map((did) => (
                            <option key={did} value={did} />
                          ))}
                      </datalist>
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
                          <span style={{ color: '#ec4899' }}>↓ {woPegRowCounts.ancestors}</span>
                          {' · '}
                          <span style={{ color: '#6366f1' }}>↑ {woPegRowCounts.descendants}</span>
                        </span>
                        <button
                          type="button"
                          className="secondary"
                          style={{ fontSize: '0.7rem', padding: '1px 8px' }}
                          onClick={() => { setWoPegHighlightRow(null); }}
                        >{tc('clear')}</button>
                      </span>
                    )}
                  </div>
                  {planResult.work_orders.length > 0 && (() => {
                    const workOrdersFiltered = planWorkOrderHideDummyProdArea
                      ? activeWorkOrders.filter((r) => !(r.product_id ?? '').trim().startsWith('VirtualProduct_'))
                      : activeWorkOrders;
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
                      ? activeWorkOrders.filter((r) => !(r.product_id ?? '').trim().startsWith('VirtualProduct_'))
                      : activeWorkOrders;
                    // Supply-backing maps used by the WO expand panel for pegging drill-down.
                    const { suppliesMap: woSuppliesMap, crossEntrySupplyMap: woCrossEntrySupplyMap, peggedQtyMap: woPeggedQtyMap } = buildWoMaps(planResult.planning_pegging ?? []);
                    // Filters refer to work-order pegging (each WO's supplies subtree), not demand pegging.
                    const anyPeggingFilter = planDemandRealMakeOnly || planDemandBuyOnly || planDemandRealMoveOnly || planWoDemandedByMultiple || planWoMultiSupply || planWoPurchaseOnly || planWoMakeOnly || planWoMoveOnly || planWoHasOverride;
                    if (anyPeggingFilter) {
                      workOrderRows = workOrderRows.filter((r) => {
                        if (planDemandRealMakeOnly && !(r.pegging_includes_real_make === true)) return false;
                        if (planDemandBuyOnly && !(r.pegging_includes_buy === true)) return false;
                        if (planDemandRealMoveOnly && !(r.pegging_includes_real_move === true)) return false;
                        if (planWoDemandedByMultiple && !(r.demanded_by_multiple === true)) return false;
                        if (planWoMultiSupply && !(r.multi_supply_available === true)) return false;
                        if (planWoPurchaseOnly) { const m = (r.method ?? '').toLowerCase(); if (m !== 'buy' && m !== 'purchase') return false; }
                        if (planWoMakeOnly && (r.method ?? '').toLowerCase() !== 'make') return false;
                        if (planWoMoveOnly && (r.method ?? '').toLowerCase() !== 'move') return false;
                        if (planWoHasOverride && !woHasSavedOverride(r)) return false;
                        return true;
                      });
                    }
                    // Demand-id filter: show only WOs pegged to this demand. Mirrors
                    // buildWoDemandGroups: a WO is pegged to demand D if r.demand_id === D
                    // OR D appears in r.wo_consolidation_split_details (consolidated row).
                    const demandIdFilter = planWoFilterDemandId.trim();
                    if (demandIdFilter) {
                      workOrderRows = workOrderRows.filter((r) => {
                        if (r.demand_id === demandIdFilter) return true;
                        return r.wo_consolidation_split_details?.some((d) => d.demand_id === demandIdFilter) ?? false;
                      });
                    }
                    // All consolidated WOs are valid plan actions — no phantom filtering.
                    // Previously we filtered by pegging-tree backing (backedWoSigs) but pegging
                    // is now lazy-loaded per demand, so planning_pegging is always empty here and
                    // the set was always empty, silently dropping all purchase and non-null-product
                    // move orders. All three methods (make/purchase/move) are passed through; the
                    // VirtualProduct visibility is already handled by the checkbox filter above.
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
                        // Include wo_group_id for all WOs (native and consolidated) so each
                        // pegging-tree node gets its own row. Without this, native WOs sharing
                        // demand/product/location/method but from different time windows collapse
                        // into one row and lose independent pegging-tree access.
                        String(r.wo_group_id ?? ''),
                      ].join('|');
                      const existing = grouped.get(key);
                      const rowQty = Number(r.quantity ?? 0) || 0;
                      if (!existing) {
                        // Track each constituent WO's span so the schedule bar can draw it as its own
                        // segment (real short durations + true gaps) instead of one solid min→max span.
                        grouped.set(key, { ...r, quantity: rowQty, _segments: [{ start: r.start_time ?? null, end: r.end_time ?? null }] });
                      } else {
                        existing.quantity = (Number(existing.quantity ?? 0) || 0) + rowQty;
                        (existing._segments ??= []).push({ start: r.start_time ?? null, end: r.end_time ?? null });
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
                    const dummyHiddenCount = new Set(
                      activeWorkOrders
                        .filter((r) => (r.product_id ?? '').trim().startsWith('VirtualProduct_'))
                        .map((r) => [
                          String(r.demand_id ?? ''), String(r.product_id ?? ''), String(r.location_id ?? ''),
                          String(r.method ?? ''), String(r.location_source ?? ''), String(r.prod_area ?? ''),
                          r.consolidated ? String(r.wo_group_id ?? '') : '',
                        ].join('|'))
                    ).size;
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
                      // A cross-demand batched WO (consolidated=true) committed exactly its
                      // batched quantity for its demands, so Requested = Committed (r.quantity) —
                      // same treatment as a shared/split WO; avoids a per-context peggedQty mismatch.
                      // In the consolidated tab every row is a consolidated WO (singleton or multi-demand)
                      // so treat them all identically: use r.quantity as Requested, skip peggedQty / demandRequestedMap.
                      const isSharedConsolidated = woTableTab === 'consolidated' || (r.wo_consolidation_split_details?.length ?? 0) > 1 || r.consolidated === true;
                      const woKeyFull = `${r.demand_id ?? ''}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                      const woKeyConsolidated = `|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                      const peggedQty = woPeggedQtyMap.get(woKeyFull) ?? woPeggedQtyMap.get(woKeyConsolidated) ?? null;
                      // peggedQty (now summed across this component's sub-assembly contexts,
                      // failed subtrees excluded) is the true component-level requirement and is
                      // directly comparable to the grouped committed quantity.
                      const usePegged = !isSharedConsolidated && peggedQty != null;
                      const demandRequested = isSharedConsolidated
                        ? (Number(r.quantity) || 0)
                        : usePegged
                          ? peggedQty
                          : r.demand_id
                            ? (demandRequestedMap.get(r.demand_id) ?? undefined)
                            : splitDemandIds.reduce((s, did) => s + (demandRequestedMap.get(did) ?? 0), 0) || undefined;
                      // Subtract inventory fulfillment ONLY in the FG-level fallback (where
                      // demandRequested is a finished-good requested_qty). For shared WOs and for
                      // component-level peggedQty, inventory is already reflected in the pegging
                      // requirement — subtracting the FG-level number would understate Requested
                      // (re-introducing Committed > Requested).
                      const inventoryFulfilled = (isSharedConsolidated || usePegged)
                        ? 0
                        : r.demand_id
                          ? (demandInventoryMap.get(r.demand_id) ?? 0)
                          : splitDemandIds.reduce((s, did) => s + (demandInventoryMap.get(did) ?? 0), 0);
                      const requested = demandRequested != null ? Math.max(0, demandRequested - inventoryFulfilled) : undefined;
                      return {
                        ...r,
                        _key: `wo-${i}-${r.product_id}-${r.location_id}`,
                        _prod_area: String(r.prod_area ?? ''),
                        _peg_order: pegOrderMap.get(`${r.demand_id ?? ''}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`),
                        _demand_label: demandLabel,
                        _demand_ids: r.demand_id ? [r.demand_id] : splitDemandIds,
                        _requested_qty: requested,
                      };
                    });
                    // Synthetic inventory rows — one per inventory-committed demand, native tab only.
                    // These appear alongside WO rows so traceability is end-to-end:
                    // demand → inventory (point bar) + WO chain (production bars).
                    if (woTableTab === 'native') {
                      const invDemands = planResult.committed_demands.filter(
                        (d) => d.commit_reason === 'inventory' && d.commit_time && d.demand_id
                          && (!demandIdFilter || d.demand_id === demandIdFilter),
                      );
                      for (const d of invDemands) {
                        woRowsAll.push({
                          product_id: d.product_id,
                          location_id: d.location_id,
                          demand_id: d.demand_id ?? undefined,
                          method: 'inventory',
                          quantity: d.quantity,
                          start_time: d.commit_time,
                          end_time: d.commit_time,
                          wo_group_id: `inv:${d.demand_id}`,
                          _is_inventory: true,
                          _demand_ids: d.demand_id ? [d.demand_id] : [],
                          _segments: [{ start: d.commit_time ?? null, end: d.commit_time ?? null }],
                        } as WoEnrichedRow);
                      }
                    }
                    let woRows: WoEnrichedRow[] = woRowsAll;
                    if (woPegHighlightRow) {
                      // Same classifier the toolbar uses for the ↓/↑ counts —
                      // they cannot disagree.
                      woRows = woRows.filter((r) => {
                        // Inventory rows: show alongside the highlighted WO when it belongs
                        // to the same demand (inventory fulfills part of the same demand).
                        if (r._is_inventory) return r.demand_id === woPegHighlightRow.demand_id;
                        return peggedRowKindFor(r, woPegHighlightRow, woPegHighlightSets) !== null;
                      });
                      // BFS depth from the highlighted row through the predecessor graph.
                      // Self=0, direct components=1, deeper sub-components=2+.
                      // Rows are sorted descending (deepest first → closest to raw material at top,
                      // FG assembly at bottom) so the list follows build-schedule order.
                      const depthMap = new Map<string, number>();
                      const bfsQueue: Array<[string, number]> = woRowPegKeys(woPegHighlightRow).map((k) => [k, 0]);
                      while (bfsQueue.length) {
                        const [k, d] = bfsQueue.shift()!;
                        if (depthMap.has(k)) continue;
                        depthMap.set(k, d);
                        for (const child of Array.from(woPegRelations.childMap.get(k) ?? [])) {
                          if (!depthMap.has(child)) bfsQueue.push([child, d + 1]);
                        }
                      }
                      woRows = woRows.map((r) => {
                        const keys = woRowPegKeys(r);
                        const depth = keys.reduce<number>((min, k) => Math.min(min, depthMap.get(k) ?? Infinity), Infinity);
                        return { ...r, _peg_depth: depth === Infinity ? 0 : depth };
                      });
                      woRows = [...woRows].sort((a, b) => {
                        const da = a._peg_depth ?? 0;
                        const db = b._peg_depth ?? 0;
                        if (da !== db) return db - da; // deepest first
                        return (a.start_time ?? '').localeCompare(b.start_time ?? '');
                      });
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
                      { key: 'product_id', label: tP('workOrders.columns.product'), sortable: true, render: (r) => {
                        if (r.product_id) return r.product_id;
                        const comps = r.move_components ?? [];
                        if (comps.length === 0) return '–';
                        // Single-product consolidated move: show the product name directly.
                        if (comps.length === 1) return comps[0].product_id;
                        // Multi-product shipment: show count with hover list.
                        const list = comps.map((c) => `${c.product_id} · ${qtyFmt(Number(c.quantity ?? 0))}`).join('\n');
                        return <span title={list} style={{ fontStyle: 'italic', color: '#a1a1aa' }}>{tP('workOrders.nComponents', { n: comps.length })}</span>;
                      } },
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
                          // Inventory rows: vertical tick at commit_time (no production span).
                          if (r._is_inventory) {
                            const ct = r.start_time ? new Date(r.start_time) : null;
                            if (!ct || Number.isNaN(ct.getTime())) return null;
                            const hSpan = Math.max(1, horizon.end.getTime() - horizon.start.getTime());
                            const leftPct = Math.max(0, Math.min(100, ((ct.getTime() - horizon.start.getTime()) / hSpan) * 100));
                            return (
                              <div style={{ position: 'relative', width: '100%', height: '1.25rem', display: 'flex', alignItems: 'center' }}>
                                <div style={{ position: 'absolute', left: `${leftPct}%`, width: '3px', height: '80%', background: '#16a34a', borderRadius: '1px', transform: 'translateX(-50%)', cursor: 'default' }} />
                              </div>
                            );
                          }
                          const isSelf = !!woPegHighlightRow
                            && r.product_id === woPegHighlightRow.product_id
                            && r.location_id === woPegHighlightRow.location_id
                            && (r.method ?? '') === (woPegHighlightRow.method ?? '')
                            && (r.demand_id ?? '') === (woPegHighlightRow.demand_id ?? '')
                            && (r.start_time ?? '') === (woPegHighlightRow.start_time ?? '');
                          // No bar-color override for peg relationships — row-level left-border
                          // (in rowStyle) signals predecessor/successor without hiding WO type color.
                          const colorOverride: string | undefined = undefined;
                          return (
                            <ScheduleBar
                              start={r.start_time}
                              end={r.end_time}
                              horizon={horizon}
                              method={r.method}
                              locale={locale}
                              selected={isSelf}
                              colorOverride={colorOverride}
                              consolidated={r.consolidated}
                              segments={r._segments}
                              onClick={() => setWoPegHighlightRow(isSelf ? null : r)}
                            />
                          );
                        },
                      },
                      ...(woTableTab === 'native' ? [
                        {
                          key: '_delta_start',
                          label: tP('workOrders.columns.deltaStart'),
                          sortable: true,
                          width: '5%' as const,
                          sortValue: (r: WoEnrichedRow) => {
                            if (!r.original_start_time || !r.start_time) return 0;
                            return Math.round((new Date(r.start_time).getTime() - new Date(r.original_start_time).getTime()) / 86400000);
                          },
                          render: (r: WoEnrichedRow) => {
                            if (!r.original_start_time || !r.start_time)
                              return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                            const d = Math.round((new Date(r.start_time).getTime() - new Date(r.original_start_time).getTime()) / 86400000);
                            const tooltip = d !== 0
                              ? tP('workOrders.columns.deltaStartTooltipShift', { date: r.original_start_time.slice(0, 10), delta: `${d > 0 ? '+' : ''}${d}d` })
                              : tP('workOrders.columns.deltaStartTooltipNoShift', { date: r.original_start_time.slice(0, 10) });
                            if (d === 0) return <span style={{ color: '#52525b', fontSize: '0.75rem' }} title={tooltip}>–</span>;
                            return (
                              <span style={{ fontSize: '0.78rem', fontWeight: 600, color: d > 0 ? '#f59e0b' : '#34d399' }}
                                title={tooltip}
                              >{d > 0 ? `+${d}d` : `${d}d`}</span>
                            );
                          },
                        },
                        {
                          key: '_delta_lead',
                          label: tP('workOrders.columns.deltaLead'),
                          sortable: true,
                          width: '5%' as const,
                          sortValue: (r: WoEnrichedRow) => {
                            if (r.original_lead_days == null || !r.start_time || !r.end_time) return 0;
                            const consLead = Math.round((new Date(r.end_time).getTime() - new Date(r.start_time).getTime()) / 86400000);
                            return consLead - r.original_lead_days;
                          },
                          render: (r: WoEnrichedRow) => {
                            if (r.original_lead_days == null || !r.start_time || !r.end_time)
                              return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                            const consLead = Math.round((new Date(r.end_time).getTime() - new Date(r.start_time).getTime()) / 86400000);
                            const d = consLead - r.original_lead_days;
                            const tooltip = d !== 0
                              ? tP('workOrders.columns.deltaLeadTooltipChange', { orig: String(r.original_lead_days), cons: String(consLead), delta: `${d > 0 ? '+' : ''}${d}d` })
                              : tP('workOrders.columns.deltaLeadTooltipNoChange', { orig: String(r.original_lead_days), cons: String(consLead) });
                            if (d === 0) return <span style={{ color: '#52525b', fontSize: '0.75rem' }} title={tooltip}>–</span>;
                            return (
                              <span style={{ fontSize: '0.78rem', fontWeight: 600, color: d > 0 ? '#f59e0b' : '#34d399' }}
                                title={tooltip}
                              >{d > 0 ? `+${d}d` : `${d}d`}</span>
                            );
                          },
                        },
                      ] : []),
                      { key: 'method', label: tP('workOrders.columns.method'), sortable: true, render: (r) => {
                        const isConsolidatedView = woTableTab === 'consolidated' || r.consolidated;
                        const c = methodColor(r.method, isConsolidatedView);
                        const label = r.method ?? '–';
                        return isConsolidatedView
                          ? <span style={{ color: c, background: `${c}22`, border: `1px solid ${c}66`, borderRadius: 4, padding: '0 6px', fontWeight: 600 }}>{label}</span>
                          : <span style={{ color: c }}>{label}</span>;
                      } },
                      { key: '_demand_label', label: tP('workOrders.columns.demand'), sortable: true, width: '9%', render: (r) => {
                        const ids = r._demand_ids ?? [];
                        if (!ids.length) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        const openDemandPegging = (did: string, e: React.MouseEvent) => {
                          e.stopPropagation();
                          const demandRow = planResult.committed_demands.find((d) => d.demand_id === did) ?? null;
                          if (!demandRow) return;
                          const demandKey = `demand|${did}|${demandRow.product_id ?? ''}|${demandRow.location_id ?? ''}`;
                          setPreviousWoExplainRow(null);
                          setPlanPeggingContext({ type: 'demand', row: demandRow });
                          setPlanPeggingOpen(true);
                          setWoPeggingRowKey(demandKey);
                          setWoPegHighlightRow(r);
                        };
                        const linkStyle: React.CSSProperties = { background: 'none', border: 'none', padding: 0, color: '#60a5fa', cursor: 'pointer', textDecoration: 'underline', fontSize: 'inherit', fontFamily: 'inherit' };
                        if (ids.length === 1) {
                          return <button type="button" style={linkStyle} onClick={(e) => openDemandPegging(ids[0], e)}>{ids[0]}</button>;
                        }
                        const visible = ids.slice(0, 2);
                        const rest = ids.length - 2;
                        return (
                          <span title={ids.join('\n')} style={{ display: 'inline-flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
                            {visible.map((did, i) => (
                              <React.Fragment key={did}>
                                {i > 0 && <span style={{ color: '#52525b' }}>,</span>}
                                <button type="button" style={linkStyle} onClick={(e) => openDemandPegging(did, e)}>{did}</button>
                              </React.Fragment>
                            ))}
                            {rest > 0 && <span style={{ color: '#a1a1aa', fontSize: '0.72rem' }}>&nbsp;+{rest}</span>}
                            <span style={{ marginLeft: 3, background: '#0891b2', color: '#fff', borderRadius: 8, padding: '1px 6px', fontSize: '0.7rem' }}>
                              {tP('workOrders.shared')}
                            </span>
                          </span>
                        );
                      } },
                      { key: 'location_source', label: tP('workOrders.columns.locationSource'), sortable: true, render: (r) => r.location_source ?? '–' },
                      { key: '_peg_order', label: tP('workOrders.columns.pegging'), sortable: true, render: (r) => {
                        const k = `${r.demand_id ?? ''}|${r.product_id}|${r.location_id}|${r.method ?? ''}|${r.start_time ?? ''}`;
                        // isPeggingActive: this WO's pegging is the direct content of the panel.
                        const isPeggingActive = woPeggingRowKey === k;
                        // isHighlightRow: this row is the woPegHighlightRow — it stays true even
                        // when the panel switches to demand context (demand nav doesn't un-select the WO).
                        const isHighlightRow = !!woPegHighlightRow
                          && r.product_id === woPegHighlightRow.product_id
                          && r.location_id === woPegHighlightRow.location_id
                          && (r.method ?? '') === (woPegHighlightRow.method ?? '')
                          && (r.demand_id ?? '') === (woPegHighlightRow.demand_id ?? '')
                          && (r.start_time ?? '') === (woPegHighlightRow.start_time ?? '');
                        const isSelected = isPeggingActive || isHighlightRow;
                        return (
                          <button
                            type="button"
                            className="secondary"
                            style={isSelected ? { background: 'rgba(56,189,248,0.2)', borderColor: '#38bdf8' } : undefined}
                            onClick={(e) => {
                              e.stopPropagation();
                              if (isPeggingActive) { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setPreviousWoExplainRow(null); setPreviousManifestWoRow(null); setWoPegHighlightRow(null); }
                              else { setPlanPeggingContext({ type: 'work_order', row: r }); setPlanPeggingOpen(true); setWoPeggingRowKey(k); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setPreviousWoExplainRow(null); setPreviousManifestWoRow(null); setWoPegHighlightRow(r); }
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
                      { key: '_woschedule', label: 'Schedule', sortable: false, render: (r) => {
                        if (!r.wo_group_id) return <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>;
                        return (
                          <button
                            type="button"
                            className="secondary"
                            style={{ fontSize: '0.72rem', padding: '2px 6px' }}
                            onClick={() => setWoScheduleModalRow(r)}
                            title="Schedule change → impact analysis"
                          >Schedule</button>
                        );
                      }},
                    ];
                    // Consolidated tab: remove per-demand action columns that don't apply
                    // (Explain, Override, Schedule-modal).
                    if (woTableTab === 'consolidated') {
                      const hide = new Set(['_explain', '_override', '_woschedule']);
                      for (let i = woColumns.length - 1; i >= 0; i--) {
                        if (hide.has(woColumns[i].key as string)) woColumns.splice(i, 1);
                      }
                    }
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
                    // • Drop _requested_qty, _demand_label: the group header already shows demand-level
                    //   totals, and per-WO Requested (WO-scoped) would duplicate or compete with it.
                    // • Override product_id / quantity / method to handle synthetic inventory rows.
                    const woDemandColumns = woColumns
                      .filter((col) => !['_requested_qty', '_demand_label'].includes(col.key as string))
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
                      // Checked before peggingSelectedProductLoc so the explicitly-selected row stays
                      // blue even when a demand-pegging tree node with the same product@location is clicked.
                      if (woPegHighlightRow) {
                        const isSelf = r === woPegHighlightRow
                          || (r.product_id === woPegHighlightRow.product_id
                            && r.location_id === woPegHighlightRow.location_id
                            && (r.method ?? '') === (woPegHighlightRow.method ?? '')
                            && (r.demand_id ?? '') === (woPegHighlightRow.demand_id ?? '')
                            && (r.start_time ?? '') === (woPegHighlightRow.start_time ?? ''));
                        if (isSelf) return { background: 'rgba(56,189,248,0.18)', outline: '1px solid rgba(56,189,248,0.5)' };
                      }
                      // Amber tint for rows whose product@location matches the
                      // currently-selected node inside the open pegging tree.
                      if (peggingSelectedProductLoc
                        && r.product_id === peggingSelectedProductLoc.product
                        && r.location_id === peggingSelectedProductLoc.location) {
                        return { background: 'rgba(251,191,36,0.12)', outline: '1px solid rgba(251,191,36,0.4)' };
                      }
                      if (woPegHighlightRow) {
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
                          Showing {qtyFmt(woRows.length)} work order{woRows.length !== 1 ? 's' : ''}
                          {planWorkOrderHideDummyProdArea && dummyHiddenCount > 0
                            ? ` (${qtyFmt(dummyHiddenCount)} with product_id = VirtualProduct_* hidden)`
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
                              // Amber tint for rows whose product@location matches the
                              // currently-selected node inside the open pegging tree
                              // (distinct from the sky-blue root-WO highlight above).
                              if (peggingSelectedProductLoc
                                && r.product_id === peggingSelectedProductLoc.product
                                && r.location_id === peggingSelectedProductLoc.location) {
                                return { background: 'rgba(251,191,36,0.12)', outline: '1px solid rgba(251,191,36,0.4)' };
                              }
                              if (!r.override_active && !r.consolidation_override_active && woHasSavedOverride(r)) return { borderLeft: '3px solid #b45309' };
                              // Predecessor/successor relationship — left-border indicates position in
                              // BOM chain without overriding the WO-type bar color (orange=consolidated,
                              // blue=native).
                              if (woPegHighlightRow) {
                                const rKeys = woRowPegKeys(r);
                                if (rKeys.some((rk) => woPegHighlightSets.ancestors.has(rk))) return { borderLeft: '3px solid #ec4899' };
                                if (rKeys.some((rk) => woPegHighlightSets.descendants.has(rk))) return { borderLeft: '3px solid #818cf8' };
                              }
                              return undefined;
                            }}
                            expandedKeys={woExpandedKeys}
                            onToggleExpand={(key) => setWoExpandedKeys((prev) => {
                              const next = new Set(prev);
                              prev.has(key) ? next.delete(key) : next.add(key);
                              return next;
                            })}
                            canExpandRow={(r) => {
                              // Show the inline ▶ only when the row actually has supplies to show.
                              // Cross-demand consolidated orders (demand_id=null) have no per-WO
                              // supplies map entry — their breakdown is the pegging drill-down — so
                              // their toggle would open empty; hide it.
                              const woKey = `${r.demand_id ?? ''}|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                              const consolidatedWoKey = `|${r.product_id ?? ''}|${r.location_id ?? ''}|${r.method ?? ''}`;
                              const sup = woSuppliesMap.get(woKey) ?? woSuppliesMap.get(consolidatedWoKey) ?? [];
                              const isMake = sup.length > 0 && sup[0].type === 'demand';
                              const direct = isMake ? (woCrossEntrySupplyMap.get(`${r.demand_id ?? ''}|${r.product_id ?? ''}`) ?? []) : [];
                              if (sup.length === 0 && direct.length === 0) return false;
                              // When a WO is highlighted, component WOs are already visible as
                              // top-level rows. The ▶ expand would duplicate them inline —
                              // suppress it so the list is the single source of truth.
                              if (woPegHighlightRow) return false;
                              return true;
                            }}
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
                                            <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Start</th>
                                            <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>End</th>
                                            <th style={{ textAlign: 'right', fontWeight: 400, paddingRight: '1.25rem' }}>Qty</th>
                                          </tr>
                                        </thead>
                                        <tbody>
                                          {directSupplies.map((s, si) => (
                                            <tr key={si} style={supplyRowStyle}>
                                              <td style={cellP}>
                                                <span style={{ fontSize: '0.65rem', padding: '1px 5px', borderRadius: 6, background: 'rgba(34,197,94,0.15)', color: '#16a34a', border: '1px solid rgba(34,197,94,0.3)' }}>{s.type}</span>
                                              </td>
                                              <td style={{ ...cellP, fontFamily: 'monospace', fontSize: '0.72rem', color: '#71717a' }}>{s.supply_id ?? s.vendor_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3' }}>{s.location_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3', fontFamily: 'monospace', fontSize: '0.72rem' }}>{s.start_time ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3', fontFamily: 'monospace', fontSize: '0.72rem' }}>{s.end_time ?? '–'}</td>
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
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>Start</th>
                                                <th style={{ textAlign: 'left', fontWeight: 400, paddingRight: '1.25rem' }}>End</th>
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
                                              <td style={{ ...cellP, fontFamily: 'monospace', fontSize: '0.72rem', color: '#71717a' }}>{s.supply_id ?? s.vendor_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3' }}>{s.location_id ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3', fontFamily: 'monospace', fontSize: '0.72rem' }}>{s.start_time ?? '–'}</td>
                                              <td style={{ ...cellP, color: '#a3a3a3', fontFamily: 'monospace', fontSize: '0.72rem' }}>{s.end_time ?? '–'}</td>
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
              {planResultTab === 'resourceUtilization' && (
                <div style={{ padding: '0.75rem 1rem' }}>
                  <ResourceUtilizationView
                    caseId={id}
                    planRunId={currentPlanRunId ?? freshPlanRunId}
                  />
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
                                    else { setSupExplainRow(r); setSupExplainKey(k); setSupExplainOpen(true); setPeggedSort(null); setPeggedDemandFilter(''); setPeggedCustomerFilter(''); }
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
                        const res = await fetch(`/allocator/api/supplies?caseId=${id}&q=${encodeURIComponent(val.trim())}`);
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
      {caseSection === 'wo_schedule' && (
        <WoScheduleImpactPanel
          caseId={id}
          baselinePlan={planResult as unknown as PlanResult}
          baselinePlanRunId={currentPlanRunId ?? freshPlanRunId}
        />
      )}
      {woScheduleModalRow && (
        <WoScheduleQuickModal
          caseId={id}
          baselinePlanRunId={currentPlanRunId ?? freshPlanRunId}
          wo={woScheduleModalRow}
          onClose={() => setWoScheduleModalRow(null)}
        />
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
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editDepth')}</span>
                                    <input type="number" min={1} max={10}
                                      value={Number(ms.depth ?? 1)}
                                      onChange={(e) => updateConfig((c) => {
                                        const m = (c.method_selection ?? {}) as Record<string, unknown>;
                                        m.depth = Math.max(1, Math.min(10, parseInt(e.target.value, 10) || 1));
                                        c.method_selection = m;
                                      })}
                                      style={{ ...inputStyle, width: 56 }} />
                                  </label>
                                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                    <span style={{ color: '#a1a1aa' }}>{tP('bootstrap.editMaxBomDepth')}</span>
                                    <input type="number" min={1} max={10}
                                      value={Number(ms.max_bom_depth ?? 3)}
                                      onChange={(e) => updateConfig((c) => {
                                        const m = (c.method_selection ?? {}) as Record<string, unknown>;
                                        m.max_bom_depth = Math.max(1, Math.min(10, parseInt(e.target.value, 10) || 3));
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
                                {/* Consolidation (WO batch window per type) */}
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', alignItems: 'center', fontSize: '0.78rem', marginBottom: 6 }}>
                                  {(['make', 'move', 'purchase'] as const).map((type) => {
                                    const configKey = `${type}_batch_scale`;
                                    const labelKey = `woBatch${type.charAt(0).toUpperCase() + type.slice(1)}` as 'woBatchMake' | 'woBatchMove' | 'woBatchPurchase';
                                    const globalFb = (cs.wo_batch_scale as string) ?? 'weekly';
                                    const val = ((cs as Record<string, unknown>)[configKey] as string) ?? globalFb;
                                    return (
                                      <label key={type} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                        <span style={{ color: '#a1a1aa' }}>{tP(`config.${labelKey}`)}</span>
                                        <select
                                          value={val}
                                          onChange={(e) => updateConfig((c) => {
                                            const v = e.target.value as 'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';
                                            const days = v === 'weekly' ? 7 : v === 'biweekly' ? 14 : v === 'monthly' ? 30 : 0;
                                            const con = (c.consolidation ?? {}) as Record<string, unknown>;
                                            con[configKey] = v;
                                            con.period_days = days;
                                            c.consolidation = con;
                                          })}
                                          style={{ ...inputStyle }}>
                                          <option value="none">{tP('config.woBatchNone')}</option>
                                          <option value="weekly">{tP('config.woBatchWeekly')}</option>
                                          <option value="biweekly">{tP('config.woBatchBiweekly')}</option>
                                          <option value="monthly">{tP('config.woBatchMonthly')}</option>
                                          <option value="all">{tP('config.woBatchAll')}</option>
                                        </select>
                                      </label>
                                    );
                                  })}
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
                // Left border priority: green (active) > blue (initial).
                const accent = isActive ? '#4ade80' : isInitial ? '#60a5fa' : 'transparent';
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
                  background: isActive ? 'rgba(74,222,128,0.04)' : isInitial ? 'rgba(96,165,250,0.04)' : 'transparent',
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
                      <span style={{ fontSize: '0.8rem', color: '#a1a1aa' }}>
                        {new Date(run.created_at).toLocaleString()}
                      </span>
                      {typeof run.duration_ms === 'number' && run.duration_ms >= 0 && (
                        <span
                          title={tP('runHistory.elapsedTitle')}
                          style={{ fontSize: '0.75rem', color: '#a1a1aa', background: '#27272a', borderRadius: 8, padding: '1px 7px' }}
                        >
                          {formatElapsedMs(run.duration_ms)}
                        </span>
                      )}
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
                const demandId = woExplainRow.demand_id;
                const demandRow = (planResult?.committed_demands ?? []).find(d => d.demand_id === demandId) ?? null;
                const customer = demandRow?.customer ?? null;
                const demandKey = `demand|${demandId ?? ''}|${demandRow?.product_id ?? ''}|${demandRow?.location_id ?? ''}`;
                return (
                  <section style={{ marginBottom: '1.25rem' }}>
                    <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{tP('woExplain.peggedDemand')}</h4>
                    <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: 1.6 }}>
                      {tP('woExplain.producesForDemand')}{' '}
                      {demandRow ? (
                        <button
                          type="button"
                          style={{ background: 'none', border: 'none', padding: 0, color: '#60a5fa', cursor: 'pointer', fontWeight: 700, fontSize: 'inherit', textDecoration: 'underline' }}
                          title={tP('supExplain.openDemandPegging')}
                          onClick={() => {
                            setPreviousWoExplainRow(woExplainRow);
                            setPlanPeggingContext({ type: 'demand', row: demandRow });
                            setPlanPeggingOpen(true);
                            setWoPeggingRowKey(demandKey);
                            // Keep woExplainKey/woExplainRow set so the WO row stays
                            // highlighted (purple) while the demand pegging is open.
                            // Cleared only when user explicitly closes/toggles the explain panel.
                            setWoExplainOpen(false);
                          }}
                        ><strong>{demandId}</strong></button>
                      ) : (
                        <strong>{demandId}</strong>
                      )}
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
              {/* ── Allocation Map (lot × demand heatmap) ─────────────────── */}
              {(() => {
                const compKey = `${supExplainRow.productId}|${supExplainRow.locationId ?? ''}`;
                // All lots for this product@location that have any allocation data
                const lots = planSupplyViewRows
                  .filter(r => `${r.productId}|${r.locationId ?? ''}` === compKey && lotDemandAllocMap.has(r.supplyId))
                  .sort((a, b) => {
                    const da = a.supplyDate ?? '9999-99-99';
                    const db = b.supplyDate ?? '9999-99-99';
                    return da < db ? -1 : da > db ? 1 : 0;
                  });
                if (lots.length === 0) return null;
                // Seed with ALL committed demands for this product@location (zero-allocation ones show as empty columns)
                const demandTotals = new Map<string, number>();
                for (const cd of planResult?.committed_demands ?? []) {
                  if (cd.demand_id && cd.product_id === supExplainRow.productId && cd.location_id === supExplainRow.locationId) {
                    demandTotals.set(cd.demand_id, 0);
                  }
                }
                // Accumulate actual allocations on top
                for (const lot of lots) {
                  const dm = lotDemandAllocMap.get(lot.supplyId);
                  if (!dm) continue;
                  dm.forEach((qty, did) => demandTotals.set(did, (demandTotals.get(did) ?? 0) + qty));
                }
                // Sort: allocated demands first (desc), then zero-allocation demands (alphabetically)
                const visibleDemands = Array.from(demandTotals.entries())
                  .sort((a, b) => b[1] !== a[1] ? b[1] - a[1] : a[0].localeCompare(b[0]))
                  .map(([d]) => d);
                const demandCustomer = (did: string) => {
                  const cd = planResult?.committed_demands?.find(d => d.demand_id === did);
                  return cd?.customer ?? null;
                };
                // Short label: strip common prefix from demand IDs for compact headers
                const shortLabel = (did: string) => did.length > 16 ? did.slice(-14) : did;
                const cellColor = (fraction: number) => {
                  if (fraction < 1e-9) return '#27272a';
                  const intensity = Math.min(1, fraction);
                  // interpolate #27272a → #7c3aed (dark to vivid purple)
                  const r = Math.round(39 + (124 - 39) * intensity);
                  const g = Math.round(39 + (58 - 39) * intensity);
                  const b = Math.round(42 + (237 - 42) * intensity);
                  return `rgb(${r},${g},${b})`;
                };
                return (
                  <section style={{ marginBottom: '1.25rem' }}>
                    <h4 style={{ margin: '0 0 0.4rem', color: '#a78bfa', fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                      Allocation Map
                      <span style={{ marginLeft: 6, fontWeight: 400, color: '#71717a', fontSize: '0.72rem', textTransform: 'none' }}>
                        {lots.length} lot{lots.length !== 1 ? 's' : ''} · {visibleDemands.length} competing demand{visibleDemands.length !== 1 ? 's' : ''}
                      </span>
                    </h4>
                    <div style={{ overflowX: 'auto', fontSize: '0.72rem' }}>
                      <table style={{ borderCollapse: 'collapse', minWidth: '100%' }}>
                        <thead>
                          <tr>
                            <th style={{ padding: '2px 6px 4px 0', textAlign: 'left', color: '#71717a', whiteSpace: 'nowrap', minWidth: 90, fontWeight: 400 }}>Lot</th>
                            <th style={{ padding: '2px 4px 4px', textAlign: 'right', color: '#71717a', whiteSpace: 'nowrap', fontWeight: 400 }}>Init qty</th>
                            {visibleDemands.map(did => (
                              <th key={did} style={{ padding: '2px 2px 4px', textAlign: 'center', maxWidth: 64, fontWeight: 400 }}>
                                <div style={{ color: '#a1a1aa', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 64 }} title={`${did}\n${demandCustomer(did) ?? ''}`}>
                                  {shortLabel(did)}
                                </div>
                                <div style={{ color: '#71717a', fontSize: '0.68rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 64 }}>
                                  {qtyFmt(demandTotals.get(did) ?? 0)}
                                </div>
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {lots.map(lot => {
                            const dm = lotDemandAllocMap.get(lot.supplyId) ?? new Map<string, number>();
                            const isCurrent = lot.supplyId === supExplainRow.supplyId;
                            return (
                              <tr key={lot.supplyId} style={{ background: isCurrent ? 'rgba(124,58,237,0.08)' : 'transparent' }}>
                                <td style={{ padding: '2px 6px 2px 0', color: isCurrent ? '#c4b5fd' : '#a1a1aa', whiteSpace: 'nowrap' }}>
                                  {lot.supplyDate ?? 'no date'}
                                </td>
                                <td style={{ padding: '2px 4px', textAlign: 'right', color: '#71717a', whiteSpace: 'nowrap' }}>
                                  {qtyFmt(lot.qty)}
                                </td>
                                {visibleDemands.map(did => {
                                  const qty = dm.get(did) ?? 0;
                                  const frac = lot.qty > 0 ? qty / lot.qty : 0;
                                  return (
                                    <td key={did} style={{ padding: '1px 2px' }}>
                                      <div
                                        title={`${did} ← ${qtyFmt(qty)} (${(frac * 100).toFixed(1)}% of lot)`}
                                        style={{
                                          background: cellColor(frac),
                                          borderRadius: 3,
                                          textAlign: 'center',
                                          padding: '2px 3px',
                                          color: frac > 0.4 ? '#f4f4f5' : frac > 0.05 ? '#c4b5fd' : '#52525b',
                                          minWidth: 36,
                                          whiteSpace: 'nowrap',
                                        }}
                                      >
                                        {qty > 0 ? qtyFmt(qty) : ''}
                                      </div>
                                    </td>
                                  );
                                })}
                              </tr>
                            );
                          })}
                        </tbody>
                        <tfoot>
                          <tr style={{ borderTop: '1px solid #3d3d40' }}>
                            <td style={{ padding: '3px 6px 1px 0', color: '#71717a' }}>Total</td>
                            <td style={{ padding: '3px 4px 1px', textAlign: 'right', color: '#71717a' }}>
                              {qtyFmt(lots.reduce((s, l) => s + l.qty, 0))}
                            </td>
                            {visibleDemands.map(did => (
                              <td key={did} style={{ padding: '3px 2px 1px', textAlign: 'center', color: '#a1a1aa', fontWeight: 600 }}>
                                {qtyFmt(demandTotals.get(did) ?? 0)}
                              </td>
                            ))}
                          </tr>
                        </tfoot>
                      </table>
                    </div>
                    <p style={{ margin: '0.3rem 0 0', fontSize: '0.7rem', color: '#52525b' }}>
                      Cell = qty actually drawn from lot by demand. Color intensity = fraction of lot.
                      Current lot highlighted.
                    </p>
                  </section>
                );
              })()}
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
                  (() => {
                    // Share = qty consumed from this lot / lot initial qty — fraction of the lot used.
                    const lotInitialQty = Number(supExplainRow.qty) || 0;
                    // Build enriched rows so we can sort/filter uniformly
                    const enriched = supExplainRow.peggedDemands.map((d) => {
                      const demandRow = planResult?.committed_demands.find((cd) => cd.demand_id === d.demandId);
                      const demandKey = demandRow ? `demand|${demandRow.demand_id ?? ''}|${demandRow.product_id}|${demandRow.location_id}` : null;
                      const groupLabel = supExplainRow.demandPath[d.demandId] ?? null;
                      const requestedQty = demandRow?.requested_qty != null ? Number(demandRow.requested_qty) : null;
                      const consumedQty = Number(d.qtyConsumed);
                      const share = lotInitialQty > 1e-9 ? (consumedQty / lotInitialQty) * 100 : 0;
                      const allocQty = lotDemandAllocMap.get(supExplainRow.supplyId)?.get(d.demandId) ?? 0;
                      return { d, demandRow, demandKey, groupLabel, requestedQty, consumedQty, share, allocQty };
                    });
                    // Filter
                    const dfLower = peggedDemandFilter.trim().toLowerCase();
                    const cfLower = peggedCustomerFilter.trim().toLowerCase();
                    const filtered = enriched.filter((r) =>
                      (!dfLower || r.d.demandId.toLowerCase().includes(dfLower)) &&
                      (!cfLower || (r.d.customer ?? '').toLowerCase().includes(cfLower))
                    );
                    // Sort
                    const sorted = peggedSort ? [...filtered].sort((a, b) => {
                      const dir = peggedSort.dir === 'asc' ? 1 : -1;
                      switch (peggedSort.key) {
                        case 'demand':   return dir * a.d.demandId.localeCompare(b.d.demandId);
                        case 'customer': return dir * (a.d.customer ?? '').localeCompare(b.d.customer ?? '');
                        case 'requested': return dir * ((a.requestedQty ?? -1) - (b.requestedQty ?? -1));
                        case 'allocated': return dir * (a.allocQty - b.allocQty);
                        case 'consumed': return dir * (a.consumedQty - b.consumedQty);
                        case 'share':    return dir * (a.share - b.share);
                        default: return 0;
                      }
                    }) : filtered;
                    // Footer sums (over filtered rows only)
                    const sumRequested = filtered.reduce((s, r) => s + (r.requestedQty ?? 0), 0);
                    const sumAllocated = filtered.reduce((s, r) => s + r.allocQty, 0);
                    const sumConsumed  = filtered.reduce((s, r) => s + r.consumedQty, 0);
                    const sortIndicator = (key: typeof peggedSort extends null ? never : NonNullable<typeof peggedSort>['key']) => {
                      if (!peggedSort || peggedSort.key !== key) return <span style={{ color: '#52525b', marginLeft: 2 }}>⇅</span>;
                      return <span style={{ color: '#a78bfa', marginLeft: 2 }}>{peggedSort.dir === 'asc' ? '↑' : '↓'}</span>;
                    };
                    const toggleSort = (key: NonNullable<typeof peggedSort>['key']) => {
                      setPeggedSort((prev) =>
                        prev?.key === key
                          ? { key, dir: prev.dir === 'asc' ? 'desc' : 'asc' }
                          : { key, dir: 'asc' }
                      );
                    };
                    const thBtn: React.CSSProperties = { background: 'none', border: 'none', cursor: 'pointer', color: '#a1a1aa', fontSize: '0.78rem', padding: 0, fontWeight: 600 };
                    return (
                      <>
                        <p style={{ margin: '0 0 0.5rem', fontSize: '0.875rem' }}>
                          <strong>{supExplainRow.peggedDemandCount}</strong> {tP('supExplain.peggedConsumedSuffix')}
                          ({' '}{tP('supExplain.peggedTotal')} <strong>{qtyFmt(Number(supExplainRow.totalPeggedQty))}</strong>{' '}):
                        </p>
                        {/* Filters */}
                        <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.4rem' }}>
                          <input
                            type="text"
                            placeholder="Filter demand…"
                            value={peggedDemandFilter}
                            onChange={(e) => setPeggedDemandFilter(e.target.value)}
                            style={{ flex: 1, fontSize: '0.75rem', padding: '2px 6px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 3, minWidth: 0 }}
                          />
                          <input
                            type="text"
                            placeholder="Filter customer…"
                            value={peggedCustomerFilter}
                            onChange={(e) => setPeggedCustomerFilter(e.target.value)}
                            style={{ flex: 1, fontSize: '0.75rem', padding: '2px 6px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 3, minWidth: 0 }}
                          />
                        </div>
                        <table style={{ width: '100%', fontSize: '0.78rem', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
                          <colgroup>
                            <col style={{ width: '22%' }} />
                            <col style={{ width: '18%' }} />
                            <col style={{ width: '13%' }} />
                            <col style={{ width: '11%' }} />
                            <col style={{ width: '12%' }} />
                            <col style={{ width: '12%' }} />
                            <col style={{ width: '12%' }} />
                          </colgroup>
                          <thead>
                            <tr style={{ textAlign: 'left' }}>
                              <th style={{ paddingBottom: '0.2rem' }}><button type="button" style={thBtn} onClick={() => toggleSort('demand')}>{tP('supExplain.peggedColDemand')}{sortIndicator('demand')}</button></th>
                              <th style={{ paddingBottom: '0.2rem' }}><button type="button" style={thBtn} onClick={() => toggleSort('customer')}>{tP('supExplain.peggedColCustomer')}{sortIndicator('customer')}</button></th>
                              <th style={{ paddingBottom: '0.2rem', color: '#a1a1aa' }}>{tP('supExplain.peggedColPath')}</th>
                              <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}><button type="button" style={{ ...thBtn, width: '100%', textAlign: 'right' }} onClick={() => toggleSort('requested')}>{tP('supExplain.peggedColRequested')}{sortIndicator('requested')}</button></th>
                              <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}><button type="button" style={{ ...thBtn, width: '100%', textAlign: 'right' }} onClick={() => toggleSort('allocated')}>{tP('supExplain.peggedColAllocated')}{sortIndicator('allocated')}</button></th>
                              <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}><button type="button" style={{ ...thBtn, width: '100%', textAlign: 'right' }} onClick={() => toggleSort('consumed')}>{tP('supExplain.peggedColQty')}{sortIndicator('consumed')}</button></th>
                              <th style={{ paddingBottom: '0.2rem', textAlign: 'right' }}><button type="button" style={{ ...thBtn, width: '100%', textAlign: 'right' }} onClick={() => toggleSort('share')}>{tP('supExplain.peggedColShare')}{sortIndicator('share')}</button></th>
                            </tr>
                          </thead>
                          <tbody>
                            {sorted.map(({ d, demandRow, demandKey, groupLabel, requestedQty, consumedQty, share, allocQty }, i) => (
                              <tr key={`${d.demandId}-${i}`} style={{ borderTop: '1px solid #3d3d40' }}>
                                <td style={{ padding: '0.2rem 0.4rem 0.2rem 0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                  {demandRow && demandKey ? (
                                    <button
                                      type="button"
                                      className="secondary"
                                      style={{ fontSize: '0.74rem', padding: '1px 6px', fontFamily: 'monospace' }}
                                      title={tP('supExplain.openDemandPegging')}
                                      onClick={() => {
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
                                    <span style={{ color: '#a1a1aa', fontStyle: 'italic' }} title="Direct main-loop / passthrough consumption (no consolidation split)">{tP('supExplain.peggedPathDirect')}</span>
                                  )}
                                </td>
                                <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right', color: '#a1a1aa' }}>{requestedQty != null ? qtyFmt(requestedQty) : '–'}</td>
                                <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right', color: '#c4b5fd' }}>{allocQty > 0 ? qtyFmt(allocQty) : '–'}</td>
                                <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right' }}>{qtyFmt(consumedQty)}</td>
                                <td style={{ padding: '0.2rem 0 0.2rem 0.4rem', textAlign: 'right', color: '#a1a1aa' }}>{share.toFixed(1)}%</td>
                              </tr>
                            ))}
                          </tbody>
                          <tfoot>
                            <tr style={{ borderTop: '2px solid #52525b', color: '#e4e4e7', fontWeight: 600 }}>
                              <td style={{ padding: '0.25rem 0.4rem 0.1rem 0' }} colSpan={3}>
                                {filtered.length < enriched.length
                                  ? <span style={{ fontSize: '0.74rem', color: '#a1a1aa' }}>{filtered.length} / {enriched.length}</span>
                                  : <span style={{ fontSize: '0.74rem', color: '#71717a' }}>{enriched.length} rows</span>}
                              </td>
                              <td style={{ padding: '0.25rem 0 0.1rem 0.4rem', textAlign: 'right' }}>{sumRequested > 0 ? qtyFmt(sumRequested) : '–'}</td>
                              <td style={{ padding: '0.25rem 0 0.1rem 0.4rem', textAlign: 'right', color: '#c4b5fd' }}>{sumAllocated > 0 ? qtyFmt(sumAllocated) : '–'}</td>
                              <td style={{ padding: '0.25rem 0 0.1rem 0.4rem', textAlign: 'right' }}>{qtyFmt(sumConsumed)}</td>
                              <td />
                            </tr>
                          </tfoot>
                        </table>
                        <p style={{ margin: '0.4rem 0 0', fontSize: '0.75rem', color: '#71717a', lineHeight: 1.5 }}>
                          {tP('supExplain.peggedShareNote')}
                        </p>
                      </>
                    );
                  })()
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
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button
                    type="button"
                    onClick={() => {
                      if (copilotMessages.length > 0 && !window.confirm(tP('copilot.clearConfirm'))) return;
                      setCopilotMessages([]);
                      setCopilotPendingJobId(null);
                      setCopilotActiveJob(null);
                      setCopilotInput('');
                    }}
                    disabled={copilotMessages.length === 0 && !copilotInput && !copilotPendingJobId}
                    style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}
                  >{tP('copilot.clear')}</button>
                  <button type="button" onClick={() => setCopilotOpen(false)} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tP('copilot.close')}</button>
                </div>
              </div>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#a1a1aa' }}>
                <strong>{tP('copilot.methods')}</strong> {planningConfig.method_selection?.multiple === true
                  ? tP('copilot.equalSplit')
                  : planningConfig.method_selection?.elaborate === true
                    ? tP('copilot.oneByScoreWithDepth', { depth: planningConfig.method_selection?.depth ?? 1 })
                    : tP('copilot.oneByPreference')}.{' '}
                <strong>{tP('copilot.purchase')}</strong> {planningConfig.purchase_allowed === false ? tP('copilot.disabled') : tP('copilot.allowed')}.{' '}
                <strong>{tP('copilot.consolidation')}</strong> {planningConfig.consolidation?.enabled === true
                  ? (() => {
                      const gfb = planningConfig.consolidation.wo_batch_scale ?? 'weekly';
                      const keyMap: Record<string, string> = { none: 'woBatchNone', weekly: 'woBatchWeekly', biweekly: 'woBatchBiweekly', monthly: 'woBatchMonthly', all: 'woBatchAll' };
                      const label = (k: 'make' | 'move' | 'purchase') => {
                        const scl = (planningConfig.consolidation?.[`${k}_batch_scale`] ?? gfb) as string;
                        return tP(`config.${keyMap[scl] ?? 'woBatchWeekly'}`);
                      };
                      return tP('copilot.consolidationOnDetail', { scale: `make:${label('make')} move:${label('move')} buy:${label('purchase')}` });
                    })()
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
                  <span style={{ fontSize: '0.875rem' }}>{renderCopilotText(m.text)}</span>
                  {m.kind === 'raw_picker' && (
                    purchasableOptions.length === 0 ? (
                      <div style={{ fontSize: '0.78rem', color: '#71717a', marginTop: '0.3rem' }}>{tP('config.purchasableNone')}</div>
                    ) : (
                      <RawMaterialPicker
                        options={purchasableOptions}
                        selected={planningConfig.purchasable_materials ?? []}
                        onChange={(next) => setPlanningConfig((c) => ({ ...c, purchase_allowed: true, purchasable_materials: next }))}
                        initialFilter={m.filter}
                        tP={tP}
                      />
                    )
                  )}
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
                // `/raw [filter]` — local slash command: render the interactive
                // purchasable-raw-material picker inline (no backend round-trip).
                const rawCmd = text.match(/^\/raw(?:\s+(.*))?$/i);
                if (rawCmd) {
                  const filter = rawCmd[1]?.trim() || undefined;
                  setCopilotMessages((prev) => [
                    ...prev,
                    { role: 'user', text },
                    { role: 'assistant', kind: 'raw_picker', filter, text: tP('config.purchasablePickerHeading') },
                  ]);
                  setCopilotInput('');
                  return;
                }
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
                    purchasable_materials: 'purchasable_materials' in cu ? cu.purchasable_materials : prev.purchasable_materials,
                    constraints: 'constraints' in cu ? cu.constraints : prev.constraints,
                    consolidation: cu.consolidation ? { ...prev.consolidation, ...cu.consolidation } : prev.consolidation,
                  }));
                };
                try {
                  // Try the full agent first; fall back to copilot if it 5xxs (e.g. OPENAI key missing).
                  const res = await planningAgent(id, text, planningConfig, copilotMessages, currentPlanRunId);
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
                } catch (err) {
                  // Agent failed (timeout, 5xx, network). Surface the actual
                  // error to the user. We used to fall back to a rule-based
                  // config-intent parser here, but it misfired on legitimate
                  // domain queries (e.g. "shutdown for 7天" → matched "X天"
                  // → "set consolidation bucket to 7 days"), confusing the
                  // user. The LLM agent is the single source of truth now.
                  const msg = err instanceof Error ? err.message : String(err);
                  setCopilotMessages((prev) => [...prev, { role: 'assistant', text: msg }]);
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
            onClick={() => { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setWoPeggingRowKey(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setPreviousWoExplainRow(null); setPreviousManifestWoRow(null); }}
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
              height: '100vh',
              overflow: 'hidden',
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
              title="Drag to resize"
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
                width: 8,
                cursor: 'col-resize',
                zIndex: 11,
                background: planPeggingResizing ? 'rgba(56, 189, 248, 0.45)' : 'rgba(255, 255, 255, 0.05)',
                borderLeft: planPeggingResizing ? '1px solid #38bdf8' : '1px solid rgba(255, 255, 255, 0.08)',
                transition: planPeggingResizing ? 'none' : 'background 120ms ease, border-color 120ms ease',
              }}
              onMouseEnter={(e) => {
                if (planPeggingResizing) return;
                (e.currentTarget as HTMLDivElement).style.background = 'rgba(56, 189, 248, 0.25)';
                (e.currentTarget as HTMLDivElement).style.borderLeftColor = 'rgba(56, 189, 248, 0.6)';
              }}
              onMouseLeave={(e) => {
                if (planPeggingResizing) return;
                (e.currentTarget as HTMLDivElement).style.background = 'rgba(255, 255, 255, 0.05)';
                (e.currentTarget as HTMLDivElement).style.borderLeftColor = 'rgba(255, 255, 255, 0.08)';
              }}
            >
              {/* Subtle vertical grip dots, vertically centered, fade in on hover. */}
              <div
                style={{
                  position: 'absolute',
                  left: '50%',
                  top: '50%',
                  transform: 'translate(-50%, -50%)',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 3,
                  pointerEvents: 'none',
                  opacity: planPeggingResizing ? 1 : 0.5,
                }}
              >
                {[0, 1, 2, 3].map((i) => (
                  <span
                    key={i}
                    style={{
                      width: 2,
                      height: 2,
                      borderRadius: '50%',
                      background: planPeggingResizing ? '#bae6fd' : '#71717a',
                    }}
                  />
                ))}
              </div>
            </div>
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
                    setSupExplainRow(previousSupExplainRow);
                    setSupExplainKey(`supply|${previousSupExplainRow.supplyId}`);
                    setSupExplainOpen(true);
                    setPlanPeggingOpen(false);
                    setPlanPeggingContext(null);
                    setWoPeggingRowKey(null);
                    setPreviousSupExplainRow(null);
                    setPreviousWoExplainRow(null);
                  }}
                  style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.78rem', padding: 0, display: 'flex', alignItems: 'center', gap: '0.3rem' }}
                >
                  ← {previousSupExplainRow.supplyId}
                </button>
              </div>
            )}
            {previousManifestWoRow && (
              <div style={{ marginBottom: '0.5rem' }}>
                <button
                  type="button"
                  onClick={() => {
                    setPlanPeggingContext({ type: 'work_order', row: previousManifestWoRow });
                    setWoPeggingRowKey(`${previousManifestWoRow.demand_id ?? ''}|${previousManifestWoRow.product_id ?? ''}|${previousManifestWoRow.location_id}|${previousManifestWoRow.method ?? ''}|${previousManifestWoRow.start_time ?? ''}`);
                    setPlanWorkOrderPeggingError(null);
                    setPreviousManifestWoRow(null);
                  }}
                  style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.78rem', padding: 0, display: 'flex', alignItems: 'center', gap: '0.3rem' }}
                >
                  ← {tP('workOrders.moveManifest.backToManifest')}
                </button>
              </div>
            )}
            {previousWoExplainRow && (
              <div style={{ marginBottom: '0.5rem' }}>
                <button
                  type="button"
                  onClick={() => {
                    if (woExplainKey) {
                      // Came from WO explain panel — reopen it.
                      setWoExplainRow(previousWoExplainRow);
                      setWoExplainOpen(true);
                    }
                    // Came from Demand column click — just close pegging panel (WO table stays).
                    setPlanPeggingOpen(false);
                    setPlanPeggingContext(null);
                    setWoPeggingRowKey(null);
                    setPreviousWoExplainRow(null);
                  }}
                  style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.78rem', padding: 0, display: 'flex', alignItems: 'center', gap: '0.3rem' }}
                >
                  {(() => {
                    const p = previousWoExplainRow;
                    const pid = p.product_id ?? p.move_components?.map((c) => c.product_id).join('+') ?? '–';
                    return `← ${pid} @ ${p.location_id ?? '–'}`;
                  })()}
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
              <button type="button" onClick={() => { setPlanPeggingOpen(false); setPlanPeggingContext(null); setPlanWorkOrderPeggingError(null); setPreviousPeggingContext(null); setPreviousSupExplainRow(null); setPreviousWoExplainRow(null); setPreviousManifestWoRow(null); }} style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}>{tc('close')}</button>
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
            {planPeggingContext.type !== 'supply' && (
              <p style={{ margin: 0, marginBottom: '0.5rem', fontSize: '0.75rem', color: '#a1a1aa' }}>
                {tP('peggingPanel.legendShortageOrigins')}
              </p>
            )}
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
              const row = planPeggingContext.row as WorkOrder;
              if (row.demand_id != null) return null;
              // Merge split_details (has per-demand qty) with consolidated_demand_ids
              // (populated for virtual-demand WOs where split_details may be empty).
              const splitDetails = (row.wo_consolidation_split_details ?? [])
                .filter((d) => !!d.demand_id && d.demand_id !== '') as Array<{ demand_id: string; allocated_qty: number }>;
              const splitIds = new Set(splitDetails.map((d) => d.demand_id));
              const allDemandIds = [
                ...splitDetails.map((d) => d.demand_id),
                ...(row.consolidated_demand_ids ?? []).filter((d) => !!d && d !== '' && !splitIds.has(d)),
              ];
              if (allDemandIds.length === 0) return null;
              // Move WOs: show manifest (demand × component × qty).
              // Synthesize move_components for singleton rows from old plans that predate backend fix.
              const effectiveMoveComponents = row.move_components ?? (
                row.method === 'move' && row.product_id
                  ? [{ product_id: row.product_id, quantity: row.quantity, demand_ids: row.demand_id ? [row.demand_id] : allDemandIds }]
                  : null
              );
              if ((effectiveMoveComponents?.length ?? 0) >= 1) {
                const comps = effectiveMoveComponents!;
                const manifestRows: { demand: string; comp: string; qty: number }[] = [];
                for (const c of comps) {
                  const demands = (c.demand_ids ?? []).filter(Boolean);
                  if (demands.length === 0) {
                    manifestRows.push({ demand: '–', comp: c.product_id, qty: Number(c.quantity) });
                  } else {
                    for (const d of demands) {
                      manifestRows.push({ demand: d, comp: c.product_id, qty: Number(c.quantity) });
                    }
                  }
                }
                // Apply sort
                const dir = manifestSortDir === 'asc' ? 1 : -1;
                manifestRows.sort((a, b) => {
                  if (manifestSortCol === 'qty') return dir * (a.qty - b.qty);
                  if (manifestSortCol === 'comp') return dir * (a.comp.localeCompare(b.comp) || a.demand.localeCompare(b.demand));
                  return dir * (a.demand.localeCompare(b.demand) || a.comp.localeCompare(b.comp));
                });
                // Footer aggregates — use raw comps (not expanded rows) to avoid double-counting qty
                const distinctDemands = new Set(manifestRows.map((r2) => r2.demand)).size;
                const distinctComps = new Set(comps.map((c) => c.product_id)).size;
                const totalQty = comps.reduce((s, c) => s + Number(c.quantity), 0);
                const thStyle = (col: typeof manifestSortCol, align: 'left' | 'right' = 'left'): React.CSSProperties => ({
                  textAlign: align, padding: '5px 14px 5px 0', fontWeight: 500, cursor: 'pointer',
                  userSelect: 'none', color: manifestSortCol === col ? '#e4e4e7' : '#71717a',
                  ...(align === 'right' ? { paddingRight: 0 } : {}),
                });
                const sortIcon = (col: typeof manifestSortCol) =>
                  manifestSortCol === col ? (manifestSortDir === 'asc' ? ' ▲' : ' ▼') : '';
                const toggleSort = (col: typeof manifestSortCol) => {
                  if (manifestSortCol === col) setManifestSortDir((d) => d === 'asc' ? 'desc' : 'asc');
                  else { setManifestSortCol(col); setManifestSortDir('asc'); }
                };
                return (
                  <div style={{ flex: 1, overflow: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                      <thead>
                        <tr style={{ borderBottom: '2px solid #3f3f46', position: 'sticky', top: 0, background: '#1c1c1e' }}>
                          <th style={thStyle('demand')} onClick={() => toggleSort('demand')}>{tP('workOrders.moveManifest.demand')}{sortIcon('demand')}</th>
                          <th style={thStyle('comp')} onClick={() => toggleSort('comp')}>{tP('workOrders.moveManifest.component')}{sortIcon('comp')}</th>
                          <th style={{ ...thStyle('qty', 'right'), paddingRight: 0 }} onClick={() => toggleSort('qty')}>{tP('workOrders.moveManifest.qty')}{sortIcon('qty')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {manifestRows.map((r2, i) => {
                          const demandRow = r2.demand !== '–'
                            ? (planResult?.committed_demands.find((d) => d.demand_id === r2.demand) ?? null)
                            : null;
                          return (
                            <tr key={i} style={{ borderBottom: '1px solid #27272a' }}>
                              <td style={{ padding: '5px 14px 5px 0', wordBreak: 'break-all', fontSize: '0.8rem' }}>
                                {demandRow ? (
                                  <button
                                    type="button"
                                    style={{ background: 'none', border: 'none', padding: 0, color: '#60a5fa', cursor: 'pointer', textDecoration: 'underline', fontSize: 'inherit', textAlign: 'left', wordBreak: 'break-all' }}
                                    onClick={() => {
                                      setPreviousManifestWoRow(row);
                                      setPlanPeggingContext({ type: 'demand', row: demandRow });
                                      setWoPeggingRowKey(`demand|${r2.demand}|${demandRow.product_id ?? ''}|${demandRow.location_id ?? ''}`);
                                      setPlanWorkOrderPeggingError(null);
                                    }}
                                  >{r2.demand}</button>
                                ) : (
                                  <span style={{ color: '#a1a1aa' }}>{r2.demand}</span>
                                )}
                              </td>
                              <td style={{ padding: '5px 14px 5px 0', color: '#e4e4e7', fontFamily: 'monospace', fontSize: '0.8rem' }}>{r2.comp}</td>
                              <td style={{ padding: '5px 0', textAlign: 'right', color: '#fafafa', fontVariantNumeric: 'tabular-nums' }}>{qtyFmt(r2.qty)}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                      <tfoot>
                        <tr style={{ borderTop: '2px solid #3f3f46', color: '#a1a1aa', fontSize: '0.78rem' }}>
                          <td style={{ padding: '5px 14px 5px 0' }}>{distinctDemands} {tP('workOrders.moveManifest.footerDemands')}</td>
                          <td style={{ padding: '5px 14px 5px 0' }}>{distinctComps} {tP('workOrders.moveManifest.footerComponents')}</td>
                          <td style={{ padding: '5px 0', textAlign: 'right', color: '#fafafa', fontVariantNumeric: 'tabular-nums' }}>{qtyFmt(totalQty)}</td>
                        </tr>
                      </tfoot>
                    </table>
                  </div>
                );
              }
              // Single-product consolidated WO: accordion — one section per logical WO
              const product_id = String(row.product_id ?? '').trim();
              const location_id = String(row.location_id ?? '').trim();
              const method = String(row.method ?? '').trim();
              return (
                <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
                  <div style={{ fontSize: '0.75rem', color: '#a1a1aa', marginBottom: 6 }}>
                    Physical WO fulfilling {allDemandIds.length} logical WO{allDemandIds.length !== 1 ? 's' : ''}:
                  </div>
                  {allDemandIds.map((did) => {
                    const qty = splitDetails.find((d) => d.demand_id === did)?.allocated_qty ?? null;
                    const nativeWo = (planResult?.work_orders_native ?? [])
                      .filter((w) => w.consolidated_group_id === row.wo_group_id && w.demand_id === did)
                      .sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''))[0];
                    const start_time = nativeWo?.start_time ?? undefined;
                    const cacheKey = `${did}|${product_id}|${location_id}|${method}|${start_time ?? ''}`;
                    const tree = planWorkOrderPeggingCache[cacheKey] ?? null;
                    const isOpen = woConsolidatedOpenSections.has(did);
                    const sectionExpanded = woConsolidatedExpanded[cacheKey] ?? new Set(['0']);
                    return (
                      <div key={did} style={{ borderTop: '1px solid #3d3d40' }}>
                        <button type="button"
                          onClick={() => setWoConsolidatedOpenSections((prev) => {
                            const next = new Set(prev);
                            if (next.has(did)) next.delete(did); else next.add(did);
                            return next;
                          })}
                          style={{ width: '100%', textAlign: 'left', background: 'none', border: 'none', padding: '6px 2px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, color: 'inherit' }}
                        >
                          <span style={{ color: '#a1a1aa', fontSize: '0.7rem', flexShrink: 0 }}>{isOpen ? '▾' : '▸'}</span>
                          <span style={{ color: '#60a5fa', fontSize: '0.8rem', fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{did}</span>
                          {qty != null && <span style={{ color: '#a78bfa', fontSize: '0.75rem', flexShrink: 0, marginLeft: 'auto' }}>{qtyFmt(qty)}</span>}
                        </button>
                        {isOpen && (
                          <div style={{ paddingLeft: 8, paddingBottom: 8 }}>
                            {!tree ? (
                              <p style={{ color: '#a1a1aa', fontSize: '0.82rem', margin: '4px 0' }}>
                                {woConsolidatedFetchingRef.current.has(cacheKey) ? 'Loading…' : 'No pegging tree.'}
                              </p>
                            ) : (
                              <PlanningPeggingTreeView
                                tree={tree}
                                expanded={sectionExpanded}
                                onToggle={(path) => setWoConsolidatedExpanded((prev) => {
                                  const cur = prev[cacheKey] ?? new Set(['0']);
                                  const next = new Set(cur);
                                  if (next.has(path)) next.delete(path); else next.add(path);
                                  return { ...prev, [cacheKey]: next };
                                })}
                                contextDemandId={did}
                                hideLotCount={true}
                                consolidatedSourceResolver={(embeddedDemandId, pid) => {
                                  const allEntries = (planResult?.planning_pegging ?? []).filter(
                                    (e) => String(e.demand_id ?? '').trim() === embeddedDemandId
                                  );
                                  return allEntries.slice(0, -1).map((e) => e.tree)
                                    .filter((t): t is PlanningPeggingNode => t != null && t.product_id === pid);
                                }}
                              />
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
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
              // All null-demand consolidated WOs (single or multi-demand) are handled by the accordion above
              if (planPeggingContext.type === 'work_order') {
                const _row = planPeggingContext.row as WorkOrder;
                if (_row.demand_id == null && (_row.consolidated_demand_ids?.length ?? 0) >= 1) return null;
              }
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
                  const cacheState = demandPeggingCache[demandIdNorm];
                  if (cacheState === 'loading') return <p style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>Loading pegging tree…</p>;
                  if (cacheState === 'error') return <p style={{ color: '#f87171', fontSize: '0.9rem' }}>Failed to load pegging tree for this demand.</p>;
                  return <p style={{ color: '#a1a1aa', fontSize: '0.9rem' }}>Loading pegging tree…</p>;
                }
              }

              // Critical path = the dominator SUB-TREE of the pegging tree.
              //   - AND junction (work_order parents): single AND-min child.
              //     Planner pre-flags it via is_bottleneck / is_root_bottleneck;
              //     break ties by smallest committed_qty/quantity ratio, then
              //     tree order. If no direct child is flagged but a descendant
              //     is, descend through the transit child with smallest ratio
              //     (method WO between BOM levels carries no flag).
              //   - OR junction (demand parents, alternative paths): every
              //     contributing child (qty>0 OR committed_qty>0) is a
              //     dominator. The path BRANCHES.
              // Mirrors the backend `traceCriticalPath` Kotlin helper exactly.
              const criticalPathSet = new Set<string>();
              const hasFlaggedDescendant = (n: PlanningPeggingNode): boolean => {
                if (n.is_bottleneck || n.is_root_bottleneck) return true;
                return (n.children ?? []).some(hasFlaggedDescendant);
              };
              const ratio = (c: PlanningPeggingNode): number => {
                const q = Number(c.quantity ?? 0);
                const cq = Number((c as { committed_qty?: number | null }).committed_qty ?? q);
                return q < 1e-9 ? 0 : cq / q;
              };
              const contributed = (c: PlanningPeggingNode): boolean => {
                const q = Number(c.quantity ?? 0);
                const cq = Number((c as { committed_qty?: number | null }).committed_qty ?? q);
                return q > 1e-9 || cq > 1e-9;
              };
              const relationOf = (n: PlanningPeggingNode): 'and' | 'or' => {
                const explicit = (n as { children_relation?: string | null }).children_relation;
                if (explicit === 'and' || explicit === 'or') return explicit;
                return n.type === 'work_order' ? 'and' : 'or';
              };
              const buildCriticalPath = (n: PlanningPeggingNode | null, path: string): void => {
                if (!n) return;
                // Consumer-attribution nodes (which demand draws from a consolidated PO) are not
                // part of the demand→source supply chain, so they don't belong on the critical path.
                if ((n as { consolidated_consumer?: boolean }).consolidated_consumer) return;
                criticalPathSet.add(path);
                const kids = n.children ?? [];
                if (kids.length === 0) return;
                // Universal rule: exclude children (and subtrees) with 0
                // contribution. Critical path traces actual flow.
                const contributingKids = kids
                  .map((c, i) => ({ c, i }))
                  .filter(({ c }) => contributed(c));
                if (contributingKids.length === 0) return;
                if (relationOf(n) === 'or') {
                  contributingKids.forEach(({ c, i }) => buildCriticalPath(c, `${path}-${i}`));
                  return;
                }
                // AND: single dominator.
                const flagged = contributingKids.filter(({ c }) => c.is_bottleneck || c.is_root_bottleneck);
                let pick: { c: PlanningPeggingNode; i: number } | null = null;
                if (flagged.length > 0) {
                  flagged.sort((a, b) => {
                    const ra = ratio(a.c);
                    const rb = ratio(b.c);
                    if (Math.abs(ra - rb) > 1e-9) return ra - rb;
                    return a.i - b.i;
                  });
                  pick = flagged[0];
                } else {
                  const transit = contributingKids.filter(({ c }) => hasFlaggedDescendant(c));
                  if (transit.length === 0) return;
                  transit.sort((a, b) => {
                    const ra = ratio(a.c);
                    const rb = ratio(b.c);
                    if (Math.abs(ra - rb) > 1e-9) return ra - rb;
                    return a.i - b.i;
                  });
                  pick = transit[0];
                }
                buildCriticalPath(pick.c, `${path}-${pick.i}`);
              };
              buildCriticalPath(tree, '0');

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
                  if (n.type === 'demand') {
                    return [n.product_id, n.location_id].some(
                      (f) => typeof f === 'string' && f.toLowerCase().includes(q)
                    );
                  }
                  if (n.type === 'supply') {
                    const pid = n.product_id;
                    const sid = n.supply_id;
                    if (typeof pid !== 'string' || typeof sid !== 'string') return false;
                    // Standard lot supply_id = "pid_loc_lot" — sid starts with pid+"_".
                    // These are detail nodes; the parent demand already covers this product
                    // occurrence. Only consolidated/named supplies (e.g.
                    // "consolidated_260-0141-02_2000") add a distinct occurrence.
                    if (sid.toLowerCase().startsWith(pid.toLowerCase() + '_')) return false;
                    return pid.toLowerCase().includes(q) || sid.toLowerCase().includes(q);
                  }
                  // work_order, purchase, operation, resource:
                  // match only on location and method — NOT product_id (handled by demand
                  // branch above) and NOT demand_id (it may embed the product_id string).
                  return [n.location_id, n.method].some(
                    (f) => typeof f === 'string' && f.toLowerCase().includes(q)
                  );
                };
                // Mirror PlanningPeggingTreeView's child filtering so paths stay in sync.
                const childContrib = (c: PlanningPeggingNode): number => {
                  const cc = (c as { committed_qty?: number | null }).committed_qty;
                  return Number((cc != null ? cc : c.quantity) ?? 0);
                };
                const visibleChildren = (n: PlanningPeggingNode): PlanningPeggingNode[] => {
                  const raw = n.children ?? [];
                  const isLegacyBlockedWo = n.type === 'work_order'
                    && !n.failed
                    && Number(n.quantity ?? 0) <= 1e-9
                    && raw.length > 0;
                  if (isLegacyBlockedWo) return [];
                  if (n.children_relation === 'or' && raw.length > 1) {
                    const contribCount = raw.filter(c => childContrib(c) > 1e-9).length;
                    if (contribCount > 0 && contribCount < raw.length)
                      return raw.filter(c => childContrib(c) > 1e-9);
                  }
                  return raw;
                };
                const walk = (n: PlanningPeggingNode, path: string, chain: string[]): void => {
                  const nextChain = [...chain, path];
                  if (nodeMatches(n)) {
                    matches.push(path);
                    chain.forEach((p) => ancestors.add(p));
                  }
                  visibleChildren(n).forEach((c, i) => walk(c, `${path}-${i}`, nextChain));
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
                  <div style={{ flex: 1, overflow: 'auto', minHeight: 0, marginTop: '0.5rem' }}>
                    {tree ? (
                      <PlanningPeggingTreeView
                        tree={tree}
                        expanded={planPeggingExpanded}
                        onToggle={(p) => setPlanPeggingExpanded((prev) => {
                          const next = new Set(prev);
                          if (next.has(p)) next.delete(p); else next.add(p);
                          return next;
                        })}
                        matchPath={planPeggingMatchPath}
                        matchPaths={planPeggingMatchPaths}
                        criticalPathSet={criticalPathSet}
                        explanationExpanded={planExplanationExpanded}
                        onToggleExplanation={(p) => setPlanExplanationExpanded((prev) => {
                          const next = new Set(prev);
                          if (next.has(p)) next.delete(p); else next.add(p);
                          return next;
                        })}
                        workOrderRootQty={planPeggingContext?.type === 'work_order'
                          ? Number((planPeggingContext.row as WorkOrder).quantity ?? 0)
                          : null}
                        contextDemandId={contextDemandId}
                        consolidatedSourceResolver={(embeddedDemandId, pid) => {
                          const allEntries = (planResult?.planning_pegging ?? []).filter(
                            (e) => String(e.demand_id ?? '').trim() === embeddedDemandId
                          );
                          return allEntries
                            .slice(0, -1)
                            .map((e) => e.tree)
                            .filter((t): t is PlanningPeggingNode => t != null && t.product_id === pid);
                        }}
                      />
                    ) : null}
                  </div>
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
                selectedKey={peggingSelectedPathKey}
                onSelect={(pathKey, nodeId) => {
                  setPeggingSelectedPathKey(pathKey);
                  // Node id is "product|location" or "product|location|period";
                  // parse the first two segments to drive the WO-row match in
                  // woRowStyle below. Period-bearing ids (inventory nodes)
                  // still map back to the same product@location.
                  const parts = nodeId.split('|');
                  const product = parts[0] ?? '';
                  const location = parts[1] ?? '';
                  if (product && location) {
                    setPeggingSelectedProductLoc({ product, location });
                  } else {
                    setPeggingSelectedProductLoc(null);
                  }
                }}
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
