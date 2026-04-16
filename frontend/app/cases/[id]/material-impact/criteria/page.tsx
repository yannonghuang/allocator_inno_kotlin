'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { getAssessmentCriteria, setAssessmentCriteria } from '@/lib/api';

const CRITERIA_HEADERS_EN = {
  high: 'the following are criteria for HIGH rating:',
  low: 'the following are criteria for LOW rating:',
  medium: 'the following are criteria for MEDIUM rating:',
} as const;

function parseCriteriaParts(text: string): { high: string; low: string; medium: string } {
  const result: Record<string, string[]> = { high: [], low: [], medium: [] };
  let current: string | null = null;
  for (const line of text.split('\n')) {
    const trimmed = line.trimStart().toLowerCase();
    if (trimmed.startsWith('the following are criteria for high') || trimmed.startsWith('以下是high') || trimmed.startsWith('以下是高') || trimmed.startsWith('以下是 high')) { current = 'high'; continue; }
    if (trimmed.startsWith('the following are criteria for low') || trimmed.startsWith('以下是low') || trimmed.startsWith('以下是低') || trimmed.startsWith('以下是 low')) { current = 'low'; continue; }
    if (trimmed.startsWith('the following are criteria for medium') || trimmed.startsWith('以下是medium') || trimmed.startsWith('以下是中') || trimmed.startsWith('以下是 medium')) { current = 'medium'; continue; }
    if (current) result[current].push(line);
  }
  return {
    high: result.high.join('\n').trim(),
    low: result.low.join('\n').trim(),
    medium: result.medium.join('\n').trim(),
  };
}

function buildCriteriaText(high: string, low: string, medium: string): string {
  const parts: string[] = [];
  if (high.trim()) { parts.push(CRITERIA_HEADERS_EN.high); parts.push(high.trim()); }
  if (low.trim()) { parts.push(CRITERIA_HEADERS_EN.low); parts.push(low.trim()); }
  if (medium.trim()) { parts.push(CRITERIA_HEADERS_EN.medium); parts.push(medium.trim()); }
  return parts.join('\n');
}

export default function CriteriaPage() {
  const { id } = useParams<{ id: string }>();
  const caseId = Number(id);
  const t = useTranslations('planning.supplyView.assessment');

  const DEFAULT_CRITERIA = {
    high: t('criteriaDefaultHigh'),
    low: t('criteriaDefaultLow'),
    medium: t('criteriaDefaultMedium'),
  };

  const [criteria, setCriteria] = useState('');
  const [high, setHigh] = useState('');
  const [low, setLow] = useState('');
  const [medium, setMedium] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // true when the backend has no saved criteria (showing defaults as placeholder)
  const [usingDefaults, setUsingDefaults] = useState(false);

  useEffect(() => {
    if (!caseId) return;
    setLoading(true);
    getAssessmentCriteria(caseId)
      .then((text) => {
        const val = text ?? '';
        setCriteria(val);
        if (!val.trim()) {
          setHigh(DEFAULT_CRITERIA.high);
          setLow(DEFAULT_CRITERIA.low);
          setMedium(DEFAULT_CRITERIA.medium);
          setUsingDefaults(true);
        } else {
          const parts = parseCriteriaParts(val);
          setHigh(parts.high);
          setLow(parts.low);
          setMedium(parts.medium);
          setUsingDefaults(false);
        }
      })
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load criteria'))
      .finally(() => setLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId]);

  const handleSave = async () => {
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      const combined = buildCriteriaText(high, low, medium);
      await setAssessmentCriteria(caseId, combined);
      setCriteria(combined);
      setUsingDefaults(false);
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  };

  const isDirty = usingDefaults || buildCriteriaText(high, low, medium) !== criteria;

  const tiers: Array<{ key: 'high' | 'low' | 'medium'; color: string; bg: string; border: string }> = [
    { key: 'high',   color: '#f87171', bg: 'rgba(248,113,113,0.12)', border: 'rgba(248,113,113,0.35)' },
    { key: 'low',    color: '#34d399', bg: 'rgba(52,211,153,0.12)',  border: 'rgba(52,211,153,0.35)'  },
    { key: 'medium', color: '#fbbf24', bg: 'rgba(251,191,36,0.12)',  border: 'rgba(251,191,36,0.35)'  },
  ];

  const valueMap = { high, low, medium };
  const setterMap = { high: setHigh, low: setLow, medium: setMedium };

  return (
    <div style={{ maxWidth: 680 }}>
      <h2 style={{ marginTop: 0 }}>{t('criteriaPageTitle')}</h2>
      <p style={{ fontSize: '0.875rem', color: '#a1a1aa', marginBottom: '1rem' }}>
        {t('criteriaPageDesc')}
      </p>
      {usingDefaults && (
        <p style={{ fontSize: '0.8rem', color: '#71717a', marginBottom: '1.25rem', padding: '0.4rem 0.75rem', background: 'rgba(255,255,255,0.04)', borderRadius: 6, border: '1px solid #3d3d40' }}>
          {t('criteriaPageNoCustom')}
        </p>
      )}

      {error && <p style={{ color: '#f87171' }}>{error}</p>}
      {loading ? (
        <p style={{ color: '#71717a' }}>{t('criteriaLoading')}</p>
      ) : (
        <>
          {tiers.map(({ key, color, bg, border }) => (
            <div key={key} style={{ marginBottom: '1rem' }}>
              <p style={{ fontSize: '0.8rem', margin: '0 0 4px', color: '#a1a1aa' }}>
                {t('criteriaPageFor')}{' '}
                <span style={{ padding: '1px 8px', borderRadius: 10, background: bg, color, border: `1px solid ${color}`, fontWeight: 700, fontSize: '0.75rem' }}>
                  {key.toUpperCase()}
                </span>
                {' '}{t('criteriaPageRating')}
              </p>
              <textarea
                value={valueMap[key]}
                onChange={(e) => setterMap[key](e.target.value)}
                rows={3}
                placeholder={t('criteriaPagePlaceholder', { tier: key.toUpperCase() })}
                style={{
                  width: '100%',
                  fontSize: '0.875rem',
                  background: '#27272a',
                  color: '#e4e4e7',
                  border: `1px solid ${border}`,
                  borderRadius: 4,
                  padding: '0.5rem',
                  resize: 'vertical',
                  boxSizing: 'border-box',
                }}
              />
            </div>
          ))}

          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: '0.5rem' }}>
            <button type="button" onClick={handleSave} disabled={saving || !isDirty}>
              {saving ? t('criteriaSaving') : t('criteriaSave')}
            </button>
            <button
              type="button"
              className="secondary"
              disabled={!isDirty}
              onClick={() => {
                if (usingDefaults) {
                  setHigh(DEFAULT_CRITERIA.high);
                  setLow(DEFAULT_CRITERIA.low);
                  setMedium(DEFAULT_CRITERIA.medium);
                } else {
                  const parts = parseCriteriaParts(criteria);
                  setHigh(parts.high);
                  setLow(parts.low);
                  setMedium(parts.medium);
                }
              }}
            >
              {t('criteriaReset')}
            </button>
            {saved && !isDirty && (
              <span style={{ fontSize: '0.8rem', color: '#4ade80' }}>{t('criteriaSaved')}</span>
            )}
          </div>
        </>
      )}
    </div>
  );
}
