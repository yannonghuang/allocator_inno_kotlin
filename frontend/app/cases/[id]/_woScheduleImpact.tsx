'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import {
  WoScheduleEvent,
  WoScheduleSelector,
  WoScheduleImpactResult,
  WoScheduleRun,
  WoAvailabilityResult,
  listWoScheduleEvents,
  createWoScheduleEvent,
  updateWoScheduleEvent,
  deleteWoScheduleEvent,
  listWoScheduleRuns,
  analyzeWoScheduleImpact,
  analyzeWoAvailability,
  PlanResult,
  WorkOrder,
} from '../../../lib/api';

type Granularity = 'day' | 'week' | 'month' | 'quarter';

const DAY_MS = 86_400_000;

// ── bucket math (UI helper; backend takes explicit start/end) ─────────────────

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

function isoWeek(date: Date): { year: number; week: number } {
  const d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const dayNum = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const week = Math.ceil((((d.getTime() - yearStart.getTime()) / DAY_MS) + 1) / 7);
  return { year: d.getUTCFullYear(), week };
}

export function bucketOfDate(d: Date, granularity: Granularity): string {
  const y = d.getFullYear();
  const m = d.getMonth() + 1;
  const day = d.getDate();
  if (granularity === 'day') return `${y}-${pad2(m)}-${pad2(day)}`;
  if (granularity === 'month') return `${y}-${pad2(m)}`;
  if (granularity === 'quarter') {
    const q = Math.floor((m - 1) / 3) + 1;
    return `${y}-Q${q}`;
  }
  const wk = isoWeek(d);
  return `${wk.year}-W${pad2(wk.week)}`;
}

/** Resolve a bucket key (granularity-specific) to its inclusive [start, end] dates. */
function bucketRangeFromKey(bucketKey: string, granularity: Granularity): { start: Date; end: Date } | null {
  if (granularity === 'day') {
    const m = bucketKey.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (!m) return null;
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    return { start: d, end: d };
  }
  if (granularity === 'month') {
    const m = bucketKey.match(/^(\d{4})-(\d{2})$/);
    if (!m) return null;
    const y = Number(m[1]); const mo = Number(m[2]);
    const start = new Date(y, mo - 1, 1);
    const end = new Date(y, mo, 0); // last day of month
    return { start, end };
  }
  if (granularity === 'quarter') {
    const m = bucketKey.match(/^(\d{4})-Q([1-4])$/);
    if (!m) return null;
    const y = Number(m[1]); const q = Number(m[2]);
    const startMo = (q - 1) * 3;
    const start = new Date(y, startMo, 1);
    const end = new Date(y, startMo + 3, 0);
    return { start, end };
  }
  // week: yyyy-W##
  const m = bucketKey.match(/^(\d{4})-W(\d{1,2})$/);
  if (!m) return null;
  // Find the Monday of that ISO week. Use Jan 4 anchor (always in week 1).
  const y = Number(m[1]); const w = Number(m[2]);
  const jan4 = new Date(y, 0, 4);
  const jan4Day = jan4.getDay() || 7;
  const week1Mon = new Date(jan4); week1Mon.setDate(jan4.getDate() - (jan4Day - 1));
  const start = new Date(week1Mon); start.setDate(week1Mon.getDate() + (w - 1) * 7);
  const end = new Date(start); end.setDate(start.getDate() + 6);
  return { start, end };
}

export function parseIsoDate(s: string | null | undefined): Date | null {
  if (!s) return null;
  const raw = s.trim().slice(0, 10);
  const m = raw.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(d.getTime()) ? null : d;
}

