'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import type { AssessmentSummary } from '@/lib/api';

type ColKey = 'date' | 'rating' | 'delay' | 'qtyPct';

type ColDef = {
  key: ColKey;
  align: 'left' | 'center' | 'right';
  defaultWidth: number;
  minWidth: number;
};

const SIZED_COLUMNS: ColDef[] = [
  { key: 'date',   align: 'left',   defaultWidth: 100, minWidth: 60 },
  { key: 'rating', align: 'center', defaultWidth: 80,  minWidth: 50 },
  { key: 'delay',  align: 'right',  defaultWidth: 70,  minWidth: 50 },
  { key: 'qtyPct', align: 'right',  defaultWidth: 80,  minWidth: 50 },
];

const STORAGE_PREFIX = 'assessmentHistoryColWidths:';

type WidthMap = Record<ColKey, number>;

function loadWidths(storageKey: string): Partial<WidthMap> {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + storageKey);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

function saveWidths(storageKey: string, widths: WidthMap) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(STORAGE_PREFIX + storageKey, JSON.stringify(widths));
  } catch {
    /* ignore quota / disabled storage */
  }
}

type Props = {
  rows: AssessmentSummary[];
  /** Separate storageKey buckets distinct tables (e.g. per-event vs main panel). */
  storageKey?: string;
};

export function AssessmentHistoryTable({ rows, storageKey = 'default' }: Props) {
  const t = useTranslations('planning.supplyView.assessment');
  const [widths, setWidths] = useState<WidthMap>(() => {
    const stored = loadWidths(storageKey);
    return {
      date:   stored.date   ?? SIZED_COLUMNS[0].defaultWidth,
      rating: stored.rating ?? SIZED_COLUMNS[1].defaultWidth,
      delay:  stored.delay  ?? SIZED_COLUMNS[2].defaultWidth,
      qtyPct: stored.qtyPct ?? SIZED_COLUMNS[3].defaultWidth,
    };
  });
  const dragRef = useRef<{ colKey: ColKey; startX: number; startW: number } | null>(null);

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      const d = dragRef.current;
      if (!d) return;
      const col = SIZED_COLUMNS.find((c) => c.key === d.colKey)!;
      const next = Math.max(col.minWidth, d.startW + (e.clientX - d.startX));
      setWidths((prev) => (prev[d.colKey] === next ? prev : { ...prev, [d.colKey]: next }));
    };
    const onUp = () => {
      if (!dragRef.current) return;
      dragRef.current = null;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      setWidths((prev) => {
        saveWidths(storageKey, prev);
        return prev;
      });
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [storageKey]);

  const onResizeStart = (colKey: ColKey) => (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    dragRef.current = { colKey, startX: e.clientX, startW: widths[colKey] };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  };

  const handle = (colKey: ColKey) => (
    <span
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize column"
      onMouseDown={onResizeStart(colKey)}
      style={{
        position: 'absolute',
        right: 0,
        top: 0,
        bottom: 0,
        width: 6,
        cursor: 'col-resize',
        userSelect: 'none',
      }}
    />
  );

  const thStyle = (align: ColDef['align']): React.CSSProperties => ({
    textAlign: align,
    padding: '3px 5px',
    fontWeight: 500,
    position: 'relative',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  });

  return (
    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.75rem', tableLayout: 'fixed' }}>
      <colgroup>
        {SIZED_COLUMNS.map((c) => <col key={c.key} style={{ width: widths[c.key] }} />)}
        <col />{/* explanation — flex-fills remaining space */}
      </colgroup>
      <thead>
        <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa' }}>
          {SIZED_COLUMNS.map((c) => (
            <th key={c.key} style={thStyle(c.align)}>
              {t(`historyColumns.${c.key}`)}
              {handle(c.key)}
            </th>
          ))}
          <th style={thStyle('left')}>{t('historyColumns.explanation')}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((h) => (
          <tr key={h.id} style={{ borderBottom: '1px solid #27272a' }}>
            <td style={{ padding: '3px 5px', color: '#71717a', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {h.createdAt.slice(0, 10)}
            </td>
            <td style={{ padding: '3px 5px', textAlign: 'center' }}>
              <span style={{
                padding: '0 6px',
                borderRadius: 10,
                fontWeight: 600,
                fontSize: '0.72rem',
                background: h.rating === 'LOW' ? 'rgba(52,211,153,0.15)' : h.rating === 'HIGH' ? 'rgba(248,113,113,0.15)' : 'rgba(251,191,36,0.15)',
                color: h.rating === 'LOW' ? '#34d399' : h.rating === 'HIGH' ? '#f87171' : '#fbbf24',
              }}>{h.rating}</span>
            </td>
            <td style={{ padding: '3px 5px', textAlign: 'right', color: '#e4e4e7' }}>{h.deliveryDelayDays}</td>
            <td style={{ padding: '3px 5px', textAlign: 'right', color: '#e4e4e7' }}>{h.quantityDecreasePct}</td>
            <td
              style={{
                padding: '3px 5px',
                color: '#a1a1aa',
                overflowWrap: 'anywhere',
                wordBreak: 'break-word',
                verticalAlign: 'top',
              }}
              title={h.explanation}
            >
              {h.explanation}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
