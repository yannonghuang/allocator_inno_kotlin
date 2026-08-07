'use client';

import React, { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  getAllocation,
  getPreferences,
  getDemandOrdering,
  getPurchasableMaterials,
  getCaseConstraints,
  getCaseSupplies,
  getCaseDemands,
  getPurchasableRawMaterials,
  getConstraintOptions,
  listAllocationVersions,
  listPreferencesVersions,
  listDemandOrderingVersions,
  listPurchasableMaterialsVersions,
  listCaseConstraintsVersions,
  type AllocationRow,
  type PreferenceRow,
  type DemandOrderRow,
  type PurchasableMaterialRow,
  type ConstraintRuleRow,
  type ConfigVersion,
  type CaseSupplyRow,
  type CaseDemandRow,
  type PurchasableRawMaterial,
  type ConstraintOptions,
} from '@/lib/api';
import { RawMaterialPicker } from './RawMaterialPicker';
import { ConstraintPicker } from './ConstraintPicker';
import { PreferenceTable } from './PreferenceTable';
import { DemandOrderTable } from './DemandOrderTable';
import { AllocationMatrixView } from './AllocationMatrixView';

export type ExternalKind = 'allocation' | 'preferences' | 'demandOrdering' | 'purchasableMaterials' | 'constraints';

/** The 5 version-reference fields carried by `PlanRun`/`BootstrapPreset` (see CaseConfigVersions'
 *  own doc) — which version of each external config object a given run actually used. Undefined
 *  for pre-versioning ("legacy") runs. */
type VersionRefs = {
  case_alloc_version_id?: number;
  pref_version_id?: number;
  demand_order_version_id?: number;
  purchasable_material_version_id?: number;
  constraint_version_id?: number;
};

const EXTERNAL_LABELS: Record<ExternalKind, string> = {
  allocation: 'Critical Material Allocation',
  preferences: 'Supply Preferences',
  demandOrdering: 'Demand Ordering',
  purchasableMaterials: 'Purchasable Materials',
  constraints: 'Constraints',
};

const VERSION_REF_KEY: Record<ExternalKind, keyof VersionRefs> = {
  allocation: 'case_alloc_version_id',
  preferences: 'pref_version_id',
  demandOrdering: 'demand_order_version_id',
  purchasableMaterials: 'purchasable_material_version_id',
  constraints: 'constraint_version_id',
};

const listVersionsFor: Record<ExternalKind, (caseId: number) => Promise<ConfigVersion[]>> = {
  allocation: listAllocationVersions,
  preferences: listPreferencesVersions,
  demandOrdering: listDemandOrderingVersions,
  purchasableMaterials: listPurchasableMaterialsVersions,
  constraints: listCaseConstraintsVersions,
};

/**
 * Renders a plan run's config in a slide-in panel — used by both the Knowledge Base panel and
 * the Plan Run History panel (previously each dumped `JSON.stringify(config, null, 2)` verbatim,
 * showing the whole `_kb_fingerprint` object and every embedded array indiscriminately).
 *
 * Now shows only the planner's OWN inline parameters directly (method_selection/purchase_allowed/
 * consolidation — the "run config parameters on UI" bucket), plus a hyperlink
 * per "external" config object (critical raw allocation / supply preferences / demand ordering /
 * purchasable materials / constraints — the case-level settings promoted out of this blob).
 * Clicking a link drills into a read-only preview within this same slide-in (not a page
 * navigation) — a "← Back" link returns to this view. When [versionRefs] carries this run's
 * resolved version id for that object (see CaseConfigVersions' own doc), the preview fetches that
 * EXACT historical version — no longer "whatever is live now." When no version id was recorded
 * (this run genuinely used no config for that kind, or predates version tracking), shows "this
 * run did not use a version of this config" instead of ever falling back to the case's CURRENT
 * default — that fallback used to make an old run look retroactively reattached the moment a
 * later run created/promoted a new default version for that kind (confirmed live: reloading an
 * older run after a newer run's first-ever Generate call showed the older run "using" the new
 * config, even though its stored version ref was still null in the DB).
 */
