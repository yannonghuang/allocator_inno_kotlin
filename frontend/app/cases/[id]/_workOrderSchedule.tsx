'use client';

import React from 'react';

export type ScheduleGranularity = 'week' | 'month' | 'quarter';

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
      return { start, end, granularity: 'week' };
    }
    return null;
  }
  const spanDays = (maxMs - minMs) / DAY_MS;
  const granularity: ScheduleGranularity = spanDays <= 90 ? 'week' : spanDays <= 540 ? 'month' : 'quarter';
  return { start: new Date(minMs), end: new Date(maxMs), granularity };
}

const METHOD_COLOR: Record<string, string> = {
  make: '#3b82f6',
  move: '#f59e0b',
  buy: '#22c55e',
  purchase: '#22c55e',
};

function methodColor(method: string | null | undefined): string {
  if (!method) return '#71717a';
  return METHOD_COLOR[method.toLowerCase()] ?? '#71717a';
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
}): JSX.Element | null {
  const sd = parseIso(start);
  const ed = parseIso(end);
  if (!sd || !ed) return null;

  const hStart = horizon.start.getTime();
  const hEnd = horizon.end.getTime();
  const hSpan = Math.max(1, hEnd - hStart);

  const sClamped = Math.max(hStart, Math.min(hEnd, sd.getTime()));
  const eClamped = Math.max(hStart, Math.min(hEnd, ed.getTime()));

  const xPct = ((sClamped - hStart) / hSpan) * 100;
  const wPct = ((eClamped - sClamped) / hSpan) * 100;

  const color = colorOverride ?? methodColor(method);
  const durationDays = Math.max(0, Math.round((ed.getTime() - sd.getTime()) / DAY_MS));
  const tooltip = `${formatDate(sd, locale)} → ${formatDate(ed, locale)} (${durationDays}d)`;

  const isInstant = sd.getTime() === ed.getTime();
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
        {isInstant ? (
          <line
            x1={`${xPct}%`}
            x2={`${xPct}%`}
            y1={0}
            y2={10}
            stroke={color}
            strokeWidth={selected ? 3 : 2}
          >
            <title>{tooltip}</title>
          </line>
        ) : (
          <rect
            x={`${xPct}%`}
            y={barY}
            width={`${Math.max(0.4, wPct)}%`}
            height={barHeight}
            fill={color}
            stroke={selected ? '#e0f2fe' : undefined}
            strokeWidth={selected ? 1 : 0}
            rx={1}
          >
            <title>{tooltip}</title>
          </rect>
        )}
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

function generateTicks(horizon: Horizon, locale: string): { ms: number; label: string }[] {
  const { start, end, granularity } = horizon;
  const ticks: { ms: number; label: string }[] = [];

  if (granularity === 'week') {
    const cur = startOfUtcWeek(start);
    while (cur.getTime() <= end.getTime()) {
      ticks.push({ ms: cur.getTime(), label: `Wk ${isoWeekNumber(cur)}` });
      cur.setUTCDate(cur.getUTCDate() + 7);
    }
  } else if (granularity === 'month') {
    const cur = startOfUtcMonth(start);
    let monthFmt: Intl.DateTimeFormat;
    try {
      monthFmt = new Intl.DateTimeFormat(locale, { month: 'short', year: 'numeric', timeZone: 'UTC' });
    } catch {
      monthFmt = new Intl.DateTimeFormat('en', { month: 'short', year: 'numeric', timeZone: 'UTC' });
    }
    while (cur.getTime() <= end.getTime()) {
      ticks.push({ ms: cur.getTime(), label: monthFmt.format(cur) });
      cur.setUTCMonth(cur.getUTCMonth() + 1);
    }
  } else {
    const cur = startOfUtcQuarter(start);
    while (cur.getTime() <= end.getTime()) {
      const q = Math.floor(cur.getUTCMonth() / 3) + 1;
      ticks.push({ ms: cur.getTime(), label: `Q${q} ${cur.getUTCFullYear()}` });
      cur.setUTCMonth(cur.getUTCMonth() + 3);
    }
  }
  return ticks;
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
