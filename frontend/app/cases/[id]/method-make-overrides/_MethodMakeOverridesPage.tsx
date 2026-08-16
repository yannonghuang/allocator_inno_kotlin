'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  getMethodMakeOverrides,
  updateMethodMakeOverrides,
  deleteMethodMakeOverrides,
  importMethodMakeOverridesCsv,
  exportMethodMakeOverridesCsv,
  listMethodMakeOverridesVersions,
  createMethodMakeOverridesVersion,
  updateMethodMakeOverridesVersion,
  deleteMethodMakeOverridesVersion,
  type MethodMakeOverrideRow,
  type ConfigVersion,
} from '../../../../lib/api';
import { MethodMakeOverridePicker } from '@/app/components/MethodMakeOverridePicker';
import { VersionSwitcher } from '@/app/components/VersionSwitcher';

/** Sort-independent equality for the row list, so reordering alone doesn't count as "dirty". */
function sameRows(a: MethodMakeOverrideRow[], b: MethodMakeOverrideRow[]): boolean {
  const key = (r: MethodMakeOverrideRow) => `${r.product_id}|${r.location_id}|${r.preference}|${r.lead_time}|${r.yield}`;
  const sa = [...a].map(key).sort();
  const sb = [...b].map(key).sort();
  return JSON.stringify(sa) === JSON.stringify(sb);
}

/**
 * Dedicated "Method Make Overrides" page — an override for the CSV-imported method_make data,
 * promoted to a case-level persisted setting the same way Constraints/Purchasable Materials were
 * (see CaseMethodMakes' own doc in Tables.kt). No Generate step: pure user input, no algorithm
 * computes a default. When a version is selected for a run, its rows REPLACE method_make.csv
 * entirely — an empty version means no make methods at all, not "fall back to CSV".
 */
