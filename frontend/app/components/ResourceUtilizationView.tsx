'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { getResourceUtilization, type ResourceUtilization } from '@/lib/api';

type Props = {
  caseId: number;
  /** Plan-run id to query. Null while no plan has been run yet. */
  planRunId: number | null;
};

type Granularity = 'day' | 'week' | 'month';

export function ResourceUtilizationView({ caseId, planRunId }: Props): JSX.Element {
  const t = useTranslations('planning.resourceUtilization');
  const [data, setData] = useState<ResourceUtilization | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [granularity, setGranularity] = useState<Granularity>('day');

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

  // Peak load is granularity-independent (always max across whole horizon).
  const peakLoad = useMemo(() => {
    if (!data) return new Map<number, number>();
    const m = new Map<number, number>();
    data.rows.forEach((r, i) => m.set(i, Math.max(0, ...r.load)));
    return m;
  }, [data]);

  // Roll up daily buckets into weekly/monthly periods. Within each period we
  // take the MAX of the daily values — a sum would be meaningless (resources
  // are concurrent capacity, not cumulative throughput), so the bar should
  // show "worst day in the period" relative to size.
  const rolled = useMemo(() => {
    if (!data) return null;
    if (granularity === 'day') {
      return { buckets: data.buckets, loadByRow: data.rows.map((r) => r.load) };
    }
    const groups: number[][] = []; // bucket idx → list of source-day indices
    const labels: string[] = [];
    let currentKey: string | null = null;
    data.buckets.forEach((iso, i) => {
      const key = periodKey(iso, granularity);
      if (key !== currentKey) {
        groups.push([]);
        labels.push(periodLabel(iso, granularity));
        currentKey = key;
      }
      groups[groups.length - 1].push(i);
    });
    const loadByRow = data.rows.map((r) =>
      groups.map((idxs) => idxs.reduce((mx, i) => Math.max(mx, r.load[i] ?? 0), 0)),
    );
    return { buckets: labels, loadByRow };
  }, [data, granularity]);

  if (planRunId == null) return <p style={{ color: '#a1a1aa' }}>{t('noPlanRun')}</p>;
  if (loading) return <p style={{ color: '#a1a1aa' }}>{t('loading')}</p>;
  if (error) return <p style={{ color: '#f87171' }}>{error}</p>;
  if (!data || data.rows.length === 0 || !rolled) return <p style={{ color: '#a1a1aa' }}>{t('noResources')}</p>;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
        <h4 style={{ margin: 0 }}>{t('heading')}</h4>
        <label style={{ fontSize: '0.8rem', color: '#a1a1aa', display: 'inline-flex', gap: '0.4rem', alignItems: 'center' }}>
          <span>{t('granularityLabel')}</span>
          <select
            value={granularity}
            onChange={(e) => setGranularity(e.target.value as Granularity)}
            style={{ fontSize: '0.8rem', padding: '0.15rem 0.3rem', background: '#27272a', color: '#e4e4e7', border: '1px solid #52525b', borderRadius: 4 }}
          >
            <option value="day">{t('granularityDay')}</option>
            <option value="week">{t('granularityWeek')}</option>
            <option value="month">{t('granularityMonth')}</option>
          </select>
        </label>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa', textAlign: 'left' }}>
              <th style={{ padding: '0.4rem 0.6rem' }}>{t('columnResource')}</th>
              <th style={{ padding: '0.4rem 0.6rem' }}>{t('columnLocation')}</th>
              <th
                style={{ padding: '0.4rem 0.6rem', textAlign: 'right', cursor: 'help' }}
                title={t('columnSizeTooltip')}
              >
                {t('columnSize')}
              </th>
              <th
                style={{ padding: '0.4rem 0.6rem', textAlign: 'right', cursor: 'help' }}
                title={t('columnPeakLoadTooltip')}
              >
                {t('columnPeakLoad')}
              </th>
              <th
                style={{ padding: '0.4rem 0.6rem', minWidth: 300, cursor: 'help' }}
                title={t('columnUtilizationTooltip', { granularity: granularityNoun(t, granularity) })}
              >
                {t('columnUtilization')}
              </th>
            </tr>
          </thead>
          <tbody>
            {data.rows.map((r, i) => (
              <tr key={`${r.resource_id}|${r.location_id}`} style={{ borderBottom: '1px solid #27272a' }}>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7' }}>{r.resource_id}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7' }}>{r.location_id}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', textAlign: 'right' }}>{r.size}</td>
                <td style={{ padding: '0.35rem 0.6rem', color: '#e4e4e7', textAlign: 'right' }}>{peakLoad.get(i)?.toFixed(2) ?? '0'}</td>
                <td style={{ padding: '0.35rem 0.6rem' }}>
                  <LoadBar buckets={rolled.buckets} load={rolled.loadByRow[i]} size={r.size} t={t} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function granularityNoun(t: ReturnType<typeof useTranslations>, g: Granularity): string {
  if (g === 'day') return t('granularityNounDay');
  if (g === 'week') return t('granularityNounWeek');
  return t('granularityNounMonth');
}

/** Group key for a day. Buckets sharing a key roll into one period. */
function periodKey(isoDate: string, g: Granularity): string {
  if (g === 'month') return isoDate.slice(0, 7); // yyyy-MM
  // ISO week: Monday-anchored. Cheap UTC math is fine — buckets are dates only.
  const d = new Date(`${isoDate}T00:00:00Z`);
  const dow = d.getUTCDay(); // 0 = Sun
  const offset = (dow + 6) % 7; // Mon = 0
  d.setUTCDate(d.getUTCDate() - offset);
  return d.toISOString().slice(0, 10);
}

/** Human-readable label for a period anchored at the given day. */
function periodLabel(isoDate: string, g: Granularity): string {
  if (g === 'month') return isoDate.slice(0, 7); // 2026-05
  // For week, label with the Monday-anchored start date so the user sees the
  // actual horizon point rather than a synthetic "Wk 19".
  return periodKey(isoDate, g);
}

/**
 * Per-bucket utilization bar. Color tints reflect ratio = bucketMax / size:
 *   ≥1.0 → red (saturation/overload)
 *   ≥0.66 → dark blue
 *   ≥0.33 → mid blue
 *   >0    → light blue
 *   0     → transparent (idle)
 * Tooltip uses the bucket label (date for daily, Monday for weekly, yyyy-MM
 * for monthly) and the load value within that bucket — which is the MAX
 * across the daily values in the period, not a sum.
 */
function LoadBar({
  buckets,
  load,
  size,
  t,
}: {
  buckets: string[];
  load: number[];
  size: number;
  t: ReturnType<typeof useTranslations>;
}): JSX.Element {
  const cells = buckets.map((label, i) => {
    const v = load[i] ?? 0;
    const ratio = size > 0 ? v / size : 0;
    let bg = 'transparent';
    if (ratio > 0) {
      if (ratio >= 1) bg = '#ef4444';
      else if (ratio >= 0.66) bg = '#3b82f6';
      else if (ratio >= 0.33) bg = '#60a5fa';
      else bg = '#93c5fd';
    }
    return (
      <div
        key={label}
        title={t('tooltipLoad', { load: v, size, date: label })}
        style={{ flex: 1, height: 14, background: bg, marginRight: 1, borderRadius: 1 }}
      />
    );
  });
  return <div style={{ display: 'flex', alignItems: 'stretch' }}>{cells}</div>;
}
