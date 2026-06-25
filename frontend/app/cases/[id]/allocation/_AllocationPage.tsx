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

type ColSpec =
  | { type: 'cust';   custId: string }
  | { type: 'demand'; demandId: string; custId: string };

type SupplyGroupKey = string;

/** One user action worth of changes — each entry is a single demand cell's old+new qty. */
type UndoBatch = Array<{ key: string; oldQty: number; newQty: number }>;

function colSpecKey(s: ColSpec): string {
  return s.type === 'cust' ? `c:${s.custId}` : `d:${s.demandId}`;
}
function pKey(supplyId: string, demandId: string): string {
  return `${supplyId}||${demandId}`;
}
function parsePKey(key: string): { supplyId: string; demandId: string } {
  const i = key.indexOf('||');
  return { supplyId: key.slice(0, i), demandId: key.slice(i + 2) };
}
function fmtQty(q: number): string {
  if (q === 0) return '—';
  return q >= 1000
    ? q.toLocaleString(undefined, { maximumFractionDigits: 0 })
    : q.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

// ── Component ─────────────────────────────────────────────────────────────────

export function AllocationPage() {
  const params = useParams();
  const caseId = Number(params.id);

  // Committed state
  const [rows, setRows]         = useState<AllocationRow[] | null>(null);
  const [supplies, setSupplies] = useState<CaseSupplyRow[]>([]);
  const [demands, setDemands]   = useState<CaseDemandRow[]>([]);

  // Pending edit buffer
  const [pendingChanges, setPendingChanges] = useState<Map<string, number>>(new Map());
  const [undoStack, setUndoStack] = useState<UndoBatch[]>([]);
  const [redoStack, setRedoStack] = useState<UndoBatch[]>([]);

  // UI flags
  const [generating, setGenerating]     = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing]         = useState(false);
  const [saving, setSaving]             = useState(false);
  const [error, setError]               = useState<string | null>(null);
  const [supplyPivot, setSupplyPivot]   = useState<SupplyPivot>('product-location');
  const [expandedSupplyGroups, setExpandedSupplyGroups] = useState<Set<string>>(new Set());
  const [expandedCustomers, setExpandedCustomers]       = useState<Set<string>>(new Set());
  const [editingCell, setEditingCell]   = useState<{ supplyId: string; spec: ColSpec } | null>(null);
  const [editValue, setEditValue]       = useState('');
  const importRef = useRef<HTMLInputElement>(null);

  const hasPending = pendingChanges.size > 0;

  // ── Load ──────────────────────────────────────────────────────────────────

  useEffect(() => {
    if (!caseId || isNaN(caseId)) return;
    setRows(null);
    Promise.all([getAllocation(caseId), getCaseSupplies(caseId), getCaseDemands(caseId)])
      .then(([a, s, d]) => { setRows(a ?? []); setSupplies(s); setDemands(d); })
      .catch(e => setError(String(e)));
  }, [caseId]);

  // ── Navigation guards ─────────────────────────────────────────────────────

  useEffect(() => {
    if (!hasPending) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [hasPending]);

  useEffect(() => {
    if (!hasPending) return;
    const h = (e: MouseEvent) => {
      const a = (e.target as HTMLElement).closest('a[href]') as HTMLAnchorElement | null;
      if (!a) return;
      const href = a.getAttribute('href') ?? '';
      if (href && !href.startsWith('#') && !confirm('You have unsaved changes. Leave without saving?')) {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    document.addEventListener('click', h, true);
    return () => document.removeEventListener('click', h, true);
  }, [hasPending]);

  // ── Keyboard shortcuts (stable listener via fn refs) ──────────────────────

  const undoRef   = useRef<() => void>(() => {});
  const redoRef   = useRef<() => void>(() => {});
  const saveRef   = useRef<() => void>(() => {});

  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (editingCell) return;
      const mod = e.metaKey || e.ctrlKey;
      if (mod && !e.shiftKey && e.key === 'z') { e.preventDefault(); undoRef.current(); }
      if (mod && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) { e.preventDefault(); redoRef.current(); }
      if (mod && e.key === 's') { e.preventDefault(); saveRef.current(); }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [editingCell]);

  // ── Derived maps ──────────────────────────────────────────────────────────

  const supplyMeta = React.useMemo(
    () => new Map(supplies.map(s => [s.supplyId, s])), [supplies]);

  const demandMeta = React.useMemo(
    () => new Map(demands.map(d => [d.demand_id, d])), [demands]);

  const supplyGroupKey = useCallback((id: string): SupplyGroupKey => {
    const s = supplyMeta.get(id);
    return s ? `${s.productId}|${s.locationId ?? ''}` : id;
  }, [supplyMeta]);

  const demandGroups = React.useMemo(() => {
    if (!rows?.length) return [];
    const m = new Map<string, Set<string>>();
    for (const r of rows) {
      const did  = r.demand_id ?? '';
      const cust = demandMeta.get(did)?.customer_id ?? 'Unknown';
      if (!m.has(cust)) m.set(cust, new Set());
      m.get(cust)!.add(did);
    }
    return Array.from(m.entries()).sort(([a], [b]) => a.localeCompare(b))
      .map(([custId, dids]) => ({ custId, demandIds: Array.from(dids).sort() }));
  }, [rows, demandMeta]);

  const colSpecs: ColSpec[] = React.useMemo(
    () => demandGroups.flatMap(({ custId, demandIds }): ColSpec[] =>
      expandedCustomers.has(custId)
        ? demandIds.map(demandId => ({ type: 'demand' as const, demandId, custId }))
        : [{ type: 'cust' as const, custId }]
    ), [demandGroups, expandedCustomers]);

  const anyCustomerExpanded = demandGroups.some(g => expandedCustomers.has(g.custId));

  const supplyGroups = React.useMemo(() => {
    if (!rows?.length) return [];
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

  const cellMap = React.useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const r of rows ?? []) {
      if (!m.has(r.supply_id)) m.set(r.supply_id, new Map());
      m.get(r.supply_id)!.set(r.demand_id ?? '', r.qty_allocated);
    }
    return m;
  }, [rows]);

  /** Committed rows overlaid with pending changes. */
  const effectiveCellMap = React.useMemo((): Map<string, Map<string, number>> => {
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
    demandGroups.find(g => g.custId === custId)?.demandIds ?? [], [demandGroups]);

  const getCellQty = useCallback((supplyId: string, spec: ColSpec): number => {
    const sm = effectiveCellMap.get(supplyId);
    if (!sm) return 0;
    if (spec.type === 'demand') return sm.get(spec.demandId) ?? 0;
    return getCustomerDemandIds(spec.custId).reduce((s, did) => s + (sm.get(did) ?? 0), 0);
  }, [effectiveCellMap, getCustomerDemandIds]);

  const getGroupCellQty = useCallback((gk: SupplyGroupKey, spec: ColSpec): number => {
    const g = supplyGroups.find(g => g.groupKey === gk);
    return g ? g.lotIds.reduce((s, sid) => s + getCellQty(sid, spec), 0) : 0;
  }, [supplyGroups, getCellQty]);

  const getLotRowTotal  = useCallback((sid: string) =>
    colSpecs.reduce((s, spec) => s + getCellQty(sid, spec), 0), [colSpecs, getCellQty]);

  const getGroupRowTotal = useCallback((gk: SupplyGroupKey) =>
    colSpecs.reduce((s, spec) => s + getGroupCellQty(gk, spec), 0), [colSpecs, getGroupCellQty]);

  const getColTotal = useCallback((spec: ColSpec): number => {
    if (supplyPivot === 'lot')
      return supplies.filter(s => effectiveCellMap.has(s.supplyId))
        .reduce((s, sup) => s + getCellQty(sup.supplyId, spec), 0);
    return supplyGroups.reduce((s, g) => s + getGroupCellQty(g.groupKey, spec), 0);
  }, [supplyPivot, supplies, supplyGroups, getCellQty, getGroupCellQty, effectiveCellMap]);

  // ── Dirty detection ───────────────────────────────────────────────────────

  const isSpecDirty = useCallback((supplyId: string, spec: ColSpec): boolean => {
    if (spec.type === 'demand') return pendingChanges.has(pKey(supplyId, spec.demandId));
    return getCustomerDemandIds(spec.custId).some(did => pendingChanges.has(pKey(supplyId, did)));
  }, [pendingChanges, getCustomerDemandIds]);

  const isGroupSpecDirty = useCallback((gk: SupplyGroupKey, spec: ColSpec): boolean => {
    const g = supplyGroups.find(g => g.groupKey === gk);
    return g?.lotIds.some(sid => isSpecDirty(sid, spec)) ?? false;
  }, [supplyGroups, isSpecDirty]);

  // ── Edit history ──────────────────────────────────────────────────────────

  const pushEdit = useCallback((batch: UndoBatch) => {
    setPendingChanges(prev => {
      const next = new Map(prev);
      for (const { key, newQty } of batch) {
        const { supplyId, demandId } = parsePKey(key);
        const committed = cellMap.get(supplyId)?.get(demandId) ?? 0;
        if (Math.abs(newQty - committed) < 1e-12) next.delete(key);
        else next.set(key, newQty);
      }
      return next;
    });
    setUndoStack(prev => [...prev, batch]);
    setRedoStack([]);
  }, [cellMap]);

  const undo = useCallback(() => {
    setUndoStack(prev => {
      if (!prev.length) return prev;
      const batch = prev[prev.length - 1];
      setRedoStack(r => [...r, batch]);
      setPendingChanges(cur => {
        const next = new Map(cur);
        for (const { key, oldQty } of batch) {
          const { supplyId, demandId } = parsePKey(key);
          const committed = cellMap.get(supplyId)?.get(demandId) ?? 0;
          if (Math.abs(oldQty - committed) < 1e-12) next.delete(key);
          else next.set(key, oldQty);
        }
        return next;
      });
      return prev.slice(0, -1);
    });
  }, [cellMap]);

  const redo = useCallback(() => {
    setRedoStack(prev => {
      if (!prev.length) return prev;
      const batch = prev[prev.length - 1];
      setUndoStack(u => [...u, batch]);
      setPendingChanges(cur => {
        const next = new Map(cur);
        for (const { key, newQty } of batch) {
          const { supplyId, demandId } = parsePKey(key);
          const committed = cellMap.get(supplyId)?.get(demandId) ?? 0;
          if (Math.abs(newQty - committed) < 1e-12) next.delete(key);
          else next.set(key, newQty);
        }
        return next;
      });
      return prev.slice(0, -1);
    });
  }, [cellMap]);

  // Wire fn refs (keeps keyboard listener stable while always calling latest)
  useEffect(() => { undoRef.current = undo; }, [undo]);
  useEffect(() => { redoRef.current = redo; }, [redo]);

  // ── Editing ───────────────────────────────────────────────────────────────

  const editSpecKey = editingCell ? colSpecKey(editingCell.spec) : null;

  const startEdit = (supplyId: string, spec: ColSpec, qty: number) => {
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
      const sm   = effectiveCellMap.get(supplyId);
      const oldTotal = dids.reduce((s, did) => s + (sm?.get(did) ?? 0), 0);
      for (const did of dids) {
        const oldQty = sm?.get(did) ?? 0;
        const nq     = oldTotal > 0 ? newQty * (oldQty / oldTotal) : newQty / dids.length;
        if (nq !== oldQty) batch.push({ key: pKey(supplyId, did), oldQty, newQty: nq });
      }
    }
    if (batch.length) pushEdit(batch);
  }, [editingCell, editValue, effectiveCellMap, getCustomerDemandIds, pushEdit]);

  const commitGroupEdit = useCallback((gk: SupplyGroupKey, spec: ColSpec, newTotal: number) => {
    const group = supplyGroups.find(g => g.groupKey === gk);
    if (!group) return;
    const oldGroupTotal = group.lotIds.reduce((s, sid) => s + getCellQty(sid, spec), 0);
    const batch: UndoBatch = [];

    for (const sid of group.lotIds) {
      const oldLotQty = getCellQty(sid, spec);
      const lotNew    = oldGroupTotal > 0
        ? newTotal * (oldLotQty / oldGroupTotal)
        : newTotal / group.lotIds.length;

      if (spec.type === 'demand') {
        const oldQty = effectiveCellMap.get(sid)?.get(spec.demandId) ?? 0;
        if (lotNew !== oldQty) batch.push({ key: pKey(sid, spec.demandId), oldQty, newQty: lotNew });
      } else {
        const dids   = getCustomerDemandIds(spec.custId);
        const sm     = effectiveCellMap.get(sid);
        const oldLotCustTotal = dids.reduce((s, did) => s + (sm?.get(did) ?? 0), 0);
        for (const did of dids) {
          const oldQty = sm?.get(did) ?? 0;
          const nq     = oldLotCustTotal > 0
            ? lotNew * (oldQty / oldLotCustTotal)
            : lotNew / Math.max(1, dids.length);
          if (nq !== oldQty) batch.push({ key: pKey(sid, did), oldQty, newQty: nq });
        }
      }
    }
    if (batch.length) pushEdit(batch);
  }, [supplyGroups, getCellQty, getCustomerDemandIds, effectiveCellMap, pushEdit]);

  // ── Save ──────────────────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    if (!pendingChanges.size) return;
    setSaving(true);
    setError(null);
    try {
      const updated: AllocationRow[] = Array.from(pendingChanges.entries()).map(([key, qty]) => {
        const { supplyId, demandId } = parsePKey(key);
        return { supply_id: supplyId, demand_id: demandId || null, qty_allocated: qty };
      });
      await updateAllocationRows(caseId, updated);
      setRows(prev => {
        if (!prev) return prev;
        const next = [...prev];
        for (const ur of updated) {
          const idx = next.findIndex(r =>
            r.supply_id === ur.supply_id && (r.demand_id ?? '') === (ur.demand_id ?? ''));
          if (idx >= 0) next[idx] = ur; else next.push(ur);
        }
        return next;
      });
      setPendingChanges(new Map());
      setUndoStack([]);
      setRedoStack([]);
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }, [pendingChanges, caseId]);

  useEffect(() => { saveRef.current = handleSave; }, [handleSave]);

  // ── Actions ───────────────────────────────────────────────────────────────

  const clearPending = () => { setPendingChanges(new Map()); setUndoStack([]); setRedoStack([]); };

  const handleGenerate = async () => {
    if (hasPending && !confirm('This will discard unsaved changes. Continue?')) return;
    setGenerating(true); setError(null);
    try { setRows(await generateAllocation(caseId)); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setGenerating(false); }
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (hasPending && !confirm('This will discard unsaved changes. Continue?')) {
      if (importRef.current) importRef.current.value = '';
      return;
    }
    setImportLoading(true); setError(null);
    try { setRows(await importAllocationCsv(caseId, await file.text())); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setImportLoading(false); if (importRef.current) importRef.current.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportAllocationCsv(caseId);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a   = Object.assign(document.createElement('a'), { href: url, download: `allocation_case_${caseId}.csv` });
      a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (!confirm('Clear the allocation map? Planning will revert to auto-allocation.')) return;
    setClearing(true);
    try { await deleteAllocation(caseId); setRows([]); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setClearing(false); }
  };

  // ── Styles ────────────────────────────────────────────────────────────────

  const btn = (v: 'primary' | 'ghost' | 'danger' | 'save' = 'ghost'): React.CSSProperties => ({
    padding: '0.35rem 0.75rem', fontSize: '0.8rem', borderRadius: 4, cursor: 'pointer',
    border: v === 'danger' ? '1px solid #7f1d1d' : v === 'primary' ? '1px solid #1d4ed8'
          : v === 'save'   ? '1px solid #065f46'  : '1px solid #3f3f46',
    background: v === 'danger' ? '#450a0a' : v === 'primary' ? '#1e3a8a'
              : v === 'save'   ? '#064e3b'  : '#1c1c1f',
    color: v === 'danger' ? '#fca5a5' : v === 'save' ? '#6ee7b7' : '#e4e4e7',
  });

  const toggleBtn = (active: boolean): React.CSSProperties => ({
    padding: '0.25rem 0.6rem', fontSize: '0.75rem', borderRadius: 3, cursor: 'pointer',
    border: '1px solid #3f3f46',
    background: active ? '#27272a' : 'transparent',
    color: active ? '#e4e4e7' : '#71717a',
  });

  const cellSt = (isEdit: boolean, qty: number, dirty: boolean): React.CSSProperties => ({
    padding: '0.3rem 0.5rem', textAlign: 'right', fontSize: '0.78rem', cursor: 'pointer',
    minWidth: 70, whiteSpace: 'nowrap',
    color:       dirty ? '#fdba74' : qty === 0 ? '#3f3f46' : '#e4e4e7',
    background:  isEdit ? '#1e3a8a22' : dirty ? '#2c1600' : 'transparent',
    outline:     dirty ? '1px solid #78350f' : 'none',
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

  // ── Render ────────────────────────────────────────────────────────────────

  if (rows === null)
    return <div style={{ padding: '2rem', color: '#71717a', fontSize: '0.875rem' }}>Loading allocation…</div>;

  const totalLots    = new Set(rows.map(r => r.supply_id)).size;
  const totalDemands = new Set(rows.map(r => r.demand_id ?? '')).size;

  const EditInput = ({ onCommit, onEscape }: { onCommit: () => void; onEscape: () => void }) => (
    <input autoFocus value={editValue}
      onChange={e => setEditValue(e.target.value)}
      onBlur={onCommit}
      onKeyDown={e => { if (e.key === 'Enter') onCommit(); if (e.key === 'Escape') onEscape(); }}
      style={{ width: 68, background: '#1e3a8a', border: 'none', color: '#e4e4e7', fontSize: '0.78rem', textAlign: 'right', padding: '0 2px' }}
    />
  );

  const sep = <span style={{ color: '#3f3f46', padding: '0 0.15rem' }}>|</span>;

  return (
    <div style={{ padding: '1.25rem 1.5rem', minHeight: '100vh', background: '#0e0e10', color: '#e4e4e7' }}>

      {/* Action bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button style={btn('primary')} onClick={handleGenerate} disabled={generating}>
          {generating ? 'Generating…' : 'Generate'}
        </button>
        <button style={btn()} onClick={() => importRef.current?.click()} disabled={importLoading}>
          {importLoading ? 'Uploading…' : 'Upload CSV'}
        </button>
        <input ref={importRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImport} />
        <button style={btn()} onClick={handleExport} disabled={!rows.length}>Download CSV</button>
        <button style={btn('danger')} onClick={handleClear} disabled={clearing || !rows.length}>
          {clearing ? 'Clearing…' : 'Clear'}
        </button>
        {!!rows.length && (<>
          {sep}
          <button style={{ ...btn(), opacity: undoStack.length ? 1 : 0.35 }}
            onClick={undo} disabled={!undoStack.length} title="Undo  Ctrl+Z">↩ Undo</button>
          <button style={{ ...btn(), opacity: redoStack.length ? 1 : 0.35 }}
            onClick={redo} disabled={!redoStack.length} title="Redo  Ctrl+Shift+Z">↪ Redo</button>
          {sep}
          <button
            style={{ ...btn(hasPending ? 'save' : 'ghost'), opacity: hasPending ? 1 : 0.35 }}
            onClick={handleSave} disabled={!hasPending || saving} title="Save  Ctrl+S">
            {saving ? 'Saving…' : hasPending ? `Save (${pendingChanges.size})` : 'Save'}
          </button>
        </>)}
      </div>

      {/* Unsaved-changes banner */}
      {hasPending && (
        <div style={{ background: '#1c1008', border: '1px solid #78350f', borderRadius: 4, padding: '0.4rem 0.75rem', fontSize: '0.78rem', color: '#fdba74', marginBottom: '0.75rem' }}>
          ⚠ {pendingChanges.size} unsaved change{pendingChanges.size !== 1 ? 's' : ''} — press <strong>Ctrl+S</strong> to save or <strong>Undo</strong> to revert.
        </div>
      )}

      {error && (
        <div style={{ background: '#450a0a', border: '1px solid #7f1d1d', borderRadius: 4, padding: '0.5rem 0.75rem', fontSize: '0.8rem', color: '#fca5a5', marginBottom: '0.75rem' }}>
          {error}
        </div>
      )}

      {!rows.length ? (
        <div style={{ padding: '3rem 1rem', textAlign: 'center', color: '#52525b', fontSize: '0.875rem' }}>
          No allocation map — click <strong style={{ color: '#93c5fd' }}>Generate</strong> to create one, or <strong style={{ color: '#93c5fd' }}>Upload CSV</strong> to load one.
        </div>
      ) : (<>
        {/* Pivot toggles */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.75rem', fontSize: '0.8rem', color: '#71717a' }}>
          <span>{totalLots} supply lots × {totalDemands} demands</span>
          {sep}
          <span>Supply:</span>
          <button style={toggleBtn(supplyPivot === 'lot')} onClick={() => setSupplyPivot('lot')}>By Lot</button>
          <button style={toggleBtn(supplyPivot === 'product-location')} onClick={() => setSupplyPivot('product-location')}>By Product+Location</button>
        </div>

        {/* Matrix */}
        <div style={{ overflowX: 'auto', border: '1px solid #27272a', borderRadius: 6 }}>
          <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
            <thead>
              <tr>
                <th rowSpan={anyCustomerExpanded ? 2 : 1} style={labelThSt}>Supply</th>
                {demandGroups.map(({ custId, demandIds }) => {
                  const exp = expandedCustomers.has(custId);
                  return (
                    <th key={custId}
                      colSpan={exp ? demandIds.length : 1}
                      rowSpan={(!exp && anyCustomerExpanded) ? 2 : 1}
                      style={{ ...thSt, cursor: 'pointer', borderLeft: '1px solid #27272a' }}
                      onClick={() => setExpandedCustomers(prev => {
                        const n = new Set(prev); n.has(custId) ? n.delete(custId) : n.add(custId); return n;
                      })}
                      title={custId}>
                      <span style={{ marginRight: 3, fontSize: '0.65rem', color: '#52525b' }}>{exp ? '▾' : '▸'}</span>
                      {custId.length > 16 ? custId.slice(0, 14) + '…' : custId}
                      <span style={{ marginLeft: 4, fontSize: '0.65rem', color: '#52525b' }}>({demandIds.length})</span>
                    </th>
                  );
                })}
                <th rowSpan={anyCustomerExpanded ? 2 : 1} style={{ ...thSt, borderLeft: '1px solid #27272a' }}>Total</th>
              </tr>
              {anyCustomerExpanded && (
                <tr>
                  {demandGroups.flatMap(({ custId, demandIds }) =>
                    expandedCustomers.has(custId)
                      ? demandIds.map(did => (
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
                supplies.filter(s => cellMap.has(s.supplyId))
                  .sort((a, b) => {
                    const ga = supplyGroupKey(a.supplyId), gb = supplyGroupKey(b.supplyId);
                    return ga !== gb ? ga.localeCompare(gb) : a.supplyId.localeCompare(b.supplyId);
                  })
                  .map(s => {
                    const total = getLotRowTotal(s.supplyId);
                    const lbl   = `${s.productId} @ ${s.locationId ?? '?'}${s.supplyDate ? ' (' + s.supplyDate.slice(0, 10) + ')' : ''}`;
                    return (
                      <tr key={s.supplyId} style={rowSt(false)}>
                        <td style={labelTd()} title={lbl}>{lbl.length > 34 ? lbl.slice(0, 32) + '…' : lbl}</td>
                        {colSpecs.map(spec => {
                          const qty   = getCellQty(s.supplyId, spec);
                          const k     = colSpecKey(spec);
                          const isEd  = editingCell?.supplyId === s.supplyId && editSpecKey === k;
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
                supplyGroups.map(group => {
                  const exp   = expandedSupplyGroups.has(group.groupKey);
                  const gTotal = getGroupRowTotal(group.groupKey);
                  return (
                    <React.Fragment key={group.groupKey}>
                      <tr style={rowSt(true)}>
                        <td style={labelTd()} title={group.label}
                          onClick={() => setExpandedSupplyGroups(prev => {
                            const n = new Set(prev); exp ? n.delete(group.groupKey) : n.add(group.groupKey); return n;
                          })}>
                          <span style={{ marginRight: 4, color: '#52525b', fontSize: '0.7rem' }}>{exp ? '▾' : '▸'}</span>
                          {group.label.length > 30 ? group.label.slice(0, 28) + '…' : group.label}
                          <span style={{ marginLeft: 6, color: '#52525b', fontSize: '0.7rem' }}>({group.lotIds.length})</span>
                        </td>
                        {colSpecs.map(spec => {
                          const qty   = getGroupCellQty(group.groupKey, spec);
                          const k     = colSpecKey(spec);
                          const isEd  = editingCell?.supplyId === group.groupKey && editSpecKey === k;
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
                      {exp && group.lotIds.map(sid => {
                        const sm    = supplyMeta.get(sid);
                        const total = getLotRowTotal(sid);
                        const lbl   = sm?.supplyDate ? sm.supplyDate.slice(0, 10) + ' · ' + sid : sid;
                        return (
                          <tr key={sid} style={rowSt(false)}>
                            <td style={labelTd(true)} title={sid}>{lbl.length > 32 ? lbl.slice(0, 30) + '…' : lbl}</td>
                            {colSpecs.map(spec => {
                              const qty   = getCellQty(sid, spec);
                              const k     = colSpecKey(spec);
                              const isEd  = editingCell?.supplyId === sid && editSpecKey === k;
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
                <td style={{ ...labelTd(), color: '#71717a', fontWeight: 500, cursor: 'default' }}>Total</td>
                {colSpecs.map(spec => {
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
      </>)}
    </div>
  );
}
