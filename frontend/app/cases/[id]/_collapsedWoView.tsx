'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import type { WorkOrder } from '@/lib/api';
import { qtyFmt } from '@/app/lib/format';
import { methodColor } from './_workOrderSchedule';

/** Same vocabulary as PlanningConfig['consolidation'].*_batch_scale (lib/api.ts) and the
 *  backend's WoBatchConfig/calendarBucket (PlanningEngine.kt) — see this file's own module doc. */
export type WoBatchScale = 'none' | 'weekly' | 'biweekly' | 'monthly' | 'all';

/** One pivotable dimension for this view's own Pivot control — independent of the flat table's
 *  `planWoPivot` (_CaseSectionPage.tsx), which stays a fixed-preset single-select including modes
 *  ('nested', 'demand') that have no equivalent here. This view instead takes an ORDERED array of
 *  these fields (`pivotFields` below): the caller picks which dimensions to pivot by and in what
 *  order, and `buildSections` groups recursively, one level per array entry.
 *
 *  'customer' groups by a GroupRow's own customer-SET (see GroupRow.customer_ids's own doc) —
 *  unlike prod_area/location, a group's customer set isn't guaranteed to be a single value, so a
 *  section here can represent several customers at once (e.g. "CustomerA, CustomerB") rather than
 *  splitting into one section per customer — see buildSections' own doc for why. */
export type WoPivotField = 'customer' | 'prod_area' | 'location';

const GRANULARITIES: WoBatchScale[] = ['none', 'weekly', 'biweekly', 'monthly', 'all'];
const DAY_MS = 86_400_000;
const HEADER_ROW_H = 28;

/** One step finer than `scale` in GRANULARITIES ('all'→'monthly'→'biweekly'→'weekly'→'none'),
 *  or null once already at 'none' (nothing finer to drill into). */
function nextFinerScale(scale: WoBatchScale): WoBatchScale | null {
  const idx = GRANULARITIES.indexOf(scale);
  return idx > 0 ? GRANULARITIES[idx - 1] : null;
}
// Sticky identity columns. COL_GROUP_W is a column dedicated ONLY to the pivot path (chevron +
// section label + count/total on aggregate rows, blank on leaf rows) — kept separate from
// Product/Location/Method so a given column position always means the same thing regardless of
// row type, instead of Product's column doubling as a colSpan'd pivot label on aggregate rows.
// Product/Location/Method are split so each is independently sortable via the Sort-by control.
// Fixed pixel widths so each column's `left` offset (for CSS sticky stacking) can be computed
// instead of measured.
const COL_GROUP_W = 200;
const COL_PRODUCT_W = 140;
const COL_LOCATION_W = 90;
const COL_METHOD_W = 110;

/** Sortable-column-header button style — same look as the app's other click-to-sort headers
 *  (e.g. the KB run inspector): plain/muted when inactive, bold/bright when this column is the
 *  active sort key. */
function sortThBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    padding: 0,
    font: 'inherit',
    color: active ? '#e4e4e7' : '#a1a1aa',
    fontWeight: active ? 600 : 400,
  };
}

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
 *  scheme as [bucketKeyFor], so a WO's bucketKeyFor(...) always matches one of these entries.
 *  Each entry also carries its own [startIso, endIso] range so a bucket can later be re-queried
 *  as the [minIso, maxIso] of a finer `buildBuckets` call (local column drill-down). */
function buildBuckets(minIso: string, maxIso: string, scale: WoBatchScale, locale: string, allLabel: string): Bucket[] {
  const buckets: Bucket[] = [];
  if (scale === 'all') return [{ key: 'all', label: allLabel, startIso: minIso, endIso: maxIso }];
  if (scale === 'monthly') {
    let y = +minIso.slice(0, 4);
    let m = +minIso.slice(5, 7);
    const maxY = +maxIso.slice(0, 4);
    const maxM = +maxIso.slice(5, 7);
    let guard = 0;
    while ((y < maxY || (y === maxY && m <= maxM)) && guard++ < 600) {
      const startIso = `${y}-${pad2(m)}-01`;
      const endIso = new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10); // last day of month m (1-indexed)
      buckets.push({ key: `${y}-${pad2(m)}`, label: `${y}-${pad2(m)}`, startIso, endIso });
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
    const startIso = dateFromEpochDay(startDay).toISOString().slice(0, 10);
    const endIso = dateFromEpochDay(startDay + step - 1).toISOString().slice(0, 10);
    if (scale === 'none') {
      buckets.push({ key: startIso, label: formatDate(dateFromEpochDay(startDay), locale), startIso, endIso: startIso });
    } else {
      const rangeLabel = formatDateRange(dateFromEpochDay(startDay), dateFromEpochDay(startDay + step - 1), locale);
      buckets.push({ key: `${scale === 'weekly' ? 'w' : 'b'}${b}`, label: rangeLabel, startIso, endIso });
    }
  }
  return buckets;
}

