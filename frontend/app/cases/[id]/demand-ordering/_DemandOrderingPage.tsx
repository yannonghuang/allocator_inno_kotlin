'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  DemandOrderRow,
  DemandOrderConfig,
  ConfigVersion,
  createDemandOrderingVersion,
  deleteDemandOrdering,
  deleteDemandOrderingVersion,
  exportDemandOrderingCsv,
  generateDemandOrdering,
  getDemandOrdering,
  importDemandOrderingCsv,
  listDemandOrderingVersions,
  updateDemandOrderRows,
  updateDemandOrderingVersion,
} from '../../../../lib/api';
import { VersionSwitcher } from '@/app/components/VersionSwitcher';
import { DemandOrderTable } from '@/app/components/DemandOrderTable';

export function DemandOrderingPage() {
  const params = useParams();
  const caseId = Number(params.id);
  // Optional ?version_id= — set when arriving from ConfigDetailView's "Open full page" link for
  // a specific historical version (see CaseConfigVersions' own doc).
  const initialVersionId = Number(useSearchParams().get('version_id')) || undefined;
  const t = useTranslations('demandOrderingPage');

  const [rows, setRows] = useState<DemandOrderRow[] | null>(null);
  const [config, setConfig] = useState<DemandOrderConfig | null>(null);

  // Pending edit buffer — demand_id -> new order value, separate from committed `rows` until Save.
  const [pendingChanges, setPendingChanges] = useState<Map<string, number>>(new Map());

  const [generating, setGenerating] = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [versionId, setVersionId] = useState<number | null>(null);
  const referenced = versions.find((v) => v.id === versionId)?.referenced ?? false;

  const hasPending = pendingChanges.size > 0;

  // ── Load ──────────────────────────────────────────────────────────────────

  const loadVersion = React.useCallback((vId: number | undefined) => {
    if (!caseId || isNaN(caseId)) return;
    setRows(null);
    Promise.all([getDemandOrdering(caseId, vId), listDemandOrderingVersions(caseId)])
      .then(([res, vs]) => {
        setRows(res?.rows ?? []);
        setConfig(res?.config ?? null);
        setVersions(vs);
        setVersionId(vId ?? vs.find((v) => v.is_default)?.id ?? vs[0]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
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
  }, [hasPending, t]);

  // ── Save (Ctrl+S) ─────────────────────────────────────────────────────────

  const saveRef = useRef<() => void>(() => {});

  const handleSave = React.useCallback(async () => {
    if (!pendingChanges.size || !rows || referenced) return;
    setSaving(true);
    setError(null);
    try {
      const byId = new Map(rows.map((r) => [r.demand_id, r]));
      const updated: DemandOrderRow[] = Array.from(pendingChanges.entries()).map(([demandId, order]) => {
        const base = byId.get(demandId)!;
        return { ...base, order };
      });
      await updateDemandOrderRows(caseId, updated.map((r) => ({ demand_id: r.demand_id, order: r.order })), versionId ?? undefined);
      setRows((prev) => {
        if (!prev) return prev;
        const next = [...prev];
        for (const ur of updated) {
          const idx = next.findIndex((r) => r.demand_id === ur.demand_id);
          if (idx >= 0) next[idx] = ur;
        }
        return next;
      });
      setPendingChanges(new Map());
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }, [pendingChanges, rows, caseId, versionId, referenced]);

  useEffect(() => { saveRef.current = handleSave; }, [handleSave]);

  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key === 's') { e.preventDefault(); saveRef.current(); }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, []);

  // ── Actions ───────────────────────────────────────────────────────────────

  const clearPending = () => setPendingChanges(new Map());

  const handleGenerate = async () => {
    if (referenced) return;
    if (hasPending && !confirm(t('confirmDiscard'))) return;
    setGenerating(true); setError(null);
    try {
      const newRows = await generateDemandOrdering(caseId, versionId ?? undefined);
      setRows(newRows);
      clearPending();
      const res = await getDemandOrdering(caseId, versionId ?? undefined);
      setConfig(res?.config ?? null);
    } catch (e) { setError(String(e)); }
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
    try { setRows(await importDemandOrderingCsv(caseId, await file.text(), versionId ?? undefined)); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setImportLoading(false); if (importRef.current) importRef.current.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportDemandOrderingCsv(caseId, versionId ?? undefined);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a = Object.assign(document.createElement('a'), { href: url, download: `demand_ordering_case_${caseId}.csv` });
      a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (referenced || !confirm(t('confirmClear'))) return;
    setClearing(true);
    try { await deleteDemandOrdering(caseId, versionId ?? undefined); setRows([]); setConfig(null); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setClearing(false); }
  };

  // ── Versioning ────────────────────────────────────────────────────────────

  const handleSwitchVersion = (vId: number) => { setVersionId(vId); loadVersion(vId); };

  const handleSaveAs = async (name: string | undefined, comments: string | undefined) => {
    setSaving(true); setError(null);
    try {
      const v = await createDemandOrderingVersion(caseId, { name, comments, rows: (rows ?? []).map((r) => ({ demand_id: r.demand_id, order: r.order })) });
      clearPending();
      loadVersion(v.id);
    } catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  };

  const handleSetDefault = async (vId: number) => {
    try {
      await updateDemandOrderingVersion(caseId, vId, { is_default: true });
      setVersions(await listDemandOrderingVersions(caseId));
    } catch (e) { setError(String(e)); }
  };

  const handleRename = async (vId: number, name: string | undefined, comments: string | undefined) => {
    try {
      await updateDemandOrderingVersion(caseId, vId, { name: name ?? null, comments: comments ?? null });
      setVersions(await listDemandOrderingVersions(caseId));
    } catch (e) { setError(String(e)); }
  };

  const handleDeleteVersion = async (vId: number) => {
    try {
      await deleteDemandOrderingVersion(caseId, vId);
      if (vId === versionId) { loadVersion(undefined); } else {
        setVersions(await listDemandOrderingVersions(caseId));
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

  const stageOrder = (demandId: string, value: number, committed: number) => {
    setPendingChanges((prev) => {
      const next = new Map(prev);
      if (value === committed) next.delete(demandId); else next.set(demandId, value);
      return next;
    });
  };

  return (
    <div style={{ padding: '1.25rem 1.5rem', minHeight: '100vh', background: '#0e0e10', color: '#e4e4e7' }}>

      <VersionSwitcher
        versions={versions}
        currentVersionId={versionId}
        onSwitch={handleSwitchVersion}
        onSaveAs={handleSaveAs}
        onSetDefault={handleSetDefault}
        onRename={handleRename}
        onDelete={handleDeleteVersion}
      />

      {/* Action bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.5rem' }}>
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
        {!!rows.length && (
          <button
            style={{ ...btn(hasPending ? 'save' : 'ghost'), opacity: hasPending ? 1 : 0.35 }}
            onClick={handleSave} disabled={!hasPending || saving || referenced} title={t('saveTitle')}>
            {saving ? t('saving') : hasPending ? t('saveWithCount', { count: pendingChanges.size }) : t('save')}
          </button>
        )}
      </div>

      {generating && (
        <div style={{ marginTop: '0.25rem', marginBottom: '0.75rem', maxWidth: 400 }}>
          <div style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.25rem' }}>
            {t('generatingHint')}
          </div>
          <div style={{ height: 8, backgroundColor: '#27272a', borderRadius: 4, overflow: 'hidden' }}>
            <div
              style={{
                height: '100%', width: '35%', borderRadius: 4,
                background: 'linear-gradient(90deg, transparent, #3b82f6, transparent)',
                animation: 'demandOrderingGenerateProgress 1.1s ease-in-out infinite',
              }}
            />
          </div>
          <style>{`
            @keyframes demandOrderingGenerateProgress {
              0% { transform: translateX(-100%); }
              100% { transform: translateX(285%); }
            }
          `}</style>
        </div>
      )}

      {config && (
        <div style={{ fontSize: '0.75rem', color: '#71717a', marginBottom: '0.75rem' }}>
          {t('lastGenerated', { date: config.generated_at.slice(0, 19).replace('T', ' ') })}
        </div>
      )}

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
        <DemandOrderTable rows={rows} pendingChanges={pendingChanges} onStageOrder={stageOrder} t={t} />
      )}
    </div>
  );
}
