'use client';

import React, { startTransition, useEffect, useState } from 'react';
import type { DominatorRef, PlanningPeggingEntry, PlanningPeggingNode, SupplyAllocationRow } from '@/lib/api';
import { getPlanRunSupplyAllocations } from '@/lib/api';
import { DominatorLink, PlanningPeggingTreeView } from './PlanningPeggingTreeView';
import { qtyFmt } from '@/app/lib/format';

export type SinglePeggingTreePanelProps = {
  tree: PlanningPeggingNode;
  /** demand_id of the pegging being viewed; suppresses redundant "(demand X)"
   *  suffixes on nodes that carry this same id. */
  contextDemandId?: string | null;
  /** When viewing WO-level pegging, override the root's displayed qty to the
   *  table row's own qty (the tree's root may carry a different value). */
  workOrderRootQty?: number | null;
  /** Full planning_pegging list — resolves "consolidated_*" supply nodes back
   *  to the original per-demand trees that fed the consolidation. */
  planningPegging: PlanningPeggingEntry[];
  /** Jump straight to a raw supply's own Breakdown view — the destination for dominator links
   *  that carry a supply_id (the vast majority; every dominator resolves to a genuine raw
   *  supply lot by design). Omitted refs (e.g. a cross-demand shared_supply_budget tag with no
   *  single lot) fall back to the same-tree search below instead. */
  onNavigateToSupply?: (supplyId: string) => void;
  /** case/run identity, needed to fetch each quantity-dominator lot's allocated/consumed
   *  amounts for the "Qty limited by" table below. When either is omitted (e.g. a work-order
   *  pegging view spanning more than one demand, where "allocated to the demand" doesn't apply
   *  to a single demand), the table still renders but its two amount columns show "–". */
  caseId?: number | null;
  runId?: number | null;
  /** Serving demand's own request_time — used only to gate the root "Delayed by" time-dominator
   *  label (see the rootIsLate computation below). Needed when `tree`'s root is a `work_order`
   *  (single-WO pegging view): that node type has no `request_time` of its own (only
   *  `start_time`/`end_time`), so the caller resolves it from the serving demand (via
   *  `contextDemandId`/the WO row's `demand_id`) and passes it through. When `tree`'s root is a
   *  `demand`, its own `request_time` is used instead and this prop is ignored. */
  servingDemandRequestTime?: string | null;
};

/** Same lenient ISO/M-d-yyyy parsing as the Demand Summary view's normalizeIso — kept local
 *  since no shared date util exists yet. Returns a zero-padded "yyyy-MM-dd" string comparable
 *  with plain `<`/`>`, or null if unparseable. */
function normalizeIso(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const iso = /^(\d{4})-(\d{2})-(\d{2})/.exec(raw);
  if (iso) return `${iso[1]}-${iso[2]}-${iso[3]}`;
  const slash = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(raw.trim());
  if (slash) return `${slash[3]}-${slash[1].padStart(2, '0')}-${slash[2].padStart(2, '0')}`;
  return null;
}

/** A single demand's or single work order's pegging tree, with search,
 *  quantity/time-dominator links, and a "why method" explanation toggle — the
 *  one view used everywhere a single (non-consolidated) pegging tree is
 *  shown. Starts fully collapsed (only the root row shows).
 *
 *  Give this component a `key` tied to the identity of what's being viewed
 *  (demand id / WO row key) so React remounts it — and resets all its
 *  internal state — when the caller switches to a different tree. */