export function ConfigDetailView({ config, caseId, versionRefs }: { config: Record<string, unknown>; caseId: number; versionRefs?: VersionRefs }) {
  const [drill, setDrill] = useState<ExternalKind | null>(null);

  if (drill) {
    return <ExternalConfigDrilldown kind={drill} caseId={caseId} versionId={versionRefs?.[VERSION_REF_KEY[drill]]} onBack={() => setDrill(null)} />;
  }

  const ms = (config.method_selection ?? {}) as Record<string, unknown>;
  const cs = (config.consolidation ?? {}) as Record<string, unknown>;
  const asc = config.app_specific_config as Record<string, unknown> | undefined;
  const globalBatchFb = (cs.wo_batch_scale as string) ?? 'weekly';

  const row = (label: string, value: React.ReactNode) => (
    <div style={{ display: 'flex', gap: 8, fontSize: '0.74rem', padding: '2px 0' }}>
      <span style={{ color: '#71717a', minWidth: 130, flexShrink: 0 }}>{label}</span>
      <span style={{ color: '#e4e4e7', fontFamily: 'monospace', wordBreak: 'break-word' }}>{value}</span>
    </div>
  );

  // Only fields that actually appear as editable inline parameters on the Planning page's own
  // config form (see _CaseSectionPage.tsx's "CONFIGURATIONS" fieldset). consolidation.enabled
  // used to be a real, live config key that wasn't itself surfaced here — removed entirely now;
  // consolidation always runs, and the batch-scale selects are the only real on/off control, per
  // WO type. mode/depth/max_bom_depth/score_weights/variant_selection — the previously-dead keys
  // this comment used to list as "real but unsurfaced" — have since been removed from the
  // backend entirely (confirmed dead, no live consumer anywhere).
  return (
    <div style={{ fontSize: '0.74rem' }}>
      <div style={{ marginBottom: 10 }}>
        {row('Root waterfall', String(ms.root_waterfall !== false))}
        {row('Max methods', String(ms.max_methods ?? 1))}
        {row('Raw material sourcing', (ms.raw_material_sourcing as string) === 'equal_split' ? 'Equal-split' : 'Waterfall')}
        {row('Horizon start', (ms.horizon_start as string) ?? 'None (no parseable demand due dates)')}
        {row('Horizon end', (ms.horizon_end as string) ?? 'None (no parseable demand due dates)')}
        {asc && Object.entries((asc.wip_supply_dates ?? {}) as Record<string, string>).map(([sid, date]) =>
          row(`WIP: ${sid}`, date)
        )}
        {row('Purchase allowed', String(config.purchase_allowed === true))}
        {row('Reallocate critical leftover', String(config.reallocate_critical_leftover === true))}
        {row('WO batch (make/move/buy)', `${(cs.make_batch_scale as string) ?? globalBatchFb} / ${(cs.move_batch_scale as string) ?? globalBatchFb} / ${(cs.purchase_batch_scale as string) ?? globalBatchFb}`)}
        {row('Analyze criticality', String(config.analyze_criticality === true))}
        {row('Check soundness', String(config.check_soundness !== false))}
      </div>
      <div style={{ borderTop: '1px solid #27272a', paddingTop: 8, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontSize: '0.62rem', color: '#52525b', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 2 }}>
          External configs (case-level, not part of this run's own parameters)
        </span>
        {(Object.keys(EXTERNAL_LABELS) as ExternalKind[]).map((k) => {
          const vId = versionRefs?.[VERSION_REF_KEY[k]];
          return (
            <button
              key={k}
              type="button"
              onClick={() => setDrill(k)}
              style={{ textAlign: 'left', background: 'none', border: 'none', color: '#93c5fd', cursor: 'pointer', fontSize: '0.76rem', padding: '2px 0' }}
            >
              {EXTERNAL_LABELS[k]}<span style={{ color: '#71717a', fontFamily: 'monospace' }}> ({vId != null ? `v${vId}` : '-'})</span> →
            </button>
          );
        })}
      </div>
    </div>
  );
}

type AllocationAux = { supplies: CaseSupplyRow[]; demands: CaseDemandRow[] };

/** Read-only preview of one external config object's content, for [versionId] — or "no version
 *  selected" if none, never a fallback to anything (no "default" concept exists — see
 *  CaseConfigVersions' own doc). Reused directly (not just from within ConfigDetailView's own
 *  drill-in flow) by the Planning page's per-object version picker preview — its "← Back" doubles
 *  as a plain close action there. Since there's no default fallback to differ on, this behaves
 *  identically whether reached from a completed run's history, the KB panel, or a live picker
 *  preview — no separate "context" needed.
 *
 *  Renders the EXACT same view component as the object's own dedicated (editable) page — just in
 *  read-only mode — rather than a separate simplified viewer, so filters/search/sort (critical
 *  for the long lists like Preferences and Purchasable Materials) work identically here. */
export function ExternalConfigDrilldown({ kind, caseId, versionId, onBack, showBackLink = true }: {
  kind: ExternalKind; caseId: number; versionId?: number; onBack: () => void;
  /** Hide the "← Back" link — for callers (the Planning page's standalone preview window) that
   *  already have their own close affordance and aren't drilling in from a list. */
  showBackLink?: boolean;
}) {
  const tPlanning = useTranslations('planning');
  const tPreferences = useTranslations('preferencesPage');
  const tDemandOrdering = useTranslations('demandOrderingPage');
  const tAllocation = useTranslations('allocationPage');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [rows, setRows] = useState<unknown[]>([]);
  const [aux, setAux] = useState<unknown>(null);
  const [versionMeta, setVersionMeta] = useState<ConfigVersion | null>(null);

  // No version ref means no override for this kind, full stop — never resolved to anything else.
  const showsNoConfig = versionId == null;

  useEffect(() => {
    if (showsNoConfig) {
      setLoading(false);
      setError(null);
      setRows([]);
      setAux(null);
      setVersionMeta(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    const load = async (): Promise<{ rows: unknown[]; aux: unknown }> => {
      switch (kind) {
        case 'allocation': {
          const [a, supplies, demandRows] = await Promise.all([
            getAllocation(caseId, versionId), getCaseSupplies(caseId), getCaseDemands(caseId),
          ]);
          return { rows: a ?? [], aux: { supplies, demands: demandRows } satisfies AllocationAux };
        }
        case 'preferences': {
          const r = await getPreferences(caseId, versionId);
          return { rows: r?.rows ?? [], aux: null };
        }
        case 'demandOrdering': {
          const r = await getDemandOrdering(caseId, versionId);
          return { rows: r?.rows ?? [], aux: null };
        }
        case 'purchasableMaterials': {
          const [pmRows, catalog] = await Promise.all([
            getPurchasableMaterials(caseId, versionId), getPurchasableRawMaterials(caseId),
          ]);
          return { rows: pmRows, aux: catalog.materials };
        }
        case 'constraints': {
          const [cRows, opts] = await Promise.all([
            getCaseConstraints(caseId, versionId), getConstraintOptions(caseId),
          ]);
          return { rows: cRows, aux: opts };
        }
      }
    };
    load()
      .then(({ rows: r, aux: a }) => { if (!cancelled) { setRows(r); setAux(a); } })
      .catch((e) => { if (!cancelled) setError(String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    if (versionId != null) {
      listVersionsFor[kind](caseId)
        .then((vs) => { if (!cancelled) setVersionMeta(vs.find((v) => v.id === versionId) ?? null); })
        .catch(() => { if (!cancelled) setVersionMeta(null); });
    } else {
      setVersionMeta(null);
    }
    return () => { cancelled = true; };
  }, [kind, caseId, versionId]);

  const renderView = () => {
    switch (kind) {
      case 'allocation': {
        const a = aux as AllocationAux | null;
        return <AllocationMatrixView rows={rows as AllocationRow[]} supplies={a?.supplies ?? []} demands={a?.demands ?? []} t={tAllocation} />;
      }
      case 'preferences':
        return <PreferenceTable rows={rows as PreferenceRow[]} t={tPreferences} />;
      case 'demandOrdering':
        return <DemandOrderTable rows={rows as DemandOrderRow[]} t={tDemandOrdering} />;
      case 'purchasableMaterials':
        return (
          <RawMaterialPicker
            options={(aux as PurchasableRawMaterial[] | null) ?? []}
            selected={(rows as PurchasableMaterialRow[]).map((r) => r.product_id)}
            readOnly
            defaultCollapsed={false}
            tP={tPlanning}
          />
        );
      case 'constraints':
        return (
          <ConstraintPicker
            options={(aux as ConstraintOptions | null) ?? { customers: [], parents: [] }}
            constraints={rows as ConstraintRuleRow[]}
            readOnly
            defaultCollapsed={false}
            tP={tPlanning}
          />
        );
    }
  };

  return (
    <div style={{ fontSize: '0.74rem' }}>
      {showBackLink && (
        <button
          type="button"
          onClick={onBack}
          style={{ background: 'none', border: 'none', color: '#93c5fd', cursor: 'pointer', fontSize: '0.76rem', padding: 0, marginBottom: 8 }}
        >
          ← Back
        </button>
      )}
      <div style={{ fontWeight: 600, color: '#fafafa', marginBottom: 4 }}>
        {EXTERNAL_LABELS[kind]}
        {versionId != null && <span style={{ color: '#71717a', fontWeight: 400 }}> — {versionMeta?.name || `Version ${versionId}`}</span>}
      </div>
      <div style={{ color: '#71717a', fontSize: '0.68rem', marginBottom: 6 }}>
        {versionId != null
          ? `Version used${versionMeta?.comments ? ` — ${versionMeta.comments}` : ''}.`
          : 'No version selected for this config ("-").'}
      </div>
      {loading && <div style={{ color: '#71717a' }}>Loading…</div>}
      {error && <div style={{ color: '#f87171' }}>{error}</div>}
      {!loading && !error && !showsNoConfig && (
        <div style={{ color: '#e4e4e7' }}>
          {renderView()}
        </div>
      )}
    </div>
  );
}
