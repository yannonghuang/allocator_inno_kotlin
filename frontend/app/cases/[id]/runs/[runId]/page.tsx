'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { getRun, getExplanations, getPegging } from '@/lib/api';

export default function RunDetail() {
  const t = useTranslations('runDetail');
  const params = useParams();
  const caseId = Number(params.id);
  const runId = Number(params.runId);
  const [runDetail, setRunDetail] = useState<{ run: { id: number; status: string }; feasible_demands: { demand_id: string }[] } | null>(null);
  const [supplyId, setSupplyId] = useState('');
  const [explanations, setExplanations] = useState<{ demand_id: string; quantity: number; reason: string }[] | null>(null);
  const [peggingDirection, setPeggingDirection] = useState<'demand-to-supply' | 'supply-to-demand'>('demand-to-supply');
  const [peggingDemandId, setPeggingDemandId] = useState('');
  const [peggingSupplyId, setPeggingSupplyId] = useState('');
  const [peggingData, setPeggingData] = useState<{ nodes: { id: string; label: string; type: string }[]; edges: { from: string; to: string; qty: number }[]; critical_path?: { path: string[] }; critical_paths_by_demand?: { paths_by_demand: { demand_id: string; path: string[] }[] } } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getRun(caseId, runId)
      .then(setRunDetail)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [caseId, runId]);

  const loadExplanations = async () => {
    if (!supplyId.trim()) return;
    setError(null);
    try {
      const data = await getExplanations(caseId, runId, supplyId.trim());
      setExplanations(data.split);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed');
      setExplanations(null);
    }
  };

  const loadPegging = async () => {
    setError(null);
    try {
      const data = await getPegging(caseId, runId, peggingDirection, peggingDemandId || undefined, peggingSupplyId || undefined);
      setPeggingData({
        nodes: data.nodes,
        edges: data.edges,
        critical_path: data.critical_path as { path: string[] } | undefined,
        critical_paths_by_demand: data.critical_paths_by_demand as { paths_by_demand: { demand_id: string; path: string[] }[] } | undefined,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Pegging failed');
      setPeggingData(null);
    }
  };

  if (loading) return <div><Link href={`/cases/${caseId}`}>{t('backToCase')}</Link><p>Loading…</p></div>;
  if (!runDetail) return <div><Link href={`/cases/${caseId}`}>{t('backToCase')}</Link><p>{t('backToCase')}</p></div>;

  const criticalPath = peggingData?.critical_path?.path ?? [];
  const pathsByDemand = peggingData?.critical_paths_by_demand?.paths_by_demand ?? [];
  const pathSet = new Set(criticalPath);
  pathsByDemand.forEach((p) => p.path.forEach((n) => pathSet.add(n)));

  // Simple layout: nodes in a grid by index
  const nodePos: Record<string, { x: number; y: number }> = {};
  peggingData?.nodes.forEach((n, i) => {
    const row = Math.floor(i / 8);
    const col = i % 8;
    nodePos[n.id] = { x: 80 + col * 100, y: 50 + row * 60 };
  });

  return (
    <div>
      <p><Link href={`/cases/${caseId}`}>{t('backToCase')}</Link></p>
      <h1>{t('run')} {runId} – {runDetail.run.status}</h1>
      {error && <p style={{ color: '#f87171' }}>{error}</p>}

      <section style={{ marginTop: '1.5rem' }}>
        <h2>{t('explainTitle')}</h2>
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <input placeholder={t('supplyIdPlaceholder')} value={supplyId} onChange={(e) => setSupplyId(e.target.value)} style={{ minWidth: 280 }} />
          <button onClick={loadExplanations}>{t('showSplit')}</button>
        </div>
        {explanations !== null && (
          <table style={{ marginTop: '0.5rem' }}>
            <thead>
              <tr>
                <th>{t('columns.demandId')}</th>
                <th>{t('columns.quantity')}</th>
                <th>{t('columns.reason')}</th>
              </tr>
            </thead>
            <tbody>
              {explanations.length === 0 ? <tr><td colSpan={3}>{t('noSplit')}</td></tr> : explanations.map((s) => (
                <tr key={s.demand_id}>
                  <td>{s.demand_id}</td>
                  <td>{s.quantity}</td>
                  <td>{s.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section style={{ marginTop: '2rem' }}>
        <h2>{t('peggingTitle')}</h2>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center', marginBottom: '0.5rem' }}>
          <select value={peggingDirection} onChange={(e) => setPeggingDirection(e.target.value as 'demand-to-supply' | 'supply-to-demand')}>
            <option value="demand-to-supply">{t('demandToSupply')}</option>
            <option value="supply-to-demand">{t('supplyToDemand')}</option>
          </select>
          {peggingDirection === 'demand-to-supply' && (
            <>
              <label>{t('demandIdLabel')}</label>
              <input placeholder={t('demandIdPlaceholder')} value={peggingDemandId} onChange={(e) => setPeggingDemandId(e.target.value)} />
            </>
          )}
          {peggingDirection === 'supply-to-demand' && (
            <>
              <label>{t('supplyIdLabel')}</label>
              <input placeholder={t('supplyIdPlaceholder2')} value={peggingSupplyId} onChange={(e) => setPeggingSupplyId(e.target.value)} />
            </>
          )}
          <button onClick={loadPegging}>{t('loadGraph')}</button>
        </div>
        {peggingData && (
          <>
            <p><strong>Nodes:</strong> {peggingData.nodes.length} &nbsp; <strong>Edges:</strong> {peggingData.edges.length}</p>
            {criticalPath.length > 0 && (
              <p><strong>{t('criticalPathMinSum')}</strong> <code>{criticalPath.join(' → ')}</code></p>
            )}
            {pathsByDemand.length > 0 && (
              <div>
                <strong>{t('criticalPathPerDemand')}</strong>
                <ul>
                  {pathsByDemand.map((p) => (
                    <li key={p.demand_id}>{p.demand_id}: <code>{p.path.join(' → ') || '–'}</code></li>
                  ))}
                </ul>
              </div>
            )}
            <div style={{ marginTop: '0.5rem', overflow: 'auto', maxHeight: 400 }}>
              <svg width={Math.max(800, 80 + (peggingData.nodes.length % 8 || 8) * 100)} height={50 + Math.ceil(peggingData.nodes.length / 8) * 60} style={{ border: '1px solid #3f3f46', borderRadius: 8 }}>
                {peggingData.edges.map((e, i) => {
                  const from = nodePos[e.from];
                  const to = nodePos[e.to];
                  if (!from || !to) return null;
                  return <line key={i} x1={from.x} y1={from.y} x2={to.x} y2={to.y} stroke="#52525b" strokeWidth={1} />;
                })}
                {peggingData.nodes.map((n) => {
                  const pos = nodePos[n.id];
                  if (!pos) return null;
                  const onPath = pathSet.has(n.id);
                  return (
                    <g key={n.id}>
                      <circle cx={pos.x} cy={pos.y} r={14} fill={onPath ? '#7c3aed' : '#27272a'} stroke={onPath ? '#a78bfa' : '#52525b'} strokeWidth={2} />
                      <text x={pos.x} y={pos.y + 4} textAnchor="middle" fontSize={9} fill="#e4e4e7">{n.label.length > 12 ? n.label.slice(0, 11) + '…' : n.label}</text>
                    </g>
                  );
                })}
              </svg>
              <p style={{ fontSize: '0.75rem', color: '#a1a1aa' }}>{t('purpleNode')}</p>
            </div>
            <div style={{ marginTop: '0.5rem', fontSize: '0.875rem' }}>
              <details>
                <summary>{t('nodeList')}</summary>
                <pre style={{ background: '#18181b', padding: '0.5rem', overflow: 'auto' }}>{JSON.stringify(peggingData.nodes.slice(0, 30), null, 2)}{peggingData.nodes.length > 30 ? '\n...' : ''}</pre>
              </details>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
