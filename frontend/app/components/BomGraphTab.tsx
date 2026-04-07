'use client';

import { useEffect, useMemo, useState } from 'react';
import { BomTree, type BomDirection } from '@/app/components/BomTree';
import { getBomGraph, type BomGraphResponse } from '@/lib/api';

export default function BomGraphTab({ caseId }: { caseId: number }) {
  const [data,      setData]      = useState<BomGraphResponse | null>(null);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState<string | null>(null);
  const [search,    setSearch]    = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [direction, setDirection] = useState<BomDirection>('demand-to-supply');
  const [activeFilters, setActiveFilters] = useState<Set<'raw' | 'finished' | 'shared'>>(new Set());

  useEffect(() => {
    setLoading(true);
    setError(null);
    getBomGraph(caseId)
      .then(d => {
        setData(d);
        // default: first demand node, otherwise first node
        const first = d.nodes.find(n => n.isDemand) ?? d.nodes[0] ?? null;
        setSelectedId(first?.id ?? null);
        setSearch('');
      })
      .catch(e => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [caseId]);

  const nodesById = useMemo(
    () => data ? Object.fromEntries(data.nodes.map(n => [n.id, n])) : {},
    [data],
  );

  // edge-derived sets for node classification
  const makeTargetIds = useMemo(() => new Set(data?.edges.filter(e => e.edgeType === 'make').map(e => e.target) ?? []), [data]);
  const makeSourceIds = useMemo(() => new Set(data?.edges.filter(e => e.edgeType === 'make').map(e => e.source) ?? []), [data]);
  // move targets: nodes that receive product via a move edge (not raw — just a different location)
  const moveTargetIds = useMemo(() => new Set(data?.edges.filter(e => e.edgeType === 'move').map(e => e.target) ?? []), [data]);
  // shared: component used as make-input for ≥2 distinct parent products
  // (mutually exclusive with finished good by definition — a make-source can't also be a non-make-source)
  const sharedIds = useMemo(() => {
    if (!data) return new Set<string>();
    const parentSets = new Map<string, Set<string>>();
    for (const e of data.edges) {
      if (e.edgeType !== 'make') continue;
      if (!parentSets.has(e.source)) parentSets.set(e.source, new Set());
      parentSets.get(e.source)!.add(e.target);
    }
    return new Set([...parentSets.entries()]
      .filter(([, parents]) => parents.size >= 2)
      .map(([id]) => id));
  }, [data]);

  if (loading) return <p style={{ color: '#a1a1aa' }}>Loading BOM graph…</p>;
  if (error)   return <p style={{ color: '#f87171' }}>Error: {error}</p>;
  if (!data || data.nodes.length === 0)
    return <p style={{ color: '#a1a1aa' }}>No BOM graph nodes found for this case.</p>;

  const toggleFilter = (f: 'raw' | 'finished' | 'shared') =>
    setActiveFilters(prev => { const s = new Set(prev); s.has(f) ? s.delete(f) : s.add(f); return s; });

  const isRaw      = (id: string) => !makeTargetIds.has(id) && !moveTargetIds.has(id);  // no make OR move edges point to it — truly procure-only
  const isFinished = (id: string) => !makeSourceIds.has(id);  // never used as a component in any BOM
  const isShared   = (id: string) => sharedIds.has(id);

  const rawCount      = data.nodes.filter(n => isRaw(n.id)).length;
  const finishedCount = data.nodes.filter(n => isFinished(n.id)).length;
  const sharedCount   = data.nodes.filter(n => isShared(n.id)).length;

  const q = search.trim().toLowerCase();
  let filtered = data.nodes.filter(n =>
    !q ||
    n.productId.toLowerCase().includes(q) ||
    n.locationId.toLowerCase().includes(q) ||
    (n.productDescription  ?? '').toLowerCase().includes(q) ||
    (n.locationDescription ?? '').toLowerCase().includes(q),
  );
  if (activeFilters.has('raw'))      filtered = filtered.filter(n => isRaw(n.id));
  if (activeFilters.has('finished')) filtered = filtered.filter(n => isFinished(n.id));
  if (activeFilters.has('shared'))   filtered = filtered.filter(n => isShared(n.id));
  // demand nodes first
  const sorted = [...filtered].sort((a, b) => Number(b.isDemand) - Number(a.isDemand));

  const selectedNode = selectedId ? nodesById[selectedId] : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>

      {/* stats row */}
      <p style={{ color: '#a1a1aa', fontSize: '0.82em', margin: 0 }}>
        <strong style={{ color: '#e4e4e7' }}>{data.nodeCount}</strong> nodes &nbsp;·&nbsp;
        <strong style={{ color: '#e4e4e7' }}>{data.edgeCount}</strong> edges &nbsp;·&nbsp;
        {data.nodes.filter(n => n.isDemand).length} demand roots
        &nbsp;·&nbsp;
        <span style={{ color: '#71717a' }}>
          Within AND groups children are all required; between OR groups choose one alternative.
          <span style={{ color: '#3b82f6' }}> Blue bracket</span> = AND.
          <span style={{ color: '#a78bfa' }}> OR</span> = alternatives.
        </span>
      </p>

      {/* picker + direction */}
      <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>

        {/* node search list */}
        <div style={{ flex: '1 1 260px', minWidth: 200, maxWidth: 380 }}>
          <div style={{ color: '#a1a1aa', fontSize: '0.82em', marginBottom: 4 }}>
            Starting node
          </div>
          {/* filter pills */}
          <div style={{ display: 'flex', gap: '0.35rem', flexWrap: 'wrap', marginBottom: 6 }}>
            {([
              { key: 'raw',      label: `Raw material`,           count: rawCount,      title: 'No make or move edges point to it — must be procured directly' },
              { key: 'finished', label: `Finished good`,          count: finishedCount, title: 'Not used as a component in any other product' },
              { key: 'shared',   label: `Shared component`,       count: sharedCount,   title: 'Contributes to 2 or more distinct parent products' },
            ] as const).map(({ key, label, count, title }) => (
              <button
                key={key}
                type="button"
                title={title}
                className={activeFilters.has(key) ? '' : 'secondary'}
                style={{ fontSize: '0.75em', padding: '2px 8px' }}
                onClick={() => toggleFilter(key)}
              >
                {label} <span style={{ opacity: 0.7 }}>({count})</span>
              </button>
            ))}
          </div>
          <input
            type="text"
            placeholder="Search product or location…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ width: '100%', marginBottom: 4 }}
          />
          <div style={{
            background: '#18181b', border: '1px solid #3f3f46',
            borderRadius: 6, maxHeight: 200, overflow: 'auto',
          }}>
            {sorted.length === 0 && (
              <div style={{ padding: '6px 10px', color: '#71717a', fontSize: '0.85em' }}>No matches</div>
            )}
            {sorted.map(n => (
              <div
                key={n.id}
                role="button"
                tabIndex={0}
                onClick={() => setSelectedId(n.id)}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') setSelectedId(n.id); }}
                style={{
                  padding: '5px 10px', cursor: 'pointer',
                  background: n.id === selectedId ? '#1e3a5f' : 'transparent',
                  borderBottom: '1px solid #27272a',
                  fontSize: '0.85em',
                }}
              >
                <div>
                  <span style={{ color: '#e4e4e7' }}>{n.productId}</span>
                  <span style={{ color: '#71717a' }}> @ {n.locationId}</span>
                  {n.isDemand   && <span style={{ marginLeft: 5, fontSize: '0.68em', padding: '1px 4px', borderRadius: 3, background: '#2d2d30', color: '#a78bfa' }}>demand</span>}
                  {isRaw(n.id)  && <span style={{ marginLeft: 5, fontSize: '0.68em', padding: '1px 4px', borderRadius: 3, background: '#2d2d30', color: '#f59e0b' }}>raw</span>}
                  {isShared(n.id) && <span style={{ marginLeft: 5, fontSize: '0.68em', padding: '1px 4px', borderRadius: 3, background: '#2d2d30', color: '#22c55e' }}>shared</span>}
                </div>
                {n.productDescription && (
                  <div style={{ color: '#71717a', fontSize: '0.78em', marginTop: 1 }}>{n.productDescription}</div>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* direction + selected summary */}
        {selectedNode && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <div style={{ color: '#a1a1aa', fontSize: '0.82em' }}>Browse direction</div>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button
                type="button"
                className={direction === 'demand-to-supply' ? '' : 'secondary'}
                onClick={() => setDirection('demand-to-supply')}
              >
                Demand → Supply
              </button>
              <button
                type="button"
                className={direction === 'supply-to-demand' ? '' : 'secondary'}
                onClick={() => setDirection('supply-to-demand')}
              >
                Supply → Demand
              </button>
            </div>
            <div style={{ fontSize: '0.82em', color: '#a1a1aa' }}>
              Starting:{' '}
              <strong style={{ color: '#e4e4e7' }}>
                {selectedNode.productId} @ {selectedNode.locationId}
              </strong>
              {selectedNode.productDescription && (
                <span> — {selectedNode.productDescription}</span>
              )}
            </div>
          </div>
        )}
      </div>

      {/* tree — key forces remount (resets expand state) on node or direction change */}
      {selectedId && (
        <BomTree
          key={`${selectedId}:${direction}`}
          data={data}
          nodesById={nodesById}
          rootNodeId={selectedId}
          direction={direction}
        />
      )}
    </div>
  );
}
