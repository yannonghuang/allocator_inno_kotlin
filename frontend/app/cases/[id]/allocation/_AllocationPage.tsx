'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import {
  AllocationRow,
  CaseDemandRow,
  CaseSupplyRow,
  deleteAllocation,
  exportAllocationCsv,
  generateAllocation,
  getAllocation,
  getCaseDemands,
  getCaseSupplies,
  importAllocationCsv,
  updateAllocationRows,
} from '../../../../lib/api';

// ── Types ─────────────────────────────────────────────────────────────────────

type SupplyPivot = 'lot' | 'product-location';

/** A resolved column in the matrix — either a collapsed customer aggregate or an individual demand. */
type ColSpec =
  | { type: 'cust';   custId: string }
  | { type: 'demand'; demandId: string; custId: string };

type SupplyGroupKey = string; // "$productId|$locationId"

function colSpecKey(spec: ColSpec): string {
  return spec.type === 'cust' ? `c:${spec.custId}` : `d:${spec.demandId}`;
}

// ── Helper: qty display ───────────────────────────────────────────────────────

function fmtQty(q: number): string {
  if (q === 0) return '—';
  if (q >= 1000) return q.toLocaleString(undefined, { maximumFractionDigits: 0 });
  return q.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

// ── Component ─────────────────────────────────────────────────────────────────

export function AllocationPage() {
  const params = useParams();
  const caseId = Number(params.id);

  const [rows, setRows]               = useState<AllocationRow[] | null>(null);
  const [supplies, setSupplies]       = useState<CaseSupplyRow[]>([]);
  const [demands, setDemands]         = useState<CaseDemandRow[]>([]);
  const [generating, setGenerating]   = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing]       = useState(false);
  const [error, setError]             = useState<string | null>(null);
  const [supplyPivot, setSupplyPivot] = useState<SupplyPivot>('product-location');
  const [expandedSupplyGroups, setExpandedSupplyGroups] = useState<Set<string>>(new Set());
  const [expandedCustomers, setExpandedCustomers]       = useState<Set<string>>(new Set());
  const [editingCell, setEditingCell] = useState<{ supplyId: string; spec: ColSpec } | null>(null);
  const [editValue, setEditValue]     = useState('');
  const importRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!caseId || isNaN(caseId)) return;
    setRows(null);
    Promise.all([
      getAllocation(caseId),
      getCaseSupplies(caseId),
      getCaseDemands(caseId),
    ]).then(([allocRows, supplyRows, demandRows]) => {
      setRows(allocRows ?? []);
      setSupplies(supplyRows);
      setDemands(demandRows);
    }).catch(e => setError(String(e)));
  }, [caseId]);

  // ── Derived maps ──────────────────────────────────────────────────────────

  const supplyMeta = React.useMemo(
    () => new Map(supplies.map(s => [s.supplyId, s])),
    [supplies],
  );

  const demandMeta = React.useMemo(
    () => new Map(demands.map(d => [d.demand_id, d])),
    [demands],
  );

  const supplyGroupKey = useCallback((supplyId: string): SupplyGroupKey => {
    const s = supplyMeta.get(supplyId);
    return s ? `${s.productId}|${s.locationId ?? ''}` : supplyId;
  }, [supplyMeta]);

  // ── Demand groups: customer → sorted demand ids ───────────────────────────

  const demandGroups = React.useMemo(() => {
    if (!rows || rows.length === 0) return [];
    const custMap = new Map<string, Set<string>>();
    for (const r of rows) {
      const did  = r.demand_id ?? '';
      const cust = demandMeta.get(did)?.customer_id ?? 'Unknown';
      if (!custMap.has(cust)) custMap.set(cust, new Set());
      custMap.get(cust)!.add(did);
    }
    return Array.from(custMap.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([custId, dids]) => ({ custId, demandIds: Array.from(dids).sort() }));
  }, [rows, demandMeta]);

  /** Flat ordered column specs — one per visible column in the matrix. */
  const colSpecs: ColSpec[] = React.useMemo(
    () => demandGroups.flatMap(({ custId, demandIds }): ColSpec[] =>
      expandedCustomers.has(custId)
        ? demandIds.map(demandId => ({ type: 'demand' as const, demandId, custId }))
        : [{ type: 'cust' as const, custId }]
    ),
    [demandGroups, expandedCustomers],
  );

  const anyCustomerExpanded = demandGroups.some(g => expandedCustomers.has(g.custId));

  const toggleCustomer = (custId: string) =>
    setExpandedCustomers(prev => {
      const next = new Set(prev);
      next.has(custId) ? next.delete(custId) : next.add(custId);
      return next;
    });

  // ── Supply groups ─────────────────────────────────────────────────────────

  const supplyGroups = React.useMemo(() => {
    if (!rows || rows.length === 0) return [];
    const groupMap = new Map<SupplyGroupKey, Set<string>>();
    for (const r of rows) {
      const gk = supplyGroupKey(r.supply_id);
      if (!groupMap.has(gk)) groupMap.set(gk, new Set());
      groupMap.get(gk)!.add(r.supply_id);
    }
    return Array.from(groupMap.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([gk, lots]) => {
        const [pid, lid] = gk.split('|');
        const label = lid ? `${pid} @ ${lid}` : pid;
        return { groupKey: gk, label, lotIds: Array.from(lots).sort() };
      });
  }, [rows, supplyGroupKey]);

  // ── Matrix cell lookup ───────────────────────────────────────────────────

  const cellMap = React.useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const r of rows ?? []) {
      if (!m.has(r.supply_id)) m.set(r.supply_id, new Map());
      m.get(r.supply_id)!.set(r.demand_id ?? '', r.qty_allocated);
    }
    return m;
  }, [rows]);

  const getCustomerDemandIds = useCallback((custId: string): string[] =>
    demandGroups.find(g => g.custId === custId)?.demandIds ?? [],
    [demandGroups],
  );

  const getCellQty = useCallback((supplyId: string, spec: ColSpec): number => {
    const sm = cellMap.get(supplyId);
    if (!sm) return 0;
    if (spec.type === 'demand') return sm.get(spec.demandId) ?? 0;
    return getCustomerDemandIds(spec.custId).reduce((s, did) => s + (sm.get(did) ?? 0), 0);
  }, [cellMap, getCustomerDemandIds]);

  const getGroupCellQty = useCallback((groupKey: SupplyGroupKey, spec: ColSpec): number => {
    const group = supplyGroups.find(g => g.groupKey === groupKey);
    if (!group) return 0;
    return group.lotIds.reduce((sum, sid) => sum + getCellQty(sid, spec), 0);
  }, [supplyGroups, getCellQty]);

  const getLotRowTotal = useCallback((supplyId: string): number =>
    colSpecs.reduce((sum, spec) => sum + getCellQty(supplyId, spec), 0),
    [colSpecs, getCellQty],
  );

  const getGroupRowTotal = useCallback((groupKey: SupplyGroupKey): number =>
    colSpecs.reduce((sum, spec) => sum + getGroupCellQty(groupKey, spec), 0),
    [colSpecs, getGroupCellQty],
  );

  const getColTotal = useCallback((spec: ColSpec): number => {
    if (supplyPivot === 'lot') {
      return supplies
        .filter(s => cellMap.has(s.supplyId))
        .reduce((sum, s) => sum + getCellQty(s.supplyId, spec), 0);
    }
    return supplyGroups.reduce((sum, g) => sum + getGroupCellQty(g.groupKey, spec), 0);
  }, [supplyPivot, supplies, supplyGroups, getCellQty, getGroupCellQty, cellMap]);

  // ── Editing ──────────────────────────────────────────────────────────────

  const editSpecKey = editingCell ? colSpecKey(editingCell.spec) : null;

  const startEdit = (supplyId: string, spec: ColSpec, currentQty: number) => {
    setEditingCell({ supplyId, spec });
    setEditValue(currentQty === 0 ? '' : String(currentQty));
  };

  const commitEdit = useCallback(async () => {
    if (!editingCell) return;
    const { supplyId, spec } = editingCell;
    const newQty = parseFloat(editValue) || 0;
    setEditingCell(null);

    if (spec.type === 'demand') {
      const updated: AllocationRow[] = [{ supply_id: supplyId, demand_id: spec.demandId, qty_allocated: newQty }];
      setRows(prev => prev ? prev.map(r =>
        r.supply_id === supplyId && (r.demand_id ?? '') === spec.demandId
          ? { ...r, qty_allocated: newQty } : r
      ) : prev);
      await updateAllocationRows(caseId, updated).catch(e => setError(String(e)));
    } else {
      const dids = getCustomerDemandIds(spec.custId);
      if (dids.length === 0) return;
      const sm = cellMap.get(supplyId);
      const oldTotal = dids.reduce((s, did) => s + (sm?.get(did) ?? 0), 0);
      const updatedRows: AllocationRow[] = dids.map(did => ({
        supply_id:     supplyId,
        demand_id:     did,
        qty_allocated: oldTotal > 0
          ? newQty * ((sm?.get(did) ?? 0) / oldTotal)
          : newQty / dids.length,
      }));
      setRows(prev => {
        if (!prev) return prev;
        const next = [...prev];
        for (const ur of updatedRows) {
          const idx = next.findIndex(r => r.supply_id === ur.supply_id && (r.demand_id ?? '') === (ur.demand_id ?? ''));
          if (idx >= 0) next[idx] = ur; else next.push(ur);
        }
        return next;
      });
      await updateAllocationRows(caseId, updatedRows).catch(e => setError(String(e)));
    }
  }, [editingCell, editValue, caseId, getCustomerDemandIds, cellMap]);

  const commitGroupEdit = useCallback(async (groupKey: SupplyGroupKey, spec: ColSpec, newTotal: number) => {
    const group = supplyGroups.find(g => g.groupKey === groupKey);
    if (!group) return;
    const oldGroupTotal = group.lotIds.reduce((s, sid) => s + getCellQty(sid, spec), 0);

    const updatedRows: AllocationRow[] = [];
    for (const sid of group.lotIds) {
      const oldLotQty = getCellQty(sid, spec);
      const lotNewQty = oldGroupTotal > 0
        ? newTotal * (oldLotQty / oldGroupTotal)
        : newTotal / group.lotIds.length;

      if (spec.type === 'demand') {
        updatedRows.push({ supply_id: sid, demand_id: spec.demandId, qty_allocated: lotNewQty });
      } else {
        const dids = getCustomerDemandIds(spec.custId);
        const sm = cellMap.get(sid);
        const oldLotCustTotal = dids.reduce((s, did) => s + (sm?.get(did) ?? 0), 0);
        for (const did of dids) {
          updatedRows.push({
            supply_id:     sid,
            demand_id:     did,
            qty_allocated: oldLotCustTotal > 0
              ? lotNewQty * ((sm?.get(did) ?? 0) / oldLotCustTotal)
              : lotNewQty / Math.max(1, dids.length),
          });
        }
      }
    }

    setRows(prev => {
      if (!prev) return prev;
      const next = [...prev];
      for (const ur of updatedRows) {
        const idx = next.findIndex(r => r.supply_id === ur.supply_id && (r.demand_id ?? '') === (ur.demand_id ?? ''));
        if (idx >= 0) next[idx] = ur; else next.push(ur);
      }
      return next;
    });
    await updateAllocationRows(caseId, updatedRows).catch(e => setError(String(e)));
  }, [supplyGroups, getCellQty, getCustomerDemandIds, cellMap, caseId]);

  // ── Actions ──────────────────────────────────────────────────────────────

  const handleGenerate = async () => {
    setGenerating(true);
    setError(null);
    try {
      setRows(await generateAllocation(caseId));
    } catch (e) {
      setError(String(e));
    } finally {
      setGenerating(false);
    }
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImportLoading(true);
    setError(null);
    try {
      setRows(await importAllocationCsv(caseId, await file.text()));
    } catch (e) {
      setError(String(e));
    } finally {
      setImportLoading(false);
      if (importRef.current) importRef.current.value = '';
    }
  };

  const handleExport = async () => {
    try {
      const csv  = await exportAllocationCsv(caseId);
      const url  = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a    = document.createElement('a');
      a.href     = url;
      a.download = `allocation_case_${caseId}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleClear = async () => {
    if (!confirm('Clear the allocation map for this case? Planning will revert to auto-allocation.')) return;
    setClearing(true);
    try {
      await deleteAllocation(caseId);
      setRows([]);
    } catch (e) {
      setError(String(e));
    } finally {
      setClearing(false);
    }
  };

  // ── Styles ────────────────────────────────────────────────────────────────

  const btnStyle = (variant: 'primary' | 'ghost' | 'danger' = 'ghost'): React.CSSProperties => ({
    padding: '0.35rem 0.75rem', fontSize: '0.8rem', borderRadius: 4,
    border:      variant === 'danger'  ? '1px solid #7f1d1d' : variant === 'primary' ? '1px solid #1d4ed8' : '1px solid #3f3f46',
    background:  variant === 'danger'  ? '#450a0a'           : variant === 'primary' ? '#1e3a8a'           : '#1c1c1f',
    color:       variant === 'danger'  ? '#fca5a5'           : '#e4e4e7',
    cursor: 'pointer',
  });

  const toggleBtnStyle = (active: boolean): React.CSSProperties => ({
    padding: '0.25rem 0.6rem', fontSize: '0.75rem', borderRadius: 3,
    border: '1px solid #3f3f46',
    background: active ? '#27272a' : 'transparent',
    color: active ? '#e4e4e7' : '#71717a',
    cursor: 'pointer',
  });

  const cellStyle = (isEdit: boolean, qty: number): React.CSSProperties => ({
    padding: '0.3rem 0.5rem', textAlign: 'right', fontSize: '0.78rem',
    color: qty === 0 ? '#3f3f46' : '#e4e4e7',
    cursor: 'pointer',
    background: isEdit ? '#1e3a8a22' : 'transparent',
    minWidth: 70, whiteSpace: 'nowrap',
  });

  const thStyle: React.CSSProperties = {
    padding: '0.3rem 0.5rem', fontSize: '0.72rem', color: '#71717a',
    textAlign: 'center', fontWeight: 500, whiteSpace: 'nowrap',
    borderBottom: '1px solid #27272a',
    position: 'sticky', top: 0, background: '#111113', zIndex: 2,
  };

  const subThStyle: React.CSSProperties = {
    ...thStyle,
    fontSize: '0.68rem', color: '#52525b', fontWeight: 400,
    top: 28,  // below the first header row
    borderTop: '1px solid #1c1c1f',
  };

  const labelThStyle: React.CSSProperties = {
    ...thStyle,
    textAlign: 'left',
    position: 'sticky', left: 0, zIndex: 3,
    minWidth: 200, maxWidth: 260,
  };

  const labelTdStyle = (indent = false): React.CSSProperties => ({
    padding: indent ? '0.3rem 0.5rem 0.3rem 1.5rem' : '0.3rem 0.5rem',
    fontSize: '0.78rem',
    color: indent ? '#a1a1aa' : '#d4d4d8',
    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
    maxWidth: 260,
    position: 'sticky', left: 0, background: '#111113',
    borderRight: '1px solid #27272a', zIndex: 1,
    cursor: indent ? 'default' : 'pointer',
  });

  const totalCellStyle: React.CSSProperties = {
    padding: '0.3rem 0.5rem', textAlign: 'right', fontSize: '0.78rem',
    color: '#71717a', borderLeft: '1px solid #27272a', fontWeight: 500, whiteSpace: 'nowrap',
  };

  const rowStyle = (isGroup: boolean): React.CSSProperties => ({
    background: isGroup ? '#16161a' : 'transparent',
    borderBottom: '1px solid #1c1c1f',
  });

  // ── Render ────────────────────────────────────────────────────────────────

  if (rows === null) {
    return <div style={{ padding: '2rem', color: '#71717a', fontSize: '0.875rem' }}>Loading allocation…</div>;
  }

  const totalLots    = rows.length > 0 ? new Set(rows.map(r => r.supply_id)).size : 0;
  const totalDemands = rows.length > 0 ? new Set(rows.map(r => r.demand_id ?? '')).size : 0;

  /** Renders the inline edit input used in both lot and group rows. */
  const EditInput = ({
    onCommit, onEscape,
  }: { onCommit: () => void; onEscape: () => void }) => (
    <input
      autoFocus
      value={editValue}
      onChange={e => setEditValue(e.target.value)}
      onBlur={onCommit}
      onKeyDown={e => { if (e.key === 'Enter') onCommit(); if (e.key === 'Escape') onEscape(); }}
      style={{ width: 68, background: '#1e3a8a', border: 'none', color: '#e4e4e7', fontSize: '0.78rem', textAlign: 'right', padding: '0 2px' }}
    />
  );

  return (
    <div style={{ padding: '1.25rem 1.5rem', minHeight: '100vh', background: '#0e0e10', color: '#e4e4e7' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button style={btnStyle('primary')} onClick={handleGenerate} disabled={generating}>
          {generating ? 'Generating…' : 'Generate'}
        </button>
        <button style={btnStyle()} onClick={() => importRef.current?.click()} disabled={importLoading}>
          {importLoading ? 'Uploading…' : 'Upload CSV'}
        </button>
        <input ref={importRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImport} />
        <button style={btnStyle()} onClick={handleExport} disabled={rows.length === 0}>Download CSV</button>
        <button style={btnStyle('danger')} onClick={handleClear} disabled={clearing || rows.length === 0}>
          {clearing ? 'Clearing…' : 'Clear'}
        </button>
      </div>

      {error && (
        <div style={{ background: '#450a0a', border: '1px solid #7f1d1d', borderRadius: 4, padding: '0.5rem 0.75rem', fontSize: '0.8rem', color: '#fca5a5', marginBottom: '0.75rem' }}>
          {error}
        </div>
      )}

      {rows.length === 0 ? (
        <div style={{ padding: '3rem 1rem', textAlign: 'center', color: '#52525b', fontSize: '0.875rem' }}>
          No allocation map — click <strong style={{ color: '#93c5fd' }}>Generate</strong> to create one, or <strong style={{ color: '#93c5fd' }}>Upload CSV</strong> to load one.
        </div>
      ) : (
        <>
          {/* Stats + supply pivot */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.75rem', fontSize: '0.8rem', color: '#71717a' }}>
            <span>{totalLots} supply lots × {totalDemands} demands</span>
            <span style={{ color: '#3f3f46' }}>|</span>
            <span>Supply:</span>
            <button style={toggleBtnStyle(supplyPivot === 'lot')} onClick={() => setSupplyPivot('lot')}>By Lot</button>
            <button style={toggleBtnStyle(supplyPivot === 'product-location')} onClick={() => setSupplyPivot('product-location')}>By Product+Location</button>
          </div>

          {/* Matrix */}
          <div style={{ overflowX: 'auto', border: '1px solid #27272a', borderRadius: 6 }}>
            <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
              <thead>
                {/* Row 1 — supply label + customer headers */}
                <tr>
                  <th rowSpan={anyCustomerExpanded ? 2 : 1} style={labelThStyle}>Supply</th>
                  {demandGroups.map(({ custId, demandIds }) => {
                    const expanded = expandedCustomers.has(custId);
                    return (
                      <th
                        key={custId}
                        colSpan={expanded ? demandIds.length : 1}
                        rowSpan={(!expanded && anyCustomerExpanded) ? 2 : 1}
                        style={{ ...thStyle, cursor: 'pointer', borderLeft: '1px solid #27272a' }}
                        onClick={() => toggleCustomer(custId)}
                        title={custId}
                      >
                        <span style={{ marginRight: 3, fontSize: '0.65rem', color: '#52525b' }}>{expanded ? '▾' : '▸'}</span>
                        {custId.length > 16 ? custId.slice(0, 14) + '…' : custId}
                        <span style={{ marginLeft: 4, fontSize: '0.65rem', color: '#52525b' }}>({demandIds.length})</span>
                      </th>
                    );
                  })}
                  <th rowSpan={anyCustomerExpanded ? 2 : 1} style={{ ...thStyle, borderLeft: '1px solid #27272a' }}>Total</th>
                </tr>
                {/* Row 2 — individual demand headers (only when at least one customer is expanded) */}
                {anyCustomerExpanded && (
                  <tr>
                    {demandGroups.flatMap(({ custId, demandIds }) =>
                      expandedCustomers.has(custId)
                        ? demandIds.map(did => (
                            <th key={did} style={{ ...subThStyle, borderLeft: '1px solid #1c1c1f' }} title={did}>
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
                  supplies
                    .filter(s => cellMap.has(s.supplyId))
                    .sort((a, b) => {
                      const gA = supplyGroupKey(a.supplyId);
                      const gB = supplyGroupKey(b.supplyId);
                      return gA !== gB ? gA.localeCompare(gB) : a.supplyId.localeCompare(b.supplyId);
                    })
                    .map(s => {
                      const rowTotal = getLotRowTotal(s.supplyId);
                      const label    = s.productId + ' @ ' + (s.locationId ?? '?') + (s.supplyDate ? ' (' + s.supplyDate.slice(0, 10) + ')' : '');
                      return (
                        <tr key={s.supplyId} style={rowStyle(false)}>
                          <td style={labelTdStyle(false)} title={label}>{label.length > 34 ? label.slice(0, 32) + '…' : label}</td>
                          {colSpecs.map(spec => {
                            const qty    = getCellQty(s.supplyId, spec);
                            const key    = colSpecKey(spec);
                            const isEdit = editingCell?.supplyId === s.supplyId && editSpecKey === key;
                            return (
                              <td key={key} style={cellStyle(isEdit, qty)}
                                onClick={() => !isEdit && startEdit(s.supplyId, spec, qty)}>
                                {isEdit
                                  ? <EditInput onCommit={commitEdit} onEscape={() => setEditingCell(null)} />
                                  : fmtQty(qty)}
                              </td>
                            );
                          })}
                          <td style={totalCellStyle}>{fmtQty(rowTotal)}</td>
                        </tr>
                      );
                    })
                ) : (
                  supplyGroups.map(group => {
                    const expanded   = expandedSupplyGroups.has(group.groupKey);
                    const groupTotal = getGroupRowTotal(group.groupKey);
                    return (
                      <React.Fragment key={group.groupKey}>
                        <tr style={rowStyle(true)}>
                          <td
                            style={labelTdStyle(false)}
                            title={group.label}
                            onClick={() => setExpandedSupplyGroups(prev => {
                              const next = new Set(prev);
                              expanded ? next.delete(group.groupKey) : next.add(group.groupKey);
                              return next;
                            })}
                          >
                            <span style={{ marginRight: 4, color: '#52525b', fontSize: '0.7rem' }}>{expanded ? '▾' : '▸'}</span>
                            {group.label.length > 30 ? group.label.slice(0, 28) + '…' : group.label}
                            <span style={{ marginLeft: 6, color: '#52525b', fontSize: '0.7rem' }}>({group.lotIds.length})</span>
                          </td>
                          {colSpecs.map(spec => {
                            const qty        = getGroupCellQty(group.groupKey, spec);
                            const key        = colSpecKey(spec);
                            const isGroupEdit = editingCell?.supplyId === group.groupKey && editSpecKey === key;
                            return (
                              <td key={key} style={{ ...cellStyle(isGroupEdit, qty), fontWeight: 500 }}
                                onClick={() => !isGroupEdit && startEdit(group.groupKey, spec, qty)}>
                                {isGroupEdit ? (
                                  <EditInput
                                    onCommit={async () => { const v = parseFloat(editValue) || 0; setEditingCell(null); await commitGroupEdit(group.groupKey, spec, v); }}
                                    onEscape={() => setEditingCell(null)}
                                  />
                                ) : fmtQty(qty)}
                              </td>
                            );
                          })}
                          <td style={{ ...totalCellStyle, fontWeight: 500 }}>{fmtQty(groupTotal)}</td>
                        </tr>
                        {expanded && group.lotIds.map(sid => {
                          const s        = supplyMeta.get(sid);
                          const rowTotal = getLotRowTotal(sid);
                          const lotLabel = s?.supplyDate ? s.supplyDate.slice(0, 10) + ' · ' + sid : sid;
                          return (
                            <tr key={sid} style={rowStyle(false)}>
                              <td style={labelTdStyle(true)} title={sid}>{lotLabel.length > 32 ? lotLabel.slice(0, 30) + '…' : lotLabel}</td>
                              {colSpecs.map(spec => {
                                const qty    = getCellQty(sid, spec);
                                const key    = colSpecKey(spec);
                                const isEdit = editingCell?.supplyId === sid && editSpecKey === key;
                                return (
                                  <td key={key} style={cellStyle(isEdit, qty)}
                                    onClick={() => !isEdit && startEdit(sid, spec, qty)}>
                                    {isEdit
                                      ? <EditInput onCommit={commitEdit} onEscape={() => setEditingCell(null)} />
                                      : fmtQty(qty)}
                                  </td>
                                );
                              })}
                              <td style={totalCellStyle}>{fmtQty(rowTotal)}</td>
                            </tr>
                          );
                        })}
                      </React.Fragment>
                    );
                  })
                )}
                {/* Column totals */}
                <tr style={{ borderTop: '1px solid #3f3f46', background: '#16161a' }}>
                  <td style={{ ...labelTdStyle(false), color: '#71717a', fontWeight: 500, cursor: 'default' }}>Total</td>
                  {colSpecs.map(spec => {
                    const key = colSpecKey(spec);
                    return <td key={key} style={{ ...totalCellStyle, borderLeft: 'none' }}>{fmtQty(getColTotal(spec))}</td>;
                  })}
                  <td style={{ ...totalCellStyle, color: '#a1a1aa' }}>
                    {fmtQty(colSpecs.reduce((s, spec) => s + getColTotal(spec), 0))}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
