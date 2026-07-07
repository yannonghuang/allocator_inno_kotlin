'use client';

import React, { startTransition, useEffect, useRef, useState } from 'react';
import {
  getWorkOrderPegging,
  type PlanningPeggingEntry,
  type PlanningPeggingNode,
  type WorkOrder,
} from '@/lib/api';
import { qtyFmt } from '@/app/lib/format';
import { PlanningPeggingTreeView } from './PlanningPeggingTreeView';

export type ConsolidatedWoAccordionProps = {
  caseId: number;
  planRunId: number | null;
  /** The consolidated batch's own wo_group_id (== consolidated_group_id) — used to find every
   *  native lot that rolled into this physical WO across all the demands it serves. */
  woGroupId: string;
  workOrdersNative: WorkOrder[];
  planningPegging: PlanningPeggingEntry[];
};

/** "Physical WO fulfilling N logical WOs" accordion: one collapsible section per demand this
 *  consolidated batch serves, each showing that demand's own qty share and (when it has more than
 *  one native lot in this batch) a per-lot "Slot N/M" breakdown with its own pegging tree.
 *
 *  The demand list and each demand's qty share are derived directly from `workOrdersNative`
 *  (grouped by demand_id for rows tagged with this batch's `woGroupId`) rather than from a
 *  separately-passed split/consolidated-demand-ids field — one source of truth, so the accordion
 *  can't show a demand or a qty that doesn't match what its own lots actually sum to.
 *
 *  Each lot's own product_id/location_id/method (not a single value for the whole batch) drives
 *  its pegging fetch — a consolidated batch isn't required to be a single product (e.g. a
 *  mixed-cargo move shipment), so every lot is traced against its own component. */
