'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  PreferenceRow,
  PreferenceConfig,
  ConfigVersion,
  createPreferencesVersion,
  deletePreferences,
  deletePreferencesVersion,
  exportPreferencesCsv,
  generatePreferences,
  getPreferences,
  importPreferencesCsv,
  listPreferencesVersions,
  updatePreferenceRows,
  updatePreferencesVersion,
} from '../../../../lib/api';
import { VersionSwitcher } from '@/app/components/VersionSwitcher';
import { PreferenceTable, rowKey, effectivePreference as effectivePreferenceOf } from '@/app/components/PreferenceTable';

function fmtScore(v: number | null): string {
  if (v === null) return '—';
  return v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/**
 * Plain controlled text input with a click-to-select suggestion list — deliberately NOT
 * `<input list>` + `<datalist>`: that combo has a well-known cross-browser quirk where, once
 * a suggestion is picked, the controlled value can get "stuck" (typing/backspace stops
 * registering) in some browsers. This is a few more lines but has no such failure mode —
 * it's a plain input the user can always edit freely, with an optional click-to-fill list.
 */
function LookupAutocomplete({
  value, onChange, options, placeholder, style,
}: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
  placeholder: string;
  style?: React.CSSProperties;
}) {
  const [focused, setFocused] = useState(false);
  const matches = React.useMemo(() => {
    const q = value.trim().toLowerCase();
    const pool = q ? options.filter((o) => o.toLowerCase().includes(q)) : options;
    return pool.slice(0, 8);
  }, [options, value]);

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <input
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onFocus={() => setFocused(true)}
        onBlur={() => setTimeout(() => setFocused(false), 150)}
        style={style}
      />
      {focused && matches.length > 0 && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, zIndex: 10, marginTop: 2,
          background: '#1c1c1f', border: '1px solid #3d3d40', borderRadius: 4,
          maxHeight: 180, overflowY: 'auto', minWidth: '100%',
        }}>
          {matches.map((opt) => (
            <div
              key={opt}
              onMouseDown={() => onChange(opt)}
              style={{ padding: '4px 8px', fontSize: '0.8rem', color: '#e4e4e7', cursor: 'pointer', whiteSpace: 'nowrap' }}
              onMouseEnter={(e) => (e.currentTarget.style.background = '#27272a')}
              onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
            >
              {opt}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function PreferencesPage() {
  const params = useParams();
  const caseId = Number(params.id);
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  // Optional ?version_id= — set when arriving from ConfigDetailView's "Open full page" link for
  // a specific historical version (see CaseConfigVersions' own doc).
  const initialVersionId = Number(searchParams.get('version_id')) || undefined;
  const t = useTranslations('preferencesPage');

  const [rows, setRows] = useState<PreferenceRow[] | null>(null);
  const [config, setConfig] = useState<PreferenceConfig | null>(null);

  // Pending edit buffer — key -> new preference value, separate from committed `rows` until Save.
  const [pendingChanges, setPendingChanges] = useState<Map<string, number>>(new Map());
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');

  const [maxBomDepth, setMaxBomDepth] = useState(3);
  const [deliveryWeight, setDeliveryWeight] = useState(0.3);
  const [inventoryWeight, setInventoryWeight] = useState(0.3);
  const [criticalMaterialWeight, setCriticalMaterialWeight] = useState(0.4);

  // Lookup panel: search by product + location, view/reorder all its alternatives together.
  const [lookupProduct, setLookupProduct] = useState('');
  const [lookupLocation, setLookupLocation] = useState('');

  const [generating, setGenerating] = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [versionId, setVersionId] = useState<number | null>(null);
  const referenced = versions.find((v) => v.id === versionId)?.referenced ?? false;

  // Generate can return a full row set with no version resolved (nothing persisted — see
  // generatePreferences' own doc); that state is "unsaved" exactly like a pending edit, just not
  // expressible as a pendingChanges delta since non-preference columns (product_id, scores, ...)
  // came from Generate too, not from a committed `rows` baseline that still exists.
  const hasUnsavedGenerate = versionId == null && (rows?.length ?? 0) > 0;
  const hasPending = pendingChanges.size > 0 || hasUnsavedGenerate;
  const pendingCount = hasUnsavedGenerate ? (rows?.length ?? 0) : pendingChanges.size;

  // ── Load ──────────────────────────────────────────────────────────────────

  const loadVersion = React.useCallback((vId: number | undefined) => {
    if (!caseId || isNaN(caseId)) return;
    setRows(null);
    Promise.all([getPreferences(caseId, vId), listPreferencesVersions(caseId)])
      .then(([res, vs]) => {
        setRows(res?.rows ?? []);
        setConfig(res?.config ?? null);
        if (res?.config) {
          setMaxBomDepth(res.config.max_bom_depth);
          setDeliveryWeight(res.config.delivery_weight);
          setInventoryWeight(res.config.inventory_weight);
          setCriticalMaterialWeight(res.config.critical_material_weight);
        }
        setVersions(vs);
        setVersionId(vId ?? vs[vs.length - 1]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
  }, [caseId]);

  useEffect(() => { loadVersion(initialVersionId); }, [loadVersion, initialVersionId]);

  // Keep the URL's version_id in sync with the resolved version — without this, the address bar
  // keeps whatever version_id it had at page load (or none) even after Save As / version-switch
  // move the user onto a different version; navigating away and back then re-reads that STALE
  // query param (see the Allocation page's own identical fix for the full failure story).
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
    if (!hasPending || !rows || referenced) return;
    // Saving with no version resolved yet is the moment a version gets created (see
    // resolvedOrCreatedVersionId's own doc) — ask for a real name here instead of silently
    // leaving it as an anonymous "Version {id}" the user then has to hunt down and rename.
    let versionName: string | undefined;
    if (versionId == null) {
      const entered = prompt(t('saveNamePrompt'));
      if (entered === null) return; // cancelled — nothing persisted
      versionName = entered.trim() || undefined;
    }
    setSaving(true);
    setError(null);
    try {
      // A staged (unsaved) Generate result has no committed baseline to diff against — the
      // whole row set IS the change, so persist it wholesale instead of pendingChanges' deltas.
      const updated: PreferenceRow[] = hasUnsavedGenerate ? rows : (() => {
        const byKey = new Map(rows.map((r) => [rowKey(r), r]));
        return Array.from(pendingChanges.entries()).map(([key, preference]) => {
          const base = byKey.get(key)!;
          return { ...base, preference };
        });
      })();
      if (versionId == null) {
        const v = await createPreferencesVersion(caseId, { name: versionName, rows: updated });
        loadVersion(v.id);
      } else {
        const { versionId: writtenVersionId } = await updatePreferenceRows(caseId, updated, versionId);
        setVersionId(writtenVersionId);
        setVersions(await listPreferencesVersions(caseId));
        setRows((prev) => {
          if (!prev) return prev;
          const next = [...prev];
          for (const ur of updated) {
            const idx = next.findIndex((r) => rowKey(r) === rowKey(ur));
            if (idx >= 0) next[idx] = ur;
          }
          return next;
        });
      }
      setPendingChanges(new Map());
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }, [hasPending, hasUnsavedGenerate, pendingChanges, rows, caseId, versionId, referenced, t, loadVersion]);

  useEffect(() => { saveRef.current = handleSave; }, [handleSave]);

  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (editingKey) return;
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key === 's') { e.preventDefault(); saveRef.current(); }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [editingKey]);

  // ── Actions ───────────────────────────────────────────────────────────────

  const clearPending = () => setPendingChanges(new Map());

  const handleGenerate = async () => {
    if (referenced) return;
    if (hasPending && !confirm(t('confirmDiscard'))) return;
    setGenerating(true); setError(null);
    try {
      const { rows: newRows, versionId: writtenVersionId } = await generatePreferences(caseId, {
        max_bom_depth: maxBomDepth, delivery_weight: deliveryWeight, inventory_weight: inventoryWeight,
        critical_material_weight: criticalMaterialWeight,
      }, versionId ?? undefined);
      setRows(newRows);
      clearPending();
      if (writtenVersionId != null) {
        // A version was already resolved — generate persisted in place immediately.
        setVersionId(writtenVersionId);
        setVersions(await listPreferencesVersions(caseId));
        const res = await getPreferences(caseId, writtenVersionId);
        setConfig(res?.config ?? null);
      }
      // else: nothing persisted (see generatePreferences' own doc) — `rows` now holds the staged,
      // unsaved result (hasUnsavedGenerate picks this up automatically since versionId is still
      // null); Save is the one place a version gets created.
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
    try {
      const { rows: newRows, versionId: writtenVersionId } = await importPreferencesCsv(caseId, await file.text(), versionId ?? undefined);
      setRows(newRows);
      setVersionId(writtenVersionId);
      setVersions(await listPreferencesVersions(caseId));
      clearPending();
    }
    catch (e) { setError(String(e)); }
    finally { setImportLoading(false); if (importRef.current) importRef.current.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportPreferencesCsv(caseId, versionId ?? undefined);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a = Object.assign(document.createElement('a'), { href: url, download: `preferences_case_${caseId}.csv` });
      a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (referenced || !confirm(t('confirmClear'))) return;
    setClearing(true);
    try { await deletePreferences(caseId, versionId ?? undefined); setRows([]); setConfig(null); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setClearing(false); }
  };

  // ── Versioning ────────────────────────────────────────────────────────────

  // Pending edits are keyed by product/location/method (rowKey) which is shared across every
  // version of this case's preferences — without clearing here, an unsaved edit made before
  // switching versions silently reappears overlaid on the newly-loaded version (see the
  // Allocation page's identical fix for the full failure story).
  const handleSwitchVersion = (vId: number) => {
    if (hasPending && !confirm(t('confirmDiscard'))) return;
    clearPending();
    setVersionId(vId);
    loadVersion(vId);
  };

  const handleSaveAs = async (name: string | undefined, comments: string | undefined) => {
    setSaving(true); setError(null);
    try {
      const v = await createPreferencesVersion(caseId, { name, comments, rows: rows ?? [] });
      clearPending();
      loadVersion(v.id);
    } catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  };


  const handleRename = async (vId: number, name: string | undefined, comments: string | undefined) => {
    try {
      await updatePreferencesVersion(caseId, vId, { name: name ?? null, comments: comments ?? null });
      setVersions(await listPreferencesVersions(caseId));
    } catch (e) { setError(String(e)); }
  };

  const handleDeleteVersion = async (vId: number) => {
    try {
      await deletePreferencesVersion(caseId, vId);
      if (vId === versionId) { loadVersion(undefined); } else {
        setVersions(await listPreferencesVersions(caseId));
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

  const numInput: React.CSSProperties = {
    width: 56, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40',
    borderRadius: 4, color: '#fafafa', fontSize: '0.8rem',
  };

  // ── Derived data (hooks — must run unconditionally, before any early return) ─

  const effectivePreference = (r: PreferenceRow): number => effectivePreferenceOf(r, pendingChanges);

  const productOptions = React.useMemo(
    () => Array.from(new Set((rows ?? []).map((r) => r.product_id))).sort(), [rows]);
  const locationOptions = React.useMemo(
    () => Array.from(new Set((rows ?? []).map((r) => r.location_id))).sort(), [rows]);

  const lookupGroup: PreferenceRow[] = React.useMemo(() => {
    const p = lookupProduct.trim();
    const l = lookupLocation.trim();
    if (!p || !l) return [];
    return (rows ?? [])
      .filter((r) => r.product_id === p && r.location_id === l)
      .sort((a, b) => effectivePreference(a) - effectivePreference(b));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, lookupProduct, lookupLocation, pendingChanges]);

  // ── Render ────────────────────────────────────────────────────────────────

  if (rows === null)
    return <div style={{ padding: '2rem', color: '#71717a', fontSize: '0.875rem' }}>{t('loading')}</div>;

  const stagePreference = (key: string, value: number, committed: number) => {
    setPendingChanges((prev) => {
      const next = new Map(prev);
      if (value === committed) next.delete(key); else next.set(key, value);
      return next;
    });
  };

  const swapWithNeighbor = (index: number, direction: -1 | 1) => {
    const other = index + direction;
    if (other < 0 || other >= lookupGroup.length) return;
    const a = lookupGroup[index];
    const b = lookupGroup[other];
    const aKey = rowKey(a), bKey = rowKey(b);
    const aPref = effectivePreference(a), bPref = effectivePreference(b);
    setPendingChanges((prev) => {
      const next = new Map(prev);
      if (bPref === a.preference) next.delete(aKey); else next.set(aKey, bPref);
      if (aPref === b.preference) next.delete(bKey); else next.set(bKey, aPref);
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
        onRename={handleRename}
        onDelete={handleDeleteVersion}
      />

      {/* Scoring parameters */}
      <div style={{ border: '1px solid #27272a', borderRadius: 6, padding: '0.6rem 0.75rem', marginBottom: '0.5rem' }}>
        <div style={{ fontSize: '0.7rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.03em', marginBottom: '0.4rem' }}>
          {t('configSectionLabel')}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
          <label
            title={t('maxBomDepthTooltip')}
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem', color: '#a1a1aa', cursor: 'help' }}>
            {t('maxBomDepthLabel')}
            <input type="number" min={1} max={10} value={maxBomDepth}
              onChange={(e) => setMaxBomDepth(Math.max(1, Math.min(10, parseInt(e.target.value, 10) || 3)))}
              style={numInput} />
          </label>
          <label
            title={t('deliveryWeightTooltip')}
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem', color: '#a1a1aa', cursor: 'help' }}>
            {t('deliveryWeightLabel')}
            <input type="number" min={0} max={1} step={0.1} value={deliveryWeight}
              onChange={(e) => setDeliveryWeight(Math.max(0, Math.min(1, parseFloat(e.target.value) || 0)))}
              style={numInput} />
          </label>
          <label
            title={t('inventoryWeightTooltip')}
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem', color: '#a1a1aa', cursor: 'help' }}>
            {t('inventoryWeightLabel')}
            <input type="number" min={0} max={1} step={0.1} value={inventoryWeight}
              onChange={(e) => setInventoryWeight(Math.max(0, Math.min(1, parseFloat(e.target.value) || 0)))}
              style={numInput} />
          </label>
          <label
            title={t('criticalMaterialWeightTooltip')}
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem', color: '#a1a1aa', cursor: 'help' }}>
            {t('criticalMaterialWeightLabel')}
            <input type="number" min={0} max={1} step={0.1} value={criticalMaterialWeight}
              onChange={(e) => setCriticalMaterialWeight(Math.max(0, Math.min(1, parseFloat(e.target.value) || 0)))}
              style={numInput} />
          </label>
        </div>
      </div>

      {/* Actions */}
      <div style={{ marginBottom: '0.5rem' }}>
        <div style={{ fontSize: '0.7rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.03em', marginBottom: '0.4rem' }}>
          {t('actionsSectionLabel')}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
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
              {saving ? t('saving') : hasPending ? t('saveWithCount', { count: pendingCount }) : t('save')}
            </button>
          )}
        </div>
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
                animation: 'preferencesGenerateProgress 1.1s ease-in-out infinite',
              }}
            />
          </div>
          <style>{`
            @keyframes preferencesGenerateProgress {
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
          {t('unsavedBanner', { count: pendingCount })}
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
      ) : (<>
        {/* Lookup panel: search by product + location, view/reorder all its alternatives */}
        <div style={{ border: '1px solid #27272a', borderRadius: 6, padding: '0.75rem 1rem', marginBottom: '1rem' }}>
          <div style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.5rem', fontWeight: 500 }}>
            {t('lookupTitle')}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
            <LookupAutocomplete
              placeholder={t('lookupProductPlaceholder')}
              value={lookupProduct} onChange={setLookupProduct}
              options={productOptions}
              style={{ ...numInput, width: 180 }}
            />
            <LookupAutocomplete
              placeholder={t('lookupLocationPlaceholder')}
              value={lookupLocation} onChange={setLookupLocation}
              options={locationOptions}
              style={{ ...numInput, width: 140 }}
            />
          </div>

          {lookupProduct.trim() && lookupLocation.trim() && (
            lookupGroup.length === 0 ? (
              <div style={{ fontSize: '0.8rem', color: '#52525b' }}>{t('lookupNoMatch')}</div>
            ) : (
              <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #27272a', color: '#71717a' }}>
                    <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}></th>
                    <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}>{t('colMethodType')}</th>
                    <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}>{t('colMethodKey')}</th>
                    <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem' }}>{t('colPreference')}</th>
                    <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem' }}>{t('colInventoryScore')}</th>
                    <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem' }}>{t('colDeliveryScore')}</th>
                    <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem' }}>{t('colCriticalMaterialScore')}</th>
                  </tr>
                </thead>
                <tbody>
                  {lookupGroup.map((row, i) => {
                    const k = rowKey(row);
                    const val = effectivePreference(row);
                    const dirty = pendingChanges.has(k);
                    return (
                      <tr key={k} style={{ borderBottom: '1px solid #1c1c1f' }}>
                        <td style={{ padding: '0.25rem 0.5rem', whiteSpace: 'nowrap' }}>
                          <button
                            onClick={() => swapWithNeighbor(i, -1)} disabled={i === 0}
                            title={t('moveUp')}
                            style={{ ...btn(), padding: '1px 6px', opacity: i === 0 ? 0.3 : 1 }}
                          >▲</button>
                          <button
                            onClick={() => swapWithNeighbor(i, 1)} disabled={i === lookupGroup.length - 1}
                            title={t('moveDown')}
                            style={{ ...btn(), padding: '1px 6px', marginLeft: 4, opacity: i === lookupGroup.length - 1 ? 0.3 : 1 }}
                          >▼</button>
                        </td>
                        <td style={{ padding: '0.25rem 0.5rem' }}>{row.method_type}</td>
                        <td style={{ padding: '0.25rem 0.5rem', fontFamily: 'monospace', fontSize: '0.75rem' }}>{row.method_key}</td>
                        <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right' }}>
                          {editingKey === k ? (
                            <input
                              autoFocus type="number" value={editValue}
                              onChange={(e) => setEditValue(e.target.value)}
                              onBlur={() => {
                                const n = parseInt(editValue, 10);
                                setEditingKey(null);
                                if (!isNaN(n)) stagePreference(k, n, row.preference);
                              }}
                              onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); if (e.key === 'Escape') setEditingKey(null); }}
                              style={numInput}
                            />
                          ) : (
                            <span
                              onClick={() => { setEditingKey(k); setEditValue(String(val)); }}
                              style={{ cursor: 'pointer', color: dirty ? '#fdba74' : '#e4e4e7', fontVariantNumeric: 'tabular-nums' }}
                            >{val}</span>
                          )}
                        </td>
                        <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right', color: '#a1a1aa' }}>{fmtScore(row.inventory_score)}</td>
                        <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right', color: '#a1a1aa' }}>{fmtScore(row.delivery_score)}</td>
                        <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right', color: '#a1a1aa' }}>{fmtScore(row.critical_material_score)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )
          )}
        </div>

        <PreferenceTable
          rows={rows}
          pendingChanges={pendingChanges}
          onStagePreference={stagePreference}
          t={t}
        />
      </>)}
    </div>
  );
}
