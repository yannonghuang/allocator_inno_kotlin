'use client';

import React, { useMemo, useState } from 'react';
import type { DemandOrderRow } from '@/lib/api';

export function effectiveOrder(r: DemandOrderRow, pendingChanges: Map<string, number>): number {
  return pendingChanges.get(r.demand_id) ?? r.order;
}

/**
 * Demand Ordering's searchable, reorderable table — shared between the dedicated Demand
 * Ordering page (editable) and the version-preview popup (read-only). Read-only mode is simply
 * "no `onStageOrder`" — the ▲/▼ reorder buttons and click-to-edit order cell disappear, the
 * search box and every other column stay identical either way.
 */
export function DemandOrderTable({
  rows,
  pendingChanges = new Map(),
  onStageOrder,
  t,
}: {
  rows: DemandOrderRow[];
  /** Uncommitted order edits, keyed by `demand_id`. Omit for a read-only render. */
  pendingChanges?: Map<string, number>;
  /** Presence of this callback is what makes the table editable (reorder buttons + click-to-edit
   *  order cell) — omit for read-only. */
  onStageOrder?: (demandId: string, value: number, committed: number) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}) {
  const [search, setSearch] = useState('');
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const readOnly = !onStageOrder;

  const numInput: React.CSSProperties = {
    width: 56, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40',
    borderRadius: 4, color: '#fafafa', fontSize: '0.8rem',
  };
  const btnStyle: React.CSSProperties = {
    padding: '0.35rem 0.75rem', fontSize: '0.8rem', borderRadius: 4, cursor: 'pointer',
    border: '1px solid #3f3f46', background: '#1c1c1f', color: '#e4e4e7',
  };

  // Always sorted by effective order (so up/down swap-with-neighbor is well-defined), optionally
  // narrowed by a free-text search across demand_id/customer_id/product_id.
  const visibleRows: DemandOrderRow[] = useMemo(() => {
    const q = search.trim().toLowerCase();
    const filtered = q
      ? rows.filter((r) =>
          r.demand_id.toLowerCase().includes(q) ||
          r.customer_id.toLowerCase().includes(q) ||
          r.product_id.toLowerCase().includes(q))
      : rows;
    return [...filtered].sort((a, b) => effectiveOrder(a, pendingChanges) - effectiveOrder(b, pendingChanges));
  }, [rows, search, pendingChanges]);

  const swapWithNeighbor = (index: number, direction: -1 | 1) => {
    if (!onStageOrder) return;
    const other = index + direction;
    if (other < 0 || other >= visibleRows.length) return;
    const a = visibleRows[index];
    const b = visibleRows[other];
    const aOrder = effectiveOrder(a, pendingChanges);
    const bOrder = effectiveOrder(b, pendingChanges);
    onStageOrder(a.demand_id, bOrder, a.order);
    onStageOrder(b.demand_id, aOrder, b.order);
  };

  return (
    <div>
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
            {!readOnly && <th style={{ textAlign: 'left', padding: '0.25rem 0.5rem' }}></th>}
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
            const val = effectiveOrder(row, pendingChanges);
            const dirty = pendingChanges.has(k);
            return (
              <tr key={k} style={{ borderBottom: '1px solid #1c1c1f' }}>
                {!readOnly && (
                  <td style={{ padding: '0.25rem 0.5rem', whiteSpace: 'nowrap' }}>
                    <button
                      onClick={() => swapWithNeighbor(i, -1)} disabled={i === 0}
                      title={t('moveUp')}
                      style={{ ...btnStyle, padding: '1px 6px', opacity: i === 0 ? 0.3 : 1 }}
                    >▲</button>
                    <button
                      onClick={() => swapWithNeighbor(i, 1)} disabled={i === visibleRows.length - 1}
                      title={t('moveDown')}
                      style={{ ...btnStyle, padding: '1px 6px', marginLeft: 4, opacity: i === visibleRows.length - 1 ? 0.3 : 1 }}
                    >▼</button>
                  </td>
                )}
                <td style={{ padding: '0.25rem 0.5rem', textAlign: 'right' }}>
                  {!readOnly && editingKey === k ? (
                    <input
                      autoFocus type="number" value={editValue}
                      onChange={(e) => setEditValue(e.target.value)}
                      onBlur={() => {
                        const n = parseInt(editValue, 10);
                        setEditingKey(null);
                        if (!isNaN(n)) onStageOrder?.(k, n, row.order);
                      }}
                      onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); if (e.key === 'Escape') setEditingKey(null); }}
                      style={numInput}
                    />
                  ) : readOnly ? (
                    <span style={{ fontVariantNumeric: 'tabular-nums' }}>{val}</span>
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
      <p style={{ fontSize: '0.75rem', color: '#71717a', marginTop: '0.5rem' }}>
        {visibleRows.length} row{visibleRows.length !== 1 ? 's' : ''}
        {search && ` (filtered from ${rows.length})`}
      </p>
    </div>
  );
}
