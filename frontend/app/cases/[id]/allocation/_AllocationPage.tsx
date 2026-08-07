'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  AllocationRow,
  CaseDemandRow,
  CaseSupplyRow,
  ConfigVersion,
  TsaRow,
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
  previewAllocation,
  updateAllocationRows,
  updateAllocationVersion,
} from '../../../../lib/api';
import { VersionSwitcher } from '@/app/components/VersionSwitcher';
import { TsaTable } from '@/app/components/TsaTable';

// ── Types ────────────────────────────────────────────────────────────────────

/** One committed cell edit, for undo/redo — a lot has at most one target, so moving it to a
 *  different customer column changes qty_cap and target together, atomically. */
type CellEdit = {
  supplyId: string;
  oldQtyCap: number | null; oldTarget: string | null;
  newQtyCap: number | null; newTarget: string | null;
};

// ── Component ─────────────────────────────────────────────────────────────────

export function AllocationPage() {
  const params = useParams();
  const caseId = Number(params.id);
  const initialVersionId = Number(useSearchParams().get('version_id')) || undefined;
  const t = useTranslations('allocationPage');

  // Committed state
  const [tsaRows, setTsaRows]   = useState<TsaRow[] | null>(null);
  const [supplies, setSupplies] = useState<CaseSupplyRow[]>([]);
  const [demands, setDemands]   = useState<CaseDemandRow[]>([]);

  // Pending edit buffer — keyed by supply_id (one TSA row per lot).
  const [pendingQtyCap, setPendingQtyCap] = useState<Map<string, number | null>>(new Map());
  const [pendingTarget, setPendingTarget] = useState<Map<string, string | null>>(new Map());
  const [undoStack, setUndoStack] = useState<CellEdit[]>([]);
  const [redoStack, setRedoStack] = useState<CellEdit[]>([]);

  // The table IS the preview — recomputed (supply_lot, demand) -> qty_allocated, refreshed after
  // every commit (including unsaved pending edits) so it's always the single source of truth for
  // both input and output, never a second stale view.
  const [previewRows, setPreviewRows] = useState<AllocationRow[]>([]);
  const [rawCriticalIds, setRawCriticalIds] = useState<string[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);

  // UI flags
  const [generating, setGenerating]       = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing]           = useState(false);
  const [saving, setSaving]               = useState(false);
  const [error, setError]                 = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [versionId, setVersionId] = useState<number | null>(null);
  const referenced = versions.find(v => v.id === versionId)?.referenced ?? false;

  const hasPending = pendingQtyCap.size > 0 || pendingTarget.size > 0;

  // ── Load ──────────────────────────────────────────────────────────────────

  const loadVersion = useCallback((vId: number | undefined) => {
    if (!caseId || isNaN(caseId)) return;
    setTsaRows(null);
    Promise.all([getAllocation(caseId, vId), getCaseSupplies(caseId), getCaseDemands(caseId), listAllocationVersions(caseId)])
      .then(([rows, s, d, vs]) => {
        setTsaRows(rows ?? []); setSupplies(s); setDemands(d);
        setVersions(vs);
        setVersionId(vId ?? vs[vs.length - 1]?.id ?? null);
      })
      .catch(e => setError(String(e)));
  }, [caseId]);

  useEffect(() => { loadVersion(initialVersionId); }, [loadVersion, initialVersionId]);

  // ── Committed lookup + effective (pending-aware) overrides ────────────────

  const committed = React.useMemo(() => new Map((tsaRows ?? []).map(r => [r.supply_id, r])), [tsaRows]);

  const effectiveQtyCap = useCallback((supplyId: string): number | null =>
    pendingQtyCap.has(supplyId) ? pendingQtyCap.get(supplyId)! : (committed.get(supplyId)?.qty_cap ?? null),
    [pendingQtyCap, committed]);
  const effectiveTarget = useCallback((supplyId: string): string | null =>
    pendingTarget.has(supplyId) ? pendingTarget.get(supplyId)! : (committed.get(supplyId)?.target ?? null),
    [pendingTarget, committed]);

  const effectiveRows = useCallback((): TsaRow[] => {
    const ids = new Set<string>();
    (tsaRows ?? []).forEach(r => ids.add(r.supply_id));
    pendingQtyCap.forEach((_, sid) => ids.add(sid));
    pendingTarget.forEach((_, sid) => ids.add(sid));
    return Array.from(ids).map(sid => ({ supply_id: sid, qty_cap: effectiveQtyCap(sid), target: effectiveTarget(sid) }));
  }, [tsaRows, pendingQtyCap, pendingTarget, effectiveQtyCap, effectiveTarget]);

  // ── Live preview — the table's only data source, refreshed after every commit ─────────────

  useEffect(() => {
    if (tsaRows === null) return;
    let cancelled = false;
    setPreviewLoading(true);
    previewAllocation(caseId, versionId ?? undefined, effectiveRows())
      .then((p) => { if (!cancelled) { setPreviewRows(p.rows); setRawCriticalIds(p.rawCriticalSupplyIds); } })
      .catch((e) => { if (!cancelled) setError(String(e)); })
      .finally(() => { if (!cancelled) setPreviewLoading(false); });
    return () => { cancelled = true; };
    // effectiveRows already depends on tsaRows/pendingQtyCap/pendingTarget — including it alone
    // as a dep would refresh on every render, so its own inputs are listed instead.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId, versionId, tsaRows, pendingQtyCap, pendingTarget]);

  const allSupplyIds = React.useMemo(() =>
    Array.from(new Set([...rawCriticalIds, ...previewRows.map(r => r.supply_id)])).sort(),
    [rawCriticalIds, previewRows]);

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

  // ── Edit history ──────────────────────────────────────────────────────────

  const applyCellEdit = useCallback((edit: CellEdit, direction: 'do' | 'undo') => {
    const qtyCap = direction === 'do' ? edit.newQtyCap : edit.oldQtyCap;
    const target = direction === 'do' ? edit.newTarget : edit.oldTarget;
    const committedQtyCap = committed.get(edit.supplyId)?.qty_cap ?? null;
    const committedTarget = committed.get(edit.supplyId)?.target ?? null;
    setPendingQtyCap(prev => {
      const n = new Map(prev);
      if (qtyCap === committedQtyCap) n.delete(edit.supplyId); else n.set(edit.supplyId, qtyCap);
      return n;
    });
    setPendingTarget(prev => {
      const n = new Map(prev);
      if (target === committedTarget) n.delete(edit.supplyId); else n.set(edit.supplyId, target);
      return n;
    });
  }, [committed]);

  const handleEditCell = useCallback((supplyId: string, customerId: string | null, newQtyCap: number | null) => {
    const oldQtyCap = effectiveQtyCap(supplyId);
    const oldTarget = effectiveTarget(supplyId);
    if (newQtyCap === oldQtyCap && customerId === oldTarget) return;
    const edit: CellEdit = { supplyId, oldQtyCap, oldTarget, newQtyCap, newTarget: customerId };
    applyCellEdit(edit, 'do');
    setUndoStack(prev => [...prev, edit]);
    setRedoStack([]);
  }, [effectiveQtyCap, effectiveTarget, applyCellEdit]);

  const undo = useCallback(() => {
    setUndoStack(prev => {
      if (!prev.length) return prev;
      const edit = prev[prev.length - 1];
      applyCellEdit(edit, 'undo');
      setRedoStack(r => [...r, edit]);
      return prev.slice(0, -1);
    });
  }, [applyCellEdit]);

  const redo = useCallback(() => {
    setRedoStack(prev => {
      if (!prev.length) return prev;
      const edit = prev[prev.length - 1];
      applyCellEdit(edit, 'do');
      setUndoStack(u => [...u, edit]);
      return prev.slice(0, -1);
    });
  }, [applyCellEdit]);

  const undoRef = useRef<() => void>(() => {});
  const redoRef = useRef<() => void>(() => {});
  const saveRef = useRef<() => void>(() => {});

  useEffect(() => { undoRef.current = undo; }, [undo]);
  useEffect(() => { redoRef.current = redo; }, [redo]);

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

  // ── Save ──────────────────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    if (!hasPending || referenced) return;
    setSaving(true);
    setError(null);
    try {
      const changedIds = new Set<string>([...Array.from(pendingQtyCap.keys()), ...Array.from(pendingTarget.keys())]);
      const updated: TsaRow[] = Array.from(changedIds).map(sid => ({
        supply_id: sid, qty_cap: effectiveQtyCap(sid), target: effectiveTarget(sid),
      }));
      await updateAllocationRows(caseId, updated, versionId ?? undefined);
      setTsaRows(prev => {
        const next = [...(prev ?? [])];
        for (const ur of updated) {
          const idx = next.findIndex(r => r.supply_id === ur.supply_id);
          if (idx >= 0) next[idx] = ur; else next.push(ur);
        }
        return next;
      });
      setPendingQtyCap(new Map());
      setPendingTarget(new Map());
      setUndoStack([]);
      setRedoStack([]);
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }, [hasPending, caseId, versionId, referenced, pendingQtyCap, pendingTarget, effectiveQtyCap, effectiveTarget]);

  useEffect(() => { saveRef.current = handleSave; }, [handleSave]);

  // ── Actions ───────────────────────────────────────────────────────────────

  const clearPending = () => { setPendingQtyCap(new Map()); setPendingTarget(new Map()); setUndoStack([]); setRedoStack([]); };

  const handleGenerate = async () => {
    if (referenced) return;
    if (hasPending && !confirm(t('confirmDiscard'))) return;
    setGenerating(true); setError(null);
    try { setTsaRows(await generateAllocation(caseId, versionId ?? undefined)); clearPending(); }
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
    try { setTsaRows(await importAllocationCsv(caseId, await file.text(), versionId ?? undefined)); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setImportLoading(false); if (importRef.current) importRef.current.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportAllocationCsv(caseId, versionId ?? undefined);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a   = Object.assign(document.createElement('a'), { href: url, download: `targeted_supply_allocation_case_${caseId}.csv` });
      a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (referenced || !confirm(t('confirmClear'))) return;
    setClearing(true);
    try { await deleteAllocation(caseId, versionId ?? undefined); setTsaRows([]); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setClearing(false); }
  };

  // ── Versioning ────────────────────────────────────────────────────────────

  const handleSwitchVersion = (vId: number) => { setVersionId(vId); loadVersion(vId); };

  const handleSaveAs = async (name: string | undefined, comments: string | undefined) => {
    setSaving(true); setError(null);
    try {
      const v = await createAllocationVersion(caseId, { name, comments, rows: effectiveRows() });
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

  if (tsaRows === null)
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

      <div style={{ fontSize: '1rem', fontWeight: 600, marginBottom: '0.25rem' }}>
        {t('tsaHeader')}
        {previewLoading && <span style={{ marginLeft: 8, fontSize: '0.72rem', color: '#71717a', fontWeight: 400 }}>⟳</span>}
      </div>
      <div style={{ fontSize: '0.76rem', color: '#71717a', marginBottom: '0.75rem', maxWidth: 760 }}>{t('tsaHint')}</div>

      {/* Action bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button style={btn('primary')} onClick={handleGenerate} disabled={generating || referenced}>
          {generating ? t('generating') : t('generate')}
        </button>
        <button style={btn()} onClick={() => importRef.current?.click()} disabled={importLoading || referenced}>
          {importLoading ? t('uploading') : t('uploadCsv')}
        </button>
        <input ref={importRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImport} />
        <button style={btn()} onClick={handleExport} disabled={!tsaRows.length}>{t('downloadCsv')}</button>
        <button style={btn('danger')} onClick={handleClear} disabled={clearing || !tsaRows.length || referenced}>
          {clearing ? t('clearing') : t('clear')}
        </button>
        {sep}
        <button style={{ ...btn(), opacity: undoStack.length ? 1 : 0.35 }}
          onClick={undo} disabled={!undoStack.length} title={t('undoTitle')}>{t('undo')}</button>
        <button style={{ ...btn(), opacity: redoStack.length ? 1 : 0.35 }}
          onClick={redo} disabled={!redoStack.length} title={t('redoTitle')}>{t('redo')}</button>
        {sep}
        <button
          style={{ ...btn(hasPending ? 'save' : 'ghost'), opacity: hasPending ? 1 : 0.35 }}
          onClick={handleSave} disabled={!hasPending || saving || referenced} title={t('saveTitle')}>
          {saving ? t('saving') : hasPending ? t('saveWithCount', { count: pendingQtyCap.size + pendingTarget.size }) : t('save')}
        </button>
      </div>

      {hasPending && (
        <div style={{ background: '#1c1008', border: '1px solid #78350f', borderRadius: 4, padding: '0.4rem 0.75rem', fontSize: '0.78rem', color: '#fdba74', marginBottom: '0.75rem' }}>
          {t('unsavedBanner', { count: pendingQtyCap.size + pendingTarget.size })}
        </div>
      )}

      {error && (
        <div style={{ background: '#450a0a', border: '1px solid #7f1d1d', borderRadius: 4, padding: '0.5rem 0.75rem', fontSize: '0.8rem', color: '#fca5a5', marginBottom: '0.75rem' }}>
          {error}
        </div>
      )}

      {!allSupplyIds.length ? (
        <div style={{ padding: '3rem 1rem', textAlign: 'center', color: '#52525b', fontSize: '0.875rem' }}>
          {t('noTsaRows')} <strong style={{ color: '#93c5fd' }}>{t('generate')}</strong> {t('noTsaRowsMiddle')} <strong style={{ color: '#93c5fd' }}>{t('uploadCsv')}</strong> {t('noTsaRowsAfter')}
        </div>
      ) : (
        <TsaTable
          rows={previewRows}
          supplies={supplies}
          demands={demands}
          supplyIds={allSupplyIds}
          editableSupplyIds={new Set(rawCriticalIds)}
          getQtyCap={effectiveQtyCap}
          getTarget={effectiveTarget}
          isPending={(sid) => pendingQtyCap.has(sid) || pendingTarget.has(sid)}
          onEditCell={referenced ? undefined : handleEditCell}
          t={t}
        />
      )}
    </div>
  );
}
