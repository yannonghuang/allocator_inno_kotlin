'use client';

import { useState } from 'react';
import { type BomGraphResponse, type BomGraphEdge, type BomGraphNode } from '@/lib/api';

export type BomDirection = 'demand-to-supply' | 'supply-to-demand';

const SEP = '\u001e';

// ── child group types ─────────────────────────────────────────────────────────

type IndependentGroup = { kind: 'independent'; edge: BomGraphEdge; childId: string };
type AndAltGroup     = { kind: 'and-alt';     altGroup: string;   items: { edge: BomGraphEdge; childId: string }[] };
type MoveGroup       = { kind: 'move';        edge: BomGraphEdge; childId: string };
type ChildGroup      = IndependentGroup | AndAltGroup | MoveGroup;

// ── child grouping ────────────────────────────────────────────────────────────

function getChildGroups(
  nodeId: string,
  data: BomGraphResponse,
  dir: BomDirection,
): ChildGroup[] {
  if (dir === 'supply-to-demand') {
    // Each outgoing make edge = one parent entry (OR-related).
    // Each outgoing move edge = one move hop.
    // Do NOT deduplicate by target: same parent via different altGroups = different contexts.
    const groups: ChildGroup[] = [];
    for (const e of data.edges) {
      if (e.source !== nodeId) continue;
      if (e.edgeType === 'move') {
        groups.push({ kind: 'move', edge: e, childId: e.target });
      } else if (e.edgeType === 'make') {
        groups.push({ kind: 'independent', edge: e, childId: e.target });
      }
    }
    return groups;
  }

  // demand-to-supply: incoming edges, makes grouped by altGroup, moves separate
  const edges  = data.edges.filter(e => e.target === nodeId);
  const makes  = edges.filter(e => e.edgeType === 'make');
  const moves  = edges.filter(e => e.edgeType === 'move');

  const altMap = new Map<string, BomGraphEdge[]>();
  const indeps: BomGraphEdge[] = [];

  for (const e of makes) {
    if (e.altGroup != null) {
      if (!altMap.has(e.altGroup)) altMap.set(e.altGroup, []);
      altMap.get(e.altGroup)!.push(e);
    } else {
      indeps.push(e);
    }
  }

  const groups: ChildGroup[] = [];
  for (const e of indeps) {
    groups.push({ kind: 'independent', edge: e, childId: e.source });
  }
  Array.from(altMap.entries()).forEach(([ag, es]: [string, BomGraphEdge[]]) => {
    groups.push({ kind: 'and-alt', altGroup: ag, items: es.map((e: BomGraphEdge) => ({ edge: e, childId: e.source })) });
  });
  for (const e of moves) {
    groups.push({ kind: 'move', edge: e, childId: e.source });
  }
  return groups;
}

// ── helpers ───────────────────────────────────────────────────────────────────

function edgeAnnotation(edge: BomGraphEdge, dir: BomDirection): string {
  if (edge.edgeType === 'make') {
    const parts: string[] = [];
    if (edge.rate != null && Math.abs(edge.rate - 1) > 0.0001) parts.push(`×${edge.rate}`);
    if (edge.altGroup) parts.push(`alt:${edge.altGroup}`);
    return parts.join(' ');
  }
  if (edge.edgeType === 'move' && edge.leadDays != null) return `${edge.leadDays}d`;
  return '';
}

const METHOD_COLOR: Record<string, string> = {
  buy:  '#f59e0b',
  make: '#3b82f6',
  move: '#06b6d4',
};

// ── OR separator ──────────────────────────────────────────────────────────────

function OrSeparator() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '3px 8px' }}>
      <div style={{ flex: 1, height: 1, background: '#3d3d40' }} />
      <span style={{ color: '#a78bfa', fontSize: '0.72em', fontWeight: 700, letterSpacing: '0.06em' }}>OR</span>
      <div style={{ flex: 1, height: 1, background: '#3d3d40' }} />
    </div>
  );
}

// ── recursive row ─────────────────────────────────────────────────────────────

type RowProps = {
  nodeId:      string;
  nodesById:   Record<string, BomGraphNode>;
  data:        BomGraphResponse;
  direction:   BomDirection;
  depth:       number;
  path:        string[];
  ancestorIds: Set<string>;
  expanded:    Set<string>;
  onToggle:    (pathKey: string, open: boolean) => void;
  incomingEdge?: BomGraphEdge;
};

