'use client';

import React from 'react';

export type ScheduleGranularity = 'day' | 'week' | 'month' | 'quarter';

export type Horizon = {
  start: Date;
  end: Date;
  granularity: ScheduleGranularity;
};

const DAY_MS = 86_400_000;

function parseIso(s: string | null | undefined): Date | null {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function computeHorizon<T extends { start_time: string | null; end_time: string | null }>(
  rows: T[],
): Horizon | null {
  let minMs = Infinity;
  let maxMs = -Infinity;
  for (const r of rows) {
    const s = parseIso(r.start_time);
    const e = parseIso(r.end_time);
    if (!s || !e) continue;
    const sMs = s.getTime();
    const eMs = e.getTime();
    if (sMs < minMs) minMs = sMs;
    if (eMs > maxMs) maxMs = eMs;
  }
  if (!Number.isFinite(minMs) || !Number.isFinite(maxMs) || maxMs <= minMs) {
    if (Number.isFinite(minMs) && Number.isFinite(maxMs) && maxMs === minMs) {
      const start = new Date(minMs);
      const end = new Date(maxMs + DAY_MS);
      return { start, end, granularity: 'day' };
    }
    return null;
  }
  const spanDays = (maxMs - minMs) / DAY_MS;
  // Tighter thresholds so each tier produces 4-12 native ticks before the
  // adaptive stepping in generateTicks kicks in. The previous 90/540 split
  // overcrowded the high end of the month tier (a 540-day span produced 18
  // monthly labels — visually mushed in a ~400px column). The new tiers:
  //   day:     ≤ 14 days   (≤ 14 ticks before subsampling)
  //   week:    ≤ 70 days   (≤ 10 ticks)
  //   month:   ≤ 365 days  (≤ 12 ticks)
  //   quarter: > 365 days  (≤ 12 ticks for ~3 years)
  const granularity: ScheduleGranularity =
    spanDays <= 14 ? 'day'
    : spanDays <= 70 ? 'week'
    : spanDays <= 365 ? 'month'
    : 'quarter';
  return { start: new Date(minMs), end: new Date(maxMs), granularity };
}

const METHOD_COLOR: Record<string, string> = {
  make: '#3b82f6',
  move: '#f59e0b',
  buy: '#22c55e',
  purchase: '#22c55e',
};

// A consolidated (cross-demand batched) WO is shown in a lighter shade of its method's base color.
const METHOD_COLOR_CONSOLIDATED: Record<string, string> = {
  make: '#93c5fd',
  move: '#fcd34d',
  buy: '#86efac',
  purchase: '#86efac',
};

export function methodColor(method: string | null | undefined, consolidated = false): string {
  if (!method) return '#71717a';
  const map = consolidated ? METHOD_COLOR_CONSOLIDATED : METHOD_COLOR;
  return map[method.toLowerCase()] ?? (consolidated ? '#a1a1aa' : '#71717a');
}

function formatDate(d: Date, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(d);
  } catch {
    return d.toISOString().slice(0, 10);
  }
}

export function ScheduleBar({
  start,
  end,
  horizon,
  method,
  locale,
  onClick,
  selected,
  colorOverride,
  consolidated,
  segments,
}: {
  start: string | null;
  end: string | null;
  horizon: Horizon;
  method: string | null | undefined;
  locale: string;
  onClick?: () => void;
  selected?: boolean;
  /** When set, overrides the method-derived bar color (used for pegging-graph highlighting). */
  colorOverride?: string;
  /** Cross-demand consolidated WO → lighter shade of the method's base color. */
  consolidated?: boolean;
  /** When a table row groups several work orders (e.g. a demand's July + August moves), each
   *  constituent's [start,end] is drawn as its OWN segment so the bar shows the real (often short)
   *  durations with the true gaps between them — instead of one solid span from min-start to
   *  max-end. Falls back to the single [start,end] when omitted. */
  segments?: { start: string | null; end: string | null }[];
}): JSX.Element | null {
  const hStart = horizon.start.getTime();
  const hEnd = horizon.end.getTime();
  const hSpan = Math.max(1, hEnd - hStart);

  // Build the list of drawable spans: the provided segments (deduped), else the single [start,end].
  const raw = (segments && segments.length ? segments : [{ start, end }]);
  const seen = new Set<string>();
  const spans = raw
    .map((s) => ({ sd: parseIso(s.start), ed: parseIso(s.end) }))
    .filter((s): s is { sd: Date; ed: Date } => {
      if (!s.sd || !s.ed) return false;
      const k = `${s.sd.getTime()}|${s.ed.getTime()}`;
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
  if (spans.length === 0) return null;

  const color = colorOverride ?? methodColor(method, consolidated);
  const handleClick = onClick
    ? (e: React.MouseEvent) => { e.stopPropagation(); onClick(); }
    : undefined;
  const cursor = onClick ? 'pointer' : undefined;
  const barHeight = selected ? 8 : 6;
  const barY = selected ? 1 : 2;

  return (
    <div
      style={{ minWidth: 140, width: '100%', padding: '2px 0', cursor }}
      onClick={handleClick}
    >
      <svg width="100%" height={10} preserveAspectRatio="none" style={{ display: 'block' }}>
        {spans.map(({ sd, ed }, i) => {
          const sClamped = Math.max(hStart, Math.min(hEnd, sd.getTime()));
          const eClamped = Math.max(hStart, Math.min(hEnd, ed.getTime()));
          const xPct = ((sClamped - hStart) / hSpan) * 100;
          const wPct = ((eClamped - sClamped) / hSpan) * 100;
          const durationDays = Math.max(0, Math.round((ed.getTime() - sd.getTime()) / DAY_MS));
          const tooltip = `${formatDate(sd, locale)} → ${formatDate(ed, locale)} (${durationDays}d)`;
          return sd.getTime() === ed.getTime() ? (
            <line key={i} x1={`${xPct}%`} x2={`${xPct}%`} y1={0} y2={10}
              stroke={color} strokeWidth={selected ? 3 : 2}>
              <title>{tooltip}</title>
            </line>
          ) : (
            <rect key={i} x={`${xPct}%`} y={barY} width={`${Math.max(0.4, wPct)}%`} height={barHeight}
              fill={color} stroke={selected ? '#e0f2fe' : undefined} strokeWidth={selected ? 1 : 0} rx={1}>
              <title>{tooltip}</title>
            </rect>
          );
        })}
      </svg>
    </div>
  );
}

function startOfUtcWeek(d: Date): Date {
  const u = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const dow = u.getUTCDay();
  const diff = (dow + 6) % 7; // ISO week starts Monday
  u.setUTCDate(u.getUTCDate() - diff);
  return u;
}

function startOfUtcMonth(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
}

function startOfUtcQuarter(d: Date): Date {
  const m = d.getUTCMonth();
  return new Date(Date.UTC(d.getUTCFullYear(), m - (m % 3), 1));
}

function isoWeekNumber(d: Date): number {
  const t = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const day = (t.getUTCDay() + 6) % 7;
  t.setUTCDate(t.getUTCDate() - day + 3);
  const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 4));
  const diff = (t.getTime() - yearStart.getTime()) / DAY_MS;
  return 1 + Math.round((diff - ((yearStart.getUTCDay() + 6) % 7)) / 7);
}

