'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  AllocationRow,
  CaseDemandRow,
  CaseSupplyRow,
  ConfigVersion,
  createAllocationVersion,
  deleteAllocation,
  deleteAllocationVersion,
  exportAllocationCsv,
  generateAllocation,
  getAllocation,
  getCaseDemands,
  getCaseSupplies,
  importAllocationCsv,
  listAllocationVersions,
  updateAllocationRows,
  updateAllocationVersion,
} from '../../../../lib/api';
import { VersionSwitcher } from '@/app/components/VersionSwitcher';
import { AllocationMatrixView, parsePKey, type UndoBatch } from '@/app/components/AllocationMatrixView';

// ── Component ─────────────────────────────────────────────────────────────────

export function AllocationPage() {
  const params = useParams();
  const caseId = Number(params.id);
  // Optional ?version_id= — set when arriving from ConfigDetailView's "Open full page" link for
  // a specific historical version (see CaseConfigVersions' own doc).
  const initialVersionId = Number(useSearchParams().get('version_id')) || undefined;
  const t = useTranslations('allocationPage');

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
  const importRef = useRef<HTMLInputElement>(null);

  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [versionId, setVersionId] = useState<number | null>(null);
  const referenced = versions.find(v => v.id === versionId)?.referenced ?? false;

  const hasPending = pendingChanges.size > 0;

  // ── Load ──────────────────────────────────────────────────────────────────

  const loadVersion = useCallback((vId: number | undefined) => {
    if (!caseId || isNaN(caseId)) return;
    setRows(null);
    Promise.all([getAllocation(caseId, vId), getCaseSupplies(caseId), getCaseDemands(caseId), listAllocationVersions(caseId)])
      .then(([a, s, d, vs]) => {
        setRows(a ?? []); setSupplies(s); setDemands(d);
        setVersions(vs);
        setVersionId(vId ?? vs[vs.length - 1]?.id ?? null);
      })
      .catch(e => setError(String(e)));
  }, [caseId]);

  useEffect(() => { loadVersion(initialVersionId); }, [loadVersion, initialVersionId]);

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
      if (href && !href.startsWith('#') && !confirm(t('confirmLeave'))) {
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
      const mod = e.metaKey || e.ctrlKey;
      if (mod && !e.shiftKey && e.key === 'z') { e.preventDefault(); undoRef.current(); }
      if (mod && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) { e.preventDefault(); redoRef.current(); }
      if (mod && e.key === 's') { e.preventDefault(); saveRef.current(); }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, []);

  // ── Committed cell map ────────────────────────────────────────────────────
  // Kept here (independent of AllocationMatrixView's own internal copy) purely so
  // pushEdit/undo/redo can look up each key's true SAVED value — the "did this net back to the
  // committed state" check needs the pre-any-pending-edit value, not the batch's own oldQty
  // (which may itself already reflect an earlier uncommitted edit).
  const cellMap = React.useMemo(() => {
    const m = new Map<string, Map<string, number>>();
    for (const r of rows ?? []) {
      if (!m.has(r.supply_id)) m.set(r.supply_id, new Map());
      m.get(r.supply_id)!.set(r.demand_id ?? '', r.qty_allocated);
    }
    return m;
  }, [rows]);

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

  // ── Save ──────────────────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    if (!pendingChanges.size || referenced) return;
    setSaving(true);
    setError(null);
    try {
      const updated: AllocationRow[] = Array.from(pendingChanges.entries()).map(([key, qty]) => {
        const { supplyId, demandId } = parsePKey(key);
        return { supply_id: supplyId, demand_id: demandId || null, qty_allocated: qty };
      });
      await updateAllocationRows(caseId, updated, versionId ?? undefined);
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
  }, [pendingChanges, caseId, versionId, referenced]);

  useEffect(() => { saveRef.current = handleSave; }, [handleSave]);

  // ── Actions ───────────────────────────────────────────────────────────────

  const clearPending = () => { setPendingChanges(new Map()); setUndoStack([]); setRedoStack([]); };

  const handleGenerate = async () => {
    if (referenced) return;
    if (hasPending && !confirm(t('confirmDiscard'))) return;
    setGenerating(true); setError(null);
    try { setRows(await generateAllocation(caseId, versionId ?? undefined)); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setGenerating(false); }
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || referenced) return;
    if (hasPending && !confirm(t('confirmDiscard'))) {
      if (importRef.current) importRef.current.value = '';
      return;
    }
    setImportLoading(true); setError(null);
    try { setRows(await importAllocationCsv(caseId, await file.text(), versionId ?? undefined)); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setImportLoading(false); if (importRef.current) importRef.current.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportAllocationCsv(caseId, versionId ?? undefined);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a   = Object.assign(document.createElement('a'), { href: url, download: `allocation_case_${caseId}.csv` });
      a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (referenced || !confirm(t('confirmClear'))) return;
    setClearing(true);
    try { await deleteAllocation(caseId, versionId ?? undefined); setRows([]); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setClearing(false); }
  };

  // ── Versioning ────────────────────────────────────────────────────────────

  const handleSwitchVersion = (vId: number) => { setVersionId(vId); loadVersion(vId); };

  const handleSaveAs = async (name: string | undefined, comments: string | undefined) => {
    setSaving(true); setError(null);
    try {
      const v = await createAllocationVersion(caseId, { name, comments, rows: rows ?? [] });
      clearPending();
      loadVersion(v.id);
    } catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  };


  const handleRename = async (vId: number, name: string | undefined, comments: string | undefined) => {
    try {
      await updateAllocationVersion(caseId, vId, { name: name ?? null, comments: comments ?? null });
      setVersions(await listAllocationVersions(caseId));
    } catch (e) { setError(String(e)); }
  };

  const handleDeleteVersion = async (vId: number) => {
    try {
      await deleteAllocationVersion(caseId, vId);
      if (vId === versionId) { loadVersion(undefined); } else {
        setVersions(await listAllocationVersions(caseId));
      }
    } catch (e) { setError(String(e)); }
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

  // ── Render ────────────────────────────────────────────────────────────────

  if (rows === null)
    return <div style={{ padding: '2rem', color: '#71717a', fontSize: '0.875rem' }}>{t('loading')}</div>;

  const sep = <span style={{ color: '#3f3f46', padding: '0 0.15rem' }}>|</span>;

  return (
    <div style={{ padding: '1.25rem 1.5rem', minHeight: '100vh', background: '#0e0e10', color: '#e4e4e7' }}>

      <VersionSwitcher
        versions={versions}
        currentVersionId={versionId}
        onSwitch={handleSwitchVersion}
        onSaveAs={handleSaveAs}
        onRename={handleRename}
        onDelete={handleDeleteVersion}
      />

      {/* Action bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button style={btn('primary')} onClick={handleGenerate} disabled={generating || referenced}>
          {generating ? t('generating') : t('generate')}
        </button>
        <button style={btn()} onClick={() => importRef.current?.click()} disabled={importLoading || referenced}>
          {importLoading ? t('uploading') : t('uploadCsv')}
        </button>
        <input ref={importRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImport} />
        <button style={btn()} onClick={handleExport} disabled={!rows.length}>{t('downloadCsv')}</button>
        <button style={btn('danger')} onClick={handleClear} disabled={clearing || !rows.length || referenced}>
          {clearing ? t('clearing') : t('clear')}
        </button>
        {!!rows.length && (<>
          {sep}
          <button style={{ ...btn(), opacity: undoStack.length ? 1 : 0.35 }}
            onClick={undo} disabled={!undoStack.length} title={t('undoTitle')}>{t('undo')}</button>
          <button style={{ ...btn(), opacity: redoStack.length ? 1 : 0.35 }}
            onClick={redo} disabled={!redoStack.length} title={t('redoTitle')}>{t('redo')}</button>
          {sep}
          <button
            style={{ ...btn(hasPending ? 'save' : 'ghost'), opacity: hasPending ? 1 : 0.35 }}
            onClick={handleSave} disabled={!hasPending || saving || referenced} title={t('saveTitle')}>
            {saving ? t('saving') : hasPending ? t('saveWithCount', { count: pendingChanges.size }) : t('save')}
          </button>
        </>)}
      </div>

      {/* Unsaved-changes banner */}
      {hasPending && (
        <div style={{ background: '#1c1008', border: '1px solid #78350f', borderRadius: 4, padding: '0.4rem 0.75rem', fontSize: '0.78rem', color: '#fdba74', marginBottom: '0.75rem' }}>
          {t('unsavedBanner', { count: pendingChanges.size })}
        </div>
      )}

      {error && (
        <div style={{ background: '#450a0a', border: '1px solid #7f1d1d', borderRadius: 4, padding: '0.5rem 0.75rem', fontSize: '0.8rem', color: '#fca5a5', marginBottom: '0.75rem' }}>
          {error}
        </div>
      )}

      {!rows.length ? (
        <div style={{ padding: '3rem 1rem', textAlign: 'center', color: '#52525b', fontSize: '0.875rem' }}>
          {t('emptyHint')} <strong style={{ color: '#93c5fd' }}>{t('generate')}</strong> {t('emptyHintMiddle')} <strong style={{ color: '#93c5fd' }}>{t('uploadCsv')}</strong> {t('emptyHintAfter')}
        </div>
      ) : (
        <AllocationMatrixView
          rows={rows}
          supplies={supplies}
          demands={demands}
          pendingChanges={pendingChanges}
          onCommitBatch={pushEdit}
          t={t}
        />
      )}
    </div>
  );
}
