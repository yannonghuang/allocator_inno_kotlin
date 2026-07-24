'use client';

import React, { useMemo, useState } from 'react';
import type { PreferenceRow } from '@/lib/api';
import { SortFilterTable, Column } from './SortFilterTable';
import { SearchableSelect } from './SearchableSelect';

export function rowKey(r: Pick<PreferenceRow, 'product_id' | 'location_id' | 'method_type' | 'method_key'>): string {
  return `${r.product_id}|${r.location_id}|${r.method_type}|${r.method_key}`;
}

export function effectivePreference(r: PreferenceRow, pendingChanges: Map<string, number>): number {
  return pendingChanges.get(rowKey(r)) ?? r.preference;
}

/** SortFilterTable needs a single unique field for React keys — PreferenceRow's uniqueness is
 *  a composite of 4 fields, so we attach the computed natural key as `_key` before rendering. */
type TableRow = PreferenceRow & { _key: string };

function fmtScore(v: number | null): string {
  if (v === null) return '—';
  return v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

const numInput: React.CSSProperties = {
  width: 56, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40',
  borderRadius: 4, color: '#fafafa', fontSize: '0.8rem',
};

/**
 * The Preferences KB's main sort/filter/searchable table — shared between the dedicated
 * Preferences page (editable) and the version-preview popup (read-only). Read-only mode is
 * simply "no `onStagePreference`" — the preference cell then renders as plain text instead of
 * a click-to-edit span, and everything else (filter, sort, all other columns) works identically
 * either way, since `SortFilterTable` already provides that generically.
 */
export function PreferenceTable({
  rows,
  pendingChanges = new Map(),
  onStagePreference,
  t,
  filterPlaceholder,
}: {
  rows: PreferenceRow[];
  /** Uncommitted preference edits, keyed by `rowKey`. Omit for a read-only render. */
  pendingChanges?: Map<string, number>;
  /** Presence of this callback is what makes the table editable — omit for read-only. */
  onStagePreference?: (key: string, value: number, committed: number) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
  filterPlaceholder?: string;
}) {
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const [filterProduct, setFilterProduct] = useState('');
  const [filterLocation, setFilterLocation] = useState('');
  const readOnly = !onStagePreference;

  const productOptions = useMemo(
    () => Array.from(new Set(rows.map((r) => r.product_id))).sort(), [rows]);
  const locationOptions = useMemo(
    () => Array.from(new Set(rows.map((r) => r.location_id))).sort(), [rows]);

  const scopedRows = useMemo(() => rows.filter((r) =>
    (!filterProduct || r.product_id === filterProduct) &&
    (!filterLocation || r.location_id === filterLocation)
  ), [rows, filterProduct, filterLocation]);

  const tableRows: TableRow[] = scopedRows.map((r) => ({ ...r, _key: rowKey(r) }));

  const columns: Column<TableRow>[] = [
    { key: 'product_id', label: t('colProduct'), sortable: true },
    { key: 'location_id', label: t('colLocation'), sortable: true },
    { key: 'prod_area', label: t('colProdArea'), sortable: true },
    { key: 'method_type', label: t('colMethodType'), sortable: true },
    { key: 'method_key', label: t('colMethodKey'), sortable: true },
    {
      key: 'preference', label: t('colPreference'), sortable: true,
      sortValue: (row) => effectivePreference(row, pendingChanges),
      render: (row) => {
        const k = rowKey(row);
        const val = effectivePreference(row, pendingChanges);
        const dirty = pendingChanges.has(k);
        if (!readOnly && editingKey === k) {
          return (
            <input
              autoFocus type="number" value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              onBlur={() => {
                const n = parseInt(editValue, 10);
                setEditingKey(null);
                if (!isNaN(n)) onStagePreference?.(k, n, row.preference);
              }}
              onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); if (e.key === 'Escape') setEditingKey(null); }}
              style={numInput}
            />
          );
        }
        if (readOnly) {
          return <span style={{ fontVariantNumeric: 'tabular-nums' }}>{val}</span>;
        }
        return (
          <span
            onClick={() => { setEditingKey(k); setEditValue(String(val)); }}
            style={{ cursor: 'pointer', color: dirty ? '#fdba74' : '#e4e4e7', fontVariantNumeric: 'tabular-nums' }}
          >
            {val}
          </span>
        );
      },
    },
    { key: 'inventory_score', label: t('colInventoryScore'), sortable: true, render: (row) => fmtScore(row.inventory_score) },
    { key: 'delivery_score', label: t('colDeliveryScore'), sortable: true, render: (row) => fmtScore(row.delivery_score) },
    { key: 'critical_material_score', label: t('colCriticalMaterialScore'), sortable: true, render: (row) => fmtScore(row.critical_material_score) },
  ];

  return (
    <div>
      <div style={{ display: 'flex', gap: 6, marginBottom: '0.5rem' }}>
        <SearchableSelect
          value={filterProduct}
          onChange={setFilterProduct}
          options={productOptions.map((p) => ({ value: p, label: p }))}
          placeholder={t('filterAllProducts')}
          width={200}
        />
        <SearchableSelect
          value={filterLocation}
          onChange={setFilterLocation}
          options={locationOptions.map((l) => ({ value: l, label: l }))}
          placeholder={t('filterAllLocations')}
          width={200}
        />
      </div>
      <SortFilterTable
        columns={columns}
        rows={tableRows}
        filterKeys={['product_id', 'location_id', 'prod_area', 'method_type', 'method_key']}
        filterPlaceholder={filterPlaceholder ?? t('filterPlaceholder')}
        defaultSortKey="preference"
        idKey="_key"
        rowId={(r) => r._key}
      />
    </div>
  );
}
