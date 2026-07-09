'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
  DemandOrderRow,
  DemandOrderConfig,
  deleteDemandOrdering,
  exportDemandOrderingCsv,
  generateDemandOrdering,
  getDemandOrdering,
  importDemandOrderingCsv,
  updateDemandOrderRows,
} from '../../../../lib/api';

export function DemandOrderingPage() {
  const params = useParams();
  const caseId = Number(params.id);
  const t = useTranslations('demandOrderingPage');

  const [rows, setRows] = useState<DemandOrderRow[] | null>(null);
  const [config, setConfig] = useState<DemandOrderConfig | null>(null);

  // Pending edit buffer — demand_id -> new order value, separate from committed `rows` until Save.
  const [pendingChanges, setPendingChanges] = useState<Map<string, number>>(new Map());
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');

  const [search, setSearch] = useState('');

  const [generating, setGenerating] = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const importRef = useRef<HTMLInputElement>(null);

  const hasPending = pendingChanges.size > 0;

  // ── Load ──────────────────────────────────────────────────────────────────

  useEffect(() => {
    if (!caseId || isNaN(caseId)) return;
    setRows(null);
    getDemandOrdering(caseId)
      .then((res) => {
        setRows(res?.rows ?? []);
        setConfig(res?.config ?? null);
      })
      .catch((e) => setError(String(e)));
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
    if (!pendingChanges.size || !rows) return;
    setSaving(true);
    setError(null);
    try {
      const byId = new Map(rows.map((r) => [r.demand_id, r]));
      const updated: DemandOrderRow[] = Array.from(pendingChanges.entries()).map(([demandId, order]) => {
        const base = byId.get(demandId)!;
        return { ...base, order };
      });
      await updateDemandOrderRows(caseId, updated.map((r) => ({ demand_id: r.demand_id, order: r.order })));
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
  }, [pendingChanges, rows, caseId]);

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
    if (hasPending && !confirm(t('confirmDiscard'))) return;
    setGenerating(true); setError(null);
    try {
      const newRows = await generateDemandOrdering(caseId);
      setRows(newRows);
      clearPending();
      const res = await getDemandOrdering(caseId);
      setConfig(res?.config ?? null);
    } catch (e) { setError(String(e)); }
    finally { setGenerating(false); }
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (hasPending && !confirm(t('confirmDiscard'))) {
      if (importRef.current) importRef.current.value = '';
      return;
    }
    setImportLoading(true); setError(null);
    try { setRows(await importDemandOrderingCsv(caseId, await file.text())); clearPending(); }
    catch (e) { setError(String(e)); }
    finally { setImportLoading(false); if (importRef.current) importRef.current.value = ''; }
  };

  const handleExport = async () => {
    try {
      const csv = await exportDemandOrderingCsv(caseId);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
      const a = Object.assign(document.createElement('a'), { href: url, download: `demand_ordering_case_${caseId}.csv` });
      a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(String(e)); }
  };

  const handleClear = async () => {
    if (!confirm(t('confirmClear'))) return;
    setClearing(true);
    try { await deleteDemandOrdering(caseId); setRows([]); setConfig(null); clearPending(); }
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

  const numInput: React.CSSProperties = {
    width: 56, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40',
    borderRadius: 4, color: '#fafafa', fontSize: '0.8rem',
  };

  // ── Derived data (hooks — must run unconditionally, before any early return) ─

  const effectiveOrder = (r: DemandOrderRow): number => pendingChanges.get(r.demand_id) ?? r.order;

  // Always sorted by effective order (so up/down swap-with-neighbor is well-defined), optionally
  // narrowed by a free-text search across demand_id/customer_id/product_id.
  const visibleRows: DemandOrderRow[] = React.useMemo(() => {
    const q = search.trim().toLowerCase();
    const filtered = q
      ? (rows ?? []).filter((r) =>
          r.demand_id.toLowerCase().includes(q) ||
          r.customer_id.toLowerCase().includes(q) ||
          r.product_id.toLowerCase().includes(q))
      : (rows ?? []);
    return [...filtered].sort((a, b) => effectiveOrder(a) - effectiveOrder(b));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, search, pendingChanges]);

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

  const swapWithNeighbor = (index: number, direction: -1 | 1) => {
    const other = index + direction;
    if (other < 0 || other >= visibleRows.length) return;
    const a = visibleRows[index];
    const b = visibleRows[other];
    const aOrder = effectiveOrder(a), bOrder = effectiveOrder(b);
    setPendingChanges((prev) => {
      const next = new Map(prev);
      if (bOrder === a.order) next.delete(a.demand_id); else next.set(a.demand_id, bOrder);
      if (aOrder === b.order) next.delete(b.demand_id); else next.set(b.demand_id, aOrder);
      return next;
    });
  };

  return (
    <div style={{ padding: '1.25rem 1.5rem', minHeight: '100vh', background: '#0e0e10', color: '#e4e4e7' }}>

      {/* Action bar */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.5rem' }}>
        <button style={btn('primary')} onClick={handleGenerate} disabled={generating}>
          {generating ? t('generating') : t('generate')}
        </button>
        <button style={btn()} onClick={() => importRef.current?.click()} disabled={importLoading}>
          {importLoading ? t('uploading') : t('uploadCsv')}
        </button>
        <input ref={importRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImport} />
        <button style={btn()} onClick={handleExport} disabled={!rows.length}>{t('downloadCsv')}</button>
        <button style={btn('danger')} onClick={handleClear} disabled={clearing || !rows.length}>
          {clearing ? t('clearing') : t('clear')}
        </button>
        {!!rows.length && (
          <button
            style={{ ...btn(hasPending ? 'save' : 'ghost'), opacity: hasPending ? 1 : 0.35 }}
            onClick={handleSave} disabled={!hasPending || saving} title={t('saveTitle')}>
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
      ) : (<>
        <div style={{ marginBottom: '0.75rem' }}>
          <input
            placeholder={t('searchPlaceholder')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ ...numInput, width: 260 }}
          />
        </div>

        <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid #27272a', color: '#71717a' }}>
              <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}></th>
              <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem' }}>{t('colOrder')}</th>
              <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}>{t('colDemandId')}</th>
              <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}>{t('colCustomer')}</th>
              <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}>{t('colProduct')}</th>
              <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}>{t('colDueTime')}</th>
              <th style={{ textAlign: 'right', padding: '0.25rem 0.5rem' }}>{t('colPriority')}</th>
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((row, i) => {
              const k = row.demand_id;
              const val = effectiveOrder(row);
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
                      onClick={() => swapWithNeighbor(i, 1)} disabled={i === visibleRows.length - 1}
                      title={t('moveDown')}
                      style={{ ...btn(), padding: '1px 6px', marginLeft: 4, opacity: i === visibleRows.length - 1 ? 0.3 : 1 }}
                    >▼</button>
                  </td>
                  <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right' }}>
                    {editingKey === k ? (
                      <input
                        autoFocus type="number" value={editValue}
                        onChange={(e) => setEditValue(e.target.value)}
                        onBlur={() => {
                          const n = parseInt(editValue, 10);
                          setEditingKey(null);
                          if (!isNaN(n)) stageOrder(k, n, row.order);
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
                  <td style={{ padding: '0.25rem 0.5rem', fontFamily: 'monospace', fontSize: '0.75rem' }}>{row.demand_id}</td>
                  <td style={{ padding: '0.25rem 0.5rem' }}>{row.customer_id}</td>
                  <td style={{ padding: '0.25rem 0.5rem' }}>{row.product_id}</td>
                  <td style={{ padding: '0.25rem 0.5rem', color: '#a1a1aa' }}>{row.request_due_time ?? '—'}</td>
                  <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right', color: '#a1a1aa' }}>{row.priority}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </>)}
    </div>
  );
}
