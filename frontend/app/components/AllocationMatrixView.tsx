'use client';

import React, { useCallback, useMemo, useState } from 'react';
import type { AllocationRow, CaseDemandRow, CaseSupplyRow } from '@/lib/api';

export type SupplyPivot = 'lot' | 'product-location';

export type ColSpec =
  | { type: 'cust';   custId: string }
  | { type: 'demand'; demandId: string; custId: string };

export type SupplyGroupKey = string;

/** One user action worth of changes — each entry is a single demand cell's old+new qty. */
export type UndoBatch = Array<{ key: string; oldQty: number; newQty: number }>;

export function colSpecKey(s: ColSpec): string {
  return s.type === 'cust' ? `c:${s.custId}` : `d:${s.demandId}`;
}
export function pKey(supplyId: string, demandId: string): string {
  return `${supplyId}||${demandId}`;
}
export function parsePKey(key: string): { supplyId: string; demandId: string } {
  const i = key.indexOf('||');
  return { supplyId: key.slice(0, i), demandId: key.slice(i + 2) };
}
export function fmtQty(q: number): string {
  if (q === 0) return '—';
  return q >= 1000
    ? q.toLocaleString(undefined, { maximumFractionDigits: 0 })
    : q.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/**
 * The Allocation map's pivoting supply×demand matrix — shared between the dedicated Allocation
 * page (editable) and the version-preview popup (read-only). Read-only mode is simply "no
 * `onCommitBatch`" — cells stop being clickable and never swap in an edit input; the pivot
 * toggle (by-lot / by-product-location), customer-column expand/collapse, and row/column totals
 * all work identically either way since they're pure view controls, not edits.
 *
 * Owns its own view-interaction state (pivot, expand/collapse, which cell is mid-edit) — the
 * caller only owns `pendingChanges` (for Save/undo-redo bookkeeping) and receives a computed
 * `UndoBatch` back via `onCommitBatch` when an edit is committed, rather than having to
 * reimplement the proportional-split math itself.
 */
export function AllocationMatrixView({
  rows,
  supplies,
  demands,
  pendingChanges = new Map(),
  onCommitBatch,
  t,
}: {
  rows: AllocationRow[];
  supplies: CaseSupplyRow[];
  demands: CaseDemandRow[];
  /** Uncommitted qty edits, keyed by `pKey(supplyId, demandId)`. Omit for a read-only render. */
  pendingChanges?: Map<string, number>;
  /** Presence of this callback is what makes the matrix editable — omit for read-only. Called
   *  with a computed batch of {key, oldQty, newQty} whenever a cell edit (single or grouped) is
   *  committed; the caller merges it into its own `pendingChanges`/undo-redo state. */
  onCommitBatch?: (batch: UndoBatch) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}) {
  const readOnly = !onCommitBatch;

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

  const demandGroups = useMemo(() => {
    if (!rows.length) return [];
    const m = new Map<string, Set<string>>();
    for (const r of rows) {
      const did = r.demand_id ?? '';
      const cust = demandMeta.get(did)?.customer_id ?? t('unknownCustomer');
      if (!m.has(cust)) m.set(cust, new Set());
      m.get(cust)!.add(did);
    }
    return Array.from(m.entries()).sort(([a], [b]) => a.localeCompare(b))
      .map(([custId, dids]) => ({ custId, demandIds: Array.from(dids).sort() }));
  }, [rows, demandMeta, t]);

  const colSpecs: ColSpec[] = useMemo(
    () => demandGroups.flatMap(({ custId, demandIds }): ColSpec[] =>
      expandedCustomers.has(custId)
        ? demandIds.map((demandId) => ({ type: 'demand' as const, demandId, custId }))
        : [{ type: 'cust' as const, custId }]
    ), [demandGroups, expandedCustomers]);

  const anyCustomerExpanded = demandGroups.some((g) => expandedCustomers.has(g.custId));

  const supplyGroups = useMemo(() => {
    if (!rows.length) return [];
    const gm = new Map<SupplyGroupKey, Set<string>>();
    for (const r of rows) {
      const gk = supplyGroupKey(r.supply_id);
      if (!gm.has(gk)) gm.set(gk, new Set());
      gm.get(gk)!.add(r.supply_id);
    }
    return Array.from(gm.entries()).sort(([a], [b]) => a.localeCompare(b))
      .map(([gk, lots]) => {
        const [pid, lid] = gk.split('|');
        return { groupKey: gk, label: lid ? `${pid} @ ${lid}` : pid, lotIds: Array.from(lots).sort() };
      });
  }, [rows, supplyGroupKey]);

  // ── Cell maps ─────────────────────────────────────────────────────────────

  const cellMap = useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const r of rows) {
      if (!m.has(r.supply_id)) m.set(r.supply_id, new Map());
      m.get(r.supply_id)!.set(r.demand_id ?? '', r.qty_allocated);
    }
    return m;
  }, [rows]);

  /** Committed rows overlaid with pending changes. */
  const effectiveCellMap = useMemo((): Map<string, Map<string, number>> => {
    if (!pendingChanges.size) return cellMap;
    const m = new Map<string, Map<string, number>>();
    cellMap.forEach((v, k) => m.set(k, new Map<string, number>(v)));
    pendingChanges.forEach((qty, key) => {
      const { supplyId, demandId } = parsePKey(key);
      if (!m.has(supplyId)) m.set(supplyId, new Map<string, number>());
      m.get(supplyId)!.set(demandId, qty);
    });
    return m;
  }, [cellMap, pendingChanges]);

  const getCustomerDemandIds = useCallback((custId: string) =>
    demandGroups.find((g) => g.custId === custId)?.demandIds ?? [], [demandGroups]);

  const getCellQty = useCallback((supplyId: string, spec: ColSpec): number => {
    const sm = effectiveCellMap.get(supplyId);
    if (!sm) return 0;
    if (spec.type === 'demand') return sm.get(spec.demandId) ?? 0;
    return getCustomerDemandIds(spec.custId).reduce((s, did) => s + (sm.get(did) ?? 0), 0);
  }, [effectiveCellMap, getCustomerDemandIds]);

  const getGroupCellQty = useCallback((gk: SupplyGroupKey, spec: ColSpec): number => {
    const g = supplyGroups.find((g) => g.groupKey === gk);
    return g ? g.lotIds.reduce((s, sid) => s + getCellQty(sid, spec), 0) : 0;
  }, [supplyGroups, getCellQty]);

  const getLotRowTotal = useCallback((sid: string) =>
    colSpecs.reduce((s, spec) => s + getCellQty(sid, spec), 0), [colSpecs, getCellQty]);

  const getGroupRowTotal = useCallback((gk: SupplyGroupKey) =>
    colSpecs.reduce((s, spec) => s + getGroupCellQty(gk, spec), 0), [colSpecs, getGroupCellQty]);

  const getColTotal = useCallback((spec: ColSpec): number => {
    if (supplyPivot === 'lot') {
      return supplies.filter((s) => effectiveCellMap.has(s.supplyId))
        .reduce((s, sup) => s + getCellQty(sup.supplyId, spec), 0);
    }
    return supplyGroups.reduce((s, g) => s + getGroupCellQty(g.groupKey, spec), 0);
  }, [supplyPivot, supplies, supplyGroups, getCellQty, getGroupCellQty, effectiveCellMap]);

  // ── Dirty detection ───────────────────────────────────────────────────────

  const isSpecDirty = useCallback((supplyId: string, spec: ColSpec): boolean => {
    if (spec.type === 'demand') return pendingChanges.has(pKey(supplyId, spec.demandId));
    return getCustomerDemandIds(spec.custId).some((did) => pendingChanges.has(pKey(supplyId, did)));
  }, [pendingChanges, getCustomerDemandIds]);

  const isGroupSpecDirty = useCallback((gk: SupplyGroupKey, spec: ColSpec): boolean => {
    const g = supplyGroups.find((g) => g.groupKey === gk);
    return g?.lotIds.some((sid) => isSpecDirty(sid, spec)) ?? false;
  }, [supplyGroups, isSpecDirty]);

  // ── Editing ───────────────────────────────────────────────────────────────

  const editSpecKey = editingCell ? colSpecKey(editingCell.spec) : null;

  const startEdit = (supplyId: string, spec: ColSpec, qty: number) => {
    if (readOnly) return;
    setEditingCell({ supplyId, spec });
    setEditValue(qty === 0 ? '' : String(qty));
  };

  const commitEdit = useCallback(() => {
    if (!editingCell) return;
    const { supplyId, spec } = editingCell;
    const newQty = parseFloat(editValue) || 0;
    setEditingCell(null);
    const batch: UndoBatch = [];

    if (spec.type === 'demand') {
      const oldQty = effectiveCellMap.get(supplyId)?.get(spec.demandId) ?? 0;
      if (newQty !== oldQty) batch.push({ key: pKey(supplyId, spec.demandId), oldQty, newQty });
    } else {
      const dids = getCustomerDemandIds(spec.custId);
      const sm = effectiveCellMap.get(supplyId);
      const oldTotal = dids.reduce((s, did) => s + (sm?.get(did) ?? 0), 0);
      for (const did of dids) {
        const oldQty = sm?.get(did) ?? 0;
        const nq = oldTotal > 0 ? newQty * (oldQty / oldTotal) : newQty / dids.length;
        if (nq !== oldQty) batch.push({ key: pKey(supplyId, did), oldQty, newQty: nq });
      }
    }
    if (batch.length) onCommitBatch?.(batch);
  }, [editingCell, editValue, effectiveCellMap, getCustomerDemandIds, onCommitBatch]);

  const commitGroupEdit = useCallback((gk: SupplyGroupKey, spec: ColSpec, newTotal: number) => {
    const group = supplyGroups.find((g) => g.groupKey === gk);
    if (!group) return;
    const oldGroupTotal = group.lotIds.reduce((s, sid) => s + getCellQty(sid, spec), 0);
    const batch: UndoBatch = [];

    for (const sid of group.lotIds) {
      const oldLotQty = getCellQty(sid, spec);
      const lotNew = oldGroupTotal > 0
        ? newTotal * (oldLotQty / oldGroupTotal)
        : newTotal / group.lotIds.length;

      if (spec.type === 'demand') {
        const oldQty = effectiveCellMap.get(sid)?.get(spec.demandId) ?? 0;
        if (lotNew !== oldQty) batch.push({ key: pKey(sid, spec.demandId), oldQty, newQty: lotNew });
      } else {
        const dids = getCustomerDemandIds(spec.custId);
        const sm = effectiveCellMap.get(sid);
        const oldLotCustTotal = dids.reduce((s, did) => s + (sm?.get(did) ?? 0), 0);
        for (const did of dids) {
          const oldQty = sm?.get(did) ?? 0;
          const nq = oldLotCustTotal > 0
            ? lotNew * (oldQty / oldLotCustTotal)
            : lotNew / Math.max(1, dids.length);
          if (nq !== oldQty) batch.push({ key: pKey(sid, did), oldQty, newQty: nq });
        }
      }
    }
    if (batch.length) onCommitBatch?.(batch);
  }, [supplyGroups, getCellQty, getCustomerDemandIds, effectiveCellMap, onCommitBatch]);

  // ── Styles ────────────────────────────────────────────────────────────────

  const toggleBtn = (active: boolean): React.CSSProperties => ({
    padding: '0.25rem 0.6rem', fontSize: '0.75rem', borderRadius: 3, cursor: 'pointer',
    border: '1px solid #3f3f46',
    background: active ? '#27272a' : 'transparent',
    color: active ? '#e4e4e7' : '#71717a',
  });

  const cellSt = (isEdit: boolean, qty: number, dirty: boolean): React.CSSProperties => ({
    padding: '0.3rem 0.5rem', textAlign: 'right', fontSize: '0.78rem', cursor: readOnly ? 'default' : 'pointer',
    minWidth: 70, whiteSpace: 'nowrap',
    color: dirty ? '#fdba74' : qty === 0 ? '#3f3f46' : '#e4e4e7',
    background: isEdit ? '#1e3a8a22' : dirty ? '#2c1600' : 'transparent',
    outline: dirty ? '1px solid #78350f' : 'none',
    outlineOffset: dirty ? '-1px' : undefined,
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

  const totalLots = new Set(rows.map((r) => r.supply_id)).size;
  const totalDemands = new Set(rows.map((r) => r.demand_id ?? '')).size;

  return (
    <div>
      {/* Pivot toggles */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.75rem', fontSize: '0.8rem', color: '#71717a' }}>
        <span>{t('lotsVsDemands', { lots: totalLots, demands: totalDemands })}</span>
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
                    {custId.length > 16 ? custId.slice(0, 14) + '…' : custId}
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
                          {did.length > 14 ? did.slice(0, 12) + '…' : did}
                        </th>
                      ))
                    : []
                )}
              </tr>
            )}
          </thead>
          <tbody>
            {supplyPivot === 'lot' ? (
              supplies.filter((s) => cellMap.has(s.supplyId))
                .sort((a, b) => {
                  const ga = supplyGroupKey(a.supplyId), gb = supplyGroupKey(b.supplyId);
                  return ga !== gb ? ga.localeCompare(gb) : a.supplyId.localeCompare(b.supplyId);
                })
                .map((s) => {
                  const total = getLotRowTotal(s.supplyId);
                  const lbl = `${s.productId} @ ${s.locationId ?? '?'}${s.supplyDate ? ' (' + s.supplyDate.slice(0, 10) + ')' : ''}`;
                  return (
                    <tr key={s.supplyId} style={rowSt(false)}>
                      <td style={labelTd()} title={lbl}>{lbl.length > 34 ? lbl.slice(0, 32) + '…' : lbl}</td>
                      {colSpecs.map((spec) => {
                        const qty = getCellQty(s.supplyId, spec);
                        const k = colSpecKey(spec);
                        const isEd = !readOnly && editingCell?.supplyId === s.supplyId && editSpecKey === k;
                        const dirty = isSpecDirty(s.supplyId, spec);
                        return (
                          <td key={k} style={cellSt(isEd, qty, dirty)}
                            onClick={() => !isEd && startEdit(s.supplyId, spec, qty)}>
                            {isEd ? <EditInput onCommit={commitEdit} onEscape={() => setEditingCell(null)} /> : fmtQty(qty)}
                          </td>
                        );
                      })}
                      <td style={totalTd}>{fmtQty(total)}</td>
                    </tr>
                  );
                })
            ) : (
              supplyGroups.map((group) => {
                // A group with a single lot has nothing to drill into — the nested row would
                // just repeat the group row's own numbers. Only show the expand affordance (and
                // ever render the nested row) when there's actually more than one lot to compare.
                const expandable = group.lotIds.length > 1;
                const exp = expandable && expandedSupplyGroups.has(group.groupKey);
                const gTotal = getGroupRowTotal(group.groupKey);
                return (
                  <React.Fragment key={group.groupKey}>
                    <tr style={rowSt(true)}>
                      <td style={{ ...labelTd(), cursor: expandable ? 'pointer' : 'default' }} title={group.label}
                        onClick={() => expandable && setExpandedSupplyGroups((prev) => {
                          const n = new Set(prev); exp ? n.delete(group.groupKey) : n.add(group.groupKey); return n;
                        })}>
                        {expandable && <span style={{ marginRight: 4, color: '#52525b', fontSize: '0.7rem' }}>{exp ? '▾' : '▸'}</span>}
                        {group.label.length > 30 ? group.label.slice(0, 28) + '…' : group.label}
                        {expandable && <span style={{ marginLeft: 6, color: '#52525b', fontSize: '0.7rem' }}>({group.lotIds.length})</span>}
                      </td>
                      {colSpecs.map((spec) => {
                        const qty = getGroupCellQty(group.groupKey, spec);
                        const k = colSpecKey(spec);
                        const isEd = !readOnly && editingCell?.supplyId === group.groupKey && editSpecKey === k;
                        const dirty = isGroupSpecDirty(group.groupKey, spec);
                        return (
                          <td key={k} style={{ ...cellSt(isEd, qty, dirty), fontWeight: 500 }}
                            onClick={() => !isEd && startEdit(group.groupKey, spec, qty)}>
                            {isEd ? (
                              <EditInput
                                onCommit={() => { const v = parseFloat(editValue) || 0; setEditingCell(null); commitGroupEdit(group.groupKey, spec, v); }}
                                onEscape={() => setEditingCell(null)}
                              />
                            ) : fmtQty(qty)}
                          </td>
                        );
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
                          {colSpecs.map((spec) => {
                            const qty = getCellQty(sid, spec);
                            const k = colSpecKey(spec);
                            const isEd = !readOnly && editingCell?.supplyId === sid && editSpecKey === k;
                            const dirty = isSpecDirty(sid, spec);
                            return (
                              <td key={k} style={cellSt(isEd, qty, dirty)}
                                onClick={() => !isEd && startEdit(sid, spec, qty)}>
                                {isEd ? <EditInput onCommit={commitEdit} onEscape={() => setEditingCell(null)} /> : fmtQty(qty)}
                              </td>
                            );
                          })}
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
                {fmtQty(colSpecs.reduce((s, spec) => s + getColTotal(spec), 0))}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
