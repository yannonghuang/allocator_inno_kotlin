'use client';

import React, { useState } from 'react';
import type { PurchasableRawMaterial } from '@/lib/api';

/**
 * Searchable, multi-valued picker for the "selective purchase" whitelist. Used on the dedicated
 * Purchasable Materials page (moved out of the Planning page's inline config form — see
 * PurchasableMaterials.kt's own doc for why this became a case-level persisted setting). Filters
 * on product_id / description / vendor / SKU series. An empty selection means "all raw materials
 * are purchasable" (the default).
 */
export function RawMaterialPicker({
  options,
  selected,
  onChange,
  initialFilter,
  defaultCollapsed,
  tP,
}: {
  options: PurchasableRawMaterial[];
  selected: string[];
  onChange: (next: string[]) => void;
  initialFilter?: string;
  defaultCollapsed?: boolean;
  tP: (k: string) => string;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [filter, setFilter] = useState(initialFilter ?? '');
  const sel = new Set(selected);
  const f = filter.trim().toLowerCase();
  // Wildcard-aware match: a query containing `*` is treated as a glob anchored at the
  // start of product_id (e.g. `160-*` → every 160- series id). Otherwise substring match
  // across id / description / vendor / sku series (the original behavior).
  const matches = (o: PurchasableRawMaterial): boolean => {
    if (!f) return true;
    if (f.includes('*')) {
      const rx = new RegExp('^' + f.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*'), 'i');
      return rx.test(o.product_id);
    }
    return o.product_id.toLowerCase().includes(f) ||
      (o.description ?? '').toLowerCase().includes(f) ||
      (o.vendor_id ?? '').toLowerCase().includes(f) ||
      (o.sku_pattern ?? '').toLowerCase().includes(f);
  };
  const shown = f ? options.filter(matches) : options;
  const shownIds = shown.map((o) => o.product_id);
  const toggle = (id: string) => {
    const next = new Set(sel);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onChange(Array.from(next));
  };
  const selectIds = (ids: string[]) => { const next = new Set(sel); ids.forEach((i) => next.add(i)); onChange(Array.from(next)); };
  const deselectIds = (ids: string[]) => { const next = new Set(sel); ids.forEach((i) => next.delete(i)); onChange(Array.from(next)); };
  const btnStyle: React.CSSProperties = { fontSize: '0.7rem', color: '#d4d4d8', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '2px 7px', cursor: 'pointer' };
  return (
    <div style={{ marginTop: '0.4rem' }}>
      {/* Toggle button — show/hide the full list; the selection count stays visible either way.
          The ⓘ explains the (surprising) whitelist semantics: empty ≡ all selected. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '3px 9px', color: '#d4d4d8', fontSize: '0.72rem', cursor: 'pointer' }}
        >
          <span>{collapsed ? '▸' : '▾'}</span>
          <span>{collapsed ? tP('config.purchasableShowList') : tP('config.purchasableHideList')}</span>
          <span style={{ color: sel.size === 0 ? '#fbbf24' : '#a1a1aa' }}>
            {`(${sel.size} / ${options.length} ${tP('config.purchasableSelected')})`}
            {sel.size === 0 && ` — ${tP('config.purchasableAllHint')}`}
          </span>
        </button>
        <span
          title={tP('config.purchasableSemantics')}
          style={{ fontSize: '0.78rem', color: '#71717a', cursor: 'help', border: '1px solid #52525b', borderRadius: '50%', width: 15, height: 15, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }}
        >
          i
        </span>
      </div>
      {!collapsed && (
        <div style={{ marginTop: 4 }}>
          <input
            type="text"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={tP('config.purchasableSearchPlaceholder')}
            style={{ width: '100%', padding: '4px 8px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem', marginBottom: 4 }}
          />
          {/* Bulk actions. Select all / Clear always apply to the WHOLE list; the wildcard
              pair (shown only when a filter is active) applies to the matched set — so
              `160-*` + Deselect matching removes just that series. The two are complementary:
              e.g. Select all, then filter 160-* → Deselect matching, filter 283-* → Deselect
              matching ⇒ everything except those series. */}
          <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 4 }}>
            <button type="button" style={btnStyle} onClick={() => onChange(options.map((o) => o.product_id))}>
              {tP('config.purchasableSelectAll')}
            </button>
            <button type="button" style={btnStyle} onClick={() => onChange([])}>
              {tP('config.purchasableClear')}
            </button>
            {f && (
              <>
                <span style={{ color: '#52525b' }}>|</span>
                <button type="button" style={btnStyle} onClick={() => selectIds(shownIds)}>
                  {`${tP('config.purchasableSelectShown')} (${shown.length})`}
                </button>
                <button type="button" style={btnStyle} onClick={() => deselectIds(shownIds)}>
                  {`${tP('config.purchasableDeselectShown')} (${shown.length})`}
                </button>
              </>
            )}
          </div>
          <div style={{ maxHeight: 160, overflowY: 'auto', border: '1px solid #3f3f46', borderRadius: 4, padding: '2px 4px' }}>
            {shown.length === 0 && (
              <div style={{ fontSize: '0.75rem', color: '#71717a', padding: '4px' }}>{tP('config.purchasableNone')}</div>
            )}
            {shown.map((o) => (
              <label key={o.product_id}
                style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '2px 0', cursor: 'pointer', fontSize: '0.78rem' }}>
                <input type="checkbox" checked={sel.has(o.product_id)} onChange={() => toggle(o.product_id)} />
                <span style={{ fontFamily: 'monospace', color: '#e4e4e7' }}>{o.product_id}</span>
                {o.description && <span style={{ color: '#a1a1aa' }}>— {o.description}</span>}
              </label>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
