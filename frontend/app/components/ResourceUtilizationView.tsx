'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { getResourceUtilization, type ResourceUtilization } from '@/lib/api';

type Props = {
  caseId: number;
  /** Plan-run id to query. Null while no plan has been run yet. */
  planRunId: number | null;
};

export function ResourceUtilizationView({ caseId, planRunId }: Props): JSX.Element {
  const t = useTranslations('planning.resourceUtilization');
  const [data, setData] = useState<ResourceUtilization | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  const peakLoad = useMemo(() => {
    if (!data) return new Map<number, number>();
    const m = new Map<number, number>();
    data.rows.forEach((r, i) => m.set(i, Math.max(0, ...r.load)));
    return m;
  }, [data]);

  if (planRunId == null) return <p style={{ color: '#a1a1aa' }}>{t('noPlanRun')}</p>;
  if (loading) return <p style={{ color: '#a1a1aa' }}>{t('loading')}</p>;
  if (error) return <p style={{ color: '#f87171' }}>{error}</p>;
  if (!data || data.rows.length === 0) return <p style={{ color: '#a1a1aa' }}>{t('noResources')}</p>;

  return (
    <div>
      <h4 style={{ marginTop: 0, marginBottom: '0.75rem' }}>{t('heading')}</h4>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid #3d3d40', color: '#a1a1aa', textAlign: 'left' }}>
              <th style={{ padding: '0.4rem 0.6rem' }}>{t('columnResource')}</th>
              <th style={{ padding: '0.4rem 0.6rem' }}>{t('columnLocation')}</th>
              <th style={{ padding: '0.4rem 0.6rem', textAlign: 'right' }}>{t('columnSize')}</th>
              <th style={{ padding: '0.4rem 0.6rem', textAlign: 'right' }}>{t('columnPeakLoad')}</th>
              <th style={{ padding: '0.4rem 0.6rem', minWidth: 300 }}>{t('columnUtilization')}</th>
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
                  <LoadBar buckets={data.buckets} load={r.load} size={r.size} t={t} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/**
 * Stacked daily-load bar: one cell per bucket. Cell height tints with
 * utilization (load/size) so saturated bars (≥1.0) saturate to red, partial
 * loads sit on a blue ramp, and idle days are flat. Tooltip on each cell
 * exposes the raw values via the i18n template.
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
  const cells = buckets.map((date, i) => {
    const v = load[i] ?? 0;
    const ratio = size > 0 ? v / size : 0;
    let bg = 'transparent';
    if (ratio > 0) {
      // 0..1 → blue ramp; >1 saturates to red so overload pops visually.
      if (ratio >= 1) bg = '#ef4444';
      else if (ratio >= 0.66) bg = '#3b82f6';
      else if (ratio >= 0.33) bg = '#60a5fa';
      else bg = '#93c5fd';
    }
    return (
      <div
        key={date}
        title={t('tooltipLoad', { load: v, size, date })}
        style={{ flex: 1, height: 14, background: bg, marginRight: 1, borderRadius: 1 }}
      />
    );
  });
  return <div style={{ display: 'flex', alignItems: 'stretch' }}>{cells}</div>;
}
