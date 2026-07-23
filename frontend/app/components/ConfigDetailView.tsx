'use client';

import React, { useEffect, useState } from 'react';
import {
  getAllocation,
  getPreferences,
  getDemandOrdering,
  getPurchasableMaterials,
  getCaseConstraints,
  type AllocationRow,
  type PreferenceRow,
  type DemandOrderRow,
  type PurchasableMaterialRow,
  type ConstraintRuleRow,
} from '@/lib/api';

type ExternalKind = 'allocation' | 'preferences' | 'demandOrdering' | 'purchasableMaterials' | 'constraints';

const EXTERNAL_LABELS: Record<ExternalKind, string> = {
  allocation: 'Critical Raw Allocation',
  preferences: 'Supply Preferences',
  demandOrdering: 'Demand Ordering',
  purchasableMaterials: 'Purchasable Materials',
  constraints: 'Constraints',
};

const EXTERNAL_PAGE_PATH: Record<ExternalKind, string> = {
  allocation: 'allocation',
  preferences: 'preferences',
  demandOrdering: 'demand-ordering',
  purchasableMaterials: 'purchasable-materials',
  constraints: 'constraints',
};

/**
 * Renders a plan run's config in a slide-in panel — used by both the Knowledge Base panel and
 * the Plan Run History panel (previously each dumped `JSON.stringify(config, null, 2)` verbatim,
 * showing the whole `_kb_fingerprint` object and every embedded array indiscriminately).
 *
 * Now shows only the planner's OWN inline parameters directly (method_selection/purchase_allowed/
 * consolidation/variant_selection — the "run config parameters on UI" bucket), plus a hyperlink
 * per "external" config object (critical raw allocation / supply preferences / demand ordering /
 * purchasable materials / constraints — the case-level settings promoted out of this blob).
 * Clicking a link drills into a read-only preview of that object's CURRENT content within this
 * same slide-in (not a page navigation) — a "← Back" link returns to this view. The preview is
 * necessarily of the object's LIVE state, not a historical snapshot from when this particular run
 * executed — only a content-hash fingerprint of that historical state was ever captured (see
 * KbFingerprint.kt's own doc), not the row-level data itself.
 */
