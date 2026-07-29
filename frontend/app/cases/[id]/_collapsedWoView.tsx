'use client';

import React, { useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { WorkOrder } from '@/lib/api';
import { qtyFmt } from '@/app/lib/format';
import { methodColor } from './_workOrderSchedule';

/** Same vocabulary as PlanningConfig['consolidation'].*_batch_scale (lib/api.ts) and the
 *  backend's WoBatchConfig/calendarBucket (PlanningEngine.kt) — see this file's own module doc. */
export type WoBatchScale = 'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';

/** Mirrors the flat table's own Pivot control (`planWoPivot` in _CaseSectionPage.tsx) — here it
 *  rolls this view's own (product, location, method) rows up under collapsible section headers
 *  instead of changing the row grouping itself. 'demand' (a flat-table-only mode) is never passed
 *  in — the caller maps it to 'none' before reaching this component. */
export type WoPivotMode = 'none' | 'prod_area' | 'location' | 'nested';

const SCALE_RANK: Record<WoBatchScale, number> = { none: 0, weekly: 1, biweekly: 2, monthly: 3, all: 4 };
const GRANULARITIES: WoBatchScale[] = ['none', 'weekly', 'biweekly', 'monthly', 'all'];
const DAY_MS = 86_400_000;

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** UTC epoch-day (days since 1970-01-01) of a date-only or datetime ISO string — the exact
 *  anchor the backend's `calendarBucket` uses (`LocalDate.toEpochDay()`), NOT an ISO
 *  Monday-aligned week. Matching this anchor exactly is the whole point of this file: the pivot
 *  must agree with consolidation's own weekly/biweekly bucket boundaries bit-for-bit. */
function epochDay(iso: string): number {
  const s = iso.slice(0, 10);
  return Math.floor(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10)) / DAY_MS);
}

function dateFromEpochDay(d: number): Date {
  return new Date(d * DAY_MS);
}

/** Mirrors `calendarBucket` (PlanningEngine.kt) exactly: same scale names, same epoch-day
 *  arithmetic for weekly/biweekly, same calendar-month grouping for monthly, one bucket for
 *  "all". "none" has no backend equivalent (unbatched rows never reach calendarBucket at all —
 *  each stays its own consolidated WO) — for DISPLAY purposes it means "bucket by exact day". */
function bucketKeyFor(iso: string, scale: WoBatchScale): string {
  const s = iso.slice(0, 10);
  if (scale === 'monthly') return s.slice(0, 7);
  if (scale === 'all') return 'all';
  if (scale === 'weekly') return `w${Math.floor(epochDay(s) / 7)}`;
  if (scale === 'biweekly') return `b${Math.floor(epochDay(s) / 14)}`;
  return s; // 'none'
}

function formatDate(d: Date, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(d);
  } catch {
    return d.toISOString().slice(0, 10);
  }
}

/** Inclusive start–end range label for a weekly/biweekly bucket. Buckets are epoch-day anchored
 *  (see epochDay's own doc) — NOT aligned to the horizon start or any calendar boundary — so a
 *  bucket's start date alone can look like it's "in the past" relative to the run's actual
 *  earliest work order (e.g. the week containing 2026-07-01 starts 2026-06-25). Showing the full
 *  range makes it visually obvious the column is a partial/leading window, not a June date. */
function formatDateRange(start: Date, end: Date, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).formatRange(start, end);
  } catch {
    return `${formatDate(start, locale)} – ${formatDate(end, locale)}`;
  }
}

/** Ordered bucket list spanning every day in [minIso, maxIso] at the given scale — same key
 *  scheme as [bucketKeyFor], so a WO's bucketKeyFor(...) always matches one of these entries. */
function buildBuckets(minIso: string, maxIso: string, scale: WoBatchScale, locale: string, allLabel: string): { key: string; label: string }[] {
  const buckets: { key: string; label: string }[] = [];
  if (scale === 'all') return [{ key: 'all', label: allLabel }];
  if (scale === 'monthly') {
    let y = +minIso.slice(0, 4);
    let m = +minIso.slice(5, 7);
    const maxY = +maxIso.slice(0, 4);
    const maxM = +maxIso.slice(5, 7);
    let guard = 0;
    while ((y < maxY || (y === maxY && m <= maxM)) && guard++ < 600) {
      buckets.push({ key: `${y}-${pad2(m)}`, label: `${y}-${pad2(m)}` });
      m += 1;
      if (m > 12) { m = 1; y += 1; }
    }
    return buckets;
  }
  const step = scale === 'biweekly' ? 14 : scale === 'weekly' ? 7 : 1;
  const minBucket = Math.floor(epochDay(minIso) / step);
  const maxBucket = Math.floor(epochDay(maxIso) / step);
  let guard = 0;
  for (let b = minBucket; b <= maxBucket && guard < 3000; b++, guard++) {
    const startDay = b * step;
    if (scale === 'none') {
      buckets.push({ key: dateFromEpochDay(startDay).toISOString().slice(0, 10), label: formatDate(dateFromEpochDay(startDay), locale) });
    } else {
      const rangeLabel = formatDateRange(dateFromEpochDay(startDay), dateFromEpochDay(startDay + step - 1), locale);
      buckets.push({ key: `${scale === 'weekly' ? 'w' : 'b'}${b}`, label: rangeLabel });
    }
  }
  return buckets;
}