// One raw (native, per-demand) work order rolled into a cell — kept individually (not just
// summed) so a cell backed by several native WOs can still list them out on hover, even though
// the cell itself only ever shows their combined qty.
type Contributor = { end_time: string; quantity: number };
type Cell = { qty: number; contributors: Contributor[] };
type GroupRow = {
  key: string;
  product_id: string;
  location_id: string;
  location_source: string;
  method: string;
  prod_area: string;
  /** Union of every constituent WO's own `customer_ids` rolled into this (product, location,
   *  method) group — sorted, deduplicated. NOT guaranteed to be a single value: unlike prod_area
   *  (part of the grouping key, so always uniform within a group) or location, a group can span
   *  time buckets/contributors from different original consolidated WOs whose own customer sets
   *  differ (e.g. one week's batch served CustomerA, the next week's served CustomerA+CustomerB).
   *  Customer pivoting/display therefore operates on the group's own aggregate set, not a
   *  per-cell breakdown — see `customerKeyFor` and `WoPivotField`'s own doc. */
  customer_ids: string[];
  cells: Map<string, Cell>;
  total: number;
};
type Bucket = { key: string; label: string; startIso: string; endIso: string };
type Section = { key: string; groups: GroupRow[]; total: number };
/** Derived column list rendered by `renderTable`: a collapsed top bucket renders as itself; an
 *  expanded one is replaced by its finer sub-buckets (mirrors AllocationMatrixView's `ColSpec`). */
type ColSpec = { type: 'top'; bucket: Bucket } | { type: 'sub'; topKey: string; bucket: Bucket };

/** Display/grouping key for a group's customer SET (see GroupRow.customer_ids's own doc) — a
 *  comma-joined label rather than one-section-per-customer, so a group touching several
 *  customers gets its own distinct section instead of being duplicated under each customer (which
 *  would double-count its quantity in every section's total). */
function customerKeyFor(ids: string[]): string {
  return ids.length > 0 ? ids.join(', ') : '(none)';
}

function buildSections(groups: GroupRow[], by: WoPivotField): Section[] {
  const map = new Map<string, GroupRow[]>();
  for (const g of groups) {
    const k = by === 'customer' ? customerKeyFor(g.customer_ids) : (by === 'prod_area' ? g.prod_area : g.location_id) || '(none)';
    if (!map.has(k)) map.set(k, []);
    map.get(k)!.push(g);
  }
  return Array.from(map.entries())
    .map(([key, gs]) => ({ key, groups: gs, total: gs.reduce((s, g) => s + g.total, 0) }))
    .sort((a, b) => b.total - a.total);
}

/**
 * Pivot/cross-tab built on top of the NATIVE (per-demand) Work Orders — the same source as the
 * flat table's Native tab, NOT the Consolidated view — rolled up into one row per (product,
 * location, method) group, time running horizontally as none(day)/weekly/biweekly/monthly/all
 * buckets (see `bucketKeyFor`'s own doc), not a generic calendar grid.
 *
 * Because the source is native rather than consolidated, a cell backed by several rows is the
 * NORMAL case (a group's own time bucket naturally collects one entry per native WO — e.g. one
 * per demand — that landed in it), not an anomaly to flag. Each cell's `contributors` list is
 * kept only so a multi-entry cell can be broken down on hover (see `Contributor`'s own doc);
 * there is no highlighting or alerting on cell composition.
 */
