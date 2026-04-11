'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { listCases, createCase, deleteCase, importCsv, type Case as CaseType } from '@/lib/api';

export default function Home() {
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
      <h1>Supply–Demand Allocator</h1>
      {error && <p style={{ color: '#f87171' }}>{error}</p>}
      <div style={{ marginBottom: '1rem', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
        <input
          placeholder="New case name"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
        />
        <button onClick={handleCreate} disabled={creating}>Create case</button>
      </div>
      {loading ? <p>Loading…</p> : (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Demands</th>
              <th>Supplies</th>
              <th>Runs</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => {
              const hasData    = (c.demand_count ?? 0) > 0 || (c.supply_count ?? 0) > 0;
              const hasAlloc   = (c.run_count ?? 0) > 0;
              const hasPlan    = (c.plan_run_count ?? 0) > 0;
              const typeLabel  = hasAlloc && hasPlan ? 'Planning + Allocation'
                               : hasPlan             ? 'Planning'
                               : hasAlloc            ? 'Allocation'
                               : hasData             ? '–'
                               : 'New';
              return (
                <tr key={c.id}>
                  <td><Link href={`/cases/${c.id}`}>{c.name}</Link></td>
                  <td style={{ color: !hasAlloc && !hasPlan ? '#52525b' : '#e4e4e7', fontSize: '0.88em' }}>{typeLabel}</td>
                  <td>{c.demand_count ?? '–'}</td>
                  <td>{c.supply_count ?? '–'}</td>
                  <td>{((c.run_count ?? 0) + (c.plan_run_count ?? 0)) || '–'}</td>
                  <td>
                    <button
                      className="secondary"
                      onClick={() => handleImport(c.id)}
                      disabled={importingId === c.id || hasData}
                      title={hasData ? 'CSV already imported — delete this case to re-import' : 'Import CSV data'}
                    >
                      {importingId === c.id ? 'Importing…' : 'Import CSV'}
                    </button>
                    {' '}
                    <button className="danger" onClick={() => handleDelete(c.id)}>Delete</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {!loading && cases.length === 0 && <p>No cases yet. Create one and import CSV data.</p>}
    </div>
  );
}
