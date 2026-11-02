'use client';

import { useEffect } from 'react';
import { setActiveCase } from '@/lib/api';

/**
 * Marks the case being viewed as the active case, so agent workflows that don't
 * carry an explicit case (e.g. a material event typed into the agent chat) resolve
 * to what the planner has on screen. Best-effort: a failure only means the agent
 * falls back to the newest case holding the supply.
 */
export function ActiveCaseMarker({ caseId }: { caseId: number }) {
  useEffect(() => {
    if (!Number.isFinite(caseId)) return;
    setActiveCase(caseId).catch(() => {});
  }, [caseId]);
  return null;
}
