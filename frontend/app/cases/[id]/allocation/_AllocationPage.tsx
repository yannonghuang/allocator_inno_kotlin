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
type DemandPivot = 'demand' | 'customer';

type SupplyGroupKey = string; // "$productId|$locationId"
type SupplyLotKey = string;   // supply_id

type DemandColKey = string;   // demand_id or customer_id

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

  const [rows, setRows] = useState<AllocationRow[] | null>(null);   // null = loading; [] = no allocation
  const [supplies, setSupplies] = useState<CaseSupplyRow[]>([]);
  const [demands, setDemands] = useState<CaseDemandRow[]>([]);
  const [generating, setGenerating] = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [supplyPivot, setSupplyPivot] = useState<SupplyPivot>('product-location');
  const [demandPivot, setDemandPivot] = useState<DemandPivot>('demand');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [editingCell, setEditingCell] = useState<{ supplyId: string; demandColKey: string } | null>(null);
  const [editValue, setEditValue] = useState('');
  const importRef = useRef<HTMLInputElement>(null);

  // Load allocation + supply metadata + demand metadata on mount
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

  // supply_id → CaseSupplyRow
  const supplyMeta = React.useMemo(() =>
    new Map(supplies.map(s => [s.supplyId, s])),
    [supplies]
  );

  // demand_id → customer_id
  const demandMeta = React.useMemo(() =>
    new Map(demands.map(d => [d.demand_id, d])),
    [demands]
  );

  // supply_id → "$productId|$locationId" group key
  const supplyGroupKey = useCallback((supplyId: string): SupplyGroupKey => {
    const s = supplyMeta.get(supplyId);
    return s ? `${s.productId}|${s.locationId ?? ''}` : supplyId;
  }, [supplyMeta]);

  // ── Column set ───────────────────────────────────────────────────────────

  const colKeys: DemandColKey[] = React.useMemo(() => {
    if (!rows || rows.length === 0) return [];
    if (demandPivot === 'demand') {
      const seen = new Set<string>();
      const keys: string[] = [];
      for (const r of rows) {
        const k = r.demand_id ?? '';
        if (!seen.has(k)) { seen.add(k); keys.push(k); }
      }
      return keys.sort();
    } else {
      // customer pivot
      const seen = new Set<string>();
      const keys: string[] = [];
      for (const r of rows) {
        const did = r.demand_id ?? '';
        const cust = demandMeta.get(did)?.customer_id ?? did;
        if (!seen.has(cust)) { seen.add(cust); keys.push(cust); }
      }
      return keys.sort();
    }
  }, [rows, demandPivot, demandMeta]);

  // ── Row structure for supply pivot ───────────────────────────────────────

  // Ordered list of supply groups (product+location)
  const supplyGroups: Array<{ groupKey: SupplyGroupKey; label: string; lotIds: SupplyLotKey[] }> = React.useMemo(() => {
    if (!rows || rows.length === 0) return [];
    const groupMap = new Map<SupplyGroupKey, Set<string>>();
    for (const r of rows) {
      const sid = r.supply_id;
      const gk = supplyGroupKey(sid);
      if (!groupMap.has(gk)) groupMap.set(gk, new Set());
      groupMap.get(gk)!.add(sid);
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

  // Build: supply_id → demand_id → qty (from rows)
  const cellMap = React.useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const r of rows ?? []) {
      if (!m.has(r.supply_id)) m.set(r.supply_id, new Map());
      m.get(r.supply_id)!.set(r.demand_id ?? '', r.qty_allocated);
    }
    return m;
  }, [rows]);

  // demand_id → customer_id reverse lookup
  const demandToCustomer = useCallback((did: string) =>
    demandMeta.get(did)?.customer_id ?? did,
    [demandMeta]
  );

  // demands grouped by customer
  const customerDemands = React.useMemo(() => {
    const m = new Map<string, string[]>();
    for (const r of rows ?? []) {
      const did = r.demand_id ?? '';
      const cust = demandToCustomer(did);
      if (!m.has(cust)) m.set(cust, []);
      if (!m.get(cust)!.includes(did)) m.get(cust)!.push(did);
    }
    return m;
  }, [rows, demandToCustomer]);

  // Get qty for a (supplyId, colKey) cell
  const getCellQty = useCallback((supplyId: string, colKey: DemandColKey): number => {
    const supplyDemands = cellMap.get(supplyId);
    if (!supplyDemands) return 0;
    if (demandPivot === 'demand') {
      return supplyDemands.get(colKey) ?? 0;
    } else {
      // customer pivot: sum across all demands for this customer
      const dids = customerDemands.get(colKey) ?? [];
      return dids.reduce((sum, did) => sum + (supplyDemands.get(did) ?? 0), 0);
    }
  }, [cellMap, demandPivot, customerDemands]);

  // Get group-level qty for (groupKey, colKey)
  const getGroupCellQty = useCallback((groupKey: SupplyGroupKey, colKey: DemandColKey): number => {
    const group = supplyGroups.find(g => g.groupKey === groupKey);
    if (!group) return 0;
    return group.lotIds.reduce((sum, sid) => sum + getCellQty(sid, colKey), 0);
  }, [supplyGroups, getCellQty]);

  // Row total for a single supply lot
  const getLotRowTotal = useCallback((supplyId: string): number =>
    colKeys.reduce((sum, col) => sum + getCellQty(supplyId, col), 0),
    [colKeys, getCellQty]
  );

  // Row total for a group
  const getGroupRowTotal = useCallback((groupKey: SupplyGroupKey): number =>
    colKeys.reduce((sum, col) => sum + getGroupCellQty(groupKey, col), 0),
    [colKeys, getGroupCellQty]
  );

  // Column total
  const getColTotal = useCallback((colKey: DemandColKey): number => {
    if (supplyPivot === 'lot') {
      const allLots = supplies.map(s => s.supplyId);
      return allLots.reduce((sum, sid) => sum + getCellQty(sid, colKey), 0);
    } else {
      return supplyGroups.reduce((sum, g) => sum + getGroupCellQty(g.groupKey, colKey), 0);
    }
  }, [supplyPivot, supplies, supplyGroups, getCellQty, getGroupCellQty]);

  // ── Editing ──────────────────────────────────────────────────────────────

  const startEdit = (supplyId: string, colKey: string, currentQty: number) => {
    setEditingCell({ supplyId, demandColKey: colKey });
    setEditValue(currentQty === 0 ? '' : String(currentQty));
  };

  const commitEdit = useCallback(async () => {
    if (!editingCell) return;
    const newQty = parseFloat(editValue) || 0;
    const { supplyId, demandColKey } = editingCell;
    setEditingCell(null);

    if (demandPivot === 'demand') {
      // Direct lot × demand edit
      const updated: AllocationRow[] = [{ supply_id: supplyId, demand_id: demandColKey || null, qty_allocated: newQty }];
      setRows(prev => prev ? prev.map(r =>
        r.supply_id === supplyId && (r.demand_id ?? '') === demandColKey
          ? { ...r, qty_allocated: newQty } : r
      ) : prev);
      await updateAllocationRows(caseId, updated).catch(e => setError(String(e)));
    } else {
      // Customer pivot: distribute proportionally to underlying demands for this customer
      const dids = customerDemands.get(demandColKey) ?? [];
      if (dids.length === 0) return;
      const oldTotal = dids.reduce((sum, did) => sum + (cellMap.get(supplyId)?.get(did) ?? 0), 0);
      const updatedRows: AllocationRow[] = dids.map(did => {
        const oldQty = cellMap.get(supplyId)?.get(did) ?? 0;
        const newDemandQty = oldTotal > 0 ? newQty * (oldQty / oldTotal) : newQty / dids.length;
        return { supply_id: supplyId, demand_id: did, qty_allocated: newDemandQty };
      });
      // Optimistically update rows
      setRows(prev => {
        if (!prev) return prev;
        const updated = [...prev];
        for (const ur of updatedRows) {
          const idx = updated.findIndex(r => r.supply_id === ur.supply_id && (r.demand_id ?? '') === (ur.demand_id ?? ''));
          if (idx >= 0) updated[idx] = ur; else updated.push(ur);
        }
        return updated;
      });
      await updateAllocationRows(caseId, updatedRows).catch(e => setError(String(e)));
    }
  }, [editingCell, editValue, demandPivot, caseId, customerDemands, cellMap]);

  const commitGroupEdit = useCallback(async (groupKey: SupplyGroupKey, colKey: DemandColKey, newTotal: number) => {
    const group = supplyGroups.find(g => g.groupKey === groupKey);
    if (!group) return;

    // Distribute newTotal proportionally across the group's lots
    const getOldQty = (sid: string) => {
      if (demandPivot === 'demand') return cellMap.get(sid)?.get(colKey) ?? 0;
      const dids = customerDemands.get(colKey) ?? [];
      return dids.reduce((sum, did) => sum + (cellMap.get(sid)?.get(did) ?? 0), 0);
    };
    const oldGroupTotal = group.lotIds.reduce((sum, sid) => sum + getOldQty(sid), 0);

    const updatedRows: AllocationRow[] = [];
    for (const sid of group.lotIds) {
      const oldQty = getOldQty(sid);
      const lotNewTotal = oldGroupTotal > 0 ? newTotal * (oldQty / oldGroupTotal) : newTotal / group.lotIds.length;

      if (demandPivot === 'demand') {
        updatedRows.push({ supply_id: sid, demand_id: colKey || null, qty_allocated: lotNewTotal });
      } else {
        const dids = customerDemands.get(colKey) ?? [];
        const oldLotCustTotal = dids.reduce((sum, did) => sum + (cellMap.get(sid)?.get(did) ?? 0), 0);
        for (const did of dids) {
          const oldDemandQty = cellMap.get(sid)?.get(did) ?? 0;
          const newDemandQty = oldLotCustTotal > 0 ? lotNewTotal * (oldDemandQty / oldLotCustTotal) : lotNewTotal / Math.max(1, dids.length);
          updatedRows.push({ supply_id: sid, demand_id: did, qty_allocated: newDemandQty });
        }
      }
    }

    setRows(prev => {
      if (!prev) return prev;
      const updated = [...prev];
      for (const ur of updatedRows) {
        const idx = updated.findIndex(r => r.supply_id === ur.supply_id && (r.demand_id ?? '') === (ur.demand_id ?? ''));
        if (idx >= 0) updated[idx] = ur; else updated.push(ur);
      }
      return updated;
    });
    await updateAllocationRows(caseId, updatedRows).catch(e => setError(String(e)));
  }, [supplyGroups, demandPivot, cellMap, customerDemands, caseId]);

  // ── Actions ──────────────────────────────────────────────────────────────

  const handleGenerate = async () => {
    setGenerating(true);
    setError(null);
    try {
      const newRows = await generateAllocation(caseId);
      setRows(newRows);
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
      const text = await file.text();
      const newRows = await importAllocationCsv(caseId, text);
      setRows(newRows);
    } catch (e) {
      setError(String(e));
    } finally {
      setImportLoading(false);
      if (importRef.current) importRef.current.value = '';
    }
  };

  const handleExport = async () => {
    try {
      const csv = await exportAllocationCsv(caseId);
      const blob = new Blob([csv], { type: 'text/csv' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
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

  // ── Render ────────────────────────────────────────────────────────────────

  const btnStyle = (variant: 'primary' | 'ghost' | 'danger' = 'ghost'): React.CSSProperties => ({
    padding: '0.35rem 0.75rem',
    fontSize: '0.8rem',
    borderRadius: 4,
    border: variant === 'danger' ? '1px solid #7f1d1d' : variant === 'primary' ? '1px solid #1d4ed8' : '1px solid #3f3f46',
    background: variant === 'danger' ? '#450a0a' : variant === 'primary' ? '#1e3a8a' : '#1c1c1f',
    color: variant === 'danger' ? '#fca5a5' : '#e4e4e7',
    cursor: 'pointer',
  });

  const toggleBtnStyle = (active: boolean): React.CSSProperties => ({
    padding: '0.25rem 0.6rem',
    fontSize: '0.75rem',
    borderRadius: 3,
    border: '1px solid #3f3f46',
    background: active ? '#27272a' : 'transparent',
    color: active ? '#e4e4e7' : '#71717a',
    cursor: 'pointer',
  });

  const cellStyle = (isEdit: boolean, qty: number): React.CSSProperties => ({
    padding: '0.3rem 0.5rem',
    textAlign: 'right',
    fontSize: '0.78rem',
    color: qty === 0 ? '#3f3f46' : '#e4e4e7',
    cursor: 'pointer',
    background: isEdit ? '#1e3a8a22' : 'transparent',
    minWidth: 70,
    whiteSpace: 'nowrap',
  });

  const thStyle: React.CSSProperties = {
    padding: '0.3rem 0.5rem',
    fontSize: '0.72rem',
    color: '#71717a',
    textAlign: 'right',
    fontWeight: 500,
    whiteSpace: 'nowrap',
    borderBottom: '1px solid #27272a',
    position: 'sticky',
    top: 0,
    background: '#111113',
    zIndex: 2,
  };

  const labelThStyle: React.CSSProperties = {
    ...thStyle,
    textAlign: 'left',
    position: 'sticky',
    left: 0,
    zIndex: 3,
    minWidth: 200,
    maxWidth: 260,
  };

  const labelTdStyle = (indent = false): React.CSSProperties => ({
    padding: indent ? '0.3rem 0.5rem 0.3rem 1.5rem' : '0.3rem 0.5rem',
    fontSize: '0.78rem',
    color: indent ? '#a1a1aa' : '#d4d4d8',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    maxWidth: 260,
    position: 'sticky',
    left: 0,
    background: '#111113',
    borderRight: '1px solid #27272a',
    zIndex: 1,
    cursor: indent ? 'default' : 'pointer',
  });

  const totalCellStyle: React.CSSProperties = {
    padding: '0.3rem 0.5rem',
    textAlign: 'right',
    fontSize: '0.78rem',
    color: '#71717a',
    borderLeft: '1px solid #27272a',
    fontWeight: 500,
    whiteSpace: 'nowrap',
  };

  const rowStyle = (isGroup: boolean): React.CSSProperties => ({
    background: isGroup ? '#16161a' : 'transparent',
    borderBottom: '1px solid #1c1c1f',
  });

  if (rows === null) {
    return <div style={{ padding: '2rem', color: '#71717a', fontSize: '0.875rem' }}>Loading allocation…</div>;
  }

  const totalLots = rows.length > 0 ? new Set(rows.map(r => r.supply_id)).size : 0;
  const totalDemands = rows.length > 0 ? new Set(rows.map(r => r.demand_id ?? '')).size : 0;

  return (
    <div style={{ padding: '1.25rem 1.5rem', minHeight: '100vh', background: '#0e0e10', color: '#e4e4e7' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button style={btnStyle('primary')} onClick={handleGenerate} disabled={generating}>
          {generating ? 'Generating…' : 'Generate'}
        </button>
        <button style={btnStyle()} onClick={() => importRef.current?.click()} disabled={importLoading}>
          {importLoading ? 'Importing…' : 'Import CSV'}
        </button>
        <input ref={importRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImport} />
        <button style={btnStyle()} onClick={handleExport} disabled={rows.length === 0}>
          Export CSV
        </button>
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
          No allocation map — click <strong style={{ color: '#93c5fd' }}>Generate</strong> to create one from the current supply &amp; demand data, or <strong style={{ color: '#93c5fd' }}>Import CSV</strong> to load one.
        </div>
      ) : (
        <>
          {/* Stats + pivots */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.75rem', fontSize: '0.8rem', color: '#71717a' }}>
            <span>{totalLots} supply lots × {totalDemands} demands</span>
            <span style={{ color: '#3f3f46' }}>|</span>
            <span>Supply:</span>
            <button style={toggleBtnStyle(supplyPivot === 'lot')} onClick={() => setSupplyPivot('lot')}>By Lot</button>
            <button style={toggleBtnStyle(supplyPivot === 'product-location')} onClick={() => setSupplyPivot('product-location')}>By Product+Location</button>
            <span style={{ color: '#3f3f46' }}>|</span>
            <span>Demand:</span>
            <button style={toggleBtnStyle(demandPivot === 'demand')} onClick={() => setDemandPivot('demand')}>By Demand</button>
            <button style={toggleBtnStyle(demandPivot === 'customer')} onClick={() => setDemandPivot('customer')}>By Customer</button>
          </div>

          {/* Matrix */}
          <div style={{ overflowX: 'auto', border: '1px solid #27272a', borderRadius: 6 }}>
            <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
              <thead>
                <tr>
                  <th style={labelThStyle}>Supply</th>
                  {colKeys.map(col => (
                    <th key={col} style={thStyle} title={col}>{col.length > 18 ? col.slice(0, 16) + '…' : col}</th>
                  ))}
                  <th style={{ ...thStyle, borderLeft: '1px solid #27272a' }}>Total</th>
                </tr>
              </thead>
              <tbody>
                {supplyPivot === 'lot' ? (
                  // Flat lot rows
                  supplies
                    .filter(s => cellMap.has(s.supplyId))
                    .sort((a, b) => {
                      const gA = supplyGroupKey(a.supplyId);
                      const gB = supplyGroupKey(b.supplyId);
                      return gA !== gB ? gA.localeCompare(gB) : a.supplyId.localeCompare(b.supplyId);
                    })
                    .map(s => {
                      const rowTotal = getLotRowTotal(s.supplyId);
                      const label = s.productId + ' @ ' + (s.locationId ?? '?') + (s.supplyDate ? ' (' + s.supplyDate.slice(0, 10) + ')' : '');
                      return (
                        <tr key={s.supplyId} style={rowStyle(false)}>
                          <td style={labelTdStyle(false)} title={label}>{label.length > 34 ? label.slice(0, 32) + '…' : label}</td>
                          {colKeys.map(col => {
                            const qty = getCellQty(s.supplyId, col);
                            const isEdit = editingCell?.supplyId === s.supplyId && editingCell.demandColKey === col;
                            return (
                              <td key={col} style={cellStyle(isEdit, qty)}
                                onClick={() => !isEdit && startEdit(s.supplyId, col, qty)}>
                                {isEdit ? (
                                  <input
                                    autoFocus
                                    value={editValue}
                                    onChange={e => setEditValue(e.target.value)}
                                    onBlur={commitEdit}
                                    onKeyDown={e => { if (e.key === 'Enter') commitEdit(); if (e.key === 'Escape') setEditingCell(null); }}
                                    style={{ width: 68, background: '#1e3a8a', border: 'none', color: '#e4e4e7', fontSize: '0.78rem', textAlign: 'right', padding: '0 2px' }}
                                  />
                                ) : fmtQty(qty)}
                              </td>
                            );
                          })}
                          <td style={totalCellStyle}>{fmtQty(rowTotal)}</td>
                        </tr>
                      );
                    })
                ) : (
                  // Grouped rows (product+location → lots)
                  supplyGroups.map(group => {
                    const expanded = expandedGroups.has(group.groupKey);
                    const groupTotal = getGroupRowTotal(group.groupKey);
                    return (
                      <React.Fragment key={group.groupKey}>
                        {/* Group header row */}
                        <tr style={rowStyle(true)}>
                          <td
                            style={labelTdStyle(false)}
                            title={group.label}
                            onClick={() => setExpandedGroups(prev => {
                              const next = new Set(prev);
                              expanded ? next.delete(group.groupKey) : next.add(group.groupKey);
                              return next;
                            })}
                          >
                            <span style={{ marginRight: 4, color: '#52525b', fontSize: '0.7rem' }}>{expanded ? '▾' : '▸'}</span>
                            {group.label.length > 30 ? group.label.slice(0, 28) + '…' : group.label}
                            <span style={{ marginLeft: 6, color: '#52525b', fontSize: '0.7rem' }}>({group.lotIds.length})</span>
                          </td>
                          {colKeys.map(col => {
                            const qty = getGroupCellQty(group.groupKey, col);
                            const isGroupEdit = editingCell?.supplyId === group.groupKey && editingCell.demandColKey === col;
                            return (
                              <td key={col} style={{ ...cellStyle(isGroupEdit, qty), fontWeight: 500 }}
                                onClick={() => !isGroupEdit && startEdit(group.groupKey, col, qty)}>
                                {isGroupEdit ? (
                                  <input
                                    autoFocus
                                    value={editValue}
                                    onChange={e => setEditValue(e.target.value)}
                                    onBlur={async () => { const v = parseFloat(editValue) || 0; setEditingCell(null); await commitGroupEdit(group.groupKey, col, v); }}
                                    onKeyDown={async e => {
                                      if (e.key === 'Enter') { const v = parseFloat(editValue) || 0; setEditingCell(null); await commitGroupEdit(group.groupKey, col, v); }
                                      if (e.key === 'Escape') setEditingCell(null);
                                    }}
                                    style={{ width: 68, background: '#1e3a8a', border: 'none', color: '#e4e4e7', fontSize: '0.78rem', textAlign: 'right', padding: '0 2px' }}
                                  />
                                ) : fmtQty(qty)}
                              </td>
                            );
                          })}
                          <td style={{ ...totalCellStyle, fontWeight: 500 }}>{fmtQty(groupTotal)}</td>
                        </tr>
                        {/* Lot rows when expanded */}
                        {expanded && group.lotIds.map(sid => {
                          const s = supplyMeta.get(sid);
                          const rowTotal = getLotRowTotal(sid);
                          const lotLabel = s?.supplyDate ? s.supplyDate.slice(0, 10) + ' · ' + sid : sid;
                          return (
                            <tr key={sid} style={rowStyle(false)}>
                              <td style={labelTdStyle(true)} title={sid}>{lotLabel.length > 32 ? lotLabel.slice(0, 30) + '…' : lotLabel}</td>
                              {colKeys.map(col => {
                                const qty = getCellQty(sid, col);
                                const isEdit = editingCell?.supplyId === sid && editingCell.demandColKey === col;
                                return (
                                  <td key={col} style={cellStyle(isEdit, qty)}
                                    onClick={() => !isEdit && startEdit(sid, col, qty)}>
                                    {isEdit ? (
                                      <input
                                        autoFocus
                                        value={editValue}
                                        onChange={e => setEditValue(e.target.value)}
                                        onBlur={commitEdit}
                                        onKeyDown={e => { if (e.key === 'Enter') commitEdit(); if (e.key === 'Escape') setEditingCell(null); }}
                                        style={{ width: 68, background: '#1e3a8a', border: 'none', color: '#e4e4e7', fontSize: '0.78rem', textAlign: 'right', padding: '0 2px' }}
                                      />
                                    ) : fmtQty(qty)}
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
                {/* Column totals row */}
                <tr style={{ borderTop: '1px solid #3f3f46', background: '#16161a' }}>
                  <td style={{ ...labelTdStyle(false), color: '#71717a', fontWeight: 500, cursor: 'default' }}>Total</td>
                  {colKeys.map(col => (
                    <td key={col} style={{ ...totalCellStyle, borderLeft: 'none' }}>{fmtQty(getColTotal(col))}</td>
                  ))}
                  <td style={{ ...totalCellStyle, color: '#a1a1aa' }}>
                    {fmtQty(colKeys.reduce((s, c) => s + getColTotal(c), 0))}
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
