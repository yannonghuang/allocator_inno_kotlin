'use client';

import React, { useState } from 'react';
import { qtyFmt } from '@/app/lib/format';
import type { CommittedDemand } from '@/lib/api';

export type ManifestRow = { demand: string | null; comp: string; qty: number };

export type WoManifestTableLabels = {
  demand: string;
  component: string;
  qty: string;
  footerDemands: string;
  footerComponents: string;
};

export type WoManifestTableProps = {
  rows: ManifestRow[];
  /** Physical total quantity — passed separately from `rows` because a row can be dropped
   *  (e.g. an uncommitted demand split) while the physical total should still reflect it. */
  totalQty: number;
  committedDemands: CommittedDemand[];
  onOpenDemandPegging: (demandRow: CommittedDemand, demandId: string) => void;
  labels: WoManifestTableLabels;
};

type SortCol = 'demand' | 'comp' | 'qty';

/** Demand x component x qty manifest table — shared by move, purchase, and make work orders
 *  in the consolidated view. One row per (demand, component), sortable; clicking a demand id
 *  drills into that demand's own pegging tree via `onOpenDemandPegging`. */
export function WoManifestTable({
  rows,
  totalQty,
  committedDemands,
  onOpenDemandPegging,
  labels,
}: WoManifestTableProps): JSX.Element {
  const [sortCol, setSortCol] = useState<SortCol>('demand');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const demandKey = (r: ManifestRow) => r.demand ?? '–';
  const dir = sortDir === 'asc' ? 1 : -1;
  const sorted = [...rows].sort((a, b) => {
    if (sortCol === 'qty') return dir * (a.qty - b.qty);
    if (sortCol === 'comp') return dir * (a.comp.localeCompare(b.comp) || demandKey(a).localeCompare(demandKey(b)));
    return dir * (demandKey(a).localeCompare(demandKey(b)) || a.comp.localeCompare(b.comp));
  });
  const distinctDemands = new Set(rows.map((r) => r.demand).filter(Boolean)).size;
  const distinctComps = new Set(rows.map((r) => r.comp)).size;

  const thStyle = (col: SortCol, align: 'left' | 'right' = 'left'): React.CSSProperties => ({
    textAlign: align, padding: '5px 14px 5px 0', fontWeight: 500, cursor: 'pointer',
    userSelect: 'none', color: sortCol === col ? '#e4e4e7' : '#71717a',
    ...(align === 'right' ? { paddingRight: 0 } : {}),
  });
  const sortIcon = (col: SortCol) => (sortCol === col ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '');
  const toggleSort = (col: SortCol) => {
    if (sortCol === col) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortCol(col); setSortDir('asc'); }
  };

  return (
    <div style={{ flex: 1, overflow: 'auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
        <thead>
          <tr style={{ borderBottom: '2px solid #3f3f46', position: 'sticky', top: 0, background: '#1c1c1e' }}>
            <th style={thStyle('demand')} onClick={() => toggleSort('demand')}>{labels.demand}{sortIcon('demand')}</th>
            <th style={thStyle('comp')} onClick={() => toggleSort('comp')}>{labels.component}{sortIcon('comp')}</th>
            <th style={{ ...thStyle('qty', 'right'), paddingRight: 0 }} onClick={() => toggleSort('qty')}>{labels.qty}{sortIcon('qty')}</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r, i) => {
            const demandRow = r.demand ? (committedDemands.find((cd) => cd.demand_id === r.demand) ?? null) : null;
            return (
              <tr key={i} style={{ borderBottom: '1px solid #27272a' }}>
                <td style={{ padding: '5px 14px 5px 0', wordBreak: 'break-all', fontSize: '0.8rem' }}>
                  {!r.demand ? (
                    <span style={{ color: '#a1a1aa' }}>–</span>
                  ) : demandRow ? (
                    <button
                      type="button"
                      style={{ background: 'none', border: 'none', padding: 0, color: '#60a5fa', cursor: 'pointer', textDecoration: 'underline', fontSize: 'inherit', textAlign: 'left', wordBreak: 'break-all' }}
                      onClick={() => onOpenDemandPegging(demandRow, r.demand!)}
                    >{r.demand}</button>
                  ) : (
                    <span style={{ color: '#a1a1aa' }}>{r.demand}</span>
                  )}
                </td>
                <td style={{ padding: '5px 14px 5px 0', color: '#e4e4e7', fontFamily: 'monospace', fontSize: '0.8rem' }}>{r.comp}</td>
                <td style={{ padding: '5px 0', textAlign: 'right', color: '#fafafa', fontVariantNumeric: 'tabular-nums' }}>{qtyFmt(r.qty)}</td>
              </tr>
            );
          })}
        </tbody>
        <tfoot>
          <tr style={{ borderTop: '2px solid #3f3f46', color: '#a1a1aa', fontSize: '0.78rem' }}>
            <td style={{ padding: '5px 14px 5px 0' }}>{distinctDemands} {labels.footerDemands}</td>
            <td style={{ padding: '5px 14px 5px 0' }}>{distinctComps} {labels.footerComponents}</td>
            <td style={{ padding: '5px 0', textAlign: 'right', color: '#fafafa', fontVariantNumeric: 'tabular-nums' }}>{qtyFmt(totalQty)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
