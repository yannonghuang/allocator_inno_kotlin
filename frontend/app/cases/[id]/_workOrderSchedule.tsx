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
  // Date-only strings (YYYY-MM-DD) are treated as UTC midnight by the spec,
  // but we display in local time — causing off-by-one in timezones behind UTC.
  // Parse them as local-midnight instead so display and arithmetic are consistent.
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim());
  if (m) return new Date(+m[1], +m[2] - 1, +m[3]);
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
          const eClamped = Math.max(hStart, Math.min(hEnd, ed.getTime() + DAY_MS));
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

/** Date-ruler for the BOR "Load" column header — tick marks + labels aligned to the same
 *  percentage x-axis as BorMiniTimeline bars, using CSS absolute positioning so text is
 *  never distorted by SVG stretching. */
export function BorTimelineRuler({
  buckets,
  woStart,
  woEnd,
}: {
  buckets: string[];
  woStart: string | null | undefined;
  woEnd: string | null | undefined;
}): JSX.Element | null {
  const woS = parseIso(woStart);
  const woE = parseIso(woEnd);
  if (!woS || !woE || buckets.length === 0) return null;

  const woStartMs = woS.getTime();
  const woEndMs = woE.getTime();

  // end_time is exclusive (d..d+1 occupies exactly day d, matching the backend's
  // ResourceScheduler/ResourceCalendar convention) — a bucket landing exactly on
  // woEnd belongs to the NEXT WO's window, not this one.
  const filtered: string[] = [];
  for (const b of buckets) {
    const d = parseIso(b);
    if (!d) continue;
    const ms = d.getTime();
    if (ms >= woStartMs && ms < woEndMs) filtered.push(b);
  }
  if (filtered.length === 0) return null;

  const n = filtered.length;
  const spanDays = Math.round((woEndMs - woStartMs) / DAY_MS);

  // Collect intermediate ticks based on span length.
  const ticks: { xPct: number; label: string }[] = [];
  const seen = new Set<string>();
  for (let i = 0; i < filtered.length; i++) {
    // First and last days are already shown as start/end anchors — skip to avoid duplicates.
    if (i === 0 || i === filtered.length - 1) continue;
    const d = parseIso(filtered[i]);
    if (!d) continue;
    let label = '';
    if (spanDays > 30) {
      if (d.getDate() === 1)
        label = new Intl.DateTimeFormat('en', { month: 'short' }).format(d);
    } else if (spanDays > 7) {
      if (d.getDay() === 1)
        label = filtered[i].slice(5, 10); // MM-DD
    } else {
      label = filtered[i].slice(5, 10);
    }
    if (label && !seen.has(label)) {
      seen.add(label);
      ticks.push({ xPct: ((i + 0.5) / n) * 100, label });
    }
  }

  const startLabel = woStart ? woStart.slice(0, 10) : '';
  const endLabel = woEnd ? woEnd.slice(0, 10) : '';

  return (
    <div style={{ position: 'relative', width: '100%', height: 18, minWidth: 80, overflow: 'visible' }}>
      {/* baseline */}
      <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0, height: 1, background: '#3f3f46' }} />
      {/* start anchor */}
      <span style={{ position: 'absolute', left: 0, bottom: 2, fontSize: '0.6rem', color: '#52525b', lineHeight: 1 }}>
        {startLabel}
      </span>
      {/* end anchor */}
      <span style={{ position: 'absolute', right: 0, bottom: 2, fontSize: '0.6rem', color: '#52525b', lineHeight: 1, transform: 'translateX(0)' }}>
        {endLabel}
      </span>
      {/* intermediate ticks */}
      {ticks.map((t, i) => (
        <React.Fragment key={i}>
          <div style={{ position: 'absolute', left: `${t.xPct}%`, bottom: 0, width: 1, height: 4, background: '#52525b' }} />
          <span style={{
            position: 'absolute', left: `${t.xPct}%`, bottom: 5,
            fontSize: '0.6rem', color: '#71717a', lineHeight: 1,
            transform: 'translateX(-50%)', whiteSpace: 'nowrap',
          }}>
            {t.label}
          </span>
        </React.Fragment>
      ))}
    </div>
  );
}

/** Mini histogram showing daily resource load vs. capacity, scoped to a WO's [start, end] window.
 *  Each bar represents one bucket-day; colour codes load fraction: green < 60%, amber 60-90%, red ≥ 90%. */
export function BorMiniTimeline({
  buckets,
  load,
  size,
  woStart,
  woEnd,
}: {
  buckets: string[];
  load: number[];
  size: number;
  woStart: string | null | undefined;
  woEnd: string | null | undefined;
}): JSX.Element | null {
  const maxH = 24;
  const woS = parseIso(woStart);
  const woE = parseIso(woEnd);
  if (!woS || !woE || size <= 0 || buckets.length === 0) return null;

  const woStartMs = woS.getTime();
  const woEndMs = woE.getTime();

  // end_time is exclusive — see the matching comment in BorTimelineRuler above.
  const filtered: { loadVal: number; date: string }[] = [];
  for (let i = 0; i < buckets.length; i++) {
    const d = parseIso(buckets[i]);
    if (!d) continue;
    const ms = d.getTime();
    if (ms >= woStartMs && ms < woEndMs) {
      filtered.push({ loadVal: load[i] ?? 0, date: buckets[i] });
    }
  }
  if (filtered.length === 0) return <span style={{ color: '#52525b', fontSize: '0.7rem' }}>—</span>;

  const n = filtered.length;
  const barW = 100 / n;

  return (
    <div style={{ minWidth: 80, width: '100%' }}>
      <svg width="100%" height={maxH + 4} preserveAspectRatio="none" style={{ display: 'block' }}>
        {/* capacity ceiling */}
        <line x1="0%" x2="100%" y1={2} y2={2} stroke="#52525b" strokeWidth={1} strokeDasharray="3 2" />
        {filtered.map(({ loadVal, date }, i) => {
          const frac = size > 0 ? Math.min(loadVal / size, 1.2) : 0;
          const barH = Math.max(1, Math.min(frac, 1.0) * maxH);
          const y = maxH + 2 - barH;
          const color = frac < 0.6 ? '#4ade80' : frac < 0.9 ? '#fbbf24' : '#f87171';
          return (
            <rect
              key={i}
              x={`${i * barW}%`}
              y={y}
              width={`${Math.max(0.5, barW - 0.3)}%`}
              height={barH}
              fill={color}
              opacity={0.85}
            >
              <title>{date}: load {loadVal.toFixed(1)} / {size}</title>
            </rect>
          );
        })}
      </svg>
    </div>
  );
}