export function MethodMakeOverridesPage() {
  const params = useParams();
  const caseId = Number(params.id);
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  // Optional ?version_id= — set when arriving from ConfigDetailView's "Open full page" link for
  // a specific historical version (see CaseConfigVersions' own doc).
  const initialVersionId = Number(searchParams.get('version_id')) || undefined;
  const t = useTranslations('methodMakeOverridesPage');

  const [saved, setSaved] = useState<MethodMakeOverrideRow[] | null>(null);
  const [draft, setDraft] = useState<MethodMakeOverrideRow[]>([]);
  const [dirty, setDirty] = useState(false);

  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [versionId, setVersionId] = useState<number | null>(null);
  const referenced = versions.find((v) => v.id === versionId)?.referenced ?? false;

  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  // ── Load ──────────────────────────────────────────────────────────────────

  const loadVersion = React.useCallback((vId: number | undefined) => {
    if (!caseId || isNaN(caseId)) return;
    setSaved(null);
    Promise.all([getMethodMakeOverrides(caseId, vId), listMethodMakeOverridesVersions(caseId)])
      .then(([rows, vs]) => {
        setSaved(rows);
        setDraft(rows);
        setDirty(false);
        setVersions(vs);
        setVersionId(vId ?? vs[vs.length - 1]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
  }, [caseId]);

  useEffect(() => { loadVersion(initialVersionId); }, [loadVersion, initialVersionId]);

  // Keep the URL's version_id in sync with the resolved version — see the Allocation page's
  // identical fix for the full failure story (stale URL param outlives a deleted/replaced
  // version, so navigating away and back silently lands on the wrong one).
  useEffect(() => {
    const current = searchParams.get('version_id');
    const desired = versionId != null ? String(versionId) : null;
    if (current === desired) return;
    const next = new URLSearchParams(searchParams.toString());
    if (desired == null) next.delete('version_id'); else next.set('version_id', desired);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
  }, [versionId, pathname, router, searchParams]);

  // ── Navigation guards ─────────────────────────────────────────────────────

  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [dirty]);

  useEffect(() => {
    if (!dirty) return;
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
  }, [dirty, t]);

  // ── Save (Ctrl+S) ─────────────────────────────────────────────────────────

  const saveRef = useRef<() => void>(() => {});

  const handleSave = React.useCallback(async () => {
    if (!dirty || referenced) return;
    setSaving(true);
    setError(null);
    try {
      const { versionId: writtenVersionId } = await updateMethodMakeOverrides(caseId, draft, versionId ?? undefined);
      setVersionId(writtenVersionId);
      setVersions(await listMethodMakeOverridesVersions(caseId));
      setSaved(draft);
      setDirty(false);
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }, [dirty, draft, caseId, versionId, referenced]);

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

  const onChangeDraft = (next: MethodMakeOverrideRow[]) => {
    setDraft(next);
    setDirty(saved === null || !sameRows(next, saved));
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || referenced) return;
    if (dirty && !confirm(t('confirmDiscard'))) { e.target.value = ''; return; }
    setImportLoading(true); setError(null);
    try {
      const text = await file.text();
      const { rows, versionId: writtenVersionId } = await importMethodMakeOverridesCsv(caseId, text, versionId ?? undefined);
      setVersionId(writtenVersionId);
      setVersions(await listMethodMakeOverridesVersions(caseId));
      setSaved(rows); setDraft(rows); setDirty(false);
    } catch (err) { setError(String(err)); }
    finally { setImportLoading(false); e.target.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportMethodMakeOverridesCsv(caseId, versionId ?? undefined);
      const blob = new Blob([csv], { type: 'text/csv' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `method_make_overrides_case_${caseId}.csv`; a.click();
      URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (referenced || !confirm(t('confirmClear'))) return;
    setClearing(true); setError(null);
    try {
      await deleteMethodMakeOverrides(caseId, versionId ?? undefined);
      setSaved([]); setDraft([]); setDirty(false);
    } catch (e) { setError(String(e)); }
    finally { setClearing(false); }
  };

  // ── Versioning ────────────────────────────────────────────────────────────

  // `draft` isn't cleared on switch — without this, an unsaved row list made before switching
  // versions stays displayed (and wrongly marked dirty/clean) against the newly-loaded version
  // (see the Allocation page's identical fix for the full failure story).
  const handleSwitchVersion = (vId: number) => {
    if (dirty && !confirm(t('confirmDiscard'))) return;
    setVersionId(vId);
    loadVersion(vId);
  };

  const handleSaveAs = async (name: string | undefined, comments: string | undefined) => {
    setSaving(true); setError(null);
    try {
      const v = await createMethodMakeOverridesVersion(caseId, { name, comments, rows: draft });
      loadVersion(v.id);
    } catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  };

  const handleRename = async (vId: number, name: string | undefined, comments: string | undefined) => {
    try {
      await updateMethodMakeOverridesVersion(caseId, vId, { name: name ?? null, comments: comments ?? null });
      setVersions(await listMethodMakeOverridesVersions(caseId));
    } catch (e) { setError(String(e)); }
  };

  const handleDeleteVersion = async (vId: number) => {
    try {
      await deleteMethodMakeOverridesVersion(caseId, vId);
      if (vId === versionId) { loadVersion(undefined); } else {
        setVersions(await listMethodMakeOverridesVersions(caseId));
      }
    } catch (e) { setError(String(e)); }
  };

  if (saved === null) {
    return <div style={{ padding: '1.5rem', color: '#a1a1aa' }}>{t('loading')}</div>;
  }

  return (
    <div style={{ padding: '1.5rem', maxWidth: 900 }}>
      <h1 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '1rem' }}>{t('title')}</h1>

      {error && (
        <div style={{ marginBottom: '0.75rem', padding: '6px 10px', background: '#3f1d1d', border: '1px solid #7f1d1d', borderRadius: 4, color: '#fca5a5', fontSize: '0.82rem' }}>
          {error}
        </div>
      )}
      {dirty && (
        <div style={{ marginBottom: '0.75rem', padding: '6px 10px', background: '#3f2d0d', border: '1px solid #92400e', borderRadius: 4, color: '#fbbf24', fontSize: '0.82rem' }}>
          {t('unsavedBanner')}
        </div>
      )}

      <VersionSwitcher
        versions={versions}
        currentVersionId={versionId}
        onSwitch={handleSwitchVersion}
        onSaveAs={handleSaveAs}
        onRename={handleRename}
        onDelete={handleDeleteVersion}
      />

      <MethodMakeOverridePicker rows={draft} onChange={onChangeDraft} defaultCollapsed={false} t={t} />

      <div style={{ display: 'flex', gap: 8, marginTop: '1rem', flexWrap: 'wrap' }}>
        <button
          onClick={handleSave}
          disabled={!dirty || saving || referenced}
          title={t('saveTitle')}
          style={{ padding: '6px 14px', background: dirty ? '#2563eb' : '#27272a', color: dirty ? '#fff' : '#71717a', border: 'none', borderRadius: 4, cursor: dirty ? 'pointer' : 'default', fontSize: '0.85rem' }}
        >
          {saving ? t('saving') : t('save')}
        </button>
        <button onClick={() => importRef.current?.click()} disabled={importLoading || referenced}
          style={{ padding: '6px 14px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.85rem' }}>
          {importLoading ? t('uploading') : t('uploadCsv')}
        </button>
        <input ref={importRef} type="file" accept=".csv" style={{ display: 'none' }} onChange={handleImport} />
        <button onClick={handleExport}
          style={{ padding: '6px 14px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.85rem' }}>
          {t('downloadCsv')}
        </button>
        <button onClick={handleClear} disabled={clearing || referenced}
          style={{ padding: '6px 14px', background: '#27272a', color: '#f87171', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.85rem' }}>
          {clearing ? t('clearing') : t('clear')}
        </button>
      </div>
    </div>
  );
}
