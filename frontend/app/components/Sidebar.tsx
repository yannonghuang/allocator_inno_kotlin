'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { LanguageSwitcher } from './LanguageSwitcher';

export const SIDEBAR_WIDTH = 220;

export function Sidebar({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  const pathname = usePathname();
  const t = useTranslations('nav');

  // Extract caseId from paths like /cases/123/...
  const caseMatch = pathname.match(/^\/cases\/(\d+)/);
  const caseId = caseMatch ? caseMatch[1] : null;

  const inCases = pathname === '/cases' || pathname === '/';
  const inPlanning = !!caseId && pathname.startsWith(`/cases/${caseId}/planning`);
  const inAllocation = !!caseId && pathname.startsWith(`/cases/${caseId}/allocation`);
  const inPreferences = !!caseId && pathname.startsWith(`/cases/${caseId}/preferences`);
  const inDemandOrdering = !!caseId && pathname.startsWith(`/cases/${caseId}/demand-ordering`);
  const inBom = !!caseId && pathname.startsWith(`/cases/${caseId}/bom`);
  const inMaterial = !!caseId && pathname.startsWith(`/cases/${caseId}/material-impact`);
  const inEvents = inMaterial && !pathname.startsWith(`/cases/${caseId}/material-impact/criteria`);
  const inCriteria = pathname === `/cases/${caseId}/material-impact/criteria`;
  const inWoSchedule = !!caseId && pathname.startsWith(`/cases/${caseId}/wo-schedule-impact`);

  const link = (active: boolean, disabled = false): React.CSSProperties => ({
    display: 'block',
    padding: '0.45rem 1rem',
    color: disabled ? '#3f3f46' : active ? '#e4e4e7' : '#a1a1aa',
    background: active ? 'rgba(59,130,246,0.12)' : 'transparent',
    borderLeft: active ? '2px solid #3b82f6' : '2px solid transparent',
    textDecoration: 'none',
    fontSize: '0.875rem',
    cursor: disabled ? 'default' : 'pointer',
    pointerEvents: disabled ? 'none' : 'auto',
    transition: 'color 0.1s, background 0.1s',
  });

  const sub = (active: boolean): React.CSSProperties => ({
    display: 'block',
    padding: '0.35rem 1rem 0.35rem 2rem',
    color: active ? '#c4b5fd' : '#71717a',
    background: active ? 'rgba(167,139,250,0.1)' : 'transparent',
    borderLeft: active ? '2px solid #a78bfa' : '2px solid transparent',
    textDecoration: 'none',
    fontSize: '0.8rem',
    transition: 'color 0.1s, background 0.1s',
  });

  return (
    <aside
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        width: SIDEBAR_WIDTH,
        height: '100vh',
        background: '#111113',
        borderRight: '1px solid #1f1f22',
        display: 'flex',
        flexDirection: 'column',
        zIndex: 100,
        overflow: 'hidden',
        transform: open ? 'translateX(0)' : `translateX(-${SIDEBAR_WIDTH}px)`,
        transition: 'transform 0.2s ease',
      }}
    >
      {/* App title + close button */}
      <div style={{ padding: '0.875rem 1rem', borderBottom: '1px solid #1f1f22', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Link
          href="/cases"
          style={{ color: '#e4e4e7', fontWeight: 700, fontSize: '0.9rem', textDecoration: 'none', lineHeight: 1.3 }}
        >
          Supply–Demand<br />Allocator
        </Link>
        <button
          onClick={onToggle}
          aria-label="Close sidebar"
          style={{ background: 'none', border: 'none', color: '#52525b', cursor: 'pointer', fontSize: '1rem', padding: '2px 4px', lineHeight: 1, flexShrink: 0 }}
        >
          ✕
        </button>
      </div>

      {/* Nav */}
      <nav style={{ flex: 1, overflowY: 'auto', paddingTop: '0.5rem', paddingBottom: '0.5rem' }}>
        {/* Cases */}
        <Link href="/cases" style={link(inCases)}>
          {t('cases')}
        </Link>

        {/* Planning */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/planning`} style={link(inPlanning)}>
              {t('planning')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('planning')}</span>
          )}
        </div>

        {/* Allocation */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/allocation`} style={link(inAllocation)}>
              {t('allocation')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('allocation')}</span>
          )}
        </div>

        {/* Preferences */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/preferences`} style={link(inPreferences)}>
              {t('preferences')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('preferences')}</span>
          )}
        </div>

        {/* Demand Ordering */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/demand-ordering`} style={link(inDemandOrdering)}>
              {t('demandOrdering')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('demandOrdering')}</span>
          )}
        </div>

        {/* BOM Graph */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/bom`} style={link(inBom)}>
              {t('bomGraph')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('bomGraph')}</span>
          )}
        </div>

        {/* Material Impact */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/material-impact`} style={link(inMaterial && !inCriteria)}>
              {t('materialImpact')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('materialImpact')}</span>
          )}
          {inMaterial && (
            <>
              <Link href={`/cases/${caseId}/material-impact`} style={sub(inEvents)}>
                {t('events')}
              </Link>
              <Link href={`/cases/${caseId}/material-impact/criteria`} style={sub(inCriteria)}>
                {t('criteria')}
              </Link>
            </>
          )}
        </div>

        {/* WO Schedule Impact */}
        <div style={{ marginTop: '0.125rem' }}>
          {caseId ? (
            <Link href={`/cases/${caseId}/wo-schedule-impact`} style={link(inWoSchedule)}>
              {t('woScheduleImpact')}
            </Link>
          ) : (
            <span style={link(false, true)}>{t('woScheduleImpact')}</span>
          )}
        </div>
      </nav>

      {/* Language switcher */}
      <div style={{ padding: '0.75rem 1rem', borderTop: '1px solid #1f1f22', flexShrink: 0 }}>
        <LanguageSwitcher />
      </div>
    </aside>
  );
}