function BomNodeRow({
  nodeId, nodesById, data, direction, depth, path, ancestorIds,
  expanded, onToggle, incomingEdge,
}: RowProps) {
  const node = nodesById[nodeId];
  if (!node) return null;

  const pathKey    = path.join(SEP);
  const isCycle    = ancestorIds.has(nodeId);
  const groups     = isCycle ? [] : getChildGroups(nodeId, data, direction);
  const hasChildren = groups.length > 0;
  const isOpen     = expanded.has(pathKey);

  const newAncestors = new Set([...Array.from(ancestorIds), nodeId]);

  // supply-to-demand: incomingEdge is the make edge from a component to THIS parent node.
  // Show AND context: all members of the same altGroup that this parent requires.
  // (The component we came from is among them; we show all so the user sees the full AND set.)
  const andContextEdges = (
    direction === 'supply-to-demand' &&
    incomingEdge?.edgeType === 'make' &&
    incomingEdge.altGroup != null
  )
    ? data.edges.filter(
        e => e.target === nodeId && e.altGroup === incomingEdge!.altGroup && e.edgeType === 'make',
      )
    : [];

  // demand-to-supply child classification
  const indeps  = groups.filter((g): g is IndependentGroup => g.kind === 'independent');
  const andAlts = groups.filter((g): g is AndAltGroup      => g.kind === 'and-alt');
  const moves   = groups.filter((g): g is MoveGroup        => g.kind === 'move');

  const annotation = incomingEdge ? edgeAnnotation(incomingEdge, direction) : '';

  return (
    <div style={{ marginLeft: depth === 0 ? 0 : 16 }}>

      {/* ── node header ── */}
      <div
        role="button"
        tabIndex={0}
        style={{
          display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
          padding: '5px 8px', borderRadius: 4,
          cursor: hasChildren ? 'pointer' : 'default',
          userSelect: 'none',
        }}
        onClick={() => hasChildren && onToggle(pathKey, !isOpen)}
        onKeyDown={e => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            if (hasChildren) onToggle(pathKey, !isOpen);
          }
        }}
      >
        <span style={{ width: 14, flexShrink: 0, color: '#71717a' }}>
          {isCycle ? '↩' : hasChildren ? (isOpen ? '▾' : '▸') : '·'}
        </span>

        <span style={{ color: '#e4e4e7' }}>
          {node.productId}
          <span style={{ color: '#71717a' }}> @ {node.locationId}</span>
        </span>

        {node.productDescription && (
          <span style={{ color: '#71717a', fontSize: '0.8em' }}>— {node.productDescription}</span>
        )}

        {/* establishment method tags */}
        {node.establishedBy.map(m => (
          <span key={m} style={{
            fontSize: '0.7em', padding: '1px 5px', borderRadius: 3,
            background: '#2d2d30', color: METHOD_COLOR[m] ?? '#a1a1aa',
          }}>{m}</span>
        ))}

        {/* move edge badge */}
        {incomingEdge?.edgeType === 'move' && (
          <span style={{
            fontSize: '0.7em', padding: '1px 5px', borderRadius: 3,
            background: '#1a3040', color: '#06b6d4',
          }}>move</span>
        )}

        {/* edge annotation (rate, transit days, altGroup) */}
        {annotation && (
          <span style={{ color: '#71717a', fontSize: '0.8em' }}>{annotation}</span>
        )}

        {/* supply-to-demand AND context: show all AND members of this altGroup inline */}
        {andContextEdges.length > 0 && (
          <span style={{
            fontSize: '0.72em', color: '#64748b', marginLeft: 2,
            display: 'flex', alignItems: 'center', gap: 3, flexWrap: 'wrap',
          }}>
            <span style={{ color: '#3b82f6', fontWeight: 700 }}>AND</span>
            {andContextEdges.map(e => {
              const n = nodesById[e.source];
              return (
                <span key={e.id} style={{
                  padding: '0px 5px', borderRadius: 3,
                  background: '#1e2a3a', color: '#93c5fd',
                }}>
                  {n ? n.productId : e.source}
                </span>
              );
            })}
          </span>
        )}

        {isCycle && (
          <span style={{ color: '#f87171', fontSize: '0.75em' }}>↩ cycle</span>
        )}
      </div>

      {/* ── children ── */}
      {isOpen && !isCycle && (
        <div style={{ borderLeft: '1px solid #3d3d40', marginLeft: 7 }}>

          {direction === 'supply-to-demand' && (
            <>
              {/* parent nodes — OR separated (each is a product that uses this component) */}
              {indeps.map((g, gi) => (
                <div key={g.edge.id}>
                  {gi > 0 && <OrSeparator />}
                  <BomNodeRow
                    nodeId={g.childId}
                    nodesById={nodesById} data={data} direction={direction}
                    depth={depth + 1} path={[...path, g.childId]}
                    ancestorIds={newAncestors} expanded={expanded} onToggle={onToggle}
                    incomingEdge={g.edge}
                  />
                </div>
              ))}

              {/* move hops */}
              {moves.map(g => (
                <BomNodeRow
                  key={g.edge.id}
                  nodeId={g.childId}
                  nodesById={nodesById} data={data} direction={direction}
                  depth={depth + 1} path={[...path, g.childId]}
                  ancestorIds={newAncestors} expanded={expanded} onToggle={onToggle}
                  incomingEdge={g.edge}
                />
              ))}
            </>
          )}

          {direction === 'demand-to-supply' && (
            <>
              {/* independent make requirements (null altGroup — always needed) */}
              {indeps.map(g => (
                <BomNodeRow
                  key={g.edge.id}
                  nodeId={g.childId}
                  nodesById={nodesById} data={data} direction={direction}
                  depth={depth + 1} path={[...path, g.childId]}
                  ancestorIds={newAncestors} expanded={expanded} onToggle={onToggle}
                  incomingEdge={g.edge}
                />
              ))}

              {/* named alt groups — OR between groups, AND within a group */}
              {andAlts.map((g, gi) => (
                <div key={g.altGroup}>
                  {gi > 0 && <OrSeparator />}

                  {/* AND group bracket — only drawn when group has 2+ members */}
                  <div style={{
                    borderLeft: g.items.length > 1 ? '2px solid #3b82f6' : undefined,
                    marginLeft: g.items.length > 1 ? 8 : 0,
                    paddingLeft: g.items.length > 1 ? 4 : 0,
                  }}>
                    <div style={{
                      fontSize: '0.68em', padding: '1px 6px', fontWeight: 700,
                      color: g.items.length > 1 ? '#3b82f6' : '#64748b',
                      letterSpacing: '0.04em',
                    }}>
                      {g.altGroup}{g.items.length > 1 ? ' — AND' : ''}
                    </div>

                    {g.items.map(({ edge, childId }) => (
                      <BomNodeRow
                        key={edge.id}
                        nodeId={childId}
                        nodesById={nodesById} data={data} direction={direction}
                        depth={depth + 1} path={[...path, childId]}
                        ancestorIds={newAncestors} expanded={expanded} onToggle={onToggle}
                        incomingEdge={edge}
                      />
                    ))}
                  </div>
                </div>
              ))}

              {/* move edges */}
              {moves.map(g => (
                <BomNodeRow
                  key={g.edge.id}
                  nodeId={g.childId}
                  nodesById={nodesById} data={data} direction={direction}
                  depth={depth + 1} path={[...path, g.childId]}
                  ancestorIds={newAncestors} expanded={expanded} onToggle={onToggle}
                  incomingEdge={g.edge}
                />
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}

// ── public component ──────────────────────────────────────────────────────────

type BomTreeProps = {
  data:        BomGraphResponse;
  nodesById:   Record<string, BomGraphNode>;
  rootNodeId:  string;
  direction:   BomDirection;
};

export function BomTree({ data, nodesById, rootNodeId, direction }: BomTreeProps) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const onToggle = (pathKey: string, open: boolean) =>
    setExpanded(prev => {
      const next = new Set(prev);
      open ? next.add(pathKey) : next.delete(pathKey);
      return next;
    });

  return (
    <div style={{
      background: '#252528', borderRadius: 8, padding: '0.5rem',
      maxHeight: '60vh', overflow: 'auto', fontSize: '0.9rem',
    }}>
      <BomNodeRow
        nodeId={rootNodeId}
        nodesById={nodesById} data={data} direction={direction}
        depth={0} path={[rootNodeId]}
        ancestorIds={new Set()} expanded={expanded} onToggle={onToggle}
      />
    </div>
  );
}
