'use client';

import React from 'react';
import type { PlanningPeggingNode } from '@/lib/api';
import { qtyFmt } from '@/app/lib/format';

/** Renders a planning_pegging tree (demand → work_order → supply | purchase)
 *  using the same node visuals the planPegging slide-in uses. All advanced
 *  features (search highlight, critical-path badges, consolidated-supply
 *  unfolding, "why method" expand) are wired through optional props so a
 *  caller that doesn't need them can pass nothing and still get a working
 *  tree. */
export type PlanningPeggingTreeProps = {
  tree: PlanningPeggingNode;
  /** Path expansion state; lifted to the caller so external buttons (search
   *  jump-to-match etc.) can expand programmatically. */
  expanded: Set<string>;
  onToggle: (path: string) => void;

  /** Currently-selected search match path. Gold-highlighted + scrolled into view. */
  matchPath?: string | null;
  /** Every search match (subtle gold). */
  matchPaths?: string[];
  /** Paths on the critical path (bottleneck / root-cause / transit). */
  criticalPathSet?: Set<string>;

  /** "Why method" panel expand state + toggle. When omitted, the
   *  explanation is never shown. */
  explanationExpanded?: Set<string>;
  onToggleExplanation?: (path: string) => void;

  /** When viewing WO-level pegging, override the root's displayed qty
   *  to the table-row's qty (the tree's root may carry a different value). */
  workOrderRootQty?: number | null;
  /** demand_id of the parent context demand; suppresses redundant
   *  "(demand X)" suffixes on labels for matching nodes. */
  contextDemandId?: string | null;

  /** Resolver for consolidated supply source trees. Called when a supply
   *  node's supply_id starts with "consolidated_"; receives the embedded
   *  demand_id and the product_id, returns the source trees that fed this
   *  consolidation. When omitted, consolidated supplies render as plain
   *  leaves with no inline source unfolding. */
  consolidatedSourceResolver?: (embeddedDemandId: string, productId: string) => PlanningPeggingNode[];
};

export function PlanningPeggingTreeView(props: PlanningPeggingTreeProps): JSX.Element {
  return (
    <NodeView
      node={props.tree}
      path="0"
      depth={0}
      xlink={false}
      expanded={props.expanded}
      onToggle={props.onToggle}
      matchPath={props.matchPath}
      matchPaths={props.matchPaths}
      criticalPathSet={props.criticalPathSet}
      explanationExpanded={props.explanationExpanded}
      onToggleExplanation={props.onToggleExplanation}
      workOrderRootQty={props.workOrderRootQty}
      contextDemandId={props.contextDemandId}
      consolidatedSourceResolver={props.consolidatedSourceResolver}
    />
  );
}

type NodeProps = Omit<PlanningPeggingTreeProps, 'tree'> & {
  node: PlanningPeggingNode;
  path: string;
  depth: number;
  xlink: boolean;
};

