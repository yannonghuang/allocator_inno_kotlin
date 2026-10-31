'use client';

import React, { useState } from 'react';
import type { MethodMakeOverrideRow } from '@/lib/api';

/** Builder for method_make overrides: a small add-row form (product_id, location_id,
 *  preference, lead_time, yield) + a removable list of added rows. Used on the dedicated Method
 *  Make Overrides page (see CaseMethodMakes' own doc in Tables.kt for why this became a
 *  case-level persisted setting). `product_id`/`location_id` are free text rather than closed
 *  dropdowns on purpose — they accept an individual product_id, a PROD_AREA value, or "*"/""
 *  (matches every product/location), so a fixed catalog would actively get in the way of the
 *  wildcard use case this object exists for.
 *
 *  No bom_id field — it's derived server-side ("BOM_" + the resolved concrete product_id, once a
 *  wildcard product_id is matched to one at plan time; see getMethods/deriveMakeBomId in
 *  PlanningEngine.kt) rather than entered here: a wildcard product_id resolves to MULTIPLE
 *  products, each with its own real BOM, so there's no single correct literal to type per row. */
export function MethodMakeOverridePicker({
  rows,
  onChange,
  defaultCollapsed,
  t,
  readOnly = false,
}: {
  rows: MethodMakeOverrideRow[];
  /** Required unless [readOnly] — a read-only render never calls this. */
  onChange?: (next: MethodMakeOverrideRow[]) => void;
  defaultCollapsed?: boolean;
  t: (k: string) => string;
  /** Hides the "add row" form and each row's remove (×) button — the row list and collapse
   *  toggle stay live. Used by the version-preview popup so it can reuse this exact picker
   *  instead of a separate simplified viewer. */
  readOnly?: boolean;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [productId, setProductId] = useState('*');
  const [locationId, setLocationId] = useState('*');
  const [preference, setPreference] = useState('');
  const [leadTime, setLeadTime] = useState('');
  const [yieldVal, setYieldVal] = useState('');

  const inputStyle: React.CSSProperties = { padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.78rem', width: 130 };
  const numStyle: React.CSSProperties = { ...inputStyle, width: 80 };
  const btnStyle: React.CSSProperties = { fontSize: '0.7rem', color: '#d4d4d8', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '3px 9px', cursor: 'pointer' };
  const toNum = (s: string): number | null => (s.trim() === '' ? null : Number(s));
  const anyPid = (p: string) => (p === '*' || !p ? t('overridesAny') : p);

  const addRow = () => {
    if (readOnly) return;
    const row: MethodMakeOverrideRow = {
      product_id: productId.trim() || '*',
      location_id: locationId.trim() || '*',
      preference: toNum(preference),
      lead_time: toNum(leadTime),
      yield: toNum(yieldVal),
    };
    onChange?.([...rows, row]);
    setProductId('*'); setLocationId('*'); setPreference(''); setLeadTime(''); setYieldVal('');
  };

  return (
    <div style={{ marginTop: '0.4rem' }}>
      <button type="button" onClick={() => setCollapsed((c) => !c)}
        style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', background: 'none', border: 'none', padding: 0, color: 'inherit', fontSize: '0.875rem', cursor: 'pointer' }}>
        <span style={{ fontSize: '0.7rem', color: '#a1a1aa' }}>{collapsed ? '▸' : '▾'}</span>
        <span>{collapsed ? t('overridesShow') : t('overridesHide')}</span>
        <span style={{ color: '#a1a1aa' }}>{`(${rows.length})`}</span>
      </button>
      {!collapsed && (
        <div style={{ marginTop: '0.35rem', marginLeft: '1.5rem' }}>
          {!readOnly && (
            <>
              <div style={{ fontSize: '0.7rem', color: '#71717a', marginBottom: 4 }}>{t('makeHint')}</div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', marginBottom: 6 }}>
                <input value={productId} onChange={(e) => setProductId(e.target.value)} placeholder={t('productIdOrProdAreaOrAny')} style={inputStyle} />
                <input value={locationId} onChange={(e) => setLocationId(e.target.value)} placeholder={t('locationIdOrAny')} style={inputStyle} />
                <input value={preference} onChange={(e) => setPreference(e.target.value)} placeholder={t('preference')} type="number" style={numStyle} />
                <input value={leadTime} onChange={(e) => setLeadTime(e.target.value)} placeholder={t('leadTime')} type="number" style={numStyle} />
                <input value={yieldVal} onChange={(e) => setYieldVal(e.target.value)} placeholder={t('yield')} type="number" step="0.01" style={numStyle} />
                <button type="button" onClick={addRow} style={btnStyle}>{t('overridesAdd')}</button>
              </div>
            </>
          )}
          {rows.length === 0 ? (
            <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{t('overridesEmpty')}</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {rows.map((r, i) => (
                <div key={`${r.product_id}|${r.location_id}|${i}`} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.76rem' }}>
                  <span style={{ fontFamily: 'monospace', color: '#e4e4e7' }}>
                    {anyPid(r.product_id)} @ {anyPid(r.location_id)}
                    {r.preference != null ? ` · pref=${r.preference}` : ''}
                    {r.lead_time != null ? ` · lead=${r.lead_time}` : ''}
                    {r.yield != null ? ` · yield=${r.yield}` : ''}
                  </span>
                  {!readOnly && (
                    <button type="button" title={t('overridesRemove')} onClick={() => onChange?.(rows.filter((_, j) => j !== i))}
                      style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer', fontSize: '0.95rem', lineHeight: 1, padding: 0 }}>×</button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