export function SinglePeggingTreePanel({
  tree,
  contextDemandId,
  workOrderRootQty,
  planningPegging,
  onNavigateToSupply,
  caseId,
  runId,
  servingDemandRequestTime,
}: SinglePeggingTreePanelProps): JSX.Element {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [explanationExpanded, setExplanationExpanded] = useState<Set<string>>(() => new Set());
  const [search, setSearch] = useState('');
  const [matchPaths, setMatchPaths] = useState<string[]>([]);
  const [matchPath, setMatchPath] = useState<string | null>(null);
  const [matchIndex, setMatchIndex] = useState(0);
  // supply_id -> {qty_allocated, qty_consumed}, for the quantity-dominator table below —
  // both sourced from plan_supply_allocation (the DB), the single source of truth for "how
  // much of this scarce lot's budget did this demand actually draw." Deliberately NOT derived
  // from this tree: the tree is reconcile()'s output, which caps a leaf's DISPLAYED quantity to
  // whatever its parent assembly could usefully absorb (an AND-sibling elsewhere in the same
  // assembly can bottleneck the whole unit) — but that's a reporting-level adjustment, not an
  // inventory give-back; the physical draw already happened and is what plan_supply_allocation
  // (and the final inventory ledger) correctly record. For a table about budget usage, the DB's
  // number — not the tree's "how much ended up usefully deliverable" number — is the right one.
  // Only fetched when the caller can identify a single owning demand (caseId/runId + a
  // resolvable demand id) — see the props' own doc for why this degrades gracefully otherwise.
  const [supplyAllocByLot, setSupplyAllocByLot] = useState<Map<string, SupplyAllocationRow> | null>(null);
  const allocDemandId = contextDemandId ?? tree.demand_id ?? null;
  useEffect(() => {
    setSupplyAllocByLot(null);
    if (caseId == null || runId == null || !allocDemandId) return;
    let cancelled = false;
    getPlanRunSupplyAllocations(caseId, runId, allocDemandId)
      .then((res) => {
        if (cancelled) return;
        setSupplyAllocByLot(new Map(res.allocations.map((a) => [a.supply_id, a])));
      })
      .catch(() => { if (!cancelled) setSupplyAllocByLot(null); });
    return () => { cancelled = true; };
  }, [caseId, runId, allocDemandId]);

  // Locates the node a DominatorRef points at, WITHIN THIS tree — the common case (a BOM
  // sibling, a method alternative, a bottom-up child, all live in the same demand's pegging).
  // Cross-tree refs (e.g. shared_supply_budget naming a different competing demand, or a
  // wave_peer WO that belongs to another demand) aren't findable here; the caller degrades
  // gracefully (the click simply does nothing) rather than attempting a page-level jump —
  // see PlanningPeggingTreeView's DominatorLink, which still shows the label either way.
  const findNodePath = (node: PlanningPeggingNode, ref: DominatorRef, path: string): string | null => {
    const matches =
      (ref.wo_group_id != null && node.wo_group_id === ref.wo_group_id) ||
      (ref.supply_id != null && (node as { supply_id?: string | null }).supply_id === ref.supply_id) ||
      (ref.product_id != null && node.product_id === ref.product_id &&
        (ref.location_id == null || node.location_id === ref.location_id) &&
        (ref.demand_id == null || node.demand_id === ref.demand_id || node.type !== 'demand'));
    if (matches) return path;
    const kids = node.children ?? [];
    for (let i = 0; i < kids.length; i++) {
      const found = findNodePath(kids[i], ref, `${path}-${i}`);
      if (found) return found;
    }
    return null;
  };

  const handleDominatorClick = (ref: DominatorRef) => {
    // Every dominator resolves to a genuine raw supply lot by design (see the backend's
    // rawDominatorRefs) — jump straight to that supply's own Breakdown view rather than
    // scrolling within this tree, where the raw leaf is often deeply nested and hard to read
    // in context. Only a ref with no supply_id (e.g. a cross-demand shared_supply_budget tag)
    // falls back to searching this tree.
    if (ref.supply_id && onNavigateToSupply) {
      onNavigateToSupply(ref.supply_id);
      return;
    }
    const found = findNodePath(tree, ref, '0');
    if (!found) return;
    setMatchPath(found);
    setExpanded((prev) => {
      const next = new Set(prev);
      const parts = found.split('-');
      for (let i = 1; i <= parts.length; i++) next.add(parts.slice(0, i).join('-'));
      next.add(found);
      return next;
    });
  };

  const runSearch = (query: string) => {
    const q = query.trim().toLowerCase();
    if (!q) {
      setMatchPaths([]);
      setMatchPath(null);
      setMatchIndex(0);
      return;
    }
    const matches: string[] = [];
    const ancestors = new Set<string>();
    const nodeMatches = (n: PlanningPeggingNode): boolean => {
      if (n.type === 'demand') {
        return [n.product_id, n.location_id].some(
          (f) => typeof f === 'string' && f.toLowerCase().includes(q)
        );
      }
      if (n.type === 'supply') {
        const pid = n.product_id;
        const sid = n.supply_id;
        if (typeof pid !== 'string' || typeof sid !== 'string') return false;
        // Standard lot supply_id = "pid_loc_lot" — sid starts with pid+"_".
        // These are detail nodes; the parent demand already covers this product
        // occurrence. Only consolidated/named supplies (e.g.
        // "consolidated_260-0141-02_2000") add a distinct occurrence.
        if (sid.toLowerCase().startsWith(pid.toLowerCase() + '_')) return false;
        return pid.toLowerCase().includes(q) || sid.toLowerCase().includes(q);
      }
      // work_order, purchase, operation, resource:
      // match only on location and method — NOT product_id (handled by demand
      // branch above) and NOT demand_id (it may embed the product_id string).
      return [n.location_id, n.method].some(
        (f) => typeof f === 'string' && f.toLowerCase().includes(q)
      );
    };
    // Mirror PlanningPeggingTreeView's child filtering so paths stay in sync.
    const childContrib = (c: PlanningPeggingNode): number => {
      const cc = (c as { committed_qty?: number | null }).committed_qty;
      return Number((cc != null ? cc : c.quantity) ?? 0);
    };
    const visibleChildren = (n: PlanningPeggingNode): PlanningPeggingNode[] => {
      const raw = n.children ?? [];
      const isLegacyBlockedWo = n.type === 'work_order'
        && !n.failed
        && Number(n.quantity ?? 0) <= 1e-9
        && raw.length > 0;
      if (isLegacyBlockedWo) return [];
      if (n.children_relation === 'or' && raw.length > 1) {
        const contribCount = raw.filter((c) => childContrib(c) > 1e-9).length;
        if (contribCount > 0 && contribCount < raw.length)
          return raw.filter((c) => childContrib(c) > 1e-9);
      }
      return raw;
    };
    const walk = (n: PlanningPeggingNode, path: string, chain: string[]): void => {
      const nextChain = [...chain, path];
      if (nodeMatches(n)) {
        matches.push(path);
        chain.forEach((p) => ancestors.add(p));
      }
      visibleChildren(n).forEach((c, i) => walk(c, `${path}-${i}`, nextChain));
    };
    walk(tree, '0', []);
    setMatchPaths(matches);
    setMatchIndex(0);
    setMatchPath(matches[0] ?? null);
    if (matches.length > 0) {
      setExpanded((prev) => {
        const next = new Set(prev);
        ancestors.forEach((p) => next.add(p));
        // Also expand the first match itself so its children are visible.
        next.add(matches[0]);
        return next;
      });
    }
  };

  const stepMatch = (delta: number) => {
    if (matchPaths.length === 0) return;
    const nextIdx = (matchIndex + delta + matchPaths.length) % matchPaths.length;
    setMatchIndex(nextIdx);
    const nextPath = matchPaths[nextIdx];
    setMatchPath(nextPath);
    // Make sure ancestors of the new match are expanded.
    setExpanded((prev) => {
      const next = new Set(prev);
      const parts = nextPath.split('-');
      for (let i = 1; i <= parts.length; i++) next.add(parts.slice(0, i).join('-'));
      next.add(nextPath);
      return next;
    });
  };

  // Defensive dedup by supply identity (the backend already dedupes new pegging trees the same
  // way, but this also cleans up any already-computed run stored before that fix). Falls back
  // to (kind, product, location) for the rare ref with no supply_id at all.
  const dedupBySupply = (refs: DominatorRef[]): DominatorRef[] => {
    const seen = new Set<string>();
    return refs.filter((r) => {
      const key = r.supply_id ?? `${r.kind}|${r.product_id}|${r.location_id}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  };
  const rootQtyDominators = dedupBySupply(tree.quantity_dominator ?? []);
  const rootTimeDominators = dedupBySupply(tree.time_dominator ?? []);

  // The backend populates time_dominator unconditionally, as a trace of "which lot/child
  // determined this timing" — NOT specifically "what delayed this" (see computeStartDt's own
  // doc: resolved even when nothing pushed the schedule past its own backward-computed
  // deadline). The UI's "Delayed by" label is only accurate when the root actually IS late —
  // otherwise it reads as a contradiction next to an on-time commit/end date. Suppress it only
  // when both dates are resolvable and prove the root is on time or early; if either is missing
  // or unparseable, fall back to showing it (the previous, always-on behavior) rather than
  // guessing.
  const rootRequestIso = normalizeIso(tree.type === 'demand' ? tree.request_time : servingDemandRequestTime);
  const rootCommitIso = normalizeIso(tree.type === 'work_order' ? tree.end_time : tree.commit_time);
  const rootKnownOnTime = rootRequestIso != null && rootCommitIso != null && rootCommitIso <= rootRequestIso;

  // Group qty-dominator rows by the material they name (product_id@location_id) rather than
  // one flat list with one combined footer. Two DIFFERENT materials showing up as dominators
  // at once are two INDEPENDENT causes/budgets, not one shared pool — e.g. one method
  // alternative bottlenecked on X while a sibling elsewhere bottlenecked on Y. Summing their
  // Allocated/Consumed into a single "Total" made that single number meaningless (apples plus
  // oranges) and, worse, visually implied the two materials were jointly responsible for one
  // shortfall rather than each independently explaining its own. A ref with no product_id
  // (rare — only shared_supply_budget/resource_contention lack one) groups under its own label
  // instead, so it still gets its own subtotal rather than silently joining an unrelated group.
  const qtyDominatorGroups: { key: string; label: string; refs: DominatorRef[] }[] = [];
  {
    const byKey = new Map<string, { key: string; label: string; refs: DominatorRef[] }>();
    for (const d of rootQtyDominators) {
      const key = d.product_id ? `${d.product_id}@${d.location_id ?? ''}` : `__${d.label}`;
      const label = d.product_id ? `${d.product_id}${d.location_id ? `@${d.location_id}` : ''}` : d.label;
      let group = byKey.get(key);
      if (!group) {
        group = { key, label, refs: [] };
        byKey.set(key, group);
        qtyDominatorGroups.push(group);
      }
      group.refs.push(d);
    }
  }
  const showAllocCols = caseId != null && runId != null;
  const showTimeDominators = rootTimeDominators.length > 0 && !rootKnownOnTime;

  return (
    <>
      {(rootQtyDominators.length > 0 || showTimeDominators) && (
        <div style={{
          marginBottom: '0.5rem', padding: '6px 8px', background: '#1c1c1e',
          border: '1px solid #3d3d40', borderRadius: 4,
          display: 'flex', flexDirection: 'column', gap: 4, fontSize: '0.78rem',
        }}>
          {qtyDominatorGroups.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {qtyDominatorGroups.map((group) => {
                // Per-group subtotal — see qtyDominatorGroups' own doc for why this must NOT
                // be combined across groups. Same "only sum rows with a genuine budget
                // entitlement" rule as before (a non-critical/purchasable lot has no
                // allocation concept at all — see qty_allocated's own doc).
                let groupAllocated: number | null = null;
                let groupConsumed = 0;
                for (const d of group.refs) {
                  const alloc = d.supply_id ? supplyAllocByLot?.get(d.supply_id) : undefined;
                  if (alloc?.qty_allocated == null) continue;
                  groupAllocated = (groupAllocated ?? 0) + alloc.qty_allocated;
                  groupConsumed += alloc.qty_consumed ?? 0;
                }
                return (
                  <table key={group.key} style={{ borderCollapse: 'collapse', width: '100%' }}>
                    <thead>
                      <tr>
                        <th style={{ textAlign: 'left', fontWeight: 400, color: '#71717a', padding: '2px 8px 2px 0' }}>
                          {qtyDominatorGroups.length > 1 ? `Qty limited by — ${group.label}` : 'Qty limited by'}
                        </th>
                        {showAllocCols && (
                          <>
                            <th style={{ textAlign: 'right', fontWeight: 400, color: '#71717a', padding: '2px 8px' }}>Allocated</th>
                            <th style={{ textAlign: 'right', fontWeight: 400, color: '#71717a', padding: '2px 0' }}>Consumed</th>
                          </>
                        )}
                      </tr>
                    </thead>
                    <tbody>
                      {group.refs.map((d, i) => {
                        const alloc = d.supply_id ? supplyAllocByLot?.get(d.supply_id) : undefined;
                        return (
                          <tr key={`rq-${group.key}-${i}`}>
                            <td style={{ padding: '2px 8px 2px 0' }}>
                              <DominatorLink kind="quantity" dominator={d} onClick={handleDominatorClick} contextDemandId={contextDemandId ?? tree.demand_id} showPrefix={false} />
                            </td>
                            {showAllocCols && (
                              <>
                                <td style={{ textAlign: 'right', padding: '2px 8px', color: '#d4d4d8' }}>
                                  {alloc?.qty_allocated != null ? qtyFmt(alloc.qty_allocated) : '–'}
                                </td>
                                <td style={{ textAlign: 'right', padding: '2px 0', color: '#d4d4d8' }}>
                                  {alloc ? qtyFmt(alloc.qty_consumed) : '–'}
                                </td>
                              </>
                            )}
                          </tr>
                        );
                      })}
                    </tbody>
                    {showAllocCols && (
                      <tfoot>
                        <tr style={{ borderTop: '1px solid #3d3d40' }}>
                          <td style={{ padding: '4px 8px 0 0', color: '#a1a1aa' }}>
                            {qtyDominatorGroups.length > 1 ? 'Subtotal' : 'Total'}
                          </td>
                          <td style={{ textAlign: 'right', padding: '4px 8px 0', color: '#fafafa', fontWeight: 600 }}>
                            {groupAllocated != null ? qtyFmt(groupAllocated) : '–'}
                          </td>
                          <td style={{ textAlign: 'right', padding: '4px 0 0', color: '#fafafa', fontWeight: 600 }}>
                            {qtyFmt(groupConsumed)}
                          </td>
                        </tr>
                      </tfoot>
                    )}
                  </table>
                );
              })}
            </div>
          )}
          {showTimeDominators && rootTimeDominators.map((d, i) => (
            <DominatorLink key={`rt-${i}`} kind="time" dominator={d} onClick={handleDominatorClick} contextDemandId={contextDemandId ?? tree.demand_id} />
          ))}
        </div>
      )}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6,
        marginTop: '0.25rem', marginBottom: '0.5rem',
        padding: '4px 6px', background: '#1c1c1e',
        border: '1px solid #3d3d40', borderRadius: 4,
      }}>
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              if (matchPaths.length > 0) stepMatch(e.shiftKey ? -1 : 1);
              else runSearch(search);
            } else if (e.key === 'Escape') {
              setSearch('');
              setMatchPaths([]);
              setMatchPath(null);
              setMatchIndex(0);
            } else {
              // Any edit invalidates prior matches; user presses Enter/Find to re-search.
              if (matchPaths.length > 0) {
                setMatchPaths([]);
                setMatchPath(null);
                setMatchIndex(0);
              }
            }
          }}
          placeholder="Find in pegging (product / location / supply / demand id)…"
          style={{ flex: 1, padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.8rem' }}
        />
        <button type="button" onClick={() => runSearch(search)}
          style={{ padding: '3px 8px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.78rem' }}>
          Find
        </button>
        <button type="button" onClick={() => stepMatch(-1)} disabled={matchPaths.length === 0}
          style={{ padding: '3px 8px', background: '#2d2d30', color: matchPaths.length === 0 ? '#52525b' : '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: matchPaths.length === 0 ? 'default' : 'pointer', fontSize: '0.78rem' }}>
          ↑
        </button>
        <button type="button" onClick={() => stepMatch(1)} disabled={matchPaths.length === 0}
          style={{ padding: '3px 8px', background: '#2d2d30', color: matchPaths.length === 0 ? '#52525b' : '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, cursor: matchPaths.length === 0 ? 'default' : 'pointer', fontSize: '0.78rem' }}>
          ↓
        </button>
        <span style={{ fontSize: '0.72rem', color: '#a1a1aa', minWidth: 60, textAlign: 'right' }}>
          {matchPaths.length === 0
            ? (search.trim() ? 'no match' : '')
            : `${matchIndex + 1} / ${matchPaths.length}`}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', minHeight: 0, marginTop: '0.5rem' }}>
        <PlanningPeggingTreeView
          tree={tree}
          expanded={expanded}
          onToggle={(p) => startTransition(() => setExpanded((prev) => {
            const next = new Set(prev);
            if (next.has(p)) next.delete(p); else next.add(p);
            return next;
          }))}
          matchPath={matchPath}
          matchPaths={matchPaths}
          explanationExpanded={explanationExpanded}
          onToggleExplanation={(p) => startTransition(() => setExplanationExpanded((prev) => {
            const next = new Set(prev);
            if (next.has(p)) next.delete(p); else next.add(p);
            return next;
          }))}
          workOrderRootQty={workOrderRootQty ?? null}
          contextDemandId={contextDemandId ?? null}
          consolidatedSourceResolver={(embeddedDemandId, pid) => {
            const allEntries = planningPegging.filter(
              (e) => String(e.demand_id ?? '').trim() === embeddedDemandId
            );
            return allEntries.slice(0, -1).map((e) => e.tree)
              .filter((t): t is PlanningPeggingNode => t != null && t.product_id === pid);
          }}
        />
      </div>
    </>
  );
}
