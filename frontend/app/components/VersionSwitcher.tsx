'use client';

import React, { useState } from 'react';
import { useTranslations } from 'next-intl';
import type { ConfigVersion } from '@/lib/api';

/**
 * Shared version switcher/manager for the 5 "external config objects" (Critical Raw Allocation,
 * Supply Preferences, Demand Ordering, Purchasable Materials, Constraints) — see
 * CaseConfigVersions' own doc in Tables.kt. Each object's dedicated page renders one of these
 * above its editor: switch which version you're viewing/editing, "Save As" the current draft
 * into a new named version, and manage existing versions (rename, delete).
 *
 * No "default version" concept — a version is just a name (or `versionLabel {id}` if unnamed);
 * there's nothing to mark "current" case-wide, only "which one is this page showing right now."
 *
 * Doesn't know anything about the object's own row shape — callers own Save/Generate/Import/
 * Clear and must disable them (or route through Save As) when `referenced` is true for the
 * current version; this component only surfaces that state, it doesn't enforce it.
 */
export function VersionSwitcher({
  versions,
  currentVersionId,
  onSwitch,
  onSaveAs,
  onRename,
  onDelete,
}: {
  versions: ConfigVersion[];
  currentVersionId: number | null;
  onSwitch: (versionId: number) => void;
  onSaveAs: (name: string | undefined, comments: string | undefined) => void;
  onRename: (versionId: number, name: string | undefined, comments: string | undefined) => void;
  onDelete: (versionId: number) => void;
}) {
  const t = useTranslations('versioning');
  const [saveAsOpen, setSaveAsOpen] = useState(false);
  const [saveAsName, setSaveAsName] = useState('');
  const [saveAsComments, setSaveAsComments] = useState('');
  const [manageOpen, setManageOpen] = useState(false);

  const current = versions.find((v) => v.id === currentVersionId);
  const referenced = current?.referenced ?? false;

  const btnStyle: React.CSSProperties = {
    padding: '4px 10px', background: '#27272a', color: '#e4e4e7',
    border: '1px solid #3d3d40', borderRadius: 4, cursor: 'pointer', fontSize: '0.78rem',
  };
  const inputStyle: React.CSSProperties = {
    padding: '4px 8px', background: '#0a0a0a', color: '#e4e4e7',
    border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.78rem',
  };

  const label = (v: ConfigVersion) => v.name || t('versionLabel', { id: v.id });

  return (
    <div style={{ marginBottom: '1rem', padding: '0.6rem 0.75rem', border: '1px solid #3d3d40', borderRadius: 6, background: '#18181b' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span style={{ fontSize: '0.68rem', color: '#71717a', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{t('version')}</span>
        {versions.length > 1 ? (
          <select
            value={currentVersionId ?? ''}
            onChange={(e) => onSwitch(Number(e.target.value))}
            style={{ padding: '4px 8px', background: '#27272a', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 4, fontSize: '0.82rem' }}
          >
            {versions.map((v) => (
              <option key={v.id} value={v.id}>
                {label(v)}
              </option>
            ))}
          </select>
        ) : (
          <span style={{ fontSize: '0.82rem', color: '#e4e4e7' }}>{current ? label(current) : t('none')}</span>
        )}
        {referenced && (
          <span
            title={t('inUseTooltip')}
            style={{ fontSize: '0.66rem', padding: '2px 6px', background: '#3f2d0d', color: '#fbbf24', borderRadius: 4 }}
          >
            {t('inUseReadOnly')}
          </span>
        )}
        <button type="button" onClick={() => setSaveAsOpen((o) => !o)} style={btnStyle}>{t('saveAs')}</button>
        <button type="button" onClick={() => setManageOpen((o) => !o)} style={btnStyle}>{t('manageVersions')}</button>
      </div>

      {saveAsOpen && (
        <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <input placeholder={t('namePlaceholder')} value={saveAsName} onChange={(e) => setSaveAsName(e.target.value)} style={inputStyle} />
          <input placeholder={t('commentsPlaceholder')} value={saveAsComments} onChange={(e) => setSaveAsComments(e.target.value)} style={{ ...inputStyle, minWidth: 220 }} />
          <button
            type="button"
            onClick={() => {
              onSaveAs(saveAsName.trim() || undefined, saveAsComments.trim() || undefined);
              setSaveAsOpen(false);
              setSaveAsName('');
              setSaveAsComments('');
            }}
            style={{ ...btnStyle, background: '#2563eb', color: '#fff', border: 'none' }}
          >
            {t('create')}
          </button>
          <button type="button" onClick={() => setSaveAsOpen(false)} style={btnStyle}>{t('cancel')}</button>
        </div>
      )}

      {manageOpen && (
        <div style={{ marginTop: 8, overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.76rem' }}>
            <thead>
              <tr style={{ color: '#71717a', borderBottom: '1px solid #3d3d40' }}>
                <th style={{ textAlign: 'left', padding: '3px 6px' }}>{t('colName')}</th>
                <th style={{ textAlign: 'left', padding: '3px 6px' }}>{t('colComments')}</th>
                <th style={{ textAlign: 'left', padding: '3px 6px' }}>{t('colStatus')}</th>
                <th style={{ padding: '3px 6px' }} />
              </tr>
            </thead>
            <tbody>
              {versions.map((v) => (
                <tr key={v.id} style={{ borderBottom: '1px solid #27272a' }}>
                  <td style={{ padding: '3px 6px', color: '#e4e4e7' }}>{label(v)}</td>
                  <td style={{ padding: '3px 6px', color: '#a1a1aa' }}>{v.comments ?? ''}</td>
                  <td style={{ padding: '3px 6px' }}>
                    {v.referenced && <span style={{ color: '#fbbf24' }}>{t('inUseReadOnly')}</span>}
                  </td>
                  <td style={{ padding: '3px 6px', textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button
                      type="button"
                      onClick={() => {
                        const name = prompt(t('renamePrompt'), v.name ?? '');
                        if (name === null) return;
                        const comments = prompt(t('renameCommentsPrompt'), v.comments ?? '');
                        onRename(v.id, name.trim() || undefined, (comments ?? '').trim() || undefined);
                      }}
                      style={{ ...btnStyle, padding: '2px 8px', marginRight: 4 }}
                    >
                      {t('rename')}
                    </button>
                    <button
                      type="button"
                      disabled={v.referenced}
                      onClick={() => { if (confirm(t('confirmDelete'))) onDelete(v.id); }}
                      style={{ ...btnStyle, padding: '2px 8px', color: '#f87171', opacity: v.referenced ? 0.4 : 1 }}
                    >
                      {t('delete')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