function NodeView({
  node, path, depth, xlink,
  expanded, onToggle,
  matchPath, matchPaths, criticalPathSet,
  explanationExpanded, onToggleExplanation,
  workOrderRootQty, contextDemandId,
  consolidatedSourceResolver,
}: NodeProps): JSX.Element {
  const rawChildren = node.children ?? [];
  const childContrib = (c: PlanningPeggingNode): number => {
    const cc = (c as { committed_qty?: number | null }).committed_qty;
    return Number((cc != null ? cc : c.quantity) ?? 0);
  };
  let childrenList = rawChildren;
  // Blocked work_order with no `failed` marker — collapse the subtree.
  // The slot's method_choice_explanation already names the deepest bottleneck.
  const isLegacyBlockedWo = node.type === 'work_order'
    && !node.failed
    && Number(node.quantity ?? 0) <= 1e-9
    && rawChildren.length > 0;
  if (isLegacyBlockedWo) childrenList = [];

  // OR-relation: hide failed alternatives when at least one path contributed.
  if (!isLegacyBlockedWo && node.children_relation === 'or' && childrenList.length > 1) {
    const contribCount = childrenList.reduce((n, c) => n + (childContrib(c) > 1e-9 ? 1 : 0), 0);
    if (contribCount > 0 && contribCount < childrenList.length) {
      childrenList = childrenList.filter((c) => childContrib(c) > 1e-9);
    }
  }

  // Consolidated supply source trees (only resolvable if the parent provides
  // the lookup). supply_id format: "consolidated_${demandId}_${productId}".
  let consolidatedSourceTrees: PlanningPeggingNode[] = [];
  if (!xlink && node.type === 'supply' && node.supply_id?.startsWith('consolidated_') && consolidatedSourceResolver) {
    const withoutPrefix = node.supply_id.slice('consolidated_'.length);
    const pid = node.product_id ?? '';
    const embeddedDemandId = pid && withoutPrefix.endsWith(`_${pid}`)
      ? withoutPrefix.slice(0, -(pid.length + 1))
      : withoutPrefix;
    consolidatedSourceTrees = consolidatedSourceResolver(embeddedDemandId, pid);
  }

  const hasChildren = childrenList.length > 0 || consolidatedSourceTrees.length > 0;
  const isRoot = path === '0';
  const expandable = hasChildren || isRoot;
  const isExpanded = expanded.has(path);
  const isDemand = node.type === 'demand';
  const isWorkOrder = node.type === 'work_order';
  const isOperation = node.type === 'operation';
  const isResource = node.type === 'resource';
  // Distinct glyphs per type:
  //   ⚙ work_order, ▢ demand/supply/purchase (need/inventory),
  //   ⚒ operation (bill-of-resources card), ◆ resource (a single resource line).
  const icon = isWorkOrder ? '⚙'
    : isOperation ? '⚒'
    : isResource ? '◆'
    : '▢';
  const typeLabel = isDemand ? 'Need'
    : isWorkOrder ? 'Work order'
    : isOperation ? 'Bill of resources'
    : isResource ? 'Resource'
    : node.type === 'supply' ? 'Supply'
    : node.type === 'purchase' ? 'Purchase'
    : '';
  const typeColor = isDemand ? '#60a5fa'
    : isWorkOrder ? '#34d399'
    : isOperation ? '#fbbf24'   // amber — production setup
    : isResource ? '#22d3ee'    // cyan — capacity / tooling
    : '#a78bfa';

  const label = isDemand
    ? (() => {
        const reqQty = Number(node.quantity ?? 0);
        const committedRaw = (node as { committed_qty?: number | null }).committed_qty;
        const commQty = committedRaw == null ? reqQty : Number(committedRaw);
        const qtyLabel = commQty < reqQty - 1e-6
          ? `${qtyFmt(commQty)} / ${qtyFmt(reqQty)}`
          : qtyFmt(reqQty);
        return `${node.product_id ?? node.demand_id ?? '–'} · ${qtyLabel} @ ${node.location_id ?? '–'}${node.demand_id && node.product_id !== node.demand_id && node.demand_id !== contextDemandId ? ` (demand ${node.demand_id})` : ''}`;
      })()
    : isWorkOrder
      ? (() => {
          const qty = (isRoot && workOrderRootQty != null) ? workOrderRootQty : Number(node.quantity ?? 0);
          const lotCount = (node as { lot_count?: number | null }).lot_count ?? null;
          const maxLotSize = (node as { max_lot_size?: number | null }).max_lot_size ?? null;
          const waveCount = node.wave_count ?? null;
          const cap = node.parallelism_cap ?? null;
          const lotPart = lotCount && lotCount > 1 && maxLotSize
            ? ` · ${lotCount} lots of up to ${qtyFmt(Number(maxLotSize))}`
            : '';
          // Wave annotation only when concurrency actually compresses the
          // schedule (wave_count < lot_count). Skip for sequential WOs to
          // keep labels tight.
          const wavePart = waveCount && lotCount && cap && cap > 1 && waveCount < lotCount
            ? ` · ${waveCount} wave${waveCount > 1 ? 's' : ''} of up to ${cap} parallel`
            : '';
          return `${node.method} ${node.product_id} @ ${node.location_id ?? '–'} · ${qtyFmt(qty)}${node.end_time ? ` · end ${node.end_time}` : ''}${lotPart}${wavePart}`;
        })()
      : isOperation
        ? (() => {
            // Bill-of-resources line — show op_id, location, and the production
            // rate fields. No qty: the operation isn't a need, it's metadata
            // describing how the parent WO is produced.
            const opId = node.operation_id ?? node.resource_id ?? '–';
            const parts: string[] = [`operation ${opId} @ ${node.location_id ?? '–'}`];
            if (node.uph != null) parts.push(`UPH ${node.uph}`);
            if (node.yield_factor != null) parts.push(`yield ${node.yield_factor}`);
            if (node.process_time != null || node.pre_process_time != null || node.post_process_time != null) {
              parts.push(`pre/proc/post ${node.pre_process_time ?? 0}/${node.process_time ?? 0}/${node.post_process_time ?? 0}s`);
            }
            // Concurrency cap = min(floor(size/rate)) across BOR resources.
            // 0 means the override isn't actually applicable; 1 means lots
            // run sequentially; > 1 means waves compress the schedule.
            if (node.parallelism_cap != null && node.parallelism_cap > 0) {
              parts.push(`up to ${node.parallelism_cap} parallel lot${node.parallelism_cap > 1 ? 's' : ''}`);
            }
            return parts.join(' · ');
          })()
        : isResource
          ? (() => {
              // resource_rate is the per-lot consumption — the number of this
              // resource a single lot of the parent WO occupies while it runs.
              // The location's pool size lives on the row payload but isn't
              // shown here; it's surfaced in the Resource utilization view
              // (where the per-lot rates sum and bump against pool capacity).
              const rid = node.resource_id ?? '–';
              const rate = node.resource_rate;
              const parts: string[] = [`${rid} @ ${node.location_id ?? '–'}`];
              if (rate != null) parts.push(`capacity ${rate}/lot`);
              return parts.join(' · ');
            })()
          : node.type === 'supply'
            ? `${node.product_id} @ ${node.location_id ?? '–'} · ${qtyFmt(Number(node.quantity ?? 0))}${node.supply_id ? ` · ${node.supply_id}` : ''}`
            : `${node.product_id} @ ${node.location_id ?? '–'} · ${qtyFmt(Number(node.quantity ?? 0))}`;

  const indentPx = 12;
  let childGroupLabel: string | null = null;
  let childGroupKind: 'and' | 'or' | 'consumers' | null = null;
  const relation = node.children_relation as 'and' | 'or' | undefined;
  // Consumer breakdown: the children are demands that draw their share from THIS single
  // consolidated order (a purchase consolidated within & across demands). They don't "supply"
  // the node (OR) nor are co-required (AND) — they consume it. Label accordingly.
  const isConsumerBreakdown = hasChildren && childrenList.length > 0 &&
    childrenList.every((c) => (c as { consolidated_consumer?: boolean }).consolidated_consumer);
  if (isConsumerBreakdown) {
    childGroupKind = 'consumers';
    childGroupLabel = 'required in the following demands — one consolidated order, shared across them.';
  } else if (relation === 'or' && hasChildren && childrenList.length > 1) {
    childGroupKind = 'or';
    childGroupLabel = 'ANY of the inventories / work orders below can supply this node (OR).';
  } else if (relation === 'and' && hasChildren && childrenList.length > 1) {
    childGroupKind = 'and';
    childGroupLabel = 'ALL of the inventories / work orders below are required together (AND).';
  } else if (!relation && hasChildren && childrenList.length > 1) {
    if (node.type === 'work_order') {
      childGroupKind = 'and';
      childGroupLabel = 'ALL of the inventories / work orders below are required together (AND).';
    } else if (node.type === 'demand') {
      const sameProductBucketsOnly = childrenList.every((c) =>
        (c.type === 'supply' || c.type === 'purchase') && c.product_id === node.product_id,
      );
      if (!sameProductBucketsOnly) {
        childGroupKind = 'or';
        childGroupLabel = 'ANY of the inventories / work orders below can supply this node (OR).';
      }
    }
  }

  const isActiveMatch = matchPath === path;
  const isAnyMatch = (matchPaths ?? []).includes(path);
  const onCriticalPath = (criticalPathSet ?? new Set<string>()).has(path);
  const isTransitOnPath = onCriticalPath
    && path !== '0'
    && !node.is_bottleneck
    && !node.is_root_bottleneck;

  const explanationPath = `explain-${path}`;
  const isExplanationOpen = (explanationExpanded ?? new Set<string>()).has(explanationPath);

  return (
    <div
      style={{ marginBottom: 4 }}
      ref={isActiveMatch ? ((el) => { if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }) : undefined}
    >
      <button
        type="button"
        onClick={() => onToggle(path)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          width: '100%',
          textAlign: 'left',
          padding: '4px 6px',
          background: isActiveMatch
            ? 'rgba(250, 204, 21, 0.28)'
            : isAnyMatch
              ? 'rgba(250, 204, 21, 0.12)'
              : (onCriticalPath && node.is_root_bottleneck)
                ? 'rgba(248, 113, 113, 0.12)'
                : (onCriticalPath && (node.is_bottleneck || isTransitOnPath))
                  ? 'rgba(250, 204, 21, 0.08)'
                  : depth % 2 === 0 ? 'rgba(255,255,255,0.04)' : 'transparent',
          border: isActiveMatch
            ? '1px solid #facc15'
            : (onCriticalPath && node.is_root_bottleneck)
              ? '1px solid rgba(248, 113, 113, 0.55)'
              : (onCriticalPath && (node.is_bottleneck || isTransitOnPath))
                ? '1px solid rgba(250, 204, 21, 0.55)'
                : 'none',
          borderRadius: 4,
          color: '#e4e4e7',
          cursor: expandable ? 'pointer' : 'default',
          fontSize: '0.85rem',
        }}
      >
        <span style={{ width: 14, flexShrink: 0 }}>{expandable ? (isExpanded ? '▼' : '▶') : '·'}</span>
        <span style={{ width: 18, flexShrink: 0, fontSize: '0.9em', color: typeColor }} title={typeLabel}>{icon}</span>
        <span style={{ flex: 1, color: typeColor }}>{label}</span>
        {onCriticalPath && node.is_bottleneck && (
          <span
            title="瓶颈 (supply-side limiter on critical path): 此子节点的供应链(BOM/库存/子配方)无法满足需求 — 其首轮可达量与需求量之比在AND兄弟中最小,通过MIN(子份额)封顶父节点的可达量。属供应侧约束。修复方向: 增加库存、启用采购、补充方法行(method_make/move/buy)、或解除更深处配方的阻塞。"
            style={{ fontSize: '0.7em', color: '#facc15', background: 'rgba(250, 204, 21, 0.16)', padding: '1px 6px', borderRadius: 3, flexShrink: 0 }}
          >瓶颈</span>
        )}
        {onCriticalPath && node.is_root_bottleneck && (
          <span
            title="根因 (demand-side allocation origin on critical path): 在iter-0合并阶段,该需求与其他需求竞争此叶子时分到的份额相对其需求量最紧 — 即同一AND层级中, 该需求的(份额/需求)比率最小。与供应是否充足无关 — 即使供应充足,本需求在此叶子上的配额最先吃紧。修复方向: 调整本需求优先级、改变 allocation_mode (fair/proportional/priority_first)、改变合并 period_days、或减少其他需求在此叶子的竞争压力。"
            style={{ fontSize: '0.7em', color: '#fff', background: 'rgba(220, 38, 38, 0.85)', padding: '1px 6px', borderRadius: 3, flexShrink: 0, fontWeight: 700 }}
          >根因</span>
        )}
        {isTransitOnPath && (
          <span
            title="关键路径上的中转节点 (Critical-path transit): 此节点本身不是短缺起源 (无 瓶颈/根因 标志)，但它在从需求到起源的支配链上。"
            style={{ fontSize: '0.7em', color: '#facc15', background: 'rgba(250, 204, 21, 0.16)', padding: '1px 6px', borderRadius: 3, flexShrink: 0 }}
          >★ 关键路径</span>
        )}
      </button>
      {node.type === 'work_order' && node.method_choice_explanation && onToggleExplanation && (
        <div style={{ marginTop: 4, marginLeft: 4, fontSize: '0.75rem', color: '#a1a1aa' }}>
          <button
            type="button"
            onClick={() => onToggleExplanation(explanationPath)}
            style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '2px 0', background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.75rem' }}
          >
            {isExplanationOpen ? '▼' : '▶'}
            Why (method)
          </button>
          {isExplanationOpen && (
            <div style={{ paddingLeft: 8, borderLeft: '2px solid #3d3d40', marginTop: 2 }}>
              <p style={{ margin: 0, lineHeight: 1.35 }}><strong>Method:</strong> {node.method_choice_explanation}</p>
            </div>
          )}
        </div>
      )}
      {expandable && isExpanded && (
        <div style={{ marginTop: 2, paddingLeft: indentPx }}>
          {childGroupLabel && (
            <div
              style={{
                marginBottom: 2,
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                fontSize: '0.7rem',
                color: childGroupKind === 'and' ? '#f97316' : childGroupKind === 'consumers' ? '#a78bfa' : '#38bdf8',
                backgroundColor: childGroupKind === 'and' ? 'rgba(249,115,22,0.12)' : childGroupKind === 'consumers' ? 'rgba(167,139,250,0.12)' : 'rgba(56,189,248,0.12)',
                borderRadius: 999,
                padding: '1px 6px',
              }}
            >
              {childGroupKind !== 'consumers' && <span style={{ fontWeight: 700 }}>{childGroupKind === 'and' ? 'AND' : 'OR'}</span>}
              <span>{childGroupLabel}</span>
            </div>
          )}
          {childrenList.length > 0
            ? childrenList.map((child, i) => (
                <NodeView
                  key={i}
                  node={child}
                  path={`${path}-${i}`}
                  depth={depth + 1}
                  xlink={xlink}
                  expanded={expanded}
                  onToggle={onToggle}
                  matchPath={matchPath}
                  matchPaths={matchPaths}
                  criticalPathSet={criticalPathSet}
                  explanationExpanded={explanationExpanded}
                  onToggleExplanation={onToggleExplanation}
                  workOrderRootQty={null}
                  contextDemandId={contextDemandId}
                  consolidatedSourceResolver={consolidatedSourceResolver}
                />
              ))
            : consolidatedSourceTrees.length > 0
              ? null
              : node.type === 'demand'
                ? ((node as { consolidated_consumer?: boolean }).consolidated_consumer
                    ? <p style={{ margin: 0, fontSize: '0.78rem', color: '#71717a', fontStyle: 'italic' }}>↳ draws its share from the single consolidated order above</p>
                    : node.failure_explanation
                      ? <p style={{ margin: 0, fontSize: '0.8rem', color: '#f87171', lineHeight: 1.4 }}>{node.failure_explanation}</p>
                      : <p style={{ margin: 0, fontSize: '0.8rem', color: '#f87171' }}>No work orders — planning could not fulfill this demand (no method or child failed).</p>)
                : node.type === 'work_order'
                  ? <p style={{ margin: 0, fontSize: '0.8rem', color: '#71717a' }}>No component breakdown (leaf work order or depth-limited).</p>
                  : null
          }
          {consolidatedSourceTrees.length > 0 && isExpanded && (
            <>
              <div style={{ marginBottom: 3, marginTop: 2, display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.7rem', color: '#fb923c', backgroundColor: 'rgba(251,146,60,0.12)', borderRadius: 999, padding: '1px 6px' }}>
                ↑ original supplies consumed by this consolidation
              </div>
              {consolidatedSourceTrees.map((t, i) => (
                <NodeView
                  key={`cs${i}`}
                  node={t}
                  path={`${path}-cs${i}`}
                  depth={depth + 1}
                  xlink={true}
                  expanded={expanded}
                  onToggle={onToggle}
                  matchPath={matchPath}
                  matchPaths={matchPaths}
                  criticalPathSet={criticalPathSet}
                  explanationExpanded={explanationExpanded}
                  onToggleExplanation={onToggleExplanation}
                  workOrderRootQty={null}
                  contextDemandId={contextDemandId}
                  consolidatedSourceResolver={consolidatedSourceResolver}
                />
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}
