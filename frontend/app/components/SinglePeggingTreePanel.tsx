'use client';

import React, { startTransition, useMemo, useState } from 'react';
import type { PlanningPeggingEntry, PlanningPeggingNode } from '@/lib/api';
import { PlanningPeggingTreeView } from './PlanningPeggingTreeView';

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
};

/** A single demand's or single work order's pegging tree, with search,
 *  critical-path highlighting, and a "why method" explanation toggle — the
 *  one view used everywhere a single (non-consolidated) pegging tree is
 *  shown. Starts fully collapsed (only the root row shows) — the dominator
 *  sub-tree (critical path) is still gold-highlighted once expanded, but
 *  isn't auto-expanded, since on a long single-chain tree that would expand
 *  almost the whole thing and defeat "collapsed by default".
 *
 *  Give this component a `key` tied to the identity of what's being viewed
 *  (demand id / WO row key) so React remounts it — and resets all its
 *  internal state — when the caller switches to a different tree. */
export function SinglePeggingTreePanel({
  tree,
  contextDemandId,
  workOrderRootQty,
  planningPegging,
}: SinglePeggingTreePanelProps): JSX.Element {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [explanationExpanded, setExplanationExpanded] = useState<Set<string>>(() => new Set());
  const [search, setSearch] = useState('');
  const [matchPaths, setMatchPaths] = useState<string[]>([]);
  const [matchPath, setMatchPath] = useState<string | null>(null);
  const [matchIndex, setMatchIndex] = useState(0);

  // Critical path = the dominator SUB-TREE of the pegging tree.
  //   - AND junction (work_order parents): single AND-min child.
  //     Planner pre-flags it via is_bottleneck / is_root_bottleneck;
  //     break ties by smallest committed_qty/quantity ratio, then
  //     tree order. If no direct child is flagged but a descendant
  //     is, descend through the transit child with smallest ratio
  //     (method WO between BOM levels carries no flag).
  //   - OR junction (demand parents, alternative paths): every
  //     contributing child (qty>0 OR committed_qty>0) is a
  //     dominator. The path BRANCHES.
  // Mirrors the backend `traceCriticalPath` Kotlin helper exactly.
  const criticalPathSet = useMemo(() => {
    const result = new Set<string>();
    const hasFlaggedDescendant = (n: PlanningPeggingNode): boolean => {
      if (n.is_bottleneck || n.is_root_bottleneck) return true;
      return (n.children ?? []).some(hasFlaggedDescendant);
    };
    const ratio = (c: PlanningPeggingNode): number => {
      const q = Number(c.quantity ?? 0);
      const cq = Number((c as { committed_qty?: number | null }).committed_qty ?? q);
      return q < 1e-9 ? 0 : cq / q;
    };
    const contributed = (c: PlanningPeggingNode): boolean => {
      const q = Number(c.quantity ?? 0);
      const cq = Number((c as { committed_qty?: number | null }).committed_qty ?? q);
      return q > 1e-9 || cq > 1e-9;
    };
    const relationOf = (n: PlanningPeggingNode): 'and' | 'or' => {
      const explicit = (n as { children_relation?: string | null }).children_relation;
      if (explicit === 'and' || explicit === 'or') return explicit;
      return n.type === 'work_order' ? 'and' : 'or';
    };
    const walk = (n: PlanningPeggingNode | null, path: string): void => {
      if (!n) return;
      if ((n as { consolidated_consumer?: boolean }).consolidated_consumer) return;
      result.add(path);
      const kids = n.children ?? [];
      if (kids.length === 0) return;
      const contributingKids = kids.map((c, i) => ({ c, i })).filter(({ c }) => contributed(c));
      if (contributingKids.length === 0) return;
      if (relationOf(n) === 'or') {
        contributingKids.forEach(({ c, i }) => walk(c, `${path}-${i}`));
        return;
      }
      const flagged = contributingKids.filter(({ c }) => c.is_bottleneck || c.is_root_bottleneck);
      let pick: { c: PlanningPeggingNode; i: number } | null = null;
      if (flagged.length > 0) {
        flagged.sort((a, b) => {
          const ra = ratio(a.c); const rb = ratio(b.c);
          return Math.abs(ra - rb) > 1e-9 ? ra - rb : a.i - b.i;
        });
        pick = flagged[0];
      } else {
        const transit = contributingKids.filter(({ c }) => hasFlaggedDescendant(c));
        if (transit.length === 0) return;
        transit.sort((a, b) => {
          const ra = ratio(a.c); const rb = ratio(b.c);
          return Math.abs(ra - rb) > 1e-9 ? ra - rb : a.i - b.i;
        });
        pick = transit[0];
      }
      walk(pick.c, `${path}-${pick.i}`);
    };
    walk(tree, '0');
    return result;
  }, [tree]);

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

  return (
    <>
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
          criticalPathSet={criticalPathSet}
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
