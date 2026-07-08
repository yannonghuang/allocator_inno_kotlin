'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocale, useTranslations } from 'next-intl';
import {
  getPlanRun,
  getResourceUtilization,
  getWorkOrderPegging,
  type PlanningPeggingEntry,
  type PlanningPeggingNode,
  type ResourceUtilization,
  type ResourceUtilizationRow,
  type WorkOrder,
} from '@/lib/api';
import { ScheduleHorizonRuler, type Horizon, type ScheduleGranularity } from '../cases/[id]/_workOrderSchedule';
import { ConsolidatedWoAccordion } from './ConsolidatedWoAccordion';
import { SinglePeggingTreePanel } from './SinglePeggingTreePanel';

type Props = {
  caseId: number;
  /** Plan-run id to query. Null while no plan has been run yet. */
  planRunId: number | null;
};

/** Per-period rollup: start/end on the horizon (ms) plus a label for the
 *  bar's tooltip. `index` keys back into the daily load array so peer rows
 *  can grab their period values in the same order. */
type Period = { startMs: number; endMs: number; label: string };

type ColKey = 'resource' | 'location' | 'size' | 'peak' | 'breakdown' | 'schedule';

const DEFAULT_COL_WIDTHS: Record<ColKey, number> = {
  resource: 140,
  location: 100,
  size: 90,
  peak: 100,
  breakdown: 90,
  schedule: 480,
};

// Bump the suffix if the schema of stored widths ever changes incompatibly
// (e.g. ColKey added/removed); the read-side merge already tolerates partial
// matches and out-of-range values via clamping.
const COL_WIDTHS_STORAGE_KEY = 'allocator.resourceUtilization.colWidths.v1';
const SLIDE_IN_WIDTH_STORAGE_KEY = 'allocator.resourceUtilization.slideInWidth.v1';
const DEFAULT_SLIDE_IN_WIDTH = 640;

type BreakdownMode = 'list' | 'wo_pegging';

/** WO context the user clicked into. Carries everything getWorkOrderPegging
 *  needs (demand_id, product_id, location_id, method) plus a label for the
 *  slide-in title. `demandId` is '' for a cross-demand consolidated batch,
 *  rendered by ConsolidatedWoAccordion (fed by woGroupId alone) instead of a
 *  single fetched tree. */
type WoContext = {
  demandId: string;
  productId: string;
  locationId: string;
  method: string;
  woGroupId?: string | null;
  startTime?: string | null;
};

/** Drag grip painted on the right edge of every resizable header. Hover
 *  surfaces a thin grey border so the affordance is discoverable without
 *  cluttering the table chrome when idle. */
function ResizeGrip({ onMouseDown, title }: { onMouseDown: (e: React.MouseEvent) => void; title: string }): JSX.Element {
  return (
    <div
      onMouseDown={onMouseDown}
      title={title}
      style={{
        position: 'absolute',
        top: 0,
        right: 0,
        bottom: 0,
        width: 6,
        cursor: 'col-resize',
        background: 'transparent',
        borderRight: '2px solid transparent',
      }}
      onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.borderRight = '2px solid #52525b'; }}
      onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.borderRight = '2px solid transparent'; }}
    />
  );
}

