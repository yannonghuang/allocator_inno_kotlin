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
 *  in — the caller maps it to 'none' before reaching this component.
 *
 *  'customer' groups by a GroupRow's own customer-SET (see GroupRow.customer_ids's own doc) —
 *  unlike prod_area/location, a group's customer set isn't guaranteed to be a single value, so a
 *  section here can represent several customers at once (e.g. "CustomerA, CustomerB") rather than
 *  splitting into one section per customer — see buildSections' own doc for why. */
export type WoPivotMode = 'none' | 'customer' | 'prod_area' | 'location' | 'nested';

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
const COL_CUSTOMER_W = 160;

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

// window_key is the contributor's OWN backend consolidation batch bucket — calendarBucket(over
// wo_window_start, that method's batch scale), reproduced here via bucketKeyFor (which mirrors
// calendarBucket bit-for-bit). This is NOT the same as the cell's display bucket (over end_time):
// end_time can be shifted later by resource-contention scheduling, which runs AFTER consolidation
// decisions are already made — two independently-and-correctly-consolidated WOs can coincidentally
// land on the same displayed end_time despite having been assigned to different windows. Only a
// shared window_key means consolidation's own key genuinely collided and should have merged them.
type Contributor = { end_time: string; quantity: number; window_key: string };
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
   *  per-cell breakdown — see `customerKeyFor` and `WoPivotMode`'s own doc. */
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