type Contributor = { end_time: string; quantity: number };
type Cell = { qty: number; contributors: Contributor[] };
type GroupRow = {
  key: string;
  product_id: string;
  location_id: string;
  method: string;
  prod_area: string;
  cells: Map<string, Cell>;
  total: number;
};
type Bucket = { key: string; label: string };
type Section = { key: string; groups: GroupRow[]; total: number };

function buildSections(groups: GroupRow[], by: 'prod_area' | 'location'): Section[] {
  const map = new Map<string, GroupRow[]>();
  for (const g of groups) {
    const k = (by === 'prod_area' ? g.prod_area : g.location_id) || '(none)';
    if (!map.has(k)) map.set(k, []);
    map.get(k)!.push(g);
  }
  return Array.from(map.entries())
    .map(([key, gs]) => ({ key, groups: gs, total: gs.reduce((s, g) => s + g.total, 0) }))
    .sort((a, b) => b.total - a.total);
}

/**
 * Pivot/cross-tab built on top of the Consolidated Work Orders view: one row per
 * (product, location, method) group, time running horizontally as none(day)/weekly/biweekly/
 * monthly/all buckets — deliberately the SAME scale vocabulary and bucket boundaries WO
 * consolidation itself uses (see `bucketKeyFor`'s own doc), not a generic calendar grid.
 *
 * A cell backed by more than one original consolidated row is only an anomaly relative to that
 * group's own METHOD batch scale: consolidation already guarantees at most one WO per
 * (product, location, method, its-own-scale-bucket). If the pivot's chosen granularity is the
 * SAME resolution or FINER than that scale, a collision means consolidation failed to merge WOs
 * it should have — flagged visibly. If the pivot is COARSER than the batch scale, seeing several
 * already-correctly-consolidated batches share one wider pivot bucket is normal — summed
 * silently, no flag.
 */
