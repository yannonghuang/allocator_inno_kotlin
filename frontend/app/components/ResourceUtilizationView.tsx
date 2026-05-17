'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { getResourceUtilization, type ResourceUtilization } from '@/lib/api';
import { ScheduleHorizonRuler, type Horizon, type ScheduleGranularity } from '../cases/[id]/_workOrderSchedule';

type Props = {
  caseId: number;
  /** Plan-run id to query. Null while no plan has been run yet. */
  planRunId: number | null;
};

/** Per-period rollup: start/end on the horizon (ms) plus a label for the
 *  bar's tooltip. `index` keys back into the daily load array so peer rows
 *  can grab their period values in the same order. */
type Period = { startMs: number; endMs: number; label: string };

type ColKey = 'resource' | 'location' | 'size' | 'peak' | 'schedule';

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
  // the horizon ruler.
  const [colWidths, setColWidths] = useState<Record<ColKey, number>>({
    resource: 140,
    location: 100,
    size: 90,
    peak: 100,
    schedule: 480,
  });
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
            </tr>
          </thead>
          <tbody>
            {data.rows.map((r, i) => (
              <tr key={`${r.resource_id}|${r.location_id}`} style={{ borderBottom: '1px solid #27272a' }}>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', width: colWidths.resource, minWidth: colWidths.resource, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.resource_id}>{r.resource_id}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', width: colWidths.location, minWidth: colWidths.location, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.location_id}>{r.location_id}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', textAlign: 'right', width: colWidths.size, minWidth: colWidths.size }}>{r.size}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', textAlign: 'right', width: colWidths.peak, minWidth: colWidths.peak }}>{peakLoad.get(i)?.toFixed(2) ?? '0'}</td>
                <td style={{ padding: '0.35rem 0.6rem', width: colWidths.schedule, minWidth: colWidths.schedule }}>
                  <LoadStrip
                    horizon={horizon}
                    periods={rolled.periods}
                    load={rolled.loadByRow[i]}
                    size={r.size}
                    t={t}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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