function formatIsoDate(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

function bucketsForWorkOrders(wos: WorkOrder[], granularity: Granularity): string[] {
  const set = new Set<string>();
  for (const wo of wos) {
    const d = parseIsoDate(wo.start_time);
    if (!d) continue;
    set.add(bucketOfDate(d, granularity));
  }
  return Array.from(set).sort();
}

// ── presentation helpers ─────────────────────────────────────────────────────

function fmtDate(s: string | null | undefined): string {
  if (!s) return '—';
  return s.slice(0, 10);
}

const STATUS_COLOR: Record<string, string> = {
  delivery_delayed: '#fbbf24',
  newly_late_vs_due: '#f87171',
  no_change: '#a1a1aa',
};

// ── filtering: UI-side ───────────────────────────────────────────────────────

type Filter = {
  granularity: Granularity;
  bucketKey: string;       // '' = unset (derive)
  productId: string;
  prodArea: string;
  locationId: string;
  method: string;
};

function emptyFilter(): Filter {
  return {
    granularity: 'week',
    bucketKey: '',
    productId: '',
    prodArea: '',
    locationId: '',
    method: '',
  };
}

/** Always hide synthetic VirtualProduct_* WOs from the impact panel — they're
 *  planner-internal placeholders, not real shop-floor work orders. */
function isVirtualProduct(productId: string | null | undefined): boolean {
  return !!productId && productId.startsWith('VirtualProduct_');
}

function applyFilter(wos: WorkOrder[], f: Filter): WorkOrder[] {
  let bucketRange: { start: Date; end: Date } | null = null;
  if (f.bucketKey) bucketRange = bucketRangeFromKey(f.bucketKey, f.granularity);
  return wos.filter(w => {
    if (!w.wo_group_id) return false;
    if (isVirtualProduct(w.product_id)) return false;
    if (f.productId && w.product_id !== f.productId) return false;
    if (f.prodArea && (w.prod_area ?? '') !== f.prodArea) return false;
    if (f.locationId && w.location_id !== f.locationId) return false;
    if (f.method && (w.method ?? '') !== f.method) return false;
    if (bucketRange) {
      const start = parseIsoDate(w.start_time);
      if (!start) return false;
      if (start < bucketRange.start || start > bucketRange.end) return false;
    }
    return true;
  });
}

/** Aggregated preview row — one entry per wo_group_id. The planner emits one
 *  row per lot, so a WO with N lots shows up N times in `work_orders`. We
 *  collapse them here so the user sees one row per WO. */
type WoSummary = {
  wo_group_id: string;
  product_id: string;
  location_id: string;
  method: string;
  prod_area: string | null;
  /** Min start across the WO's lots. */
  start_time: string | null;
  /** Max end across the WO's lots. */
  end_time: string | null;
  /** Sum of lot quantities. */
  quantity: number;
  /** Number of lots collapsed into this row. */
  lot_count: number;
};

function aggregateByWoGroup(lots: WorkOrder[]): WoSummary[] {
  const byGid = new Map<string, WoSummary>();
  for (const w of lots) {
    const gid = w.wo_group_id as string;
    const existing = byGid.get(gid);
    if (!existing) {
      byGid.set(gid, {
        wo_group_id: gid,
        product_id: w.product_id,
        location_id: w.location_id,
        method: w.method ?? '',
        prod_area: w.prod_area ?? null,
        start_time: w.start_time ?? null,
        end_time: w.end_time ?? null,
        quantity: w.quantity ?? 0,
        lot_count: 1,
      });
    } else {
      const sNew = parseIsoDate(w.start_time);
      const sCur = parseIsoDate(existing.start_time);
      if (sNew && (!sCur || sNew < sCur)) existing.start_time = w.start_time ?? existing.start_time;
      const eNew = parseIsoDate(w.end_time);
      const eCur = parseIsoDate(existing.end_time);
      if (eNew && (!eCur || eNew > eCur)) existing.end_time = w.end_time ?? existing.end_time;
      existing.quantity += w.quantity ?? 0;
      existing.lot_count += 1;
    }
  }
  // Stable order: by start_time asc, then gid.
  return Array.from(byGid.values()).sort((a, b) => {
    const sa = a.start_time ?? '';
    const sb = b.start_time ?? '';
    if (sa !== sb) return sa < sb ? -1 : 1;
    return a.wo_group_id < b.wo_group_id ? -1 : 1;
  });
}

/** Default bucketStart/end derived from selected WOs:
 *    bucketStart = min(start_time), bucketEnd = max(end_time).
 *  Falls back to today/today if the list is empty. Accepts any row shape
 *  with `start_time`/`end_time` strings (lot rows or aggregated WoSummary). */
function deriveBucketDates(wos: { start_time: string | null; end_time: string | null }[]): { start: string; end: string } {
  let minD: Date | null = null;
  let maxD: Date | null = null;
  for (const w of wos) {
    const s = parseIsoDate(w.start_time);
    const e = parseIsoDate(w.end_time);
    if (s && (!minD || s < minD)) minD = s;
    if (e && (!maxD || e > maxD)) maxD = e;
    if (s && (!maxD || s > maxD)) maxD = s;
  }
  const today = new Date();
  return {
    start: minD ? formatIsoDate(minD) : formatIsoDate(today),
    end: maxD ? formatIsoDate(maxD) : formatIsoDate(today),
  };
}

// ── component ────────────────────────────────────────────────────────────────

type DraftEvent = {
  filter: Filter;
  selectedGids: Set<string>;
  changeMode: 'days' | 'date';
  delayDays: number;
  delayToDate: string;
  note: string;
};

function emptyDraft(): DraftEvent {
  return {
    filter: emptyFilter(),
    selectedGids: new Set(),
    changeMode: 'days',
    delayDays: 7,
    delayToDate: '',
    note: '',
  };
}

function buildSelectorFromDraft(d: DraftEvent, wos: WorkOrder[]): WoScheduleSelector | null {
  const gids = Array.from(d.selectedGids);
  if (gids.length === 0) return null;
  const picked = wos.filter(w => w.wo_group_id && d.selectedGids.has(w.wo_group_id));
  let start: string;
  let end: string;
  if (d.filter.bucketKey) {
    const r = bucketRangeFromKey(d.filter.bucketKey, d.filter.granularity);
    if (!r) return null;
    start = formatIsoDate(r.start);
    end = formatIsoDate(r.end);
  } else {
    const derived = deriveBucketDates(picked);
    start = derived.start;
    end = derived.end;
  }
  return { bucketStart: start, bucketEnd: end, woGroupIds: gids };
}

/** Edit-mode draft (only delay + note are editable on a saved event;
 *  selectors are fixed once an event is created — re-pick the WO set by
 *  deleting + creating a new event). */
type EditDraft = {
  changeMode: 'days' | 'date';
  delayDays: number;
  delayToDate: string;
  note: string;
};

function eventToEditDraft(ev: WoScheduleEvent): EditDraft {
  return {
    changeMode: ev.delayDays != null ? 'days' : 'date',
    delayDays: ev.delayDays ?? 7,
    delayToDate: ev.delayToDate ?? '',
    note: ev.note ?? '',
  };
}

export function WoScheduleImpactPanel({
  caseId,
  baselinePlan,
  baselinePlanRunId,
}: {
  caseId: number;
  baselinePlan: PlanResult | null;
  baselinePlanRunId?: number | null;
}) {
  const t = useTranslations('planning.woScheduleImpact');
  const [events, setEvents] = React.useState<WoScheduleEvent[]>([]);
  const [draft, setDraft] = React.useState<DraftEvent | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [draftError, setDraftError] = React.useState<string | null>(null);

  // Per-event lifecycle state — keyed by event id so each card runs independently.
  const [results, setResults] = React.useState<Record<number, WoScheduleImpactResult>>({});
  const [loadingByEv, setLoadingByEv] = React.useState<Record<number, boolean>>({});
  const [errorByEv, setErrorByEv] = React.useState<Record<number, string | null>>({});
  const [editingByEv, setEditingByEv] = React.useState<Record<number, EditDraft | undefined>>({});
  const [savingByEv, setSavingByEv] = React.useState<Record<number, boolean>>({});
  const [collapsedByEv, setCollapsedByEv] = React.useState<Record<number, boolean>>({});

  const reload = React.useCallback(async () => {
    if (!caseId) return;
    try {
      const list = await listWoScheduleEvents(caseId);
      setEvents(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [caseId]);

  React.useEffect(() => { void reload(); }, [reload]);

  const wos = baselinePlan?.work_orders ?? [];

  async function handleSave() {
    if (!draft) return;
    const sel = buildSelectorFromDraft(draft, wos);
    if (!sel) {
      setDraftError(t('error.noSelection'));
      return;
    }
    if (draft.changeMode === 'days' && draft.delayDays <= 0) {
      setDraftError(t('error.noShift'));
      return;
    }
    if (draft.changeMode === 'date' && !draft.delayToDate) {
      setDraftError(t('error.noShift'));
      return;
    }
    setDraftError(null);
    try {
      await createWoScheduleEvent(caseId, {
        selectors: [sel],
        delayDays: draft.changeMode === 'days' ? draft.delayDays : null,
        delayToDate: draft.changeMode === 'date' ? draft.delayToDate : null,
        note: draft.note.trim() || null,
      });
      setDraft(null);
      await reload();
    } catch (e) {
      setDraftError(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleDelete(id: number) {
    try {
      await deleteWoScheduleEvent(caseId, id);
      await reload();
      setResults(prev => { const o = { ...prev }; delete o[id]; return o; });
      setErrorByEv(prev => { const o = { ...prev }; delete o[id]; return o; });
      setEditingByEv(prev => { const o = { ...prev }; delete o[id]; return o; });
      setCollapsedByEv(prev => { const o = { ...prev }; delete o[id]; return o; });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleSaveEdit(ev: WoScheduleEvent) {
    const ed = editingByEv[ev.id];
    if (!ed) return;
    if (ed.changeMode === 'days' && ed.delayDays <= 0) {
      setErrorByEv(prev => ({ ...prev, [ev.id]: t('error.noShift') }));
      return;
    }
    if (ed.changeMode === 'date' && !ed.delayToDate) {
      setErrorByEv(prev => ({ ...prev, [ev.id]: t('error.noShift') }));
      return;
    }
    setSavingByEv(prev => ({ ...prev, [ev.id]: true }));
    setErrorByEv(prev => ({ ...prev, [ev.id]: null }));
    try {
      const updated = await updateWoScheduleEvent(caseId, ev.id, {
        selectors: ev.selectors,                   // selectors are immutable on edit
        delayDays: ed.changeMode === 'days' ? ed.delayDays : null,
        delayToDate: ed.changeMode === 'date' ? ed.delayToDate : null,
        note: ed.note.trim() || null,
      });
      setEvents(evs => evs.map(e => e.id === ev.id ? updated : e));
      setEditingByEv(prev => { const o = { ...prev }; delete o[ev.id]; return o; });
      // Edit invalidates the prior analysis result.
      setResults(prev => { const o = { ...prev }; delete o[ev.id]; return o; });
    } catch (e) {
      setErrorByEv(prev => ({ ...prev, [ev.id]: e instanceof Error ? e.message : String(e) }));
    } finally {
      setSavingByEv(prev => ({ ...prev, [ev.id]: false }));
    }
  }

  async function handleAnalyze(ev: WoScheduleEvent) {
    setLoadingByEv(prev => ({ ...prev, [ev.id]: true }));
    setErrorByEv(prev => ({ ...prev, [ev.id]: null }));
    try {
      const result = await analyzeWoScheduleImpact({
        selectors: ev.selectors,
        delayDays: ev.delayDays,
        delayToDate: ev.delayToDate,
        planRunId: baselinePlanRunId ?? null,
        caseId,
        persist: true,
        note: ev.note,
        woScheduleEventId: ev.id,
      });
      setResults(prev => ({ ...prev, [ev.id]: result }));
      setCollapsedByEv(prev => ({ ...prev, [ev.id]: false }));
      // Refresh persisted run history for this event so the new run shows up.
      void refreshRuns(ev.id);
    } catch (e) {
      setErrorByEv(prev => ({ ...prev, [ev.id]: e instanceof Error ? e.message : String(e) }));
    } finally {
      setLoadingByEv(prev => ({ ...prev, [ev.id]: false }));
    }
  }

  // ── Persistent runs history (per event) ─────────────────────────────────────
  const [runsByEv, setRunsByEv] = React.useState<Record<number, WoScheduleRun[]>>({});
  const [runsHistoryOpen, setRunsHistoryOpen] = React.useState<Record<number, boolean>>({});
  const [runsLoadingByEv, setRunsLoadingByEv] = React.useState<Record<number, boolean>>({});

  const refreshRuns = React.useCallback(async (eventId: number) => {
    setRunsLoadingByEv(prev => ({ ...prev, [eventId]: true }));
    try {
      const list = await listWoScheduleRuns(caseId, eventId);
      setRunsByEv(prev => ({ ...prev, [eventId]: list }));
    } catch (e) {
      setErrorByEv(prev => ({ ...prev, [eventId]: e instanceof Error ? e.message : String(e) }));
    } finally {
      setRunsLoadingByEv(prev => ({ ...prev, [eventId]: false }));
    }
  }, [caseId]);

  function toggleHistory(eventId: number) {
    const next = !(runsHistoryOpen[eventId] ?? false);
    setRunsHistoryOpen(prev => ({ ...prev, [eventId]: next }));
    if (next && !runsByEv[eventId]) void refreshRuns(eventId);
  }

  if (!baselinePlan) {
    return (
      <section style={{ padding: '1rem', color: '#a1a1aa' }}>
        {t('noBaselinePlan')}
      </section>
    );
  }

  return (
    <section style={{ padding: '1rem', maxWidth: 1200 }}>
      <h2 style={{ marginTop: 0 }}>{t('title')}</h2>
      <p style={{ color: '#a1a1aa', fontSize: '0.85rem', marginBottom: '1rem' }}>{t('description')}</p>

      {error && (
        <div style={{ background: 'rgba(248,113,113,0.12)', border: '1px solid rgba(248,113,113,0.4)', padding: '0.5rem 0.75rem', borderRadius: 4, marginBottom: '1rem', color: '#fca5a5' }}>
          {error}
        </div>
      )}

      {draft === null ? (
        <button
          type="button"
          onClick={() => setDraft(emptyDraft())}
          style={{ marginBottom: '1.25rem' }}
        >
          {t('newEvent')}
        </button>
      ) : (
        <DraftForm
          draft={draft}
          setDraft={setDraft}
          allWos={wos}
          caseId={caseId}
          baselinePlanRunId={baselinePlanRunId ?? null}
          onCancel={() => { setDraft(null); setDraftError(null); }}
          onSave={handleSave}
          error={draftError}
        />
      )}

      {events.length === 0 ? (
        <p style={{ color: '#71717a', fontSize: '0.85rem' }}>{t('noEvents')}</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
          {events.map(ev => {
            const editing = editingByEv[ev.id];
            const saving = savingByEv[ev.id] ?? false;
            const loading = loadingByEv[ev.id] ?? false;
            const evError = errorByEv[ev.id];
            const collapsed = collapsedByEv[ev.id] ?? false;
            const result = results[ev.id];
            return (
              <li
                key={ev.id}
                style={{
                  border: '1px solid #3d3d40',
                  borderRadius: 6,
                  padding: '0.75rem',
                  background: '#1f1f22',
                }}
              >
                {editing ? (
                  <EditEventForm
                    ev={ev}
                    allWos={wos}
                    edit={editing}
                    saving={saving}
                    error={evError}
                    setEdit={(patch) => setEditingByEv(prev => ({ ...prev, [ev.id]: { ...prev[ev.id]!, ...patch } }))}
                    onSave={() => handleSaveEdit(ev)}
                    onCancel={() => {
                      setEditingByEv(prev => { const o = { ...prev }; delete o[ev.id]; return o; });
                      setErrorByEv(prev => ({ ...prev, [ev.id]: null }));
                    }}
                  />
                ) : (
                  <>
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'center' }}>
                      <div
                        style={{ flex: 1, display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 6, cursor: 'pointer', minWidth: 0 }}
                        onClick={() => setCollapsedByEv(prev => ({ ...prev, [ev.id]: !collapsed }))}
                      >
                        <span style={{ color: '#71717a', fontSize: '0.78rem', userSelect: 'none' }}>{collapsed ? '▸' : '▾'}</span>
                        <strong style={{ color: '#e4e4e7', fontSize: '0.85rem' }}>#{ev.id}</strong>
                        {ev.selectors.map((s, i) => (
                          <span key={i} style={{ background: '#27272a', color: '#a1a1aa', borderRadius: 4, padding: '2px 7px', fontSize: '0.74rem' }}>
                            {s.bucketStart}…{s.bucketEnd} · {s.woGroupIds.length} {t('selector.wos')}
                          </span>
                        ))}
                        <span style={{ background: ev.delayDays != null ? '#7c3aed' : '#0ea5e9', color: '#fff', borderRadius: 6, padding: '2px 7px', fontSize: '0.75rem' }}>
                          {ev.delayDays != null ? `+${ev.delayDays}d` : `→ ${ev.delayToDate}`}
                        </span>
                        {ev.note && <span style={{ color: '#a1a1aa', fontSize: '0.78rem' }}>{ev.note}</span>}
                        <span style={{ color: '#52525b', fontSize: '0.72rem', marginLeft: 'auto' }}>{new Date(ev.createdAt).toLocaleString()}</span>
                      </div>
                      <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                        <button type="button" className="secondary" style={{ fontSize: '0.78rem', padding: '3px 10px' }}
                          onClick={() => toggleHistory(ev.id)}
                          title={t('history.tooltip')}
                        >{t('history.button')}</button>
                        <button type="button" className="secondary" style={{ fontSize: '0.78rem', padding: '3px 10px' }}
                          onClick={() => {
                            setCollapsedByEv(prev => ({ ...prev, [ev.id]: false }));
                            setEditingByEv(prev => ({ ...prev, [ev.id]: eventToEditDraft(ev) }));
                          }}
                        >{t('edit')}</button>
                        <button type="button" className="secondary" style={{ fontSize: '0.78rem', padding: '3px 10px', color: '#f87171', borderColor: '#f87171' }}
                          onClick={() => handleDelete(ev.id)}
                        >{t('delete')}</button>
                        <button type="button" disabled={loading} style={{ fontSize: '0.78rem', padding: '3px 10px' }}
                          onClick={() => handleAnalyze(ev)}
                        >{loading ? t('analyzing') : t('submit')}</button>
                      </div>
                    </div>

                    {evError && (
                      <p style={{ color: '#fca5a5', fontSize: '0.78rem', margin: '0.5rem 0 0' }}>{evError}</p>
                    )}

                    {!collapsed && result && <ResultsTable result={result} />}

                    {(runsHistoryOpen[ev.id] ?? false) && (
                      <RunsHistory
                        runs={runsByEv[ev.id] ?? []}
                        loading={runsLoadingByEv[ev.id] ?? false}
                      />
                    )}
                  </>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

// ── inline edit form for a saved event ────────────────────────────────────────

/** Derive the distinct dimension values that characterize a saved selector
 *  by looking up the WOs in the baseline plan. The selector itself only
 *  stores (bucketStart, bucketEnd, woGroupIds) — the original filter
 *  conditions (product / prod_area / location / method) used at create time
 *  weren't persisted, but they can be recovered from the WOs the gids point
 *  to. Returns sorted distinct lists per dimension. */
function selectorConditions(s: WoScheduleSelector, allWos: WorkOrder[]): {
  products: string[]; prodAreas: string[]; locations: string[]; methods: string[]; missingCount: number;
} {
  const gids = new Set(s.woGroupIds);
  const products = new Set<string>();
  const prodAreas = new Set<string>();
  const locations = new Set<string>();
  const methods = new Set<string>();
  const seenGids = new Set<string>();
  for (const w of allWos) {
    if (!w.wo_group_id || !gids.has(w.wo_group_id)) continue;
    seenGids.add(w.wo_group_id);
    products.add(w.product_id);
    if (w.prod_area) prodAreas.add(w.prod_area);
    locations.add(w.location_id);
    if (w.method) methods.add(w.method);
  }
  const missingCount = s.woGroupIds.length - seenGids.size;
  return {
    products: Array.from(products).sort(),
    prodAreas: Array.from(prodAreas).sort(),
    locations: Array.from(locations).sort(),
    methods: Array.from(methods).sort(),
    missingCount,
  };
}

function EditEventForm({
  ev, allWos, edit, saving, error, setEdit, onSave, onCancel,
}: {
  ev: WoScheduleEvent;
  allWos: WorkOrder[];
  edit: EditDraft;
  saving: boolean;
  error: string | null;
  setEdit: (patch: Partial<EditDraft>) => void;
  onSave: () => void;
  onCancel: () => void;
}) {
  const t = useTranslations('planning.woScheduleImpact');
  const totalWoCount = ev.selectors.reduce((acc, s) => acc + s.woGroupIds.length, 0);

  const chipBase: React.CSSProperties = {
    background: '#27272a',
    color: '#e4e4e7',
    borderRadius: 4,
    padding: '1px 7px',
    fontSize: '0.72rem',
    border: '1px solid #3d3d40',
  };
  function ConditionRow({ label, values }: { label: string; values: string[] }) {
    if (values.length === 0) return null;
    return (
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 4 }}>
        <span style={{ color: '#71717a', minWidth: 68, fontSize: '0.72rem' }}>{label}:</span>
        {values.map(v => (
          <span key={v} style={chipBase}>{v}</span>
        ))}
      </div>
    );
  }

  return (
    <div>
      <div style={{ fontSize: '0.78rem', color: '#71717a', marginBottom: '0.5rem' }}>
        <div style={{ marginBottom: 4 }}>
          {t('editForm.summary')}: #{ev.id} · {ev.selectors.length} {t('editForm.selector', { count: ev.selectors.length })} · {totalWoCount} {t('selector.wos')}
        </div>
        <div style={{ fontSize: '0.72rem', color: '#52525b', marginBottom: 6 }}>{t('editForm.selectorsImmutable')}</div>

        {/* Read-only selector details so the user can see exactly what the event covers. */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {ev.selectors.map((s, i) => {
            const cond = selectorConditions(s, allWos);
            return (
              <div
                key={i}
                style={{
                  background: '#161618',
                  border: '1px solid #2d2d31',
                  borderRadius: 4,
                  padding: '0.45rem 0.6rem',
                  fontSize: '0.74rem',
                  color: '#a1a1aa',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 4,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
                  <span style={{ color: '#71717a' }}>{t('editForm.bucket')}:</span>
                  <code style={{ color: '#e4e4e7' }}>{s.bucketStart}</code>
                  <span>→</span>
                  <code style={{ color: '#e4e4e7' }}>{s.bucketEnd}</code>
                  <span style={{ marginLeft: 8, color: '#71717a' }}>·</span>
                  <span style={{ color: '#71717a' }}>{t('editForm.woGroupIds')}:</span>
                  <span style={{ color: '#e4e4e7' }}>{s.woGroupIds.length}</span>
                  {cond.missingCount > 0 && (
                    <span style={{ color: '#fbbf24', fontSize: '0.7rem' }}>
                      {t('editForm.missingFromBaseline', { count: cond.missingCount })}
                    </span>
                  )}
                </div>

                <ConditionRow label={t('selector.product')} values={cond.products} />
                <ConditionRow label={t('selector.prodArea')} values={cond.prodAreas} />
                <ConditionRow label={t('selector.location')} values={cond.locations} />
                <ConditionRow label={t('selector.method')} values={cond.methods} />

                {s.woGroupIds.length > 0 && (
                  <details>
                    <summary style={{ cursor: 'pointer', color: '#71717a', fontSize: '0.72rem' }}>{t('editForm.showWos')}</summary>
                    <div
                      style={{
                        marginTop: 4,
                        maxHeight: 100,
                        overflow: 'auto',
                        fontFamily: 'ui-monospace, monospace',
                        fontSize: '0.7rem',
                        color: '#d4d4d8',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-all',
                      }}
                    >
                      {s.woGroupIds.join(', ')}
                    </div>
                  </details>
                )}
              </div>
            );
          })}
        </div>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.6rem 1rem', alignItems: 'flex-end', marginBottom: '0.5rem' }}>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('change.label')}</label>
          <select
            style={inputStyle}
            value={edit.changeMode}
            onChange={e => setEdit({ changeMode: e.target.value as 'days' | 'date' })}
          >
            <option value="days">{t('change.byDays')}</option>
            <option value="date">{t('change.toDate')}</option>
          </select>
        </div>
        {edit.changeMode === 'days' ? (
          <div style={fieldStyle}>
            <label style={labelStyle}>{t('change.byDays')}</label>
            <input
              type="number"
              min={1}
              style={{ ...inputStyle, width: 120 }}
              value={edit.delayDays}
              onChange={e => setEdit({ delayDays: Math.max(0, Number(e.target.value) || 0) })}
            />
          </div>
        ) : (
          <div style={fieldStyle}>
            <label style={labelStyle}>{t('change.toDate')}</label>
            <input
              type="date"
              style={{ ...inputStyle, width: 160 }}
              value={edit.delayToDate}
              onChange={e => setEdit({ delayToDate: e.target.value })}
            />
          </div>
        )}
        <div style={{ flex: 1, minWidth: 200, ...fieldStyle }}>
          <label style={labelStyle}>{t('note')}</label>
          <input
            style={inputStyle}
            value={edit.note}
            onChange={e => setEdit({ note: e.target.value })}
          />
        </div>
      </div>
      {error && <div style={{ color: '#fca5a5', fontSize: '0.8rem', marginBottom: '0.5rem' }}>{error}</div>}
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" disabled={saving} onClick={onSave}>{saving ? t('analyzing') : t('save')}</button>
        <button type="button" className="secondary" onClick={onCancel}>{t('cancel')}</button>
      </div>
    </div>
  );
}

// ── draft form ────────────────────────────────────────────────────────────────

const labelStyle: React.CSSProperties = { fontSize: '0.78rem', color: '#a1a1aa', display: 'block', marginBottom: 2 };
const fieldStyle: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 2 };
const inputStyle: React.CSSProperties = { background: '#27272a', border: '1px solid #3d3d40', color: '#e4e4e7', padding: '0.3rem', borderRadius: 4, fontSize: '0.85rem' };

function DraftForm({
  draft, setDraft, allWos, caseId, baselinePlanRunId, onCancel, onSave, error,
}: {
  draft: DraftEvent;
  setDraft: (d: DraftEvent) => void;
  allWos: WorkOrder[];
  caseId: number;
  baselinePlanRunId: number | null;
  onCancel: () => void;
  onSave: () => void;
  error: string | null;
}) {
  const t = useTranslations('planning.woScheduleImpact');

  // Distinct option lists derived from the full plan WOs (not just filtered),
  // so users can re-pick after narrowing or widening. VirtualProduct_* WOs
  // are filtered out everywhere — they don't represent real work orders.
  const realWos = React.useMemo(() => allWos.filter(w => !isVirtualProduct(w.product_id)), [allWos]);
  const products = React.useMemo(() => {
    const s = new Set<string>();
    for (const w of realWos) s.add(w.product_id);
    return Array.from(s).sort();
  }, [realWos]);
  const prodAreas = React.useMemo(() => {
    const s = new Set<string>();
    for (const w of realWos) if (w.prod_area) s.add(w.prod_area);
    return Array.from(s).sort();
  }, [realWos]);
  const locations = React.useMemo(() => {
    const s = new Set<string>();
    for (const w of realWos) s.add(w.location_id);
    return Array.from(s).sort();
  }, [realWos]);
  const bucketKeys = React.useMemo(
    () => bucketsForWorkOrders(realWos, draft.filter.granularity),
    [realWos, draft.filter.granularity],
  );

  // Live filtered + aggregated list. `applyFilter` returns lot-level rows; we
  // then collapse by wo_group_id so the preview shows one row per WO.
  const matching = React.useMemo(
    () => aggregateByWoGroup(applyFilter(allWos, draft.filter)),
    [allWos, draft.filter],
  );

  // Drop selections that are no longer in the matching set when filter changes.
  React.useEffect(() => {
    const valid = new Set(matching.map(w => w.wo_group_id).filter(Boolean) as string[]);
    let changed = false;
    const next = new Set<string>();
    draft.selectedGids.forEach(g => {
      if (valid.has(g)) next.add(g);
      else changed = true;
    });
    if (changed) setDraft({ ...draft, selectedGids: next });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matching]);

  // Live bucket preview: shows which (bucketStart, bucketEnd) will be sent to the backend.
  // - If user picked a bucket key, use that bucket's range.
  // - Else derive from min(start)/max(end) of currently-selected WOs.
  const bucketPreview = React.useMemo<{ start: string; end: string; source: 'key' | 'derived' } | null>(() => {
    if (draft.filter.bucketKey) {
      const r = bucketRangeFromKey(draft.filter.bucketKey, draft.filter.granularity);
      if (r) return { start: formatIsoDate(r.start), end: formatIsoDate(r.end), source: 'key' };
    }
    if (draft.selectedGids.size === 0) return null;
    const picked = matching.filter(w => w.wo_group_id && draft.selectedGids.has(w.wo_group_id));
    const d = deriveBucketDates(picked);
    return { start: d.start, end: d.end, source: 'derived' };
  }, [draft.filter.bucketKey, draft.filter.granularity, draft.selectedGids, matching]);

  const allSelected = matching.length > 0 && matching.every(w => w.wo_group_id && draft.selectedGids.has(w.wo_group_id));

  function setFilter(patch: Partial<Filter>) {
    setDraft({ ...draft, filter: { ...draft.filter, ...patch } });
  }
  function toggleGid(g: string) {
    const next = new Set(draft.selectedGids);
    if (next.has(g)) next.delete(g); else next.add(g);
    setDraft({ ...draft, selectedGids: next });
  }
  function selectAll() {
    const next = new Set<string>();
    for (const w of matching) if (w.wo_group_id) next.add(w.wo_group_id);
    setDraft({ ...draft, selectedGids: next });
  }
  function deselectAll() {
    setDraft({ ...draft, selectedGids: new Set() });
  }

  return (
    <div style={{ border: '1px solid #3b82f6', borderRadius: 6, padding: '0.875rem', background: 'rgba(59,130,246,0.05)', marginBottom: '1.25rem' }}>
      {/* Filter row */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.6rem 1rem', marginBottom: '0.75rem' }}>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('selector.product')}</label>
          <input
            list="wo-impact-products"
            style={{ ...inputStyle, minWidth: 180 }}
            value={draft.filter.productId}
            onChange={e => setFilter({ productId: e.target.value })}
            placeholder={t('selector.any')}
          />
          <datalist id="wo-impact-products">
            {products.map(p => <option key={p} value={p} />)}
          </datalist>
        </div>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('selector.prodArea')}</label>
          <select style={inputStyle} value={draft.filter.prodArea} onChange={e => setFilter({ prodArea: e.target.value })}>
            <option value="">{t('selector.any')}</option>
            {prodAreas.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </div>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('selector.location')}</label>
          <select style={inputStyle} value={draft.filter.locationId} onChange={e => setFilter({ locationId: e.target.value })}>
            <option value="">{t('selector.any')}</option>
            {locations.map(l => <option key={l} value={l}>{l}</option>)}
          </select>
        </div>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('selector.method')}</label>
          <select style={inputStyle} value={draft.filter.method} onChange={e => setFilter({ method: e.target.value })}>
            <option value="">{t('selector.any')}</option>
            <option value="make">make</option>
            <option value="move">move</option>
            <option value="buy">buy</option>
          </select>
        </div>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('selector.granularity')}</label>
          <select
            style={inputStyle}
            value={draft.filter.granularity}
            onChange={e => setFilter({ granularity: e.target.value as Granularity, bucketKey: '' })}
          >
            <option value="day">{t('granularity.day')}</option>
            <option value="week">{t('granularity.week')}</option>
            <option value="month">{t('granularity.month')}</option>
            <option value="quarter">{t('granularity.quarter')}</option>
          </select>
        </div>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('selector.bucket')} <span style={{ color: '#71717a' }}>({t('selector.bucketOptional')})</span></label>
          <select style={inputStyle} value={draft.filter.bucketKey} onChange={e => setFilter({ bucketKey: e.target.value })}>
            <option value="">{t('selector.any')}</option>
            {bucketKeys.map(b => <option key={b} value={b}>{b}</option>)}
          </select>
        </div>
      </div>

      {/* Preview list */}
      <div style={{ border: '1px solid #3d3d40', borderRadius: 4, marginBottom: '0.75rem' }}>
        <div style={{ padding: '0.4rem 0.6rem', borderBottom: '1px solid #3d3d40', display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.78rem', background: '#1c1c1f' }}>
          <span style={{ color: '#a1a1aa' }}>
            {t('preview.heading', { matched: matching.length, selected: draft.selectedGids.size })}
          </span>
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              type="button"
              className="secondary"
              style={{ fontSize: '0.74rem', padding: '2px 8px' }}
              onClick={selectAll}
              disabled={matching.length === 0 || allSelected}
            >
              {t('preview.selectAll')}
            </button>
            <button
              type="button"
              className="secondary"
              style={{ fontSize: '0.74rem', padding: '2px 8px' }}
              onClick={deselectAll}
              disabled={draft.selectedGids.size === 0}
            >
              {t('preview.deselectAll')}
            </button>
          </div>
        </div>
        {matching.length === 0 ? (
          <div style={{ padding: '0.6rem', color: '#71717a', fontSize: '0.82rem' }}>{t('preview.noMatches')}</div>
        ) : (
          <div style={{ maxHeight: 260, overflow: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
              <thead>
                <tr style={{ background: '#27272a', textAlign: 'left' }}>
                  <th style={{ ...th, width: 28 }}></th>
                  <th style={th}>{t('preview.col.gid')}</th>
                  <th style={th}>{t('preview.col.product')}</th>
                  <th style={th}>{t('preview.col.location')}</th>
                  <th style={th}>{t('preview.col.method')}</th>
                  <th style={th}>{t('preview.col.prodArea')}</th>
                  <th style={th}>{t('preview.col.start')}</th>
                  <th style={th}>{t('preview.col.end')}</th>
                  <th style={{ ...th, textAlign: 'right' }}>{t('preview.col.qty')}</th>
                </tr>
              </thead>
              <tbody>
                {matching.map(w => {
                  const gid = w.wo_group_id;
                  const checked = draft.selectedGids.has(gid);
                  return (
                    <tr key={gid} style={{ borderBottom: '1px solid #2d2d31', background: checked ? 'rgba(59,130,246,0.08)' : undefined }}>
                      <td style={td}>
                        <input type="checkbox" checked={checked} onChange={() => toggleGid(gid)} />
                      </td>
                      <td style={td}>
                        <code style={{ color: '#e4e4e7' }}>{gid}</code>
                        {w.lot_count > 1 && (
                          <span style={{ marginLeft: 6, fontSize: '0.7rem', color: '#71717a' }}>×{w.lot_count}</span>
                        )}
                      </td>
                      <td style={td}>{w.product_id}</td>
                      <td style={td}>{w.location_id}</td>
                      <td style={td}>{w.method}</td>
                      <td style={td}>{w.prod_area ?? ''}</td>
                      <td style={td}>{fmtDate(w.start_time)}</td>
                      <td style={td}>{fmtDate(w.end_time)}</td>
                      <td style={{ ...td, textAlign: 'right' }}>{w.quantity}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Bucket preview — what (bucketStart, bucketEnd) will be sent to the backend */}
      {bucketPreview && (
        <div
          style={{
            fontSize: '0.78rem',
            color: '#a1a1aa',
            background: '#1c1c1f',
            border: '1px solid #2d2d31',
            borderRadius: 4,
            padding: '0.4rem 0.6rem',
            marginBottom: '0.6rem',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <span style={{ color: '#71717a' }}>{t('bucketPreview.label')}</span>
          <code style={{ color: '#e4e4e7' }}>{bucketPreview.start}</code>
          <span>→</span>
          <code style={{ color: '#e4e4e7' }}>{bucketPreview.end}</code>
          <span
            style={{
              fontSize: '0.7rem',
              padding: '1px 6px',
              borderRadius: 10,
              background: bucketPreview.source === 'derived' ? 'rgba(167,139,250,0.18)' : 'rgba(56,189,248,0.18)',
              color: bucketPreview.source === 'derived' ? '#c4b5fd' : '#7dd3fc',
              border: `1px solid ${bucketPreview.source === 'derived' ? 'rgba(167,139,250,0.4)' : 'rgba(56,189,248,0.4)'}`,
            }}
          >
            {bucketPreview.source === 'derived' ? t('bucketPreview.derived') : t('bucketPreview.fromKey')}
          </span>
        </div>
      )}

      {/* Max safe delay chip (closed-form availability — sub-millisecond on the server). */}
      <AvailabilityChip
        caseId={caseId}
        baselinePlanRunId={baselinePlanRunId}
        bucketPreview={bucketPreview}
        selectedGids={draft.selectedGids}
        currentDelayDays={draft.changeMode === 'days' ? draft.delayDays : null}
        onUse={(n) => setDraft({ ...draft, changeMode: 'days', delayDays: n })}
      />

      {/* Change row */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.6rem 1rem', alignItems: 'flex-end', marginBottom: '0.5rem' }}>
        <div style={fieldStyle}>
          <label style={labelStyle}>{t('change.label')}</label>
          <select
            style={inputStyle}
            value={draft.changeMode}
            onChange={e => setDraft({ ...draft, changeMode: e.target.value as 'days' | 'date' })}
          >
            <option value="days">{t('change.byDays')}</option>
            <option value="date">{t('change.toDate')}</option>
          </select>
        </div>
        {draft.changeMode === 'days' ? (
          <div style={fieldStyle}>
            <label style={labelStyle}>{t('change.byDays')}</label>
            <input
              type="number"
              style={{ ...inputStyle, width: 120 }}
              value={draft.delayDays}
              onChange={e => setDraft({ ...draft, delayDays: Math.max(0, Number(e.target.value) || 0) })}
              min={1}
            />
          </div>
        ) : (
          <div style={fieldStyle}>
            <label style={labelStyle}>{t('change.toDate')}</label>
            <input
              type="date"
              style={{ ...inputStyle, width: 160 }}
              value={draft.delayToDate}
              onChange={e => setDraft({ ...draft, delayToDate: e.target.value })}
            />
          </div>
        )}
        <div style={{ flex: 1, minWidth: 200, ...fieldStyle }}>
          <label style={labelStyle}>{t('note')}</label>
          <input
            style={inputStyle}
            value={draft.note}
            onChange={e => setDraft({ ...draft, note: e.target.value })}
          />
        </div>
      </div>

      <p style={{ fontSize: '0.74rem', color: '#71717a', margin: '0 0 0.6rem' }}>
        {draft.changeMode === 'days' ? t('change.daysHint') : t('change.toDateHint')}
      </p>

      {error && <div style={{ color: '#fca5a5', fontSize: '0.8rem', marginBottom: '0.5rem' }}>{error}</div>}

      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" onClick={onSave} disabled={draft.selectedGids.size === 0}>{t('save')}</button>
        <button type="button" className="secondary" onClick={onCancel}>{t('cancel')}</button>
      </div>
    </div>
  );
}

// ── results table ─────────────────────────────────────────────────────────────

function SafetyBanner({ chosen, max }: { chosen: number | null; max: number | null | undefined }) {
  const t = useTranslations('planning.woScheduleImpact');
  if (chosen == null || max == null) return null;
  const within = chosen <= max;
  return (
    <div
      style={{
        fontSize: '0.78rem',
        background: within ? 'rgba(34,197,94,0.10)' : 'rgba(248,113,113,0.10)',
        border: `1px solid ${within ? 'rgba(34,197,94,0.4)' : 'rgba(248,113,113,0.4)'}`,
        color: within ? '#86efac' : '#fca5a5',
        borderRadius: 4,
        padding: '0.4rem 0.6rem',
        marginBottom: '0.5rem',
      }}
    >
      {within
        ? t('availability.banner.withinSafe', { chosen, max })
        : t('availability.banner.exceedsSafe', { chosen, max, by: chosen - max })}
    </div>
  );
}

function ResultsTable({ result }: { result: WoScheduleImpactResult }) {
  const t = useTranslations('planning.woScheduleImpact');
  return (
    <div style={{ marginTop: '0.75rem', borderTop: '1px solid #3d3d40', paddingTop: '0.6rem' }}>
      <SafetyBanner chosen={result.delayDays} max={result.maxFeasibleDays} />
      <div style={{ display: 'flex', gap: '1.25rem', fontSize: '0.78rem', color: '#a1a1aa', marginBottom: '0.4rem' }}>
        <span>{t('results.matchedWoCount')} <strong style={{ color: '#e4e4e7' }}>{result.matchedWoCount}</strong></span>
        <span>{t('results.impactedDemandCount')} <strong style={{ color: '#e4e4e7' }}>{result.impactedDemandCount}</strong></span>
        {result.maxFeasibleDays != null && (
          <span>{t('availability.chipLabel')}: <strong style={{ color: '#e4e4e7' }}>{t('availability.daysValue', { days: result.maxFeasibleDays })}</strong></span>
        )}
      </div>
      {result.impacts.length === 0 ? (
        <p style={{ color: '#71717a', fontSize: '0.85rem' }}>{t('results.noImpacts')}</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
          <thead>
            <tr style={{ background: '#27272a', textAlign: 'left' }}>
              <th style={th}>{t('results.col.demandId')}</th>
              <th style={th}>{t('results.col.product')}</th>
              <th style={th}>{t('results.col.customer')}</th>
              <th style={th}>{t('results.col.due')}</th>
              <th style={th}>{t('results.col.baselineCommit')}</th>
              <th style={th}>{t('results.col.contingentCommit')}</th>
              <th style={{ ...th, textAlign: 'right' }}>{t('results.col.daysDelta')}</th>
              <th style={th}>{t('results.col.status')}</th>
            </tr>
          </thead>
          <tbody>
            {result.impacts.map(d => (
              <tr key={d.demandId} style={{ borderBottom: '1px solid #2d2d31' }}>
                <td style={td}>{d.demandId}</td>
                <td style={td}>{d.productId}</td>
                <td style={td}>{d.customerId}</td>
                <td style={td}>{fmtDate(d.requestDueTime)}</td>
                <td style={td}>{fmtDate(d.baselineCommitTime)}</td>
                <td style={td}>{fmtDate(d.contingentCommitTime)}</td>
                <td style={{ ...td, textAlign: 'right', color: d.daysDelta > 0 ? '#fbbf24' : '#a1a1aa' }}>{d.daysDelta}</td>
                <td style={{ ...td, color: STATUS_COLOR[d.status] ?? '#a1a1aa' }}>
                  {t(`status.${d.status}` as 'status.delivery_delayed' | 'status.newly_late_vs_due' | 'status.no_change')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

const th: React.CSSProperties = { padding: '0.4rem 0.5rem', borderBottom: '1px solid #3d3d40' };
const td: React.CSSProperties = { padding: '0.35rem 0.5rem' };

// ── Max-safe-delay chip (DraftForm) ──────────────────────────────────────────

/** Renders the closed-form availability cap inline next to the bucket-preview
 *  chip. Recomputes whenever the selection or bucket changes (debounced ~250ms). */
function AvailabilityChip({
  caseId,
  baselinePlanRunId,
  bucketPreview,
  selectedGids,
  currentDelayDays,
  onUse,
}: {
  caseId: number;
  baselinePlanRunId: number | null;
  bucketPreview: { start: string; end: string; source: 'key' | 'derived' } | null;
  selectedGids: Set<string>;
  currentDelayDays: number | null;
  onUse: (n: number) => void;
}) {
  const t = useTranslations('planning.woScheduleImpact');
  const [data, setData] = React.useState<WoAvailabilityResult | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  // Stable token for debouncing — recompute when bucket or selection changes.
  const gidsKey = React.useMemo(() => Array.from(selectedGids).sort().join(','), [selectedGids]);
  const token = `${bucketPreview?.start ?? ''}|${bucketPreview?.end ?? ''}|${gidsKey}`;

  React.useEffect(() => {
    if (!bucketPreview || selectedGids.size === 0) {
      setData(null);
      setError(null);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      setLoading(true);
      setError(null);
      try {
        const r = await analyzeWoAvailability({
          selectors: [{
            bucketStart: bucketPreview.start,
            bucketEnd: bucketPreview.end,
            woGroupIds: Array.from(selectedGids),
          }],
          planRunId: baselinePlanRunId,
          caseId,
        });
        if (!controller.signal.aborted) setData(r);
      } catch (e) {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }, 250);
    return () => { controller.abort(); clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, caseId, baselinePlanRunId]);

  if (selectedGids.size === 0) {
    return (
      <div
        style={{
          fontSize: '0.78rem', color: '#71717a',
          background: '#1c1c1f', border: '1px dashed #2d2d31', borderRadius: 4,
          padding: '0.4rem 0.6rem', marginBottom: '0.6rem',
        }}
      >
        {t('availability.chipLabel')}: <span style={{ color: '#52525b' }}>{t('availability.chipPickWos')}</span>
      </div>
    );
  }

  const exceeds = currentDelayDays != null && data != null && currentDelayDays > data.maxFeasibleDays;
  const within = currentDelayDays != null && data != null && currentDelayDays <= data.maxFeasibleDays;

  return (
    <div
      style={{
        fontSize: '0.78rem', color: '#a1a1aa',
        background: '#1c1c1f',
        border: `1px solid ${exceeds ? 'rgba(248,113,113,0.5)' : within ? 'rgba(34,197,94,0.5)' : '#2d2d31'}`,
        borderRadius: 4,
        padding: '0.4rem 0.6rem', marginBottom: '0.6rem',
        display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8,
      }}
    >
      <span style={{ color: '#71717a' }}>{t('availability.chipLabel')}:</span>
      {loading ? (
        <span style={{ color: '#52525b' }}>…</span>
      ) : error ? (
        <span style={{ color: '#fca5a5' }}>{t('availability.error.requestFailed')}</span>
      ) : data ? (
        <>
          <strong style={{ color: '#e4e4e7' }}>{t('availability.daysValue', { days: data.maxFeasibleDays })}</strong>
          {data.bottlenecks.length > 0 && (
            <span style={{ color: '#71717a', fontSize: '0.74rem' }}>
              · {data.bottlenecks[0].kind === 'demand_root'
                  ? t('availability.bottleneck.demandRoot', {
                      demandId: data.bottlenecks[0].demandId ?? '?',
                      days: data.bottlenecks[0].slackDays,
                    })
                  : t('availability.bottleneck.boundary', {
                      gid: data.bottlenecks[0].parentGid ?? '?',
                      days: data.bottlenecks[0].slackDays,
                    })}
              {data.bottlenecks.length > 1 && ` (+${data.bottlenecks.length - 1})`}
            </span>
          )}
          {data.maxFeasibleDays > 0 && currentDelayDays !== data.maxFeasibleDays && (
            <button
              type="button"
              className="secondary"
              style={{ fontSize: '0.72rem', padding: '1px 8px', marginLeft: 'auto' }}
              onClick={() => onUse(data.maxFeasibleDays)}
            >{t('availability.chipUse')}</button>
          )}
        </>
      ) : null}
    </div>
  );
}

// ── Per-event runs history ────────────────────────────────────────────────────

function RunsHistory({ runs, loading }: { runs: WoScheduleRun[]; loading: boolean }) {
  const t = useTranslations('planning.woScheduleImpact');
  const [expanded, setExpanded] = React.useState<Set<number>>(new Set());
  function toggle(id: number) {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  }
  return (
    <div style={{ marginTop: '0.75rem', borderTop: '1px solid #3d3d40', paddingTop: '0.6rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: '0.4rem' }}>
        <strong style={{ fontSize: '0.82rem', color: '#e4e4e7' }}>{t('history.heading')}</strong>
        <span style={{ fontSize: '0.74rem', color: '#71717a' }}>
          {loading ? t('history.loading') : t('history.count', { count: runs.length })}
        </span>
      </div>
      {(!loading && runs.length === 0) ? (
        <p style={{ color: '#71717a', fontSize: '0.78rem', margin: 0 }}>{t('history.empty')}</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
          {runs.map(run => {
            const isOpen = expanded.has(run.planRunId);
            const promoted = run.status === 'success';
            return (
              <li
                key={run.planRunId}
                style={{ border: '1px solid #2d2d31', borderRadius: 4, padding: '0.4rem 0.6rem', background: '#161618' }}
              >
                <div
                  style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8, cursor: 'pointer' }}
                  onClick={() => toggle(run.planRunId)}
                >
                  <span style={{ color: '#71717a', fontSize: '0.74rem', userSelect: 'none' }}>{isOpen ? '▾' : '▸'}</span>
                  <span style={{ fontSize: '0.78rem', color: '#e4e4e7' }}>
                    <code>#{run.planRunId}</code>
                  </span>
                  <span style={{ background: '#3f3f46', color: '#e4e4e7', borderRadius: 4, padding: '1px 6px', fontSize: '0.7rem' }}>
                    {run.delayDays != null ? `+${run.delayDays}d` : `→ ${run.delayToDate}`}
                  </span>
                  <span style={{ color: '#a1a1aa', fontSize: '0.74rem' }}>
                    {t('results.matchedWoCount')} <strong style={{ color: '#e4e4e7' }}>{run.matchedWoCount}</strong>
                  </span>
                  <span style={{ color: '#a1a1aa', fontSize: '0.74rem' }}>
                    {t('results.impactedDemandCount')} <strong style={{ color: run.impactedDemandCount > 0 ? '#fbbf24' : '#e4e4e7' }}>{run.impactedDemandCount}</strong>
                  </span>
                  {promoted && (
                    <span style={{ background: '#166534', color: '#fff', borderRadius: 4, padding: '1px 6px', fontSize: '0.7rem' }}>
                      {t('history.promoted')}
                    </span>
                  )}
                  {run.note && <span style={{ color: '#71717a', fontSize: '0.74rem' }}>{run.note}</span>}
                  <span style={{ color: '#52525b', fontSize: '0.7rem', marginLeft: 'auto' }}>{new Date(run.createdAt).toLocaleString()}</span>
                </div>
                {isOpen && run.impacts.length > 0 && (
                  <div style={{ marginTop: '0.4rem' }}>
                    <ResultsTable result={{
                      caseId: 0,
                      planRunId: run.baselinePlanRunId,
                      contingentPlanRunId: run.planRunId,
                      matchedWoCount: run.matchedWoCount,
                      delayDays: run.delayDays,
                      delayToDate: run.delayToDate,
                      impactedDemandCount: run.impactedDemandCount,
                      impacts: run.impacts,
                      note: run.note,
                    }} />
                  </div>
                )}
                {isOpen && run.impacts.length === 0 && (
                  <p style={{ color: '#71717a', fontSize: '0.78rem', margin: '0.4rem 0 0' }}>{t('results.noImpacts')}</p>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

// ── Per-WO quick modal ────────────────────────────────────────────────────────

/**
 * Lightweight modal opened from the per-WO row action in the work-orders table.
 * Pre-fills woGroupIds with the row's gid and bucketStart/bucketEnd with the
 * row's start/end. User picks delayDays or delayToDate and clicks Analyze.
 */
export function WoScheduleQuickModal({
  caseId,
  baselinePlanRunId,
  wo,
  onClose,
}: {
  caseId: number;
  baselinePlanRunId: number | null;
  wo: { wo_group_id?: string | null; start_time?: string | null; end_time?: string | null; product_id?: string; location_id?: string; method?: string };
  onClose: () => void;
}) {
  const t = useTranslations('planning.woScheduleImpact');
  const startStr = (wo.start_time ?? '').slice(0, 10);
  const endStr = (wo.end_time ?? wo.start_time ?? '').slice(0, 10);
  const [changeMode, setChangeMode] = React.useState<'days' | 'date'>('days');
  const [delayDays, setDelayDays] = React.useState(7);
  const [delayToDate, setDelayToDate] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [result, setResult] = React.useState<WoScheduleImpactResult | null>(null);

  async function analyze() {
    if (!wo.wo_group_id) {
      setError('Missing wo_group_id on selected row');
      return;
    }
    if (!startStr || !endStr) {
      setError(t('error.noBucketDates'));
      return;
    }
    if (changeMode === 'days' && delayDays <= 0) {
      setError(t('error.noShift'));
      return;
    }
    if (changeMode === 'date' && !delayToDate) {
      setError(t('error.noShift'));
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const r = await analyzeWoScheduleImpact({
        selectors: [{
          bucketStart: startStr,
          bucketEnd: endStr,
          woGroupIds: [wo.wo_group_id],
        }],
        delayDays: changeMode === 'days' ? delayDays : null,
        delayToDate: changeMode === 'date' ? delayToDate : null,
        planRunId: baselinePlanRunId,
        caseId,
        persist: true,
      });
      setResult(r);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      style={{
        position: 'fixed', inset: 0, zIndex: 9998,
        background: 'rgba(0,0,0,0.55)', display: 'flex', justifyContent: 'center', alignItems: 'center',
      }}
      onClick={onClose}
      role="dialog"
    >
      <div
        style={{
          background: '#18181b', border: '1px solid #3d3d40', borderRadius: 8, padding: '1.25rem',
          minWidth: 520, maxWidth: 900, maxHeight: '90vh', overflow: 'auto',
        }}
        onClick={e => e.stopPropagation()}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.6rem' }}>
          <h3 style={{ margin: 0 }}>{t('title')}</h3>
          <button type="button" className="secondary" onClick={onClose}>✕</button>
        </div>
        <div style={{ fontSize: '0.8rem', color: '#a1a1aa', marginBottom: '0.75rem' }}>
          {wo.product_id}@{wo.location_id} ({wo.method}) · wo_group_id=<strong>{wo.wo_group_id ?? '?'}</strong> · {startStr}…{endStr}
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '0.6rem' }}>
          <select value={changeMode} onChange={e => setChangeMode(e.target.value as 'days' | 'date')}>
            <option value="days">{t('change.byDays')}</option>
            <option value="date">{t('change.toDate')}</option>
          </select>
          {changeMode === 'days' ? (
            <input
              type="number"
              min={1}
              value={delayDays}
              onChange={e => setDelayDays(Math.max(0, Number(e.target.value) || 0))}
              style={{ width: 100 }}
            />
          ) : (
            <input
              type="date"
              value={delayToDate}
              onChange={e => setDelayToDate(e.target.value)}
            />
          )}
          <button type="button" disabled={busy} onClick={analyze}>
            {busy ? t('analyzing') : t('submit')}
          </button>
        </div>
        <p style={{ fontSize: '0.72rem', color: '#71717a', margin: '0 0 0.75rem' }}>
          {changeMode === 'days' ? t('change.daysHint') : t('change.toDateHint')}
        </p>

        {error && <div style={{ color: '#fca5a5', fontSize: '0.85rem', marginBottom: '0.5rem' }}>{error}</div>}
        {result && <ResultsTable result={result} />}
      </div>
    </div>
  );
}