export function CollapsedWoView({
  rows,
  makeBatchScale = 'weekly',
  moveBatchScale = 'weekly',
  purchaseBatchScale = 'weekly',
  pivot = 'none',
}: {
  rows: WorkOrder[];
  makeBatchScale?: WoBatchScale;
  moveBatchScale?: WoBatchScale;
  purchaseBatchScale?: WoBatchScale;
  pivot?: WoPivotMode;
}) {
  const tP = useTranslations('planning');
  const locale = useLocale();
  const [granularity, setGranularity] = useState<WoBatchScale>('weekly');
  const [filterText, setFilterText] = useState('');
  const [sortBy, setSortBy] = useState<'qty' | 'product' | 'location'>('qty');

  const scaleForMethod = (method: string): WoBatchScale => {
    if (method === 'make') return makeBatchScale;
    if (method === 'move') return moveBatchScale;
    if (method === 'purchase' || method === 'buy') return purchaseBatchScale;
    return 'weekly';
  };

  const { groups, buckets, excludedCount, flaggedCount } = useMemo(() => {
    const candidateRows = rows.filter((r) => r.method !== 'inventory');
    const validRows = candidateRows.filter((r) => !!r.end_time);
    const excludedCount = candidateRows.length - validRows.length;
    if (validRows.length === 0) {
      return { groups: [] as GroupRow[], buckets: [] as Bucket[], excludedCount, flaggedCount: 0 };
    }
    const ends = validRows.map((r) => (r.end_time as string).slice(0, 10));
    const minIso = ends.reduce((a, b) => (a < b ? a : b));
    const maxIso = ends.reduce((a, b) => (a > b ? a : b));
    const buckets = buildBuckets(minIso, maxIso, granularity, locale, tP('config.woBatchAll'));

    const groupMap = new Map<string, GroupRow>();
    for (const r of validRows) {
      const key = `${r.product_id}|${r.location_id}|${r.method}`;
      let grp = groupMap.get(key);
      if (!grp) {
        grp = { key, product_id: r.product_id, location_id: r.location_id, method: r.method, prod_area: r.prod_area ?? '', cells: new Map(), total: 0 };
        groupMap.set(key, grp);
      }
      if (!grp.prod_area && r.prod_area) grp.prod_area = r.prod_area;
      const iso = (r.end_time as string).slice(0, 10);
      const bucketKey = bucketKeyFor(iso, granularity);
      const qty = Number(r.quantity) || 0;
      let cell = grp.cells.get(bucketKey);
      if (!cell) {
        cell = { qty: 0, contributors: [] };
        grp.cells.set(bucketKey, cell);
      }
      cell.qty += qty;
      cell.contributors.push({ end_time: r.end_time as string, quantity: qty });
      grp.total += qty;
    }
    const groups = Array.from(groupMap.values());
    let flaggedCount = 0;
    for (const grp of groups) {
      const consolidationRank = SCALE_RANK[scaleForMethod(grp.method)];
      const pivotRank = SCALE_RANK[granularity];
      const alertOnCollision = consolidationRank >= pivotRank;
      for (const cell of Array.from(grp.cells.values())) {
        if (cell.contributors.length > 1 && alertOnCollision) flaggedCount++;
      }
    }
    return { groups, buckets, excludedCount, flaggedCount };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, granularity, locale, makeBatchScale, moveBatchScale, purchaseBatchScale]);

  // Filter + sort are cheap over the already-grouped rows, so kept out of the heavy
  // grouping/bucketing memo above — typing in the filter box never re-buckets.
  const visibleGroups = useMemo(() => {
    const f = filterText.trim().toLowerCase();
    let out = f
      ? groups.filter((g) => g.product_id.toLowerCase().includes(f) || g.location_id.toLowerCase().includes(f))
      : groups;
    out = [...out].sort((a, b) => {
      if (sortBy === 'product') return a.product_id.localeCompare(b.product_id) || a.location_id.localeCompare(b.location_id);
      if (sortBy === 'location') return a.location_id.localeCompare(b.location_id) || a.product_id.localeCompare(b.product_id);
      return b.total - a.total;
    });
    return out;
  }, [groups, filterText, sortBy]);

  if (buckets.length === 0) {
    return <div style={{ padding: '1rem', color: '#a1a1aa', fontSize: '0.875rem' }}>{tP('workOrders.collapsedEmpty')}</div>;
  }

  const renderTable = (rowsForTable: GroupRow[]) => (
    <div style={{ overflowX: 'auto', border: '1px solid #3f3f46', borderRadius: 6 }}>
      <table style={{ borderCollapse: 'collapse', fontSize: '0.8rem', minWidth: '100%' }}>
        <thead>
          <tr>
            <th style={{ position: 'sticky', left: 0, zIndex: 1, background: '#18181b', textAlign: 'left', padding: '6px 10px', borderBottom: '1px solid #3f3f46', borderRight: '1px solid #3f3f46', whiteSpace: 'nowrap' }}>
              {tP('workOrders.collapsedGroupHeader')}
            </th>
            {buckets.map((p) => (
              <th key={p.key} style={{ padding: '6px 10px', borderBottom: '1px solid #3f3f46', color: '#a1a1aa', whiteSpace: 'nowrap', textAlign: 'right' }}>
                {p.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rowsForTable.map((grp) => {
            const consolidationRank = SCALE_RANK[scaleForMethod(grp.method)];
            const pivotRank = SCALE_RANK[granularity];
            const alertOnCollision = consolidationRank >= pivotRank;
            return (
              <tr key={grp.key}>
                <td style={{ position: 'sticky', left: 0, zIndex: 1, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', borderRight: '1px solid #3f3f46', whiteSpace: 'nowrap' }}>
                  <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: methodColor(grp.method), marginRight: 6 }} />
                  {grp.product_id} · {grp.location_id} · {grp.method}
                </td>
                {buckets.map((p) => {
                  const cell = grp.cells.get(p.key);
                  const flagged = !!cell && cell.contributors.length > 1 && alertOnCollision;
                  return (
                    <td
                      key={p.key}
                      title={flagged ? cell!.contributors.map((c) => `${c.end_time}: ${qtyFmt(c.quantity)}`).join('\n') : undefined}
                      style={{
                        padding: '6px 10px',
                        borderBottom: '1px solid #27272a',
                        textAlign: 'right',
                        color: cell ? '#e4e4e7' : '#3f3f46',
                        background: flagged ? 'rgba(239,68,68,0.15)' : undefined,
                        outline: flagged ? '1px solid rgba(239,68,68,0.5)' : undefined,
                        outlineOffset: flagged ? '-1px' : undefined,
                      }}
                    >
                      {cell ? (flagged ? `⚠ ${qtyFmt(cell.qty)}` : qtyFmt(cell.qty)) : '–'}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );

  const renderSectionSummary = (label: string, groupCount: number, total: number) => (
    <span>
      <strong style={{ color: '#f4f4f5' }}>{label}</strong>
      <span style={{ color: '#71717a', marginLeft: 8, fontSize: '0.78rem' }}>
        {groupCount} · {qtyFmt(total)}
      </span>
    </span>
  );

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.6rem', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.82rem', color: '#a1a1aa' }}>
          {tP('workOrders.collapsedGranularity')}
          <select
            value={granularity}
            onChange={(e) => setGranularity(e.target.value as WoBatchScale)}
            style={{ padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem' }}
          >
            {GRANULARITIES.map((g) => (
              <option key={g} value={g}>
                {tP(`config.woBatch${g.charAt(0).toUpperCase()}${g.slice(1)}`)}
              </option>
            ))}
          </select>
        </label>
        <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.82rem', color: '#a1a1aa' }}>
          {tP('workOrders.collapsedSortBy')}
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as 'qty' | 'product' | 'location')}
            style={{ padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem' }}
          >
            <option value="qty">{tP('workOrders.collapsedSortQty')}</option>
            <option value="product">{tP('workOrders.collapsedSortProduct')}</option>
            <option value="location">{tP('workOrders.collapsedSortLocation')}</option>
          </select>
        </label>
        <input
          type="text"
          value={filterText}
          onChange={(e) => setFilterText(e.target.value)}
          placeholder={tP('workOrders.collapsedFilterPlaceholder')}
          style={{ padding: '3px 8px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem', width: 180 }}
        />
        {excludedCount > 0 && (
          <span style={{ fontSize: '0.78rem', color: '#71717a' }}>{tP('workOrders.collapsedNoEndDate', { n: excludedCount })}</span>
        )}
      </div>
      {flaggedCount > 0 && (
        <div style={{ padding: '6px 12px', marginBottom: '0.6rem', background: 'rgba(239,68,68,0.12)', border: '1px solid rgba(239,68,68,0.4)', borderRadius: 5, color: '#f87171', fontSize: '0.82rem' }}>
          ⚠ {tP('workOrders.collapsedAlertBanner', { n: flaggedCount })}
        </div>
      )}
      {visibleGroups.length === 0 ? (
        <div style={{ padding: '1rem', color: '#a1a1aa', fontSize: '0.875rem' }}>{tP('workOrders.collapsedEmpty')}</div>
      ) : pivot === 'none' ? (
        renderTable(visibleGroups)
      ) : pivot === 'nested' ? (
        buildSections(visibleGroups, 'prod_area').map((outer) => (
          <details key={outer.key} open style={{ border: '1px solid #3f3f46', borderRadius: 6, padding: '0.4rem 0.6rem', marginBottom: '0.5rem' }}>
            <summary style={{ cursor: 'pointer', padding: '2px 0' }}>{renderSectionSummary(outer.key, outer.groups.length, outer.total)}</summary>
            <div style={{ marginTop: '0.4rem', paddingLeft: '0.75rem' }}>
              {buildSections(outer.groups, 'location').map((inner) => (
                <details key={inner.key} open style={{ border: '1px solid #27272a', borderRadius: 6, padding: '0.35rem 0.55rem', marginBottom: '0.4rem' }}>
                  <summary style={{ cursor: 'pointer', padding: '2px 0' }}>{renderSectionSummary(inner.key, inner.groups.length, inner.total)}</summary>
                  <div style={{ marginTop: '0.35rem' }}>{renderTable(inner.groups)}</div>
                </details>
              ))}
            </div>
          </details>
        ))
      ) : (
        buildSections(visibleGroups, pivot).map((section) => (
          <details key={section.key} open style={{ border: '1px solid #3f3f46', borderRadius: 6, padding: '0.4rem 0.6rem', marginBottom: '0.5rem' }}>
            <summary style={{ cursor: 'pointer', padding: '2px 0' }}>{renderSectionSummary(section.key, section.groups.length, section.total)}</summary>
            <div style={{ marginTop: '0.4rem' }}>{renderTable(section.groups)}</div>
          </details>
        ))
      )}
    </div>
  );
}