function buildSections(groups: GroupRow[], by: 'prod_area' | 'location' | 'customer'): Section[] {
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
 * Pivot/cross-tab built on top of the Consolidated Work Orders view: one row per
 * (product, location, method) group, time running horizontally as none(day)/weekly/biweekly/
 * monthly/all buckets — deliberately the SAME scale vocabulary and bucket boundaries WO
 * consolidation itself uses (see `bucketKeyFor`'s own doc), not a generic calendar grid.
 *
 * A cell backed by more than one original consolidated row is only a real anomaly if those rows
 * share the same `window_key` — consolidation's OWN batch key (calendarBucket over
 * `wo_window_start`, that method's batch scale), reproduced via `bucketKeyFor`/`cellHasWindowCollision`.
 * `end_time` alone is NOT a reliable "should these have merged" signal: a separate
 * resource-contention scheduling pass runs AFTER consolidation and can push a WO's `end_time`
 * later, so two independently-and-correctly-consolidated WOs can coincidentally land in the same
 * displayed cell despite having been assigned to different consolidation windows. Only a shared
 * `window_key` means consolidation's own key genuinely collided and it still didn't merge them —
 * flagged visibly; everything else is summed silently, no flag.
 *
 * KNOWN REMAINING FALSE-POSITIVE CLASS (investigated on case 173, 2026-07-30; not fixed —
 * revisit later): a WO that was a *singleton* at every consolidation stage never gets
 * `wo_window_start` populated at all (it's only written on a merge's output — PlanningEngine.kt
 * has exactly 3 call sites, all merge-branch-only), so this component falls back to `end_time` for
 * it, which reintroduces the exact false-positive risk above. Confirmed root cause (debug-log
 * instrumented rerun of case 173): two independently-and-correctly-consolidated singleton WOs
 * (different weeks at consolidation time) can each be pushed later by resource-contention
 * scheduling by a *different* number of days and coincidentally land on the same final day —
 * observed e.g. product 500-6267 (pushed 8d and 3d respectively, converging on the same date) and
 * 500-4212 (pushed 20d and 24d). This is NOT a consolidation bug — R5_predecessor_sequencing
 * (SoundnessChecker.kt) is actively kept sound across pushes via `resequenceFromPegging`'s
 * `pushUp` DAG cascade (PlanningEngine.kt ~7606), so ordering is fine; it's a display-only
 * coincidence from consolidation and resource-contention scheduling running as two one-way,
 * non-communicating phases. Two fixes were considered and rejected:
 *   1. Re-run consolidation after arbitration: a merge can increase a batch's `lot_count`/duration
 *      beyond what `ResourceScheduler.arbitrate` already reserved on that shared resource for that
 *      day — risks a genuine R12 resource-overload violation, and re-arbitrating to fix that isn't
 *      guaranteed to converge (the existing arbitrate→cascade loop is deliberately capped at two
 *      passes, not run to a fixed point).
 *   2. Arbitrate before consolidating: PlanningEngine.kt ~6434 documents that arbitration
 *      deliberately runs over CONSOLIDATED lots "so capacity is checked against the real
 *      production-lot count instead of an inflated per-demand count" — arbitrating first would
 *      systematically overstate resource contention (many small native WOs instead of few batched
 *      ones) and degrade fill-rate/delivery-performance on every run, not just this edge case.
 * No frontend or backend fix applied for this class; the banner will still occasionally
 * over-count on cases with heavy resource contention. See project memory for the full writeup.
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
  // Two pivot row levels, each collapsed by default (aggregate-row-only), mirroring
  // AllocationMatrixView's expandedSupplyGroups: sectionExpanded reveals the sub-level (the other
  // dimension, or Location under nested's PROD_AREA); leafExpanded (keyed by the composite
  // `${topKey}|${subKey}`) reveals that sub-section's actual leaf rows.
  const [sectionExpanded, setSectionExpanded] = useState<Set<string>>(new Set());
  const [leafExpanded, setLeafExpanded] = useState<Set<string>>(new Set());
  const toggleBucket = (key: string) =>
    setExpandedBuckets((prev) => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  const toggleSection = (key: string) =>
    setSectionExpanded((prev) => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  const toggleLeaf = (key: string) =>
    setLeafExpanded((prev) => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });

  const scaleForMethod = (method: string): WoBatchScale => {
    if (method === 'make') return makeBatchScale;
    if (method === 'move') return moveBatchScale;
    if (method === 'purchase' || method === 'buy') return purchaseBatchScale;
    return 'weekly';
  };

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
      // Consolidation's own grouping key (PlanningEngine.kt ~4540) for MOVE work orders is
      // ("__move__", location_source, location_id, prod_area, bucket) — WITHOUT product_id — so
      // several already-correctly-consolidated mixed-shipment moves sharing one destination (this
      // row's location_id) but a different source OR prod_area would otherwise collapse into a
      // single group here and look like an unmerged collision. Both fields are effectively no-ops
      // for make/buy: location_source is always null, and a given (product_id, location_id) pair
      // already implies one prod_area.
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
      const windowIso = ((r.wo_window_start ?? r.end_time) as string).slice(0, 10);
      const windowKey = bucketKeyFor(windowIso, scaleForMethod(r.method));
      cell.contributors.push({ end_time: r.end_time as string, quantity: qty, window_key: windowKey });
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
  }, [rows, granularity, locale, makeBatchScale, moveBatchScale, purchaseBatchScale]);

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
  // A cell is a genuine consolidation miss only if 2+ of its contributors share the same
  // window_key — i.e. consolidation's OWN batch key collided and it still didn't merge them.
  // Contributors that merely display in the same cell (same end_time bucket) but came from
  // different consolidation windows are NOT a collision — see Contributor's own doc.
  const cellHasWindowCollision = (cell: Cell): boolean => {
    const seen = new Set<string>();
    for (const c of cell.contributors) {
      if (seen.has(c.window_key)) return true;
      seen.add(c.window_key);
    }
    return false;
  };

  // Recomputed from the currently-visible columns (not the raw grouping pass) so drilling into a
  // flagged bucket can legitimately clear the flag (the collision only existed at the coarser
  // view) or keep it (a real consolidation problem) — both are informative.
  const visibleFlaggedCount = useMemo(() => {
    let count = 0;
    for (const grp of groups) {
      for (const spec of colSpecs) {
        const cell = getCellForSpec(grp, spec);
        if (cell && cellHasWindowCollision(cell)) count++;
      }
    }
    return count;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groups, colSpecs, drillPlan, granularity]);

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
        <td style={{ position: 'sticky', left: 0, zIndex: 1, width: COL_GROUP_W, minWidth: COL_GROUP_W, background: '#18181b', borderBottom: '1px solid #27272a', borderRight: '1px solid #27272a' }} />
        <td style={{ position: 'sticky', left: COL_GROUP_W, zIndex: 1, width: COL_PRODUCT_W, minWidth: COL_PRODUCT_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', whiteSpace: 'nowrap' }}>
          {grp.product_id}
        </td>
        <td
          title={grp.location_source ? `${grp.location_source} → ${grp.location_id}` : undefined}
          style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W, zIndex: 1, width: COL_LOCATION_W, minWidth: COL_LOCATION_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
        >
          {grp.location_source ? `${grp.location_source} → ${grp.location_id}` : grp.location_id}
        </td>
        <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W, zIndex: 1, width: COL_METHOD_W, minWidth: COL_METHOD_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', whiteSpace: 'nowrap' }}>
          <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: methodColor(grp.method), marginRight: 6 }} />
          {grp.method}
        </td>
        <td
          title={grp.customer_ids.length > 0 ? grp.customer_ids.join(', ') : undefined}
          style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W + COL_METHOD_W, zIndex: 1, width: COL_CUSTOMER_W, minWidth: COL_CUSTOMER_W, background: '#18181b', padding: '6px 10px', borderBottom: '1px solid #27272a', borderRight: '1px solid #3f3f46', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', color: grp.customer_ids.length > 0 ? '#e4e4e7' : '#3f3f46' }}
        >
          {grp.customer_ids.length > 0 ? grp.customer_ids.join(', ') : '–'}
        </td>
        {colSpecs.map((spec) => {
          const cell = getCellForSpec(grp, spec);
          const flagged = !!cell && cellHasWindowCollision(cell);
          return (
            <td
              key={spec.type === 'top' ? spec.bucket.key : `${spec.topKey}|${spec.bucket.key}`}
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
  // The pivot path lives ONLY in the dedicated Group column (col 1) — Product/Location/Method/
  // Customer (cols 2-5) stay blank here, exactly mirroring where they'd be blank/populated on leaf
  // rows, so a given column always means the same thing regardless of row type. Collapsed by
  // default (only this row renders); expanding reveals the next level (sub-sections or leaf rows).
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
            borderBottom: '1px solid #3f3f46', borderRight: '1px solid #27272a', borderTop,
            cursor: 'pointer', whiteSpace: 'nowrap',
          }}
        >
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <span style={{ color: '#71717a', fontSize: '0.75rem' }}>{expanded ? '▾' : '▸'}</span>
            {renderSectionSummary(label, groups.length, total)}
          </span>
        </td>
        <td style={{ position: 'sticky', left: COL_GROUP_W, zIndex: 1, width: COL_PRODUCT_W, minWidth: COL_PRODUCT_W, background: bg, borderBottom: '1px solid #3f3f46', borderTop }} />
        <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W, zIndex: 1, width: COL_LOCATION_W, minWidth: COL_LOCATION_W, background: bg, borderBottom: '1px solid #3f3f46', borderTop }} />
        <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W, zIndex: 1, width: COL_METHOD_W, minWidth: COL_METHOD_W, background: bg, borderBottom: '1px solid #3f3f46', borderTop }} />
        <td style={{ position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W + COL_METHOD_W, zIndex: 1, width: COL_CUSTOMER_W, minWidth: COL_CUSTOMER_W, background: bg, borderBottom: '1px solid #3f3f46', borderRight: '1px solid #3f3f46', borderTop }} />
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

  // Both single-dimension pivots (drill reveals the OTHER dimension) and 'nested' (fixed
  // PROD_AREA -> Location) share the same two-level shape: top section (sectionExpanded) ->
  // sub-section (leafExpanded, composite-keyed) -> leaf rows. Each level starts collapsed.
  const bodyRows: React.ReactNode[] = [];
  if (pivot === 'none') {
    for (const grp of visibleGroups) bodyRows.push(renderRow(grp));
  } else {
    const topDim: 'prod_area' | 'location' | 'customer' = pivot === 'nested' ? 'prod_area' : pivot;
    const subDim: 'prod_area' | 'location' = pivot === 'location' ? 'prod_area' : 'location';
    for (const top of buildSections(visibleGroups, topDim)) {
      const topExpanded = sectionExpanded.has(top.key);
      bodyRows.push(renderAggregateRow(top.key, top.key, top.groups, 0, topExpanded, () => toggleSection(top.key)));
      if (!topExpanded) continue;
      for (const sub of buildSections(top.groups, subDim)) {
        const subKey = `${top.key}|${sub.key}`;
        const subExpanded = leafExpanded.has(subKey);
        bodyRows.push(renderAggregateRow(subKey, sub.key, sub.groups, 1, subExpanded, () => toggleLeaf(subKey)));
        if (subExpanded) {
          for (const grp of sub.groups) bodyRows.push(renderRow(grp));
        }
      }
    }
  }

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
      {visibleFlaggedCount > 0 && (
        <div style={{ padding: '6px 12px', marginBottom: '0.6rem', background: 'rgba(239,68,68,0.12)', border: '1px solid rgba(239,68,68,0.4)', borderRadius: 5, color: '#f87171', fontSize: '0.82rem' }}>
          ⚠ {tP('workOrders.collapsedAlertBanner', { n: visibleFlaggedCount })}
        </div>
      )}
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
                    padding: '6px 10px', borderBottom: '1px solid #3f3f46', borderRight: '1px solid #27272a',
                    color: '#a1a1aa', whiteSpace: 'nowrap',
                  }}
                >
                  {tP('workOrders.collapsedColGroup')}
                </th>
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
                    padding: '6px 10px', borderBottom: '1px solid #3f3f46',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <button type="button" onClick={() => toggleSort('method')} style={sortThBtnStyle(sort?.key === 'method')}>
                    {tP('workOrders.collapsedColMethod')}{sortIndicator('method')}
                  </button>
                </th>
                <th
                  rowSpan={anyBucketExpanded ? 2 : 1}
                  style={{
                    position: 'sticky', left: COL_GROUP_W + COL_PRODUCT_W + COL_LOCATION_W + COL_METHOD_W, top: 0, zIndex: 3,
                    width: COL_CUSTOMER_W, minWidth: COL_CUSTOMER_W, background: '#18181b', textAlign: 'left',
                    padding: '6px 10px', borderBottom: '1px solid #3f3f46', borderRight: '1px solid #3f3f46',
                    whiteSpace: 'nowrap', color: '#a1a1aa',
                  }}
                >
                  {tP('workOrders.collapsedColCustomer')}
                </th>
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
