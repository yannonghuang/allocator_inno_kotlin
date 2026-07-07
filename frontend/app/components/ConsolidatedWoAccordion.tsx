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
  productId: string;
  locationId: string;
  method: string;
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
 *  can't show a demand or a qty that doesn't match what its own lots actually sum to. */
export function ConsolidatedWoAccordion({
  caseId,
  planRunId,
  productId,
  locationId,
  method,
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

  useEffect(() => {
    if (!planRunId || allDemandIds.length === 0) return;
    for (const did of allDemandIds) {
      // A demand can have MULTIPLE native WO lots consolidated into this same physical WO group
      // (e.g. two separate waterfall/lot slots) that can share an IDENTICAL start_time (both
      // snapped to the same final consolidated wave) — key by each lot's own native wo_group_id
      // (unique per physical lot) instead, falling back to start_time only for the rare native
      // row that lacks one, so this fetches a tree per distinct lot, not just the first.
      const nativeWos = (byDemand.get(did) ?? [])
        .slice()
        .sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''));
      const seenLotKeys = new Set<string>();
      const lots: { key: string; startTime: string; woGroupId: string }[] = [];
      for (const w of nativeWos) {
        const lotGid = w.wo_group_id ?? '';
        const startTime = w.start_time ?? '';
        const key = lotGid || startTime;
        if (seenLotKeys.has(key)) continue;
        seenLotKeys.add(key);
        lots.push({ key, startTime, woGroupId: lotGid });
      }
      const effectiveLots = lots.length > 0 ? lots : [{ key: '', startTime: '', woGroupId: '' }];
      for (const { key, startTime, woGroupId: lotGid } of effectiveLots) {
        const cacheKey = `${did}|${productId}|${locationId}|${method}|${key}`;
        if (treeCache[cacheKey]) {
          setExpanded((prev) => (prev[cacheKey] ? prev : { ...prev, [cacheKey]: new Set(['0']) }));
          continue;
        }
        if (fetchingRef.current.has(cacheKey)) continue;
        fetchingRef.current.add(cacheKey);
        getWorkOrderPegging(caseId, {
          demand_id: did, product_id: productId, location_id: locationId, method,
          start_time: startTime || undefined,
          wo_group_id: lotGid || undefined,
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
  }, [caseId, planRunId, woGroupId, productId, locationId, method, allDemandIds.join(',')]);

  return (
    <div style={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
      <div style={{ fontSize: '0.75rem', color: '#a1a1aa', marginBottom: 6 }}>
        Physical WO fulfilling {allDemandIds.length} logical WO{allDemandIds.length !== 1 ? 's' : ''}:
      </div>
      {allDemandIds.map((did) => {
        const nativeWos = (byDemand.get(did) ?? [])
          .slice()
          .sort((a, b) => (a.start_time ?? '').localeCompare(b.start_time ?? ''));
        const qty = nativeWos.reduce((s, w) => s + Number(w.quantity ?? 0), 0);
        const seenLotKeys = new Set<string>();
        const lots: { key: string; startTime: string; woGroupId: string }[] = [];
        for (const w of nativeWos) {
          const lotGid = w.wo_group_id ?? '';
          const startTime = w.start_time ?? '';
          const key = lotGid || startTime;
          if (seenLotKeys.has(key)) continue;
          seenLotKeys.add(key);
          lots.push({ key, startTime, woGroupId: lotGid });
        }
        const slots = lots.length > 0 ? lots : [{ key: '', startTime: '', woGroupId: '' }];
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
              {slots.length > 1 && (
                <span style={{ color: '#71717a', fontSize: '0.7rem', flexShrink: 0 }}>· {slots.length} slots</span>
              )}
              <span style={{ color: '#a78bfa', fontSize: '0.75rem', flexShrink: 0, marginLeft: 'auto' }}>{qtyFmt(qty)}</span>
            </button>
            {isOpen && (
              <div style={{ paddingLeft: 8, paddingBottom: 8 }}>
                {slots.map(({ key, startTime, woGroupId: lotGid }, slotIdx) => {
                  const cacheKey = `${did}|${productId}|${locationId}|${method}|${key}`;
                  const tree = treeCache[cacheKey] ?? null;
                  const sectionExpanded = expanded[cacheKey] ?? new Set(['0']);
                  const slotWo = nativeWos.find((w) => (lotGid ? w.wo_group_id === lotGid : (w.start_time ?? '') === startTime));
                  return (
                    <div key={cacheKey} style={slotIdx > 0 ? { marginTop: 6, paddingTop: 6, borderTop: '1px dashed #3d3d40' } : undefined}>
                      {slots.length > 1 && (
                        <p style={{ margin: '0 0 4px', fontSize: '0.72rem', color: '#71717a' }}>
                          Slot {slotIdx + 1}/{slots.length}{slotWo ? `: ${slotWo.start_time ?? '–'} → ${slotWo.end_time ?? '–'} · ${qtyFmt(Number(slotWo.quantity ?? 0))}` : ''}
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