/** Target visible tick count. Adaptive stepping subsamples raw ticks when
 *  generateTicks would emit more than this — keeps labels readable in a
 *  ~400px column without overlap. */
const TARGET_TICK_COUNT = 10;

function generateTicks(horizon: Horizon, locale: string): { ms: number; label: string }[] {
  const { start, end, granularity } = horizon;
  const raw: { ms: number; label: string }[] = [];

  let dayFmt: Intl.DateTimeFormat
  let monthFmt: Intl.DateTimeFormat;
  try {
    dayFmt = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' });
    monthFmt = new Intl.DateTimeFormat(locale, { month: 'short', year: 'numeric', timeZone: 'UTC' });
  } catch {
    dayFmt = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', timeZone: 'UTC' });
    monthFmt = new Intl.DateTimeFormat('en', { month: 'short', year: 'numeric', timeZone: 'UTC' });
  }

  if (granularity === 'day') {
    const cur = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate()));
    while (cur.getTime() <= end.getTime()) {
      raw.push({ ms: cur.getTime(), label: dayFmt.format(cur) });
      cur.setUTCDate(cur.getUTCDate() + 1);
    }
  } else if (granularity === 'week') {
    const cur = startOfUtcWeek(start);
    while (cur.getTime() <= end.getTime()) {
      raw.push({ ms: cur.getTime(), label: `Wk ${isoWeekNumber(cur)}` });
      cur.setUTCDate(cur.getUTCDate() + 7);
    }
  } else if (granularity === 'month') {
    const cur = startOfUtcMonth(start);
    while (cur.getTime() <= end.getTime()) {
      raw.push({ ms: cur.getTime(), label: monthFmt.format(cur) });
      cur.setUTCMonth(cur.getUTCMonth() + 1);
    }
  } else {
    const cur = startOfUtcQuarter(start);
    while (cur.getTime() <= end.getTime()) {
      const q = Math.floor(cur.getUTCMonth() / 3) + 1;
      raw.push({ ms: cur.getTime(), label: `Q${q} ${cur.getUTCFullYear()}` });
      cur.setUTCMonth(cur.getUTCMonth() + 3);
    }
  }

  // Adaptive subsampling: when raw produces more than TARGET_TICK_COUNT
  // ticks, step by ⌈raw / TARGET⌉ so labels don't overlap. Keeps the first
  // tick anchored to the granularity boundary so labels remain meaningful.
  if (raw.length <= TARGET_TICK_COUNT) return raw;
  const step = Math.ceil(raw.length / TARGET_TICK_COUNT);
  return raw.filter((_, i) => i % step === 0);
}

export function ScheduleHorizonRuler({
  horizon,
  locale,
  caption,
}: {
  horizon: Horizon;
  locale: string;
  caption?: string;
}): JSX.Element {
  const hStart = horizon.start.getTime();
  const hEnd = horizon.end.getTime();
  const hSpan = Math.max(1, hEnd - hStart);
  const ticks = generateTicks(horizon, locale);

  return (
    <div style={{ width: '100%', minWidth: 140, display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
      {caption && (
        <span style={{ fontSize: '0.65rem', color: '#a1a1aa', flexShrink: 0 }}>{caption}</span>
      )}
      <div style={{ flex: 1, minWidth: 0 }}>
        <svg width="100%" height={20} preserveAspectRatio="none" style={{ display: 'block', overflow: 'visible' }}>
          <line x1="0%" x2="100%" y1={10} y2={10} stroke="#3d3d40" strokeWidth={1} />
          {ticks.map((t) => {
            const xPct = ((t.ms - hStart) / hSpan) * 100;
            if (xPct < -1 || xPct > 101) return null;
            return (
              <g key={t.ms}>
                <line x1={`${xPct}%`} x2={`${xPct}%`} y1={4} y2={16} stroke="#52525b" strokeWidth={1} />
                <text x={`${xPct}%`} y={1} dx={3} fontSize={9} fill="#a1a1aa" dominantBaseline="hanging">
                  {t.label}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}
