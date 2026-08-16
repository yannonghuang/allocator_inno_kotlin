'use client';

import React, { useState } from 'react';
import type { MethodMoveOverrideRow } from '@/lib/api';

/** Builder for method_move overrides: a small add-row form (product_id, from_location_id,
 *  to_location_id, transit_time, transit_time_uom, preference) + a removable list of added rows.
 *  Used on the dedicated Method Move Overrides page (see CaseMethodMoves' own doc in Tables.kt).
 *  `product_id`/`from_location_id`/`to_location_id` are free text rather than closed dropdowns on
 *  purpose — they accept an individual value, a PROD_AREA value (product_id only), or "*"/""
 *  (matches everything), matching MethodMakeOverridePicker's identical rationale. */
export function MethodMoveOverridePicker({
  rows,
  onChange,
  defaultCollapsed,
  t,
  readOnly = false,
}: {
  rows: MethodMoveOverrideRow[];
  /** Required unless [readOnly] — a read-only render never calls this. */
  onChange?: (next: MethodMoveOverrideRow[]) => void;
  defaultCollapsed?: boolean;
  t: (k: string) => string;
  /** Hides the "add row" form and each row's remove (×) button — the row list and collapse
   *  toggle stay live. Used by the version-preview popup so it can reuse this exact picker
   *  instead of a separate simplified viewer. */
  readOnly?: boolean;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [productId, setProductId] = useState('*');
  const [fromLocationId, setFromLocationId] = useState('*');
  const [toLocationId, setToLocationId] = useState('');
  const [transitTime, setTransitTime] = useState('');
  const [transitTimeUom, setTransitTimeUom] = useState('');
  const [preference, setPreference] = useState('');

  const canAdd = !!toLocationId.trim();

  const inputStyle: React.CSSProperties = { padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.78rem', width: 130 };
  const numStyle: React.CSSProperties = { ...inputStyle, width: 80 };
  const btnStyle: React.CSSProperties = { fontSize: '0.7rem', color: '#d4d4d8', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '3px 9px', cursor: 'pointer' };
  const toNum = (s: string): number | null => (s.trim() === '' ? null : Number(s));
  const anyPid = (p: string) => (p === '*' || !p ? t('overridesAny') : p);

  const addRow = () => {
    if (!canAdd || readOnly) return;
    const row: MethodMoveOverrideRow = {
      product_id: productId.trim() || '*',
      from_location_id: fromLocationId.trim() || '*',
      to_location_id: toLocationId.trim(),
      transit_time: toNum(transitTime),
      transit_time_uom: transitTimeUom.trim() || null,
      preference: toNum(preference),
    };
    onChange?.([...rows, row]);
    setProductId('*'); setFromLocationId('*'); setToLocationId(''); setTransitTime(''); setTransitTimeUom(''); setPreference('');
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
              <div style={{ fontSize: '0.7rem', color: '#71717a', marginBottom: 4 }}>{t('moveHint')}</div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', marginBottom: 6 }}>
                <input value={productId} onChange={(e) => setProductId(e.target.value)} placeholder={t('productIdOrProdAreaOrAny')} style={inputStyle} />
                <input value={fromLocationId} onChange={(e) => setFromLocationId(e.target.value)} placeholder={t('fromLocationIdOrAny')} style={inputStyle} />
                <input value={toLocationId} onChange={(e) => setToLocationId(e.target.value)} placeholder={t('toLocationId')} style={inputStyle} />
                <input value={transitTime} onChange={(e) => setTransitTime(e.target.value)} placeholder={t('transitTime')} type="number" style={numStyle} />
                <input value={transitTimeUom} onChange={(e) => setTransitTimeUom(e.target.value)} placeholder={t('transitTimeUom')} style={numStyle} />
                <input value={preference} onChange={(e) => setPreference(e.target.value)} placeholder={t('preference')} type="number" style={numStyle} />
                <button type="button" onClick={addRow} disabled={!canAdd} style={{ ...btnStyle, opacity: canAdd ? 1 : 0.4, cursor: canAdd ? 'pointer' : 'default' }}>{t('overridesAdd')}</button>
              </div>
            </>
          )}
          {rows.length === 0 ? (
            <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{t('overridesEmpty')}</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {rows.map((r, i) => (
                <div key={`${r.product_id}|${r.from_location_id}|${r.to_location_id}|${i}`} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.76rem' }}>
                  <span style={{ fontFamily: 'monospace', color: '#e4e4e7' }}>
                    {anyPid(r.product_id)}: {anyPid(r.from_location_id)} → {r.to_location_id}
                    {r.transit_time != null ? ` · transit=${r.transit_time}${r.transit_time_uom ? ` ${r.transit_time_uom}` : ''}` : ''}
                    {r.preference != null ? ` · pref=${r.preference}` : ''}
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
