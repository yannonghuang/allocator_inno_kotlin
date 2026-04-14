'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { listCases, createCase, deleteCase, importCsv, type Case as CaseType } from '@/lib/api';

export default function CasesPage() {
  const t = useTranslations('home');
  const tc = useTranslations('common');
  const [cases, setCases] = useState<CaseType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);
  const [importingId, setImportingId] = useState<number | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listCases();
      setCases(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load cases');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    if (!newName.trim()) return;
    setCreating(true);
    setError(null);
    try {
      await createCase(newName.trim());
      setNewName('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create');
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Delete this case and all its data?')) return;
    setError(null);
    try {
      await deleteCase(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete');
    }
  };

  const handleImport = async (id: number) => {
    setImportingId(id);
    setError(null);
    try {
      await importCsv(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Import failed (ensure backend has access to csv folder)');
    } finally {
      setImportingId(null);
    }
  };

  return (
    <div>
      <h1>{t('title')}</h1>
      {error && <p style={{ color: '#f87171' }}>{error}</p>}
      <div style={{ marginBottom: '1rem', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
        <input
          placeholder={t('newCasePlaceholder')}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
        />
        <button onClick={handleCreate} disabled={creating}>
          {creating ? t('creating') : t('createCase')}
        </button>
      </div>
      {loading ? <p>{t('loadingCases')}</p> : (
        <table>
          <thead>
            <tr>
              <th>{t('columns.name')}</th>
              <th>{t('columns.type')}</th>
              <th>{t('columns.demands')}</th>
              <th>{t('columns.supplies')}</th>
              <th>{t('columns.runs')}</th>
              <th>{t('columns.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => {
              const hasData  = (c.demand_count ?? 0) > 0 || (c.supply_count ?? 0) > 0;
              const hasAlloc = (c.run_count ?? 0) > 0;
              const hasPlan  = (c.plan_run_count ?? 0) > 0;
              const typeLabel = hasAlloc && hasPlan ? t('caseType.planningAllocation')
                              : hasPlan             ? t('caseType.planning')
                              : hasAlloc            ? t('caseType.allocation')
                              : hasData             ? t('caseType.noRuns')
                              : t('caseType.new');
              return (
                <tr key={c.id}>
                  <td>
                    <Link href={`/cases/${c.id}/planning`}>{c.name}</Link>
                  </td>
                  <td style={{ color: !hasAlloc && !hasPlan ? '#52525b' : '#e4e4e7', fontSize: '0.88em' }}>{typeLabel}</td>
                  <td>{c.demand_count ?? '–'}</td>
                  <td>{c.supply_count ?? '–'}</td>
                  <td>{((c.run_count ?? 0) + (c.plan_run_count ?? 0)) || '–'}</td>
                  <td>
                    <button
                      className="secondary"
                      onClick={() => handleImport(c.id)}
                      disabled={importingId === c.id || hasData}
                      title={hasData ? t('importDisabledTooltip') : t('importTooltip')}
                    >
                      {importingId === c.id ? t('importing') : t('importCsv')}
                    </button>
                    {' '}
                    <button className="danger" onClick={() => handleDelete(c.id)}>{tc('delete')}</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {!loading && cases.length === 0 && <p>{t('noCases')}</p>}
    </div>
  );
}