export function CollapsedWoView({
  rows,
  pivotFields = [],
}: {
  rows: WorkOrder[];
  /** Ordered pivot dimensions — [] renders the flat (ungrouped) table; each entry adds one more
   *  nested section level, in the order given (see WoPivotField's own doc). */
  pivotFields?: WoPivotField[];
}) {
  const tP = useTranslations('planning');
  const locale = useLocale();
  const [granularity, setGranularity] = useState<WoBatchScale>('weekly');
  const [filterText, setFilterText] = useState('');
  // null = default sort (total qty, descending). Set by clicking a Product/Location/Method
  // column header — same {key, dir} toggle pattern used elsewhere in this app (e.g. the KB run
  // inspector's sortable columns): first click = ascending, second click on the same column =
  // descending, third click clears back to the default.
  const [sort, setSort] = useState<{ key: 'product' | 'location' | 'method'; dir: 'asc' | 'desc' } | null>(null);
  const toggleSort = (key: 'product' | 'location' | 'method') => {
    setSort((prev) => {
      if (!prev || prev.key !== key) return { key, dir: 'asc' };
      if (prev.dir === 'asc') return { key, dir: 'desc' };
      return null;
    });
  };
  const sortIndicator = (key: 'product' | 'location' | 'method') =>
    sort?.key === key ? (sort.dir === 'asc' ? ' ↑' : ' ↓') : '';

  // Local drill-down state — kept in this component (not lifted to the caller), mirroring
  // AllocationMatrixView's expandedCustomers/expandedSupplyGroups: this view owns its own
  // view-interaction state, the caller only owns the row data.
  const [expandedBuckets, setExpandedBuckets] = useState<Set<string>>(new Set());
  // One expansion flag per pivot section, at any depth — keyed by the full path of section keys
  // joined with '|' (e.g. "CustomerA|East"), so an arbitrary number of nested pivot levels (driven
  // by `pivotFields`' length) share a single collapsed-by-default toggle set, rather than one
  // fixed state variable per level as the old two-level (section/leaf) version needed.
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(new Set());
  const toggleBucket = (key: string) =>
    setExpandedBuckets((prev) => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  const togglePath = (path: string) =>
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      next.has(path) ? next.delete(path) : next.add(path);
      return next;
    });
  // A path from a since-removed pivot level (e.g. the user dropped a field or reordered) would
  // otherwise linger in the set with no section left to reveal it — reset whenever the field
  // selection itself changes so expansion state never outlives the levels it was keyed to.
  useEffect(() => {
    setExpandedPaths(new Set());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pivotFields.join('|')]);

  const { groups, buckets, excludedCount } = useMemo(() => {
    const candidateRows = rows.filter((r) => r.method !== 'inventory');
    const validRows = candidateRows.filter((r) => !!r.end_time);
    const excludedCount = candidateRows.length - validRows.length;
    if (validRows.length === 0) {
      return { groups: [] as GroupRow[], buckets: [] as Bucket[], excludedCount };
    }
    const ends = validRows.map((r) => (r.end_time as string).slice(0, 10));
    const minIso = ends.reduce((a, b) => (a < b ? a : b));
    const maxIso = ends.reduce((a, b) => (a > b ? a : b));
    const buckets = buildBuckets(minIso, maxIso, granularity, locale, tP('config.woBatchAll'));

    const groupMap = new Map<string, GroupRow>();
    // Accumulated separately from GroupRow itself (a plain Set is cheaper to mutate per-row than
    // repeatedly rebuilding a sorted array) and finalized into `customer_ids` once, below.
    const customerIdSets = new Map<string, Set<string>>();
    for (const r of validRows) {
      // Mirrors consolidation's own grouping key (PlanningEngine.kt ~4540) for MOVE work orders —
      // ("__move__", location_source, location_id, prod_area, bucket), WITHOUT product_id — so
      // mixed-shipment moves sharing one destination (this row's location_id) but a different
      // source OR prod_area still land in distinct display groups here, matching how consolidation
      // itself would treat them. Both fields are effectively no-ops for make/buy: location_source
      // is always null, and a given (product_id, location_id) pair already implies one prod_area.
      const locationSource = r.location_source ?? '';
      const prodArea = r.prod_area ?? '';
      const key = `${r.product_id}|${r.location_id}|${locationSource}|${prodArea}|${r.method}`;
      let grp = groupMap.get(key);
      if (!grp) {
        // Mixed-shipment MOVE work orders have product_id === null at the API level (not ''), so
        // it must be coalesced here — every downstream use (filter's .toLowerCase(), sort's
        // .localeCompare()) assumes a string and would throw on a raw null.
        grp = { key, product_id: r.product_id ?? '', location_id: r.location_id, location_source: locationSource, method: r.method, prod_area: prodArea, customer_ids: [], cells: new Map(), total: 0 };
        groupMap.set(key, grp);
      }
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
      if (r.customer_ids && r.customer_ids.length > 0) {
        let set = customerIdSets.get(key);
        if (!set) {
          set = new Set();
          customerIdSets.set(key, set);
        }
        for (const cid of r.customer_ids) set.add(cid);
      }
    }
    for (const grp of Array.from(groupMap.values())) {
      grp.customer_ids = Array.from(customerIdSets.get(grp.key) ?? []).sort();
    }
    const groups = Array.from(groupMap.values());
    return { groups, buckets, excludedCount };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, granularity, locale]);

  // Column plan: each top-level bucket renders as itself, unless locally expanded, in which case
  // it's replaced by finer sub-buckets computed from that bucket's OWN [startIso, endIso] range —
  // buildBuckets is reused as-is, just called again with a narrower range and one scale finer.
  const drillPlan = useMemo(() => {
    const subBucketsByTop = new Map<string, Bucket[]>();
    const finerByTop = new Map<string, WoBatchScale>();
    const finer = nextFinerScale(granularity);
    if (finer) {
      for (const b of buckets) {
        if (!expandedBuckets.has(b.key)) continue;
        subBucketsByTop.set(b.key, buildBuckets(b.startIso, b.endIso, finer, locale, tP('config.woBatchAll')));
        finerByTop.set(b.key, finer);
      }
    }
    return { subBucketsByTop, finerByTop, canDrill: !!finer };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [buckets, expandedBuckets, granularity, locale]);

  const colSpecs = useMemo<ColSpec[]>(() => {
    const specs: ColSpec[] = [];
    for (const b of buckets) {
      const subs = drillPlan.subBucketsByTop.get(b.key);
      if (subs && subs.length > 0) {
        for (const sb of subs) specs.push({ type: 'sub', topKey: b.key, bucket: sb });
      } else {
        specs.push({ type: 'top', bucket: b });
      }
    }
    return specs;
  }, [buckets, drillPlan]);
  const anyBucketExpanded = drillPlan.subBucketsByTop.size > 0;

  /** A sub-bucket has no cell of its own — it's derived on demand by re-bucketing its parent top
   *  cell's raw `contributors` (end_time+quantity) at the finer scale. This is why `Cell` tracks
   *  contributors in the first place: it's exactly the data a local drill needs, with no need to
   *  re-touch the original `rows` or re-run the heavy grouping memo. */
  const getCellForSpec = (grp: GroupRow, spec: ColSpec): Cell | undefined => {
    if (spec.type === 'top') return grp.cells.get(spec.bucket.key);
    const parentCell = grp.cells.get(spec.topKey);
    if (!parentCell) return undefined;
    const finer = drillPlan.finerByTop.get(spec.topKey)!;
    let cell: Cell | undefined;
    for (const c of parentCell.contributors) {
      if (bucketKeyFor(c.end_time.slice(0, 10), finer) !== spec.bucket.key) continue;
      if (!cell) cell = { qty: 0, contributors: [] };
      cell.qty += c.quantity;
      cell.contributors.push(c);
    }
    return cell;
  };

  // Filter + sort are cheap over the already-grouped rows, so kept out of the heavy
  // grouping/bucketing memo above — typing in the filter box never re-buckets.
  const visibleGroups = useMemo(() => {
    const f = filterText.trim().toLowerCase();
    let out = f
      ? groups.filter((g) => g.product_id.toLowerCase().includes(f) || g.location_id.toLowerCase().includes(f))
      : groups;
    out = [...out].sort((a, b) => {
      if (!sort) return b.total - a.total;
      const flip = sort.dir === 'asc' ? 1 : -1;
      if (sort.key === 'product') return flip * (a.product_id.localeCompare(b.product_id) || a.location_id.localeCompare(b.location_id));
      if (sort.key === 'location') return flip * (a.location_id.localeCompare(b.location_id) || a.product_id.localeCompare(b.product_id));
      return flip * (a.method.localeCompare(b.method) || a.product_id.localeCompare(b.product_id));
    });
    return out;
  }, [groups, filterText, sort]);

  // Product/Location/Method only mean something once an actual (product, location, method) leaf
  // row is on screen — while every visible row is still an aggregate section header, those
  // columns would just be blank width. Always shown in flat (pivotFields.length === 0) mode,
  // since every row there already IS a leaf; in pivot mode, revealed once expansion reaches the
  // leaf level anywhere in the tree (same expandedPaths-driven traversal as renderPivotLevel).
  // Computed before the early empty-buckets return below so hook call order stays stable.
  const leafRowsVisible = useMemo(() => {
    if (pivotFields.length === 0) return true;
    const anyLeafAt = (levelGroups: GroupRow[], depth: number, pathPrefix: string): boolean => {
      const isLastLevel = depth === pivotFields.length - 1;
      for (const section of buildSections(levelGroups, pivotFields[depth])) {
        const path = pathPrefix ? `${pathPrefix}|${section.key}` : section.key;
        if (!expandedPaths.has(path)) continue;
        if (isLastLevel) return true;
        if (anyLeafAt(section.groups, depth + 1, path)) return true;
      }
      return false;
    };
    return anyLeafAt(visibleGroups, 0, '');
  }, [pivotFields, visibleGroups, expandedPaths]);

  if (buckets.length === 0) {
    return <div style={{ padding: '1rem', color: '#a1a1aa', fontSize: '0.875rem' }}>{tP('workOrders.collapsedEmpty')}</div>;
  }

  // One row per (product, location, method) group — reused by every pivot mode below, always
  // under the SAME shared <thead> (see the single <table> in the return below). The temporal
  // axis lives once, at the outermost level of the whole view; pivot sections are just full-width
  // divider rows in the same tbody, not separate nested tables each with their own header.
  const renderRow = (grp: GroupRow) => {
    return (
      <tr key={grp.key}>
        <td style={{ position: 'sticky', left: 0, zIndex: 1, width: COL_GROUP_W, minWidth: COL_GROUP_W, background: '#18181b', borderBottom: '1px solid #27272a', borderRight: leafRowsVisible ? '1px solid #27272a' : '1px solid #3f3f46' }} />
        {leafRowsVisible && (
          <>
            <td style={{ position: 'sticky', left: COL_GROUP_W, zIndex: 1, width: COL_PRODUCT_W, minWidth: COL_PRODUCT_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', whiteSpace: 'nowrap' }}>
              {grp.product_id}
            </td>
            <td
              title={grp.location_source ? `${grp.location_source} → ${grp.location_id}` : undefined}
              style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W, zIndex: 1, width: COL_LOCATION_W, minWidth: COL_LOCATION_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
            >
              {grp.location_source ? `${grp.location_source} → ${grp.location_id}` : grp.location_id}
            </td>
            <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W, zIndex: 1, width: COL_METHOD_W, minWidth: COL_METHOD_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', borderRight: '1px solid #3f3f46', whiteSpace: 'nowrap' }}>
              <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: methodColor(grp.method), marginRight: 6 }} />
              {grp.method}
            </td>
          </>
        )}
        {colSpecs.map((spec) => {
          const cell = getCellForSpec(grp, spec);
          const multiEntry = !!cell && cell.contributors.length > 1;
          return (
            <td
              key={spec.type === 'top' ? spec.bucket.key : `${spec.topKey}|${spec.bucket.key}`}
              title={multiEntry ? cell!.contributors.map((c) => `${c.end_time}: ${qtyFmt(c.quantity)}`).join('\n') : undefined}
              style={{
                padding: '6px 10px',
                borderBottom: '1px solid #27272a',
                textAlign: 'right',
                color: cell ? '#e4e4e7' : '#3f3f46',
              }}
            >
              {cell ? qtyFmt(cell.qty) : '–'}
            </td>
          );
        })}
      </tr>
    );
  };

  const renderSectionSummary = (label: string, groupCount: number, total: number) => (
    <span>
      <strong style={{ color: '#f4f4f5' }}>{label}</strong>
      <span style={{ color: '#71717a', marginLeft: 8, fontSize: '0.78rem' }}>
        {groupCount} · {qtyFmt(total)}
      </span>
    </span>
  );

  // Pivot aggregate row — mirrors AllocationMatrixView's collapsed supply-group row: every time
  // column gets a REAL sum across the section's member groups (not one grand total crammed into a
  // single cell), so the aggregate is legible against the same temporal axis the leaf rows use.
  // The pivot path lives ONLY in the dedicated Group column (col 1) — Product/Location/Method
  // (cols 2-4, hidden entirely until a leaf row is visible — see leafRowsVisible) stay blank here,
  // exactly mirroring where they'd be blank/populated on leaf rows, so a given column always means
  // the same thing regardless of row type. Collapsed by default (only this row renders); expanding
  // reveals the next level (sub-sections or leaf rows).
  const renderAggregateRow = (key: string, label: string, groups: GroupRow[], indent: number, expanded: boolean, onToggle: () => void) => {
    const total = groups.reduce((s, g) => s + g.total, 0);
    const bg = indent === 0 ? '#1f1f23' : '#19191c';
    const borderTop = indent === 0 ? '1px solid #3f3f46' : undefined;
    return (
      <tr key={`agg-${key}`}>
        <td
          onClick={onToggle}
          style={{
            position: 'sticky', left: 0, zIndex: 1, width: COL_GROUP_W, minWidth: COL_GROUP_W, background: bg,
            padding: `6px 10px 6px ${10 + indent * 20}px`,
            borderBottom: '1px solid #3f3f46', borderRight: leafRowsVisible ? '1px solid #27272a' : '1px solid #3f3f46', borderTop,
            cursor: 'pointer', whiteSpace: 'nowrap',
          }}
        >
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <span style={{ color: '#71717a', fontSize: '0.75rem' }}>{expanded ? '▾' : '▸'}</span>
            {renderSectionSummary(label, groups.length, total)}
          </span>
        </td>
        {leafRowsVisible && (
          <>
            <td style={{ position: 'sticky', left: COL_GROUP_W, zIndex: 1, width: COL_PRODUCT_W, minWidth: COL_PRODUCT_W, background: bg, borderBottom: '1px solid #3f3f46', borderTop }} />
            <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W, zIndex: 1, width: COL_LOCATION_W, minWidth: COL_LOCATION_W, background: bg, borderBottom: '1px solid #3f3f46', borderTop }} />
            <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W, zIndex: 1, width: COL_METHOD_W, minWidth: COL_METHOD_W, background: bg, borderBottom: '1px solid #3f3f46', borderRight: '1px solid #3f3f46', borderTop }} />
          </>
        )}
        {colSpecs.map((spec) => {
          const sum = groups.reduce((s, grp) => s + (getCellForSpec(grp, spec)?.qty ?? 0), 0);
          return (
            <td
              key={spec.type === 'top' ? spec.bucket.key : `${spec.topKey}|${spec.bucket.key}`}
              style={{
                padding: '6px 10px', borderBottom: '1px solid #3f3f46', textAlign: 'right',
                background: bg, color: sum ? '#e4e4e7' : '#3f3f46', fontWeight: 600,
              }}
            >
              {sum ? qtyFmt(sum) : '–'}
            </td>
          );
        })}
      </tr>
    );
  };

  // Recursive pivot: one nested section level per entry in `pivotFields`, in the order given.
  // Each level starts collapsed; expanding the LAST level reveals leaf rows, expanding any earlier
  // level recurses into the next field. Mirrors the old fixed two-level shape but generalizes to
  // any number of levels (including zero, i.e. the flat table).
  const renderPivotLevel = (groups: GroupRow[], depth: number, pathPrefix: string): React.ReactNode[] => {
    const field = pivotFields[depth];
    const isLastLevel = depth === pivotFields.length - 1;
    const nodes: React.ReactNode[] = [];
    for (const section of buildSections(groups, field)) {
      const path = pathPrefix ? `${pathPrefix}|${section.key}` : section.key;
      const expanded = expandedPaths.has(path);
      nodes.push(renderAggregateRow(path, section.key, section.groups, depth, expanded, () => togglePath(path)));
      if (!expanded) continue;
      if (isLastLevel) {
        for (const grp of section.groups) nodes.push(renderRow(grp));
      } else {
        nodes.push(...renderPivotLevel(section.groups, depth + 1, path));
      }
    }
    return nodes;
  };
  const bodyRows: React.ReactNode[] = pivotFields.length === 0
    ? visibleGroups.map((grp) => renderRow(grp))
    : renderPivotLevel(visibleGroups, 0, '');

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
      {visibleGroups.length === 0 ? (
        <div style={{ padding: '1rem', color: '#a1a1aa', fontSize: '0.875rem' }}>{tP('workOrders.collapsedEmpty')}</div>
      ) : (
        <div style={{ overflowX: 'auto', border: '1px solid #3f3f46', borderRadius: 6 }}>
          <table style={{ borderCollapse: 'collapse', fontSize: '0.8rem', minWidth: '100%' }}>
            <thead>
              <tr>
                <th
                  rowSpan={anyBucketExpanded ? 2 : 1}
                  style={{
                    position: 'sticky', left: 0, top: 0, zIndex: 3,
                    width: COL_GROUP_W, minWidth: COL_GROUP_W, background: '#18181b', textAlign: 'left',
                    padding: '6px 10px', borderBottom: '1px solid #3f3f46', borderRight: leafRowsVisible ? '1px solid #27272a' : '1px solid #3f3f46',
                    color: '#a1a1aa', whiteSpace: 'nowrap',
                  }}
                >
                  {tP('workOrders.collapsedColGroup')}
                </th>
                {leafRowsVisible && (
                  <>
                    <th rowSpan={anyBucketExpanded ? 2 : 1} style={{ position: 'sticky', left: COL_GROUP_W, top: 0, zIndex: 3, width: COL_PRODUCT_W, minWidth: COL_PRODUCT_W, background: '#18181b', textAlign: 'left', padding: '6px 10px', borderBottom: '1px solid #3f3f46', whiteSpace: 'nowrap' }}>
                      <button type="button" onClick={() => toggleSort('product')} style={sortThBtnStyle(sort?.key === 'product')}>
                        {tP('workOrders.collapsedColProduct')}{sortIndicator('product')}
                      </button>
                    </th>
                    <th rowSpan={anyBucketExpanded ? 2 : 1} style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W, top: 0, zIndex: 3, width: COL_LOCATION_W, minWidth: COL_LOCATION_W, background: '#18181b', textAlign: 'left', padding: '6px 10px', borderBottom: '1px solid #3f3f46', whiteSpace: 'nowrap' }}>
                      <button type="button" onClick={() => toggleSort('location')} style={sortThBtnStyle(sort?.key === 'location')}>
                        {tP('workOrders.collapsedColLocation')}{sortIndicator('location')}
                      </button>
                    </th>
                    <th
                      rowSpan={anyBucketExpanded ? 2 : 1}
                      style={{
                        position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W, top: 0, zIndex: 3,
                        width: COL_METHOD_W, minWidth: COL_METHOD_W, background: '#18181b', textAlign: 'left',
                        padding: '6px 10px', borderBottom: '1px solid #3f3f46', borderRight: '1px solid #3f3f46',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <button type="button" onClick={() => toggleSort('method')} style={sortThBtnStyle(sort?.key === 'method')}>
                        {tP('workOrders.collapsedColMethod')}{sortIndicator('method')}
                      </button>
                    </th>
                  </>
                )}
                {buckets.map((b) => {
                  const subs = drillPlan.subBucketsByTop.get(b.key);
                  const expanded = !!subs && subs.length > 0;
                  return (
                    <th
                      key={b.key}
                      colSpan={expanded ? subs!.length : 1}
                      rowSpan={!expanded && anyBucketExpanded ? 2 : 1}
                      onClick={drillPlan.canDrill ? () => toggleBucket(b.key) : undefined}
                      title={b.label}
                      style={{
                        position: 'sticky', top: 0, zIndex: 2, background: '#18181b',
                        padding: '6px 10px', borderBottom: '1px solid #3f3f46', color: '#a1a1aa',
                        whiteSpace: 'nowrap', textAlign: 'right', cursor: drillPlan.canDrill ? 'pointer' : 'default',
                      }}
                    >
                      {drillPlan.canDrill && <span style={{ marginRight: 4 }}>{expanded ? '▾' : '▸'}</span>}
                      {b.label}
                    </th>
                  );
                })}
              </tr>
              {anyBucketExpanded && (
                <tr>
                  {buckets.flatMap((b) => {
                    const subs = drillPlan.subBucketsByTop.get(b.key);
                    if (!subs || subs.length === 0) return [];
                    return subs.map((sb) => (
                      <th
                        key={`${b.key}|${sb.key}`}
                        title={sb.label}
                        style={{
                          position: 'sticky', top: HEADER_ROW_H, zIndex: 2, background: '#18181b',
                          padding: '4px 10px', borderBottom: '1px solid #3f3f46', borderTop: '1px solid #27272a',
                          color: '#71717a', fontSize: '0.72rem', fontWeight: 400, whiteSpace: 'nowrap', textAlign: 'right',
                        }}
                      >
                        {sb.label}
                      </th>
                    ));
                  })}
                </tr>
              )}
            </thead>
            <tbody>{bodyRows}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