export function ConfigDetailView({ config, caseId }: { config: Record<string, unknown>; caseId: number }) {
  const [drill, setDrill] = useState<ExternalKind | null>(null);

  if (drill) {
    return <ExternalConfigDrilldown kind={drill} caseId={caseId} onBack={() => setDrill(null)} />;
  }

  const ms = (config.method_selection ?? {}) as Record<string, unknown>;
  const cs = (config.consolidation ?? {}) as Record<string, unknown>;
  const globalBatchFb = (cs.wo_batch_scale as string) ?? 'weekly';

  const row = (label: string, value: React.ReactNode) => (
    <div style={{ display: 'flex', gap: 8, fontSize: '0.74rem', padding: '2px 0' }}>
      <span style={{ color: '#71717a', minWidth: 130, flexShrink: 0 }}>{label}</span>
      <span style={{ color: '#e4e4e7', fontFamily: 'monospace', wordBreak: 'break-word' }}>{value}</span>
    </div>
  );

  // Only fields that actually appear as editable inline parameters on the Planning page's own
  // config form (see _CaseSectionPage.tsx's "CONFIGURATIONS" fieldset) — mode/depth/max_bom_depth/
  // score_weights/variant_selection/consolidation.enabled are real config keys but aren't
  // themselves surfaced there, so they're deliberately left out here too.
  return (
    <div style={{ fontSize: '0.74rem' }}>
      <div style={{ marginBottom: 10 }}>
        {row('Max methods', String(ms.max_methods ?? 1))}
        {row('Purchase allowed', String(config.purchase_allowed === true))}
        {row('WO batch (make/move/buy)', `${(cs.make_batch_scale as string) ?? globalBatchFb} / ${(cs.move_batch_scale as string) ?? globalBatchFb} / ${(cs.purchase_batch_scale as string) ?? globalBatchFb}`)}
        {row('Analyze criticality', String(config.analyze_criticality === true))}
        {row('Check soundness', String(config.check_soundness !== false))}
      </div>
      <div style={{ borderTop: '1px solid #27272a', paddingTop: 8, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontSize: '0.62rem', color: '#52525b', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 2 }}>
          External configs (case-level, not part of this run's own parameters)
        </span>
        {(Object.keys(EXTERNAL_LABELS) as ExternalKind[]).map((k) => (
          <button
            key={k}
            type="button"
            onClick={() => setDrill(k)}
            style={{ textAlign: 'left', background: 'none', border: 'none', color: '#93c5fd', cursor: 'pointer', fontSize: '0.76rem', padding: '2px 0' }}
          >
            {EXTERNAL_LABELS[k]} →
          </button>
        ))}
      </div>
    </div>
  );
}

function ExternalConfigDrilldown({ kind, caseId, onBack }: { kind: ExternalKind; caseId: number; onBack: () => void }) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [rows, setRows] = useState<unknown[]>([]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    const load = async (): Promise<unknown[]> => {
      switch (kind) {
        case 'allocation': return (await getAllocation(caseId)) ?? [];
        case 'preferences': { const r = await getPreferences(caseId); return r?.rows ?? []; }
        case 'demandOrdering': { const r = await getDemandOrdering(caseId); return r?.rows ?? []; }
        case 'purchasableMaterials': return await getPurchasableMaterials(caseId);
        case 'constraints': return await getCaseConstraints(caseId);
      }
    };
    load()
      .then((r) => { if (!cancelled) setRows(r); })
      .catch((e) => { if (!cancelled) setError(String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [kind, caseId]);

  const MAX_PREVIEW = 50;
  const preview = rows.slice(0, MAX_PREVIEW);

  return (
    <div style={{ fontSize: '0.74rem' }}>
      <button
        type="button"
        onClick={onBack}
        style={{ background: 'none', border: 'none', color: '#93c5fd', cursor: 'pointer', fontSize: '0.76rem', padding: 0, marginBottom: 8 }}
      >
        ← Back
      </button>
      <div style={{ fontWeight: 600, color: '#fafafa', marginBottom: 4 }}>{EXTERNAL_LABELS[kind]}</div>
      <div style={{ color: '#71717a', fontSize: '0.68rem', marginBottom: 6 }}>
        Live current state — a historical run only captured a content fingerprint, not this
        row-level data (see KbFingerprint.kt).
      </div>
      {loading && <div style={{ color: '#71717a' }}>Loading…</div>}
      {error && <div style={{ color: '#f87171' }}>{error}</div>}
      {!loading && !error && (
        <>
          <div style={{ color: '#a1a1aa', marginBottom: 4 }}>
            {rows.length === 0 ? 'No rows.' : `${rows.length} row${rows.length === 1 ? '' : 's'}${rows.length > MAX_PREVIEW ? ` (showing first ${MAX_PREVIEW})` : ''}`}
          </div>
          {preview.length > 0 && (
            <div style={{ maxHeight: 260, overflowY: 'auto', background: '#0a0a0a', borderRadius: 4, padding: '4px 6px' }}>
              <RowsTable kind={kind} rows={preview} />
            </div>
          )}
          <a
            href={`/cases/${caseId}/${EXTERNAL_PAGE_PATH[kind]}`}
            style={{ display: 'inline-block', marginTop: 8, color: '#93c5fd', fontSize: '0.72rem' }}
          >
            Open full {EXTERNAL_LABELS[kind]} page →
          </a>
        </>
      )}
    </div>
  );
}

function RowsTable({ kind, rows }: { kind: ExternalKind; rows: unknown[] }) {
  switch (kind) {
    case 'allocation': {
      const r = rows as AllocationRow[];
      return (
        <table style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.68rem' }}>
          <tbody>
            {r.map((row, i) => (
              <tr key={i}>
                <td style={{ color: '#e4e4e7', padding: '1px 4px' }}>{row.supply_id}</td>
                <td style={{ color: '#a1a1aa', padding: '1px 4px' }}>{row.demand_id ?? '—'}</td>
                <td style={{ color: '#a78bfa', padding: '1px 4px', textAlign: 'right' }}>{row.qty_allocated}</td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
    case 'preferences': {
      const r = rows as PreferenceRow[];
      return (
        <table style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.68rem' }}>
          <tbody>
            {r.map((row, i) => (
              <tr key={i}>
                <td style={{ color: '#e4e4e7', padding: '1px 4px' }}>{row.product_id}@{row.location_id}</td>
                <td style={{ color: '#a1a1aa', padding: '1px 4px' }}>{row.method_type}</td>
                <td style={{ color: '#a78bfa', padding: '1px 4px', textAlign: 'right' }}>{row.preference}</td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
    case 'demandOrdering': {
      const r = rows as DemandOrderRow[];
      return (
        <table style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.68rem' }}>
          <tbody>
            {r.map((row, i) => (
              <tr key={i}>
                <td style={{ color: '#a78bfa', padding: '1px 4px' }}>{row.order}</td>
                <td style={{ color: '#e4e4e7', padding: '1px 4px' }}>{row.demand_id}</td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
    case 'purchasableMaterials': {
      const r = rows as PurchasableMaterialRow[];
      return (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {r.map((row, i) => (
            <span key={i} style={{ color: '#e4e4e7', fontFamily: 'monospace', background: '#1c1c1e', borderRadius: 3, padding: '1px 5px' }}>
              {row.product_id}
            </span>
          ))}
        </div>
      );
    }
    case 'constraints': {
      const r = rows as ConstraintRuleRow[];
      return (
        <table style={{ width: '100%', fontFamily: 'monospace', fontSize: '0.68rem' }}>
          <tbody>
            {r.map((row, i) => (
              <tr key={i}>
                <td style={{ color: '#e4e4e7', padding: '1px 4px' }}>{row.customer_id}</td>
                <td style={{ color: '#a1a1aa', padding: '1px 4px' }}>{row.parent}@{row.location}</td>
                <td style={{ color: '#a78bfa', padding: '1px 4px' }}>⇒ {row.child}</td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
  }
}
