'use client';

import React, { useCallback, useMemo, useState } from 'react';
import type { AllocationRow, CaseDemandRow, CaseSupplyRow } from '@/lib/api';

export type SupplyPivot = 'lot' | 'product-location';

/** 'untargeted' is a synthetic column (target = null) alongside real customer columns — see
 *  this component's own doc for why it exists as a first-class column, not just an empty state. */
export type ColSpec =
  | { type: 'untargeted' }
  | { type: 'cust'; custId: string }
  | { type: 'demand'; demandId: string; custId: string };

export type SupplyGroupKey = string;

export function colSpecKey(s: ColSpec): string {
  if (s.type === 'untargeted') return 'u';
  return s.type === 'cust' ? `c:${s.custId}` : `d:${s.demandId}`;
}
export function fmtQty(q: number): string {
  if (q === 0) return '—';
  return q >= 1000
    ? q.toLocaleString(undefined, { maximumFractionDigits: 0 })
    : q.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/**
 * The unified Targeted Supply Allocation (TSA) table — one grid that's simultaneously the TSA
 * INPUT editor and the recomputed per-demand OUTPUT preview, instead of two separate views. Row
 * axis: supply grouped by (product, location), expandable to individual lots — spanning BOTH raw
 * critical-material lots (in [editableSupplyIds]) AND critical-STOCK lots (on-hand inventory of
 * an otherwise-elastic product that only inherits targeting from its mandatory raw material) in
 * the SAME nested tree, so a stock position's derived numbers sit visibly under its own
 * (product, location) group rather than a separate table.
 *
 * Column axis: a synthetic "Untargeted" column (target = null) plus one column per customer,
 * each expandable to its individual demands.
 *
 * Editable cells are the intersection of (an individual RAW lot row) × (Untargeted or a
 * collapsed customer column, never a drilled-in demand column) — see [onEditCell]'s own doc.
 * Every other cell is read-only, computed one of three ways: a (product, location) GROUP row's
 * aggregate across its lots; a drilled-in DEMAND column's finer split of a customer's total
 * (however coarse or fine, always the recomputed buildSupplyAllocation output, never something a
 * user set directly); or a critical-STOCK lot's entire row, whose numbers are propagated
 * entirely from its dependent raw material's own allocation. A cell can combine more than one of
 * these (e.g. a stock lot's own customer-column cell after that customer's demands expand).
 */
export function TsaTable({
  rows,
  supplies,
  demands,
  supplyIds,
  editableSupplyIds,
  getQtyCap,
  getTarget,
  isPending,
  onEditCell,
  t,
}: {
  /** Recomputed (supply_lot, demand) -> qty_allocated preview rows — the OUTPUT every read-only
   *  cell in this table renders. */
  rows: AllocationRow[];
  supplies: CaseSupplyRow[];
  demands: CaseDemandRow[];
  /** The full row universe: every critical-material supply_id (raw ∪ stock), independent of
   *  whether it currently has any competing demand in [rows] — a raw lot with zero draws must
   *  still appear as an editable row, not silently disappear. */
  supplyIds: string[];
  /** Subset of [supplyIds] that are genuinely raw critical-material lots — the only rows with any
   *  editable cell at all. Omit (or pass an empty set with no [onEditCell]) for a read-only render. */
  editableSupplyIds?: Set<string>;
  /** Effective (pending-edit-aware) TSA override lookups — null means "no override for this
   *  field," displayed as this lot's own physical qty/target (see [onEditCell]'s doc). Required
   *  when [onEditCell] is present. */
  getQtyCap?: (supplyId: string) => number | null;
  getTarget?: (supplyId: string) => string | null;
  /** Whether [supplyId] has an UNSAVED pending edit (distinct from merely having a saved
   *  qty_cap/target at all) — drives the "unsaved" cell highlight. Omit to never highlight. */
  isPending?: (supplyId: string) => boolean;
  /**
   * Presence of this callback is what makes the table editable — omit for read-only. Called when
   * the user commits a value into an editable (raw lot, Untargeted-or-customer) cell, with
   * `customerId` null for the Untargeted column. Since a lot has at most ONE target (see TSA's
   * own doc — confirmed single-target-per-lot, not a per-customer multi-cap matrix), committing
   * ANY cell in a raw lot's row MOVES its target to that column — the previously-active column's
   * cell reverts to blank/zero. Editing the row's own currently-active cell just changes qty_cap,
   * leaving target unchanged.
   */
  onEditCell?: (supplyId: string, customerId: string | null, newQtyCap: number | null) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}) {
  const readOnly = !onEditCell;
  const editableIds = editableSupplyIds ?? new Set<string>();

  const [supplyPivot, setSupplyPivot] = useState<SupplyPivot>('product-location');
  const [expandedSupplyGroups, setExpandedSupplyGroups] = useState<Set<string>>(new Set());
  const [expandedCustomers, setExpandedCustomers] = useState<Set<string>>(new Set());
  const [editingCell, setEditingCell] = useState<{ supplyId: string; spec: ColSpec } | null>(null);
  const [editValue, setEditValue] = useState('');

  // ── Derived maps ──────────────────────────────────────────────────────────

  const supplyMeta = useMemo(() => new Map(supplies.map((s) => [s.supplyId, s])), [supplies]);
  const demandMeta = useMemo(() => new Map(demands.map((d) => [d.demand_id, d])), [demands]);

  const supplyGroupKey = useCallback((id: string): SupplyGroupKey => {
    const s = supplyMeta.get(id);
    return s ? `${s.productId}|${s.locationId ?? ''}` : id;
  }, [supplyMeta]);

  /** Chronological sort key for a lot's supply_date — NOT the same as sorting by supply_id
   *  text, which embeds the date unpadded (e.g. "8/8/2026") and so sorts "8/22" before "8/8".
   *  Non-date values (e.g. "wip") and missing dates sort last, stably by supply_id. */
  const supplyDateMs = useCallback((id: string): number => {
    const raw = supplyMeta.get(id)?.supplyDate;
    if (!raw) return Number.POSITIVE_INFINITY;
    const ms = new Date(raw).getTime();
    return Number.isNaN(ms) ? Number.POSITIVE_INFINITY : ms;
  }, [supplyMeta]);

  const byDateThenId = useCallback((a: string, b: string): number => {
    const da = supplyDateMs(a), db = supplyDateMs(b);
    return da !== db ? da - db : a.localeCompare(b);
  }, [supplyDateMs]);

  const demandGroups = useMemo(() => {
    if (!demands.length) return [];
    const m = new Map<string, Set<string>>();
    for (const d of demands) {
      const cust = d.customer_id ?? t('unknownCustomer');
      if (!m.has(cust)) m.set(cust, new Set());
      m.get(cust)!.add(d.demand_id);
    }
    return Array.from(m.entries()).sort(([a], [b]) => a.localeCompare(b))
      .map(([custId, dids]) => ({ custId, demandIds: Array.from(dids).sort() }));
  }, [demands, t]);

  const colSpecs: ColSpec[] = useMemo(() => [
    { type: 'untargeted' as const },
    ...demandGroups.flatMap(({ custId, demandIds }): ColSpec[] =>
      expandedCustomers.has(custId)
        ? demandIds.map((demandId) => ({ type: 'demand' as const, demandId, custId }))
        : [{ type: 'cust' as const, custId }]
    ),
  ], [demandGroups, expandedCustomers]);

  const anyCustomerExpanded = demandGroups.some((g) => expandedCustomers.has(g.custId));

  const supplyGroups = useMemo(() => {
    if (!supplyIds.length) return [];
    const gm = new Map<SupplyGroupKey, Set<string>>();
    for (const sid of supplyIds) {
      const gk = supplyGroupKey(sid);
      if (!gm.has(gk)) gm.set(gk, new Set());
      gm.get(gk)!.add(sid);
    }
    return Array.from(gm.entries()).sort(([a], [b]) => a.localeCompare(b))
      .map(([gk, lots]) => {
        const [pid, lid] = gk.split('|');
        return { groupKey: gk, label: lid ? `${pid} @ ${lid}` : pid, lotIds: Array.from(lots).sort(byDateThenId) };
      });
  }, [supplyIds, supplyGroupKey, byDateThenId]);

  // ── Output (recomputed) cell values — from `rows`, the buildSupplyAllocation preview ────────

  const outputCellMap = useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const r of rows) {
      if (!r.demand_id) continue;
      if (!m.has(r.supply_id)) m.set(r.supply_id, new Map());
      m.get(r.supply_id)!.set(r.demand_id, r.qty_allocated);
    }
    return m;
  }, [rows]);

  const getCustomerDemandIds = useCallback((custId: string) =>
    demandGroups.find((g) => g.custId === custId)?.demandIds ?? [], [demandGroups]);

  const getOutputQty = useCallback((sid: string, spec: ColSpec): number => {
    // Untargeted is never a real output dimension: `rows` are per-lot BUDGET CEILINGS (each
    // demand's proportional share of its own need at this lot), not actual draws — by design,
    // several demands can each carry a real, legitimate ceiling from the SAME lot, and their sum
    // routinely exceeds the lot's own physical qty (see allocateCriticalSuppliesPerLot's own doc:
    // "a demand can end up with more aggregate per-lot budget than its own physical need"). So
    // "physical minus sum(qty_allocated)" is not a meaningful idle/leftover quantity — it's
    // mixing an entitlement ceiling with actual consumption, which this data doesn't contain
    // (that only exists post-commit, in a real plan run's own supply-allocation record).
    if (spec.type === 'untargeted') return 0;
    const sm = outputCellMap.get(sid);
    if (!sm) return 0;
    if (spec.type === 'demand') return sm.get(spec.demandId) ?? 0;
    return getCustomerDemandIds(spec.custId).reduce((s, did) => s + (sm.get(did) ?? 0), 0);
  }, [outputCellMap, getCustomerDemandIds]);

  // ── Input (editable) cell values — this lot's own effective TSA override ────────────────────

  const isEditableLot = useCallback((sid: string) => !readOnly && editableIds.has(sid), [readOnly, editableIds]);

  /** This lot's currently-active column: whichever customer it's targeted to (falling back to
   *  its own physical target when there's no override yet), or Untargeted when untargeted. */
  const activeColSpec = useCallback((sid: string): ColSpec => {
    const override = getTarget?.(sid) ?? null;
    const target = override ?? supplyMeta.get(sid)?.target ?? null;
    return target ? { type: 'cust', custId: target } : { type: 'untargeted' };
  }, [getTarget, supplyMeta]);

  /** Whether [spec] is this lot's own currently-active column (Untargeted-or-target customer) —
   *  the ONE cell per lot that reflects its target/cap state, a plain fact from the underlying
   *  supply data (or a TSA override, where one exists) — true regardless of whether the case
   *  supports editing it at all. Every other cell, including Untargeted/other-customer columns on
   *  the SAME row, shows the real computed output instead (see [getCellQty]). */
  const isActiveCol = useCallback((sid: string, spec: ColSpec): boolean => {
    const active = activeColSpec(sid);
    return spec.type === 'untargeted'
      ? active.type === 'untargeted'
      : spec.type === 'cust' && active.type === 'cust' && active.custId === spec.custId;
  }, [activeColSpec]);

  /** This lot's own cap/qty at its active column — a TSA override where one exists (and the case
   *  supports it), else its plain physical qty. Shown on EVERY lot's active column, editable or
   *  not: "this lot has no target, and its qty is X" is a fact about the data, not something that
   *  requires TSA support to know — only the ability to CHANGE it does (see [isEditableCell]). */
  const getInputQty = useCallback((sid: string): number => {
    const cap = getQtyCap?.(sid) ?? null;
    return cap ?? supplyMeta.get(sid)?.qty ?? 0;
  }, [getQtyCap, supplyMeta]);

  /** The active column shows this lot's own target/cap state (see [getInputQty]) — on every lot,
   *  not just editable ones. Every other cell, including Untargeted/other-customer columns on the
   *  SAME row, always shows the real computed output (a non-matching customer's true zero, or a
   *  drilled-in demand's split) — never a second, disagreeing reading of the same column. */
  const getCellQty = useCallback((sid: string, spec: ColSpec): number =>
    (spec.type !== 'demand' && isActiveCol(sid, spec)) ? getInputQty(sid) : getOutputQty(sid, spec),
    [isActiveCol, getInputQty, getOutputQty]);

  const isEditableCell = useCallback((sid: string, spec: ColSpec) =>
    isEditableLot(sid) && spec.type !== 'demand', [isEditableLot]);

  /** A GROUP row aggregates whatever its own lot rows display: for real customer/demand columns
   *  that's actual computed consumption (never a sum of caps — caps can legitimately overlap/
   *  exceed physical qty, so summing them would misrepresent "how much is really going to this
   *  customer"); for Untargeted specifically, each lot's own displayed value already IS the
   *  per-lot fact (physical/cap when untargeted, 0 when targeted — see [getCellQty]), so summing
   *  it here is exactly "how much of this group's supply currently carries no target," matching
   *  what expanding the group and reading its own rows shows. */
  const getGroupOutputQty = useCallback((gk: SupplyGroupKey, spec: ColSpec): number => {
    const g = supplyGroups.find((g) => g.groupKey === gk);
    if (!g) return 0;
    return spec.type === 'untargeted'
      ? g.lotIds.reduce((s, sid) => s + getCellQty(sid, spec), 0)
      : g.lotIds.reduce((s, sid) => s + getOutputQty(sid, spec), 0);
  }, [supplyGroups, getOutputQty, getCellQty]);

  // Untargeted is a different VIEW of the same physical qty shown under the real customer
  // columns (a lot's supply is either "shows as untargeted" or "shows as flowing to a customer"
  // — never both), not a separate pool — so it's excluded from every cross-column SUM (row
  // totals, the grand total) to avoid double-counting. Its own column total (below) still shows
  // fine on its own — that's a same-column sum, not a cross-column one.
  const summableColSpecs = useMemo(() => colSpecs.filter((s) => s.type !== 'untargeted'), [colSpecs]);

  // A lot row's own active column displays its CAP (getCellQty), not its computed output (see
  // getCellQty's own doc) — so the row's Total must sum getCellQty too, not getOutputQty, or it
  // silently disagrees with the one cell in the row a user would actually add up by eye. Group
  // rows have no such active-column special case (every cell is real computed output, per
  // getGroupOutputQty's own doc), so their total is unaffected and stays output-based.
  const getLotRowTotal = useCallback((sid: string) =>
    summableColSpecs.reduce((s, spec) => s + getCellQty(sid, spec), 0), [summableColSpecs, getCellQty]);

  const getGroupRowTotal = useCallback((gk: SupplyGroupKey) =>
    summableColSpecs.reduce((s, spec) => s + getGroupOutputQty(gk, spec), 0), [summableColSpecs, getGroupOutputQty]);

  const getColTotal = useCallback((spec: ColSpec): number => {
    if (supplyPivot === 'lot') {
      return supplies.filter((s) => supplyIds.includes(s.supplyId)).reduce((s, sup) => s + getCellQty(sup.supplyId, spec), 0);
    }
    return supplyGroups.reduce((s, g) => s + getGroupOutputQty(g.groupKey, spec), 0);
  }, [supplyPivot, supplies, supplyIds, supplyGroups, getCellQty, getGroupOutputQty]);

  // ── Dirty detection (pending edit on this lot's currently-active cell) ──────────────────────

  const isSpecDirty = useCallback((sid: string, spec: ColSpec): boolean =>
    isEditableCell(sid, spec) && !!isPending?.(sid) && isActiveCol(sid, spec),
    [isEditableCell, isPending, isActiveCol]);

  // ── Editing ───────────────────────────────────────────────────────────────

  const editSpecKey = editingCell ? colSpecKey(editingCell.spec) : null;

  const startEdit = (sid: string, spec: ColSpec) => {
    if (!isEditableCell(sid, spec)) return;
    setEditingCell({ supplyId: sid, spec });
    // Prefill with the current cap only on the row's own active column; any other column starts
    // blank — typing there MOVES the target here with a fresh value, not adds to what's shown.
    const qty = isActiveCol(sid, spec) ? getInputQty(sid) : 0;
    setEditValue(qty === 0 ? '' : String(qty));
  };

  const commitEdit = useCallback(() => {
    if (!editingCell) return;
    const { supplyId, spec } = editingCell;
    const newQty = editValue.trim() === '' ? null : (parseFloat(editValue) || 0);
    setEditingCell(null);
    const customerId = spec.type === 'cust' ? spec.custId : null;
    onEditCell?.(supplyId, customerId, newQty);
  }, [editingCell, editValue, onEditCell]);

  // ── Styles ────────────────────────────────────────────────────────────────

  const toggleBtn = (active: boolean): React.CSSProperties => ({
    padding: '0.25rem 0.6rem', fontSize: '0.75rem', borderRadius: 3, cursor: 'pointer',
    border: '1px solid #3f3f46',
    background: active ? '#27272a' : 'transparent',
    color: active ? '#e4e4e7' : '#71717a',
  });

  const cellSt = (isEdit: boolean, qty: number, dirty: boolean, editable: boolean): React.CSSProperties => ({
    padding: '0.3rem 0.5rem', textAlign: 'right', fontSize: '0.78rem',
    cursor: editable ? 'pointer' : 'default',
    minWidth: 70, whiteSpace: 'nowrap',
    color: dirty ? '#fdba74' : qty === 0 ? '#3f3f46' : editable ? '#e4e4e7' : '#a1a1aa',
    background: isEdit ? '#1e3a8a22' : dirty ? '#2c1600' : editable ? '#132030' : 'transparent',
    // Editable cells get a visible border so they read as inputs; read-only cells stay flat.
    outline: dirty ? '1px solid #78350f' : editable ? '1px solid #1e3a5f' : 'none',
    outlineOffset: (dirty || editable) ? '-1px' : undefined,
  });

  const thSt: React.CSSProperties = {
    padding: '0.3rem 0.5rem', fontSize: '0.72rem', color: '#71717a',
    textAlign: 'center', fontWeight: 500, whiteSpace: 'nowrap',
    borderBottom: '1px solid #27272a',
    position: 'sticky', top: 0, background: '#111113', zIndex: 2,
  };
  const subThSt: React.CSSProperties = { ...thSt, fontSize: '0.68rem', color: '#52525b', fontWeight: 400, top: 28, borderTop: '1px solid #1c1c1f' };
  const labelThSt: React.CSSProperties = { ...thSt, textAlign: 'left', position: 'sticky', left: 0, zIndex: 3, minWidth: 200, maxWidth: 260 };

  const labelTd = (indent = false): React.CSSProperties => ({
    padding: indent ? '0.3rem 0.5rem 0.3rem 1.5rem' : '0.3rem 0.5rem',
    fontSize: '0.78rem', color: indent ? '#a1a1aa' : '#d4d4d8',
    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 260,
    position: 'sticky', left: 0, background: '#111113',
    borderRight: '1px solid #27272a', zIndex: 1, cursor: indent ? 'default' : 'pointer',
  });
  const totalTd: React.CSSProperties = {
    padding: '0.3rem 0.5rem', textAlign: 'right', fontSize: '0.78rem',
    color: '#71717a', borderLeft: '1px solid #27272a', fontWeight: 500, whiteSpace: 'nowrap',
  };
  const rowSt = (isGroup: boolean): React.CSSProperties => ({
    background: isGroup ? '#16161a' : 'transparent', borderBottom: '1px solid #1c1c1f',
  });

  const sep = <span style={{ color: '#3f3f46', padding: '0 0.15rem' }}>|</span>;

  const EditInput = ({ onCommit, onEscape }: { onCommit: () => void; onEscape: () => void }) => (
    <input autoFocus value={editValue}
      onChange={(e) => setEditValue(e.target.value)}
      onBlur={onCommit}
      onKeyDown={(e) => { if (e.key === 'Enter') onCommit(); if (e.key === 'Escape') onEscape(); }}
      style={{ width: 68, background: '#1e3a8a', border: 'none', color: '#e4e4e7', fontSize: '0.78rem', textAlign: 'right', padding: '0 2px' }}
    />
  );

  const colLabel = (spec: ColSpec): string => {
    if (spec.type === 'untargeted') return t('untargetedHeader');
    if (spec.type === 'cust') return spec.custId.length > 16 ? spec.custId.slice(0, 14) + '…' : spec.custId;
    return spec.demandId.length > 14 ? spec.demandId.slice(0, 12) + '…' : spec.demandId;
  };

  const totalLots = supplyIds.length;
  const totalDemands = demands.length;

  const renderCell = (sid: string, spec: ColSpec) => {
    const qty = getCellQty(sid, spec);
    const k = colSpecKey(spec);
    const isEd = editingCell?.supplyId === sid && editSpecKey === k;
    const dirty = isSpecDirty(sid, spec);
    const editable = isEditableCell(sid, spec);
    return (
      <td key={k} style={cellSt(isEd, qty, dirty, editable)}
        onClick={() => !isEd && editable && startEdit(sid, spec)}>
        {isEd ? <EditInput onCommit={commitEdit} onEscape={() => setEditingCell(null)} /> : fmtQty(qty)}
      </td>
    );
  };

  return (
    <div>
      {/* Legend + pivot toggles */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.75rem', fontSize: '0.8rem', color: '#71717a' }}>
        <span>{t('lotsVsDemands', { lots: totalLots, demands: totalDemands })}</span>
        {!readOnly && (<>
          {sep}
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: '#132030', border: '1px solid #1e3a5f', display: 'inline-block' }} />
            {t('editableLegend')}
          </span>
        </>)}
        {sep}
        <span>{t('supplyLabel')}</span>
        <button style={toggleBtn(supplyPivot === 'lot')} onClick={() => setSupplyPivot('lot')}>{t('byLot')}</button>
        <button style={toggleBtn(supplyPivot === 'product-location')} onClick={() => setSupplyPivot('product-location')}>{t('byProductLocation')}</button>
      </div>

      {/* Matrix */}
      <div style={{ overflowX: 'auto', border: '1px solid #27272a', borderRadius: 6 }}>
        <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
          <thead>
            <tr>
              <th rowSpan={anyCustomerExpanded ? 2 : 1} style={labelThSt}>{t('supplyHeader')}</th>
              <th rowSpan={anyCustomerExpanded ? 2 : 1} style={thSt}>{t('untargetedHeader')}</th>
              {demandGroups.map(({ custId, demandIds }) => {
                const exp = expandedCustomers.has(custId);
                return (
                  <th key={custId}
                    colSpan={exp ? demandIds.length : 1}
                    rowSpan={(!exp && anyCustomerExpanded) ? 2 : 1}
                    style={{ ...thSt, cursor: 'pointer', borderLeft: '1px solid #27272a' }}
                    onClick={() => setExpandedCustomers((prev) => {
                      const n = new Set(prev); n.has(custId) ? n.delete(custId) : n.add(custId); return n;
                    })}
                    title={custId}>
                    <span style={{ marginRight: 3, fontSize: '0.65rem', color: '#52525b' }}>{exp ? '▾' : '▸'}</span>
                    {colLabel({ type: 'cust', custId })}
                    <span style={{ marginLeft: 4, fontSize: '0.65rem', color: '#52525b' }}>({demandIds.length})</span>
                  </th>
                );
              })}
              <th rowSpan={anyCustomerExpanded ? 2 : 1} style={{ ...thSt, borderLeft: '1px solid #27272a' }}>{t('totalHeader')}</th>
            </tr>
            {anyCustomerExpanded && (
              <tr>
                {demandGroups.flatMap(({ custId, demandIds }) =>
                  expandedCustomers.has(custId)
                    ? demandIds.map((did) => (
                        <th key={did} style={{ ...subThSt, borderLeft: '1px solid #1c1c1f' }} title={did}>
                          {colLabel({ type: 'demand', demandId: did, custId })}
                        </th>
                      ))
                    : []
                )}
              </tr>
            )}
          </thead>
          <tbody>
            {supplyPivot === 'lot' ? (
              supplies.filter((s) => supplyIds.includes(s.supplyId))
                .sort((a, b) => {
                  const ga = supplyGroupKey(a.supplyId), gb = supplyGroupKey(b.supplyId);
                  return ga !== gb ? ga.localeCompare(gb) : byDateThenId(a.supplyId, b.supplyId);
                })
                .map((s) => {
                  const total = getLotRowTotal(s.supplyId);
                  const lbl = `${s.productId} @ ${s.locationId ?? '?'}${s.supplyDate ? ' (' + s.supplyDate.slice(0, 10) + ')' : ''}`;
                  return (
                    <tr key={s.supplyId} style={rowSt(false)}>
                      <td style={labelTd()} title={lbl}>{lbl.length > 34 ? lbl.slice(0, 32) + '…' : lbl}</td>
                      {colSpecs.map((spec) => renderCell(s.supplyId, spec))}
                      <td style={totalTd}>{fmtQty(total)}</td>
                    </tr>
                  );
                })
            ) : (
              supplyGroups.map((group) => {
                // Every (product, location) group follows the same nested pattern — including a
                // single-lot group (e.g. a critical-stock position with only one on-hand row) —
                // so raw and stock rows read identically instead of stock silently flattening.
                const exp = expandedSupplyGroups.has(group.groupKey);
                const gTotal = getGroupRowTotal(group.groupKey);
                return (
                  <React.Fragment key={group.groupKey}>
                    <tr style={rowSt(true)}>
                      <td style={{ ...labelTd(), cursor: 'pointer' }} title={group.label}
                        onClick={() => setExpandedSupplyGroups((prev) => {
                          const n = new Set(prev); exp ? n.delete(group.groupKey) : n.add(group.groupKey); return n;
                        })}>
                        <span style={{ marginRight: 4, color: '#52525b', fontSize: '0.7rem' }}>{exp ? '▾' : '▸'}</span>
                        {group.label.length > 30 ? group.label.slice(0, 28) + '…' : group.label}
                        <span style={{ marginLeft: 6, color: '#52525b', fontSize: '0.7rem' }}>({group.lotIds.length})</span>
                      </td>
                      {colSpecs.map((spec) => {
                        const qty = getGroupOutputQty(group.groupKey, spec);
                        const k = colSpecKey(spec);
                        return <td key={k} style={{ ...cellSt(false, qty, false, false), fontWeight: 500 }}>{fmtQty(qty)}</td>;
                      })}
                      <td style={{ ...totalTd, fontWeight: 500 }}>{fmtQty(gTotal)}</td>
                    </tr>
                    {exp && group.lotIds.map((sid) => {
                      const sm = supplyMeta.get(sid);
                      const total = getLotRowTotal(sid);
                      const lbl = sm?.supplyDate ? sm.supplyDate.slice(0, 10) + ' · ' + sid : sid;
                      return (
                        <tr key={sid} style={rowSt(false)}>
                          <td style={labelTd(true)} title={sid}>{lbl.length > 32 ? lbl.slice(0, 30) + '…' : lbl}</td>
                          {colSpecs.map((spec) => renderCell(sid, spec))}
                          <td style={totalTd}>{fmtQty(total)}</td>
                        </tr>
                      );
                    })}
                  </React.Fragment>
                );
              })
            )}
            {/* Column totals */}
            <tr style={{ borderTop: '1px solid #3f3f46', background: '#16161a' }}>
              <td style={{ ...labelTd(), color: '#71717a', fontWeight: 500, cursor: 'default' }}>{t('totalHeader')}</td>
              {colSpecs.map((spec) => {
                const k = colSpecKey(spec);
                return <td key={k} style={{ ...totalTd, borderLeft: 'none' }}>{fmtQty(getColTotal(spec))}</td>;
              })}
              <td style={{ ...totalTd, color: '#a1a1aa' }}>
                {fmtQty(summableColSpecs.reduce((s, spec) => s + getColTotal(spec), 0))}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