export function ResourceUtilizationView({ caseId, planRunId }: Props): JSX.Element {
  const t = useTranslations('planning.resourceUtilization');
  const locale = useLocale();
  const [data, setData] = useState<ResourceUtilization | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [granularity, setGranularity] = useState<ScheduleGranularity>('day');
  // Pixel widths per column. All columns are drag-resizable via the grip on
  // the right edge of each header. Schedule starts wider since it carries
  // the horizon ruler. Persisted to localStorage so widths survive reloads.
  // Initial render uses defaults to avoid SSR/hydration mismatch; saved
  // values overlay in a post-mount effect.
  const [colWidths, setColWidths] = useState<Record<ColKey, number>>(DEFAULT_COL_WIDTHS);

  // Breakdown slide-in: which resource is being explored, which mode is
  // active (flat WO list / single-WO pegging), and the relevant identifier.
  // WO pegging is fetched per-click and cached by a stable WO key.
  const [breakdownRow, setBreakdownRow] = useState<ResourceUtilizationRow | null>(null);
  const [breakdownMode, setBreakdownMode] = useState<BreakdownMode>('list');
  const [peggingWo, setPeggingWo] = useState<WoContext | null>(null);
  // work_orders_native + planning_pegging from a plan-run fetch, needed by
  // ConsolidatedWoAccordion for a cross-demand batch WO (see the wo_pegging fetch effect below).
  const [accordionData, setAccordionData] = useState<{ workOrdersNative: WorkOrder[]; planningPegging: PlanningPeggingEntry[] } | null>(null);
  const [woTrees, setWoTrees] = useState<Map<string, PlanningPeggingNode>>(new Map());
  const [woTreesLoading, setWoTreesLoading] = useState(false);
  const [woTreesError, setWoTreesError] = useState<string | null>(null);

  // Slide-in width (px). Drag-resizable via a handle on its left edge.
  // Persisted to localStorage, sanitized on read to the same bounds the
  // drag handler enforces.
  const [slideInWidth, setSlideInWidth] = useState<number>(DEFAULT_SLIDE_IN_WIDTH);
  const slideInResizeRef = useRef<{ startX: number; startW: number } | null>(null);

  const onSlideInResizeMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    slideInResizeRef.current = { startX: e.clientX, startW: slideInWidth };
    const onMove = (mv: MouseEvent) => {
      const s = slideInResizeRef.current;
      if (!s) return;
      // Slide-in is anchored on the right, so a leftward drag widens it.
      const next = Math.max(360, Math.min(2000, s.startW - (mv.clientX - s.startX)));
      setSlideInWidth(next);
    };
    const onUp = () => {
      slideInResizeRef.current = null;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [slideInWidth]);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(SLIDE_IN_WIDTH_STORAGE_KEY);
      if (!raw) return;
      const v = JSON.parse(raw);
      if (typeof v === 'number' && Number.isFinite(v)) {
        setSlideInWidth(Math.max(360, Math.min(2000, v)));
      }
    } catch {
      // Storage unavailable — defaults apply.
    }
  }, []);

  useEffect(() => {
    try {
      window.localStorage.setItem(SLIDE_IN_WIDTH_STORAGE_KEY, JSON.stringify(slideInWidth));
    } catch {
      // Silent — resizing still works in-session.
    }
  }, [slideInWidth]);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(COL_WIDTHS_STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Partial<Record<ColKey, number>>;
      // Sanitize: ignore non-numbers, clamp to the same bounds the drag
      // handler enforces so a corrupted/old value can't render the table
      // unusable.
      const sanitized: Partial<Record<ColKey, number>> = {};
      (Object.keys(DEFAULT_COL_WIDTHS) as ColKey[]).forEach((k) => {
        const v = parsed[k];
        if (typeof v !== 'number' || !Number.isFinite(v)) return;
        const minPx = k === 'schedule' ? 200 : 60;
        sanitized[k] = Math.max(minPx, Math.min(4000, v));
      });
      if (Object.keys(sanitized).length > 0) {
        setColWidths((prev) => ({ ...prev, ...sanitized }));
      }
    } catch {
      // localStorage disabled / quota / parse error — fall back to defaults.
    }
  }, []);

  useEffect(() => {
    try {
      window.localStorage.setItem(COL_WIDTHS_STORAGE_KEY, JSON.stringify(colWidths));
    } catch {
      // Storage unavailable — drag still works for the current session.
    }
  }, [colWidths]);

  // Fetch the plan-run (once) on transition into wo_pegging mode for a
  // cross-demand batch WO — that mode needs work_orders_native/planning_pegging
  // to feed ConsolidatedWoAccordion. Resets when planRunId changes.
  useEffect(() => {
    setAccordionData(null);
  }, [planRunId]);

  useEffect(() => {
    // Deps intentionally exclude accordionData: the setState call inside this
    // effect would otherwise re-trigger it, cleanup would flip `cancelled`
    // before the fetch lands. Cache hits are handled by the closure-captured
    // accordionData (refreshed every time peggingWo or planRunId changes).
    const needsAccordionData = breakdownMode === 'wo_pegging' && peggingWo?.demandId === '';
    if (!needsAccordionData || planRunId == null) return;
    if (accordionData != null) return;
    let cancelled = false;
    getPlanRun(caseId, planRunId)
      .then((pr) => {
        if (cancelled) return;
        setAccordionData({
          workOrdersNative: pr.result?.work_orders_native ?? [],
          planningPegging: pr.result?.planning_pegging ?? [],
        });
      })
      .catch((e: unknown) => {
        if (!cancelled && typeof console !== 'undefined' && console.error)
          console.error('[ResourceUtilizationView] accordion data fetch failed', e);
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [breakdownMode, caseId, planRunId, peggingWo]);

  // Fetch WO-scoped pegging on transition to wo_pegging mode. Same exhaustive-
  // deps caveat as the demand-pegging effect above — see comment there.
  useEffect(() => {
    // Cross-demand batches (demandId === '') are rendered by ConsolidatedWoAccordion instead,
    // fed by the accordionData fetch above — skip this single-tree fetch for that case.
    if (breakdownMode !== 'wo_pegging' || !peggingWo || peggingWo.demandId === '' || planRunId == null) return;
    const k = woCacheKey(peggingWo);
    if (woTrees.has(k)) return;
    let cancelled = false;
    setWoTreesLoading(true);
    setWoTreesError(null);
    getWorkOrderPegging(caseId, {
      demand_id: peggingWo.demandId,
      product_id: peggingWo.productId,
      location_id: peggingWo.locationId,
      method: peggingWo.method,
      start_time: peggingWo.startTime ?? undefined,
      wo_group_id: peggingWo.woGroupId ?? undefined,
      run_id: planRunId,
    })
      .then((res) => { if (!cancelled) setWoTrees((prev) => new Map(prev).set(k, res.tree)); })
      .catch((e: unknown) => { if (!cancelled) setWoTreesError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setWoTreesLoading(false); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [breakdownMode, peggingWo, caseId, planRunId]);

  // Reset the WO cache when the plan-run changes (rows from a different run
  // would point at trees that no longer exist).
  useEffect(() => {
    setWoTrees(new Map());
    setWoTreesError(null);
  }, [planRunId]);

  const resizingRef = useRef<{ key: ColKey; startX: number; startW: number } | null>(null);

  const onResizeMouseDown = useCallback((key: ColKey) => (e: React.MouseEvent) => {
    e.preventDefault();
    resizingRef.current = { key, startX: e.clientX, startW: colWidths[key] };
    const onMove = (mv: MouseEvent) => {
      const s = resizingRef.current;
      if (!s) return;
      const minPx = s.key === 'schedule' ? 200 : 60;
      const next = Math.max(minPx, Math.min(4000, s.startW + (mv.clientX - s.startX)));
      setColWidths((prev) => ({ ...prev, [s.key]: next }));
    };
    const onUp = () => {
      resizingRef.current = null;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [colWidths]);

  useEffect(() => {
    if (planRunId == null) {
      setData(null);
      setError(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    getResourceUtilization(caseId, planRunId)
      .then((res) => { if (!cancelled) setData(res); })
      .catch((e: unknown) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [caseId, planRunId]);

  // Horizon for the ruler: full range from the backend response, granularity
  // from the dropdown. Defaults to 'day' on first load.
  const horizon: Horizon | null = useMemo(() => {
    if (!data) return null;
    const start = parseUtcDate(data.horizon.start);
    const end = parseUtcDate(data.horizon.end);
    if (!start || !end) return null;
    return { start, end, granularity };
  }, [data, granularity]);

  // Peak load is granularity-independent — always the max across all daily
  // buckets in the response.
  const peakLoad = useMemo(() => {
    if (!data) return new Map<number, number>();
    const m = new Map<number, number>();
    data.rows.forEach((r, i) => m.set(i, Math.max(0, ...r.load)));
    return m;
  }, [data]);

  // Roll up daily buckets into the selected granularity. Within each period
  // we keep the MAX daily value — capacity is concurrent, so the bar shows
  // "worst day in the period" relative to size.
  const rolled = useMemo(() => {
    if (!data) return null;
    const periods: Period[] = [];
    const dayIdxByPeriod: number[][] = [];
    let currentKey: string | null = null;
    data.buckets.forEach((iso, i) => {
      const dayMs = parseUtcDate(iso)?.getTime() ?? 0;
      const key = periodKey(iso, granularity);
      if (key !== currentKey) {
        periods.push({
          startMs: periodStartMs(iso, granularity),
          endMs: periodEndMs(iso, granularity),
          label: periodLabel(iso, granularity, locale),
        });
        dayIdxByPeriod.push([]);
        currentKey = key;
      }
      dayIdxByPeriod[dayIdxByPeriod.length - 1].push(i);
      // endMs stretches with each new day in the period when granularity=day
      // would otherwise leave a 1-day rectangle; for week/month/quarter the
      // precomputed periodEndMs already covers the whole period.
      if (granularity === 'day') {
        periods[periods.length - 1].endMs = dayMs + DAY_MS;
      }
    });
    const loadByRow = data.rows.map((r) =>
      dayIdxByPeriod.map((idxs) => idxs.reduce((mx, i) => Math.max(mx, r.load[i] ?? 0), 0)),
    );
    return { periods, loadByRow };
  }, [data, granularity, locale]);

  if (planRunId == null) return <p style={{ color: '#a1a1aa' }}>{t('noPlanRun')}</p>;
  if (loading) return <p style={{ color: '#a1a1aa' }}>{t('loading')}</p>;
  if (error) return <p style={{ color: '#f87171' }}>{error}</p>;
  if (!data || data.rows.length === 0 || !rolled || !horizon) {
    return <p style={{ color: '#a1a1aa' }}>{t('noResources')}</p>;
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
        <h4 style={{ margin: 0 }}>{t('heading')}</h4>
        <label style={{ fontSize: '0.8rem', color: '#a1a1aa', display: 'inline-flex', gap: '0.4rem', alignItems: 'center' }}>
          <span>{t('granularityLabel')}</span>
          <select
            value={granularity}
            onChange={(e) => setGranularity(e.target.value as ScheduleGranularity)}
            style={{ fontSize: '0.8rem', padding: '0.15rem 0.3rem', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 4 }}
          >
            <option value="day">{t('granularityDay')}</option>
            <option value="week">{t('granularityWeek')}</option>
            <option value="month">{t('granularityMonth')}</option>
            <option value="quarter">{t('granularityQuarter')}</option>
          </select>
        </label>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ minWidth: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa', textAlign: 'left' }}>
              <th style={{ padding: '0.4rem 0.6rem', width: colWidths.resource, minWidth: colWidths.resource, position: 'relative' }}>
                {t('columnResource')}
                <ResizeGrip onMouseDown={onResizeMouseDown('resource')} title={t('resizeHandleTooltip')} />
              </th>
              <th style={{ padding: '0.4rem 0.6rem', width: colWidths.location, minWidth: colWidths.location, position: 'relative' }}>
                {t('columnLocation')}
                <ResizeGrip onMouseDown={onResizeMouseDown('location')} title={t('resizeHandleTooltip')} />
              </th>
              <th
                style={{ padding: '0.4rem 0.6rem', textAlign: 'right', cursor: 'help', width: colWidths.size, minWidth: colWidths.size, position: 'relative' }}
                title={t('columnSizeTooltip')}
              >
                {t('columnSize')}
                <ResizeGrip onMouseDown={onResizeMouseDown('size')} title={t('resizeHandleTooltip')} />
              </th>
              <th
                style={{ padding: '0.4rem 0.6rem', textAlign: 'right', cursor: 'help', width: colWidths.peak, minWidth: colWidths.peak, position: 'relative' }}
                title={t('columnPeakLoadTooltip')}
              >
                {t('columnPeakLoad')}
                <ResizeGrip onMouseDown={onResizeMouseDown('peak')} title={t('resizeHandleTooltip')} />
              </th>
              <th
                style={{ padding: '0.4rem 0.6rem', width: colWidths.schedule, minWidth: colWidths.schedule, position: 'relative' }}
                title={t('columnUtilizationTooltip', { granularity: granularityNoun(t, granularity) })}
              >
                <ScheduleHorizonRuler horizon={horizon} locale={locale} />
                <ResizeGrip onMouseDown={onResizeMouseDown('schedule')} title={t('resizeHandleTooltip')} />
              </th>
              <th style={{ padding: '0.4rem 0.6rem', width: colWidths.breakdown, minWidth: colWidths.breakdown, position: 'relative' }}>
                {t('columnBreakdown')}
                <ResizeGrip onMouseDown={onResizeMouseDown('breakdown')} title={t('resizeHandleTooltip')} />
              </th>
            </tr>
          </thead>
          <tbody>
            {data.rows.map((r, i) => {
              // Single-WO parallelism cap can't see other WOs at the same
              // location, so peak load can exceed pool size when concurrent
              // WOs share a resource. Surface that visually: red row tint +
              // red peak number + warning tooltip so the user knows the
              // schedule is optimistic for this resource. Real fix is
              // cross-WO arbitration (deferred).
              const peak = peakLoad.get(i) ?? 0;
              const overloaded = peak > r.size + 1e-9;
              const bg = overloaded
                ? 'rgba(239,68,68,0.10)'
                : breakdownRow === r ? 'rgba(167,139,250,0.08)' : undefined;
              return (
              <tr
                key={`${r.resource_id}|${r.location_id}`}
                style={{
                  borderBottom: '1px solid #27272a',
                  background: bg,
                }}
              >
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', width: colWidths.resource, minWidth: colWidths.resource, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.resource_id}>{r.resource_id}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', width: colWidths.location, minWidth: colWidths.location, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.location_id}>{r.location_id}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', textAlign: 'right', width: colWidths.size, minWidth: colWidths.size }}>{r.size}</td>
                <td
                  style={{
                    padding: '0.35rem 0.6rem',
                    color: overloaded ? '#f87171' : '#e4e4e7',
                    fontWeight: overloaded ? 600 : undefined,
                    textAlign: 'right',
                    width: colWidths.peak,
                    minWidth: colWidths.peak,
                    cursor: overloaded ? 'help' : undefined,
                  }}
                  title={overloaded ? t('peakOverloadTooltip', { peak: peak.toFixed(2), size: r.size }) : undefined}
                >
                  {peak.toFixed(2)}
                  {overloaded && <span style={{ marginLeft: 4 }}>⚠</span>}
                </td>
                <td style={{ padding: '0.35rem 0.6rem', width: colWidths.schedule, minWidth: colWidths.schedule }}>
                  <LoadStrip
                    horizon={horizon}
                    periods={rolled.periods}
                    load={rolled.loadByRow[i]}
                    size={r.size}
                    t={t}
                  />
                </td>
                <td style={{ padding: '0.35rem 0.6rem', width: colWidths.breakdown, minWidth: colWidths.breakdown }}>
                  {(r.contributors?.length ?? 0) > 0 ? (
                    <button
                      type="button"
                      className="secondary"
                      style={breakdownRow === r ? { background: 'rgba(167,139,250,0.25)', borderColor: '#a78bfa' } : undefined}
                      onClick={() => {
                        if (breakdownRow === r) { setBreakdownRow(null); setBreakdownMode('list'); }
                        else { setBreakdownRow(r); setBreakdownMode('list'); }
                      }}
                    >{t('show')}</button>
                  ) : <span style={{ color: '#52525b', fontSize: '0.75rem' }}>–</span>}
                </td>
              </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {breakdownRow && typeof document !== 'undefined' && createPortal(
        <BreakdownSlideIn
          caseId={caseId}
          planRunId={planRunId}
          accordionData={accordionData}
          row={breakdownRow}
          mode={breakdownMode}
          peggingWo={peggingWo}
          woTrees={woTrees}
          woTreesLoading={woTreesLoading}
          woTreesError={woTreesError}
          slideInWidth={slideInWidth}
          onResizeMouseDown={onSlideInResizeMouseDown}
          onClose={() => {
            setBreakdownRow(null);
            setBreakdownMode('list');
            setPeggingWo(null);
          }}
          onOpenWoPegging={(ctx) => { setPeggingWo(ctx); setBreakdownMode('wo_pegging'); }}
          onBackToList={() => { setBreakdownMode('list'); setPeggingWo(null); }}
          t={t}
        />,
        document.body,
      )}
    </div>
  );
}

const DAY_MS = 86_400_000;

function parseUtcDate(s: string): Date | null {
  if (!s) return null;
  const d = new Date(`${s.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? null : d;
}

function granularityNoun(t: ReturnType<typeof useTranslations>, g: ScheduleGranularity): string {
  if (g === 'day') return t('granularityNounDay');
  if (g === 'week') return t('granularityNounWeek');
  if (g === 'month') return t('granularityNounMonth');
  return t('granularityNounQuarter');
}

/** Group key for a day. Buckets sharing a key roll into one period. */
function periodKey(isoDate: string, g: ScheduleGranularity): string {
  if (g === 'month') return isoDate.slice(0, 7);
  if (g === 'quarter') {
    const d = parseUtcDate(isoDate)!;
    const q = Math.floor(d.getUTCMonth() / 3);
    return `${d.getUTCFullYear()}-Q${q + 1}`;
  }
  if (g === 'week') {
    const d = parseUtcDate(isoDate)!;
    const dow = d.getUTCDay();
    const offset = (dow + 6) % 7; // Mon = 0
    d.setUTCDate(d.getUTCDate() - offset);
    return d.toISOString().slice(0, 10);
  }
  return isoDate;
}

/** UTC midnight of the first day in the period containing isoDate. */
function periodStartMs(isoDate: string, g: ScheduleGranularity): number {
  const d = parseUtcDate(isoDate)!;
  if (g === 'month') return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1);
  if (g === 'quarter') {
    const q = Math.floor(d.getUTCMonth() / 3);
    return Date.UTC(d.getUTCFullYear(), q * 3, 1);
  }
  if (g === 'week') {
    const dow = d.getUTCDay();
    const offset = (dow + 6) % 7;
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - offset);
  }
  return d.getTime();
}

/** UTC midnight of the first day AFTER the period containing isoDate. */
function periodEndMs(isoDate: string, g: ScheduleGranularity): number {
  const d = parseUtcDate(isoDate)!;
  if (g === 'month') return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
  if (g === 'quarter') {
    const q = Math.floor(d.getUTCMonth() / 3);
    return Date.UTC(d.getUTCFullYear(), (q + 1) * 3, 1);
  }
  if (g === 'week') {
    return periodStartMs(isoDate, g) + 7 * DAY_MS;
  }
  return d.getTime() + DAY_MS;
}

function periodLabel(isoDate: string, g: ScheduleGranularity, locale: string): string {
  if (g === 'month') return isoDate.slice(0, 7);
  if (g === 'quarter') {
    const d = parseUtcDate(isoDate)!;
    return `Q${Math.floor(d.getUTCMonth() / 3) + 1} ${d.getUTCFullYear()}`;
  }
  if (g === 'week') {
    return new Date(periodStartMs(isoDate, g)).toISOString().slice(0, 10);
  }
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(parseUtcDate(isoDate)!);
  } catch {
    return isoDate;
  }
}

/**
 * Per-row utilization strip rendered to the same horizon as the table's
 * ScheduleHorizonRuler header. Each period is one positioned rectangle
 * (x/width derived from horizon coords), so monthly buckets cover an
 * actual month of the axis instead of being squashed into equal-width
 * cells. Tint reflects ratio = period_max / size:
 *   ≥1.0 → red (saturation)
 *   ≥0.66 → blue
 *   ≥0.33 → mid-blue
 *   >0    → light-blue
 *   0     → transparent
 */
function LoadStrip({
  horizon,
  periods,
  load,
  size,
  t,
}: {
  horizon: Horizon;
  periods: Period[];
  load: number[];
  size: number;
  t: ReturnType<typeof useTranslations>;
}): JSX.Element {
  const hStart = horizon.start.getTime();
  const hEnd = horizon.end.getTime();
  const hSpan = Math.max(1, hEnd - hStart);

  return (
    <div style={{ minWidth: 140, width: '100%', padding: '2px 0' }}>
      <svg width="100%" height={14} preserveAspectRatio="none" style={{ display: 'block' }}>
        {periods.map((p, i) => {
          const v = load[i] ?? 0;
          const ratio = size > 0 ? v / size : 0;
          if (ratio <= 0) return null;
          const x = Math.max(0, ((p.startMs - hStart) / hSpan) * 100);
          // Clamp the right edge so a period that overhangs the horizon
          // (e.g. month containing the horizon end) doesn't render past it.
          const w = Math.max(
            0.3,
            Math.min(100 - x, ((p.endMs - Math.max(hStart, p.startMs)) / hSpan) * 100),
          );
          let bg: string;
          if (ratio >= 1) bg = '#ef4444';
          else if (ratio >= 0.66) bg = '#3b82f6';
          else if (ratio >= 0.33) bg = '#60a5fa';
          else bg = '#93c5fd';
          return (
            <rect
              key={i}
              x={`${x}%`}
              y={2}
              width={`${w}%`}
              height={10}
              fill={bg}
              rx={1}
            >
              <title>{t('tooltipLoad', { load: v, size, date: p.label })}</title>
            </rect>
          );
        })}
      </svg>
    </div>
  );
}

function BreakdownSlideIn({
  caseId,
  planRunId,
  accordionData,
  row,
  mode,
  peggingWo,
  woTrees,
  woTreesLoading,
  woTreesError,
  slideInWidth,
  onResizeMouseDown,
  onClose,
  onOpenWoPegging,
  onBackToList,
  t,
}: {
  caseId: number;
  planRunId: number | null;
  accordionData: { workOrdersNative: WorkOrder[]; planningPegging: PlanningPeggingEntry[] } | null;
  row: ResourceUtilizationRow;
  mode: BreakdownMode;
  peggingWo: WoContext | null;
  woTrees: Map<string, PlanningPeggingNode>;
  woTreesLoading: boolean;
  woTreesError: string | null;
  slideInWidth: number;
  onResizeMouseDown: (e: React.MouseEvent) => void;
  onClose: () => void;
  onOpenWoPegging: (ctx: WoContext) => void;
  onBackToList: () => void;
  t: ReturnType<typeof useTranslations>;
}): JSX.Element {
  const woTree = peggingWo ? woTrees.get(woCacheKey(peggingWo)) : null;

  const title = mode === 'list'
    ? t('breakdownTitle')
    : t('breakdownWoPeggingTitle', { product: peggingWo?.productId ?? '', location: peggingWo?.locationId ?? '' });

  return (
    <div
      style={{ position: 'fixed', inset: 0, zIndex: 9997, display: 'flex', justifyContent: 'flex-end', pointerEvents: 'none' }}
      role="dialog"
      aria-label="Resource utilization breakdown"
    >
      <div
        style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.4)', pointerEvents: 'auto' }}
        onClick={onClose}
        aria-hidden
      />
      <div
        style={{
          position: 'relative', zIndex: 10, width: slideInWidth, maxWidth: '95vw', height: '100vh',
          display: 'flex', flexDirection: 'column', background: '#1c1c1e', color: '#e4e4e7',
          boxShadow: '-4px 0 24px rgba(0,0,0,0.4)', pointerEvents: 'auto',
        }}
      >
        {/* Resize grip on the left edge — mousedown captures global mousemove
            so the cursor can leave the 8px hit zone during the drag. */}
        <div
          role="separator"
          aria-label="Resize panel"
          onMouseDown={onResizeMouseDown}
          onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(99,102,241,0.5)'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
          style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 8, cursor: 'col-resize', zIndex: 11, transition: 'background-color 120ms' }}
        />
        <div style={{ padding: '1rem 1.25rem', borderBottom: '1px solid #3d3d40', flexShrink: 0 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
            <h3 style={{ margin: 0, color: '#fafafa', fontSize: '1rem' }}>{title}</h3>
            <button
              type="button"
              onClick={onClose}
              style={{ padding: '4px 10px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}
            >{t('close')}</button>
          </div>
          <p style={{ margin: 0, fontSize: '0.8rem', color: '#a1a1aa' }}>
            <strong>{row.resource_id}</strong> @ {row.location_id}
            {' · '}{t('columnSize')}: {row.size}
            {' · '}{t('columnPeakLoad')}: {Math.max(0, ...row.load).toFixed(2)}
          </p>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', padding: '1rem 1.25rem' }}>
          {mode === 'list' && (
            <BreakdownList
              contributors={row.contributors ?? []}
              onOpenWoPegging={onOpenWoPegging}
              t={t}
            />
          )}
          {mode === 'wo_pegging' && (
            <>
              <button
                type="button"
                onClick={onBackToList}
                style={{ background: 'none', border: 'none', color: '#a1a1aa', cursor: 'pointer', fontSize: '0.78rem', padding: 0, marginBottom: '0.75rem', display: 'flex', alignItems: 'center', gap: '0.3rem' }}
              >
                ← {t('breakdownBackToList')}
              </button>
              {peggingWo && peggingWo.demandId !== '' && (
                <p style={{ margin: '0 0 0.5rem', fontSize: '0.75rem', color: '#71717a', fontFamily: 'monospace' }}>
                  {peggingWo.demandId} · {peggingWo.method} · {peggingWo.startTime ?? '–'}
                </p>
              )}
              {peggingWo?.demandId === '' ? (
                // Cross-demand consolidated batch — same accordion _CaseSectionPage.tsx uses for
                // its own "Consolidated" WO table drill-down, so both surfaces render identically.
                !accordionData ? (
                  <p style={{ color: '#a1a1aa' }}>{t('peggingLoading')}</p>
                ) : (
                  <ConsolidatedWoAccordion
                    key={peggingWo.woGroupId ?? ''}
                    caseId={caseId}
                    planRunId={planRunId}
                    woGroupId={peggingWo.woGroupId ?? ''}
                    workOrdersNative={accordionData.workOrdersNative}
                    planningPegging={accordionData.planningPegging}
                  />
                )
              ) : (
                <>
                  {woTreesLoading && <p style={{ color: '#a1a1aa' }}>{t('peggingLoading')}</p>}
                  {woTreesError && <p style={{ color: '#f87171' }}>{woTreesError}</p>}
                  {!woTreesLoading && !woTreesError && !woTree && (
                    <p style={{ color: '#a1a1aa' }}>{t('peggingWoNotFound')}</p>
                  )}
                  {woTree && peggingWo && (
                    <SinglePeggingTreePanel
                      key={woCacheKey(peggingWo)}
                      tree={woTree}
                      contextDemandId={peggingWo.demandId || null}
                      planningPegging={accordionData?.planningPegging ?? []}
                    />
                  )}
                </>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/** Stable cache key for a WO's pegging request — the WO is identified by
 *  (demand, product, location, method, start_time, wo_group_id). start_time
 *  disambiguates multi-lot WOs that share the same product/location/method;
 *  wo_group_id further disambiguates cross-demand batches (demandId is ''
 *  for all of them, so product/location/method/start_time alone could collide
 *  across two distinct batches). */
function woCacheKey(ctx: WoContext): string {
  return `${ctx.demandId}|${ctx.productId}|${ctx.locationId}|${ctx.method}|${ctx.startTime ?? ''}|${ctx.woGroupId ?? ''}`;
}

type WoSortCol = 'product' | 'location' | 'start' | 'end';

/** Sort WO rows by the active column, with product/start/end as a stable
 *  tie-break (in that order, skipping the active column) so rows still read
 *  as a coherent timeline rather than jumping around on equal keys. */
function sortWos(
  wos: NonNullable<ResourceUtilizationRow['contributors']>,
  col: WoSortCol,
  dir: 'asc' | 'desc',
): typeof wos {
  const keyOf = (w: (typeof wos)[number], c: WoSortCol) => (
    c === 'product' ? w.product_id
      : c === 'location' ? w.location_id
      : c === 'start' ? w.start_time
      : w.end_time
  ) ?? '';
  const tieBreakOrder: WoSortCol[] = ['product', 'start', 'end'].filter((c) => c !== col) as WoSortCol[];
  const d = dir === 'asc' ? 1 : -1;
  return [...wos].sort((a, b) => (
    d * keyOf(a, col).localeCompare(keyOf(b, col))
    || tieBreakOrder.reduce((acc, c) => acc || keyOf(a, c).localeCompare(keyOf(b, c)), 0)
  ));
}

function BreakdownList({
  contributors,
  onOpenWoPegging,
  t,
}: {
  contributors: NonNullable<ResourceUtilizationRow['contributors']>;
  onOpenWoPegging: (ctx: WoContext) => void;
  t: ReturnType<typeof useTranslations>;
}): JSX.Element {
  const [sortCol, setSortCol] = useState<WoSortCol>('product');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  const toggleSort = (col: WoSortCol) => {
    if (sortCol === col) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortCol(col); setSortDir('asc'); }
  };
  const sortIcon = (col: WoSortCol) => (sortCol === col ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '');
  const thStyle = (col: WoSortCol, align: 'left' | 'right' = 'left'): React.CSSProperties => ({
    paddingBottom: 2, textAlign: align, cursor: 'pointer', userSelect: 'none',
    color: sortCol === col ? '#e4e4e7' : '#71717a',
  });

  if (contributors.length === 0) return <p style={{ color: '#a1a1aa' }}>{t('breakdownEmpty')}</p>;
  return (
    <table style={{ width: '100%', fontSize: '0.74rem', borderCollapse: 'collapse' }}>
      <thead>
        <tr style={{ color: '#71717a', textAlign: 'left' }}>
          <th style={thStyle('product')} onClick={() => toggleSort('product')}>{t('breakdownColProduct')}{sortIcon('product')}</th>
          <th style={thStyle('location')} onClick={() => toggleSort('location')}>{t('breakdownColLocation')}{sortIcon('location')}</th>
          <th style={thStyle('start')} onClick={() => toggleSort('start')}>{t('breakdownColStart')}{sortIcon('start')}</th>
          <th style={thStyle('end')} onClick={() => toggleSort('end')}>{t('breakdownColEnd')}{sortIcon('end')}</th>
          <th style={{ paddingBottom: 2, textAlign: 'right' }}>{t('breakdownColQty')}</th>
          <th style={{ paddingBottom: 2, textAlign: 'right' }}>{t('breakdownColPeakLoad')}</th>
        </tr>
      </thead>
      <tbody>
        {sortWos(contributors, sortCol, sortDir).map((wo, wi) => {
          // WO pegging is keyed by (demand_id, product_id, location_id, method) for a
          // single-demand WO, or (demand_ids, win_start, win_end, product_id, location_id,
          // method) for a cross-demand consolidated batch (wo.demand_id is null but the WO
          // still carries the set of demands it serves in demand_ids). The breakdown only
          // emits make-WO contributors, but read method off the row when present so future
          // non-make rows still resolve.
          const hasDemandIds = !!(wo.demand_ids && wo.demand_ids.length > 0);
          const peggable = !!(wo.product_id && wo.location_id && (wo.demand_id || hasDemandIds));
          return (
            <tr key={`${wo.wo_group_id ?? ''}-${wi}`} style={{ borderTop: '1px solid #27272a' }}>
              <td style={{ padding: '2px 6px 2px 0', fontFamily: 'monospace' }}>
                {peggable ? (
                  <button
                    type="button"
                    onClick={() => onOpenWoPegging({
                      // '' for a cross-demand consolidated batch — rendered by
                      // ConsolidatedWoAccordion, which derives its own demand/lot
                      // breakdown from work_orders_native + woGroupId alone.
                      demandId: wo.demand_id ?? '',
                      productId: wo.product_id!,
                      locationId: wo.location_id!,
                      method: 'make',
                      woGroupId: wo.wo_group_id ?? null,
                      startTime: wo.start_time ?? null,
                    })}
                    title={t('breakdownOpenWoPegging')}
                    style={{
                      background: 'none', border: 'none', padding: 0, cursor: 'pointer',
                      color: '#60a5fa', fontFamily: 'monospace', fontSize: 'inherit',
                      textDecoration: 'underline', textUnderlineOffset: 2,
                    }}
                  >{wo.product_id}</button>
                ) : (
                  <span>{wo.product_id}</span>
                )}
              </td>
              <td style={{ padding: '2px 6px 2px 0' }}>{wo.location_id ?? '–'}</td>
              <td style={{ padding: '2px 6px 2px 0' }}>{wo.start_time ?? '–'}</td>
              <td style={{ padding: '2px 6px 2px 0' }}>
                {wo.end_time ?? '–'}
                {wo.lot_count && wo.lot_count > 1 && (
                  <span style={{ color: '#71717a', marginLeft: 4 }}>· {wo.lot_count} lots</span>
                )}
              </td>
              <td style={{ padding: '2px 0 2px 6px', textAlign: 'right' }}>
                {wo.quantity != null ? wo.quantity.toLocaleString(undefined, { maximumFractionDigits: 2 }) : '–'}
              </td>
              <td style={{ padding: '2px 0 2px 6px', textAlign: 'right' }}>{wo.peak_load?.toFixed(2) ?? '–'}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