export function ConsolidatedWoAccordion({
  caseId,
  planRunId,
  woGroupId,
  workOrdersNative,
  planningPegging,
}: ConsolidatedWoAccordionProps): JSX.Element {
  const [openSections, setOpenSections] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Record<string, Set<string>>>({});
  const [treeCache, setTreeCache] = useState<Record<string, PlanningPeggingNode>>({});
  const fetchingRef = useRef<Set<string>>(new Set());

  const nativeForBatch = workOrdersNative.filter((w) => w.consolidated_group_id === woGroupId);
  const byDemand = new Map<string, WorkOrder[]>();
  for (const w of nativeForBatch) {
    const did = w.demand_id;
    if (!did) continue;
    if (!byDemand.has(did)) byDemand.set(did, []);
    byDemand.get(did)!.push(w);
  }
  const allDemandIds = Array.from(byDemand.keys()).sort();

  // A demand can have MULTIPLE native WO lots consolidated into this same physical WO group
  // (e.g. two separate waterfall/lot slots, or two different components of a mixed-cargo move)
  // that can share an IDENTICAL start_time (both snapped to the same final consolidated wave) —
  // key by each lot's own native wo_group_id (unique per physical lot) instead, falling back to
  // start_time only for the rare native row that lacks one, so every distinct lot gets its own
  // slot instead of collapsing into just the first.
  const lotsByDemand = (did: string): { key: string; wo: WorkOrder }[] => {
    const nativeWos = (byDemand.get(did) ?? [])
      .slice()
      .sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''));
    const seenKeys = new Set<string>();
    const lots: { key: string; wo: WorkOrder }[] = [];
    for (const w of nativeWos) {
      const key = (w.wo_group_id ?? '') || (w.start_time ?? '');
      if (seenKeys.has(key)) continue;
      seenKeys.add(key);
      lots.push({ key, wo: w });
    }
    return lots;
  };

  useEffect(() => {
    if (!planRunId || allDemandIds.length === 0) return;
    for (const did of allDemandIds) {
      for (const { key, wo } of lotsByDemand(did)) {
        const cacheKey = `${did}|${wo.product_id}|${wo.location_id}|${wo.method}|${key}`;
        if (treeCache[cacheKey]) {
          setExpanded((prev) => (prev[cacheKey] ? prev : { ...prev, [cacheKey]: new Set(['0']) }));
          continue;
        }
        if (fetchingRef.current.has(cacheKey)) continue;
        fetchingRef.current.add(cacheKey);
        getWorkOrderPegging(caseId, {
          demand_id: did, product_id: wo.product_id, location_id: wo.location_id, method: wo.method,
          start_time: wo.start_time || undefined,
          wo_group_id: wo.wo_group_id || undefined,
          run_id: planRunId,
        })
          .then((res) => {
            setTreeCache((prev) => ({ ...prev, [cacheKey]: res.tree }));
            setExpanded((prev) => ({ ...prev, [cacheKey]: new Set(['0']) }));
          })
          .catch((err) => {
            if (typeof console !== 'undefined' && console.error)
              console.error('[WO consolidated pegging]', did, err);
          })
          .finally(() => { fetchingRef.current.delete(cacheKey); });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId, planRunId, woGroupId, allDemandIds.join(',')]);

  return (
    <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
      <div style={{ fontSize: '0.75rem', color: '#a1a1aa', marginBottom: 6 }}>
        Physical WO fulfilling {allDemandIds.length} logical WO{allDemandIds.length !== 1 ? 's' : ''}:
      </div>
      {allDemandIds.map((did) => {
        const lots = lotsByDemand(did);
        const qty = lots.reduce((s, { wo }) => s + Number(wo.quantity ?? 0), 0);
        const isOpen = openSections.has(did);
        return (
          <div key={did} style={{ borderTop: '1px solid #3d3d40' }}>
            <button type="button"
              onClick={() => setOpenSections((prev) => {
                const next = new Set(prev);
                if (next.has(did)) next.delete(did); else next.add(did);
                return next;
              })}
              style={{ width: '100%', textAlign: 'left', background: 'none', border: 'none', padding: '6px 2px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, color: 'inherit' }}
            >
              <span style={{ color: '#a1a1aa', fontSize: '0.7rem', flexShrink: 0 }}>{isOpen ? '▾' : '▸'}</span>
              <span style={{ color: '#60a5fa', fontSize: '0.8rem', fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{did}</span>
              {lots.length > 1 && (
                <span style={{ color: '#71717a', fontSize: '0.7rem', flexShrink: 0 }}>· {lots.length} slots</span>
              )}
              <span style={{ color: '#a78bfa', fontSize: '0.75rem', flexShrink: 0, marginLeft: 'auto' }}>{qtyFmt(qty)}</span>
            </button>
            {isOpen && (
              <div style={{ paddingLeft: 8, paddingBottom: 8 }}>
                {lots.map(({ key, wo }, slotIdx) => {
                  const cacheKey = `${did}|${wo.product_id}|${wo.location_id}|${wo.method}|${key}`;
                  const tree = treeCache[cacheKey] ?? null;
                  const sectionExpanded = expanded[cacheKey] ?? new Set(['0']);
                  return (
                    <div key={cacheKey} style={slotIdx > 0 ? { marginTop: 6, paddingTop: 6, borderTop: '1px dashed #3d3d40' } : undefined}>
                      {lots.length > 1 && (
                        <p style={{ margin: '0 0 4px', fontSize: '0.72rem', color: '#71717a' }}>
                          Slot {slotIdx + 1}/{lots.length}: {wo.product_id}@{wo.location_id} · {wo.start_time ?? '–'} → {wo.end_time ?? '–'} · {qtyFmt(Number(wo.quantity ?? 0))}
                        </p>
                      )}
                      {!tree ? (
                        <p style={{ color: '#a1a1aa', fontSize: '0.82rem', margin: '4px 0' }}>
                          {fetchingRef.current.has(cacheKey) ? 'Loading…' : 'No pegging tree.'}
                        </p>
                      ) : (
                        <PlanningPeggingTreeView
                          tree={tree}
                          expanded={sectionExpanded}
                          onToggle={(path) => startTransition(() => setExpanded((prev) => {
                            const cur = prev[cacheKey] ?? new Set(['0']);
                            const next = new Set(cur);
                            if (next.has(path)) next.delete(path); else next.add(path);
                            return { ...prev, [cacheKey]: next };
                          }))}
                          contextDemandId={did}
                          hideLotCount={true}
                          consolidatedSourceResolver={(embeddedDemandId, pid) => {
                            const allEntries = planningPegging.filter(
                              (e) => String(e.demand_id ?? '').trim() === embeddedDemandId
                            );
                            return allEntries.slice(0, -1).map((e) => e.tree)
                              .filter((t): t is PlanningPeggingNode => t != null && t.product_id === pid);
                          }}
                        />
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
