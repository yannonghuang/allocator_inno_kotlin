'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  getPurchasableMaterials,
  updatePurchasableMaterials,
  deletePurchasableMaterials,
  importPurchasableMaterialsCsv,
  exportPurchasableMaterialsCsv,
  getPurchasableRawMaterials,
  type PurchasableRawMaterial,
} from '../../../../lib/api';
import { RawMaterialPicker } from '@/app/components/RawMaterialPicker';

/**
 * Dedicated "Purchasable Materials" page — the whitelist promoted out of the Planning page's
 * inline config form (see PurchasableMaterials.kt's own doc). No Generate step: this is pure
 * user input, no algorithm computes a default (unlike Allocation/Preferences/DemandOrdering).
 */
export function PurchasableMaterialsPage() {
  const params = useParams();
  const caseId = Number(params.id);
  const t = useTranslations('purchasableMaterialsPage');
  const tP = useTranslations('planning');  // RawMaterialPicker's own labels live under planning.config.*

  const [options, setOptions] = useState<PurchasableRawMaterial[]>([]);
  const [saved, setSaved] = useState<string[] | null>(null);
  const [draft, setDraft] = useState<string[]>([]);
  const [dirty, setDirty] = useState(false);

  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  // ── Load ──────────────────────────────────────────────────────────────────

  useEffect(() => {
    if (!caseId || isNaN(caseId)) return;
    setSaved(null);
    Promise.all([getPurchasableMaterials(caseId), getPurchasableRawMaterials(caseId)])
      .then(([rows, catalog]) => {
        const ids = rows.map((r) => r.product_id);
        setSaved(ids);
        setDraft(ids);
        setDirty(false);
        setOptions(catalog.materials);
      })
      .catch((e) => setError(String(e)));
  }, [caseId]);

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
    if (!dirty) return;
    setSaving(true);
    setError(null);
    try {
      await updatePurchasableMaterials(caseId, draft);
      setSaved(draft);
      setDirty(false);
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }, [dirty, draft, caseId]);

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

  const onChangeDraft = (next: string[]) => {
    setDraft(next);
    setDirty(saved === null || JSON.stringify([...next].sort()) !== JSON.stringify([...saved].sort()));
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (dirty && !confirm(t('confirmDiscard'))) { e.target.value = ''; return; }
    setImportLoading(true); setError(null);
    try {
      const text = await file.text();
      const rows = await importPurchasableMaterialsCsv(caseId, text);
      const ids = rows.map((r) => r.product_id);
      setSaved(ids); setDraft(ids); setDirty(false);
    } catch (err) { setError(String(err)); }
    finally { setImportLoading(false); e.target.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportPurchasableMaterialsCsv(caseId);
      const blob = new Blob([csv], { type: 'text/csv' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `purchasable_materials_case_${caseId}.csv`; a.click();
      URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (!confirm(t('confirmClear'))) return;
    setClearing(true); setError(null);
    try {
      await deletePurchasableMaterials(caseId);
      setSaved([]); setDraft([]); setDirty(false);
    } catch (e) { setError(String(e)); }
    finally { setClearing(false); }
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

      <RawMaterialPicker options={options} selected={draft} onChange={onChangeDraft} defaultCollapsed={false} tP={tP} />

      <div style={{ display: 'flex', gap: 8, marginTop: '1rem', flexWrap: 'wrap' }}>
        <button
          onClick={handleSave}
          disabled={!dirty || saving}
          title={t('saveTitle')}
          style={{ padding: '6px 14px', background: dirty ? '#2563eb' : '#27272a', color: dirty ? '#fff' : '#71717a', border: 'none', borderRadius: 4, cursor: dirty ? 'pointer' : 'default', fontSize: '0.85rem' }}
        >
          {saving ? t('saving') : t('save')}
        </button>
        <button onClick={() => importRef.current?.click()} disabled={importLoading}
          style={{ padding: '6px 14px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.85rem' }}>
          {importLoading ? t('uploading') : t('uploadCsv')}
        </button>
        <input ref={importRef} type="file" accept=".csv" style={{ display: 'none' }} onChange={handleImport} />
        <button onClick={handleExport}
          style={{ padding: '6px 14px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.85rem' }}>
          {t('downloadCsv')}
        </button>
        <button onClick={handleClear} disabled={clearing}
          style={{ padding: '6px 14px', background: '#27272a', color: '#f87171', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.85rem' }}>
          {clearing ? t('clearing') : t('clear')}
        </button>
      </div>
    </div>
  );
}
