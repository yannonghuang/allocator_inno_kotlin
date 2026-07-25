'use client';

import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

/**
 * A floating, draggable, resizable popup window — NOT a full-screen modal (no backdrop, no
 * click-outside-to-close). Multiple can be open at once without one covering another
 * fixed-position panel (e.g. a chat slide-in) the way an edge-anchored full-height panel would.
 *
 * Extracted from the Planning page's pegging drill-down panel (its first consumer) so any other
 * panel in the app can get the same movable/resizable behavior instead of re-implementing drag +
 * resize state/effects inline. Title bar is the drag handle; resize is a bottom-right corner
 * handle (both dimensions together, like a desktop window) — mirrors the pre-existing left-edge
 * width-only resize idiom used elsewhere in this codebase, generalized to two dimensions.
 */
export function MovableResizablePopup({
  title,
  onClose,
  children,
  initialWidth = 420,
  initialHeight = 700,
  minWidth = 320,
  minHeight = 240,
  /** Horizontal space to leave clear on the right at first open (e.g. a chat slide-in's width)
   *  — purely a starting position; still fully draggable afterward. */
  reserveRight = 0,
  zIndex = 9998,
  ariaLabel,
}: {
  title: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
  initialWidth?: number;
  initialHeight?: number;
  minWidth?: number;
  minHeight?: number;
  reserveRight?: number;
  zIndex?: number;
  ariaLabel: string;
}) {
  const [width, setWidth] = useState(initialWidth);
  const [height, setHeight] = useState(initialHeight);
  // SSR-safe fixed default; replaced on mount (window isn't available at initial-state time) —
  // see the position-reset effect below. Every mount = a freshly opened popup (callers
  // conditionally render this component), so resetting on mount is exactly "reset on open".
  const [pos, setPos] = useState({ x: 120, y: 48 });

  const dragRef = useRef<{ startX: number; startY: number; startPosX: number; startPosY: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const resizeRef = useRef<{ startX: number; startY: number; startW: number; startH: number } | null>(null);
  const [resizing, setResizing] = useState(false);

  useEffect(() => {
    setPos({ x: Math.max(24, window.innerWidth - initialWidth - reserveRight), y: 56 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!resizing) return;
    const onMove = (e: MouseEvent) => {
      const r = resizeRef.current;
      if (!r) return;
      setWidth(Math.min(window.innerWidth * 0.9, Math.max(minWidth, r.startW + (e.clientX - r.startX))));
      setHeight(Math.min(window.innerHeight * 0.9, Math.max(minHeight, r.startH + (e.clientY - r.startY))));
    };
    const onUp = () => {
      resizeRef.current = null;
      setResizing(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [resizing, minWidth, minHeight]);

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => {
      const r = dragRef.current;
      if (!r) return;
      const x = Math.min(window.innerWidth - 60, Math.max(-width + 60, r.startPosX + (e.clientX - r.startX)));
      const y = Math.min(window.innerHeight - 40, Math.max(0, r.startPosY + (e.clientY - r.startY)));
      setPos({ x, y });
    };
    const onUp = () => {
      dragRef.current = null;
      setDragging(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [dragging, width]);

  if (typeof document === 'undefined') return null;

  return createPortal(
    <div style={{ position: 'fixed', inset: 0, zIndex, pointerEvents: 'none' }} role="dialog" aria-label={ariaLabel}>
      <div
        style={{
          position: 'fixed',
          left: pos.x,
          top: pos.y,
          zIndex: 10,
          width,
          maxWidth: '90vw',
          minWidth,
          height,
          maxHeight: '90vh',
          minHeight,
          overflow: 'hidden',
          background: '#1c1c1e',
          color: '#e4e4e7',
          border: '1px solid #3d3d40',
          borderRadius: 10,
          boxShadow: '0 12px 40px rgba(0,0,0,0.5)',
          padding: '1.25rem',
          pointerEvents: 'auto',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <div
          role="separator"
          aria-label={`Resize ${ariaLabel}`}
          title="Drag to resize"
          onMouseDown={(e) => {
            e.preventDefault();
            e.stopPropagation();
            resizeRef.current = { startX: e.clientX, startY: e.clientY, startW: width, startH: height };
            setResizing(true);
          }}
          style={{
            position: 'absolute',
            right: 0,
            bottom: 0,
            width: 18,
            height: 18,
            cursor: 'nwse-resize',
            zIndex: 11,
            background: resizing ? 'rgba(56, 189, 248, 0.45)' : 'rgba(255, 255, 255, 0.05)',
            borderTop: resizing ? '1px solid #38bdf8' : '1px solid rgba(255, 255, 255, 0.08)',
            borderLeft: resizing ? '1px solid #38bdf8' : '1px solid rgba(255, 255, 255, 0.08)',
            borderBottomRightRadius: 10,
            transition: resizing ? 'none' : 'background 120ms ease, border-color 120ms ease',
          }}
          onMouseEnter={(e) => {
            if (resizing) return;
            (e.currentTarget as HTMLDivElement).style.background = 'rgba(56, 189, 248, 0.25)';
          }}
          onMouseLeave={(e) => {
            if (resizing) return;
            (e.currentTarget as HTMLDivElement).style.background = 'rgba(255, 255, 255, 0.05)';
          }}
        />
        <div
          onMouseDown={(e) => {
            dragRef.current = { startX: e.clientX, startY: e.clientY, startPosX: pos.x, startPosY: pos.y };
            setDragging(true);
          }}
          style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem', cursor: 'move', flexShrink: 0 }}
        >
          <h3 style={{ margin: 0, color: '#fafafa' }}>{title}</h3>
          <button
            type="button"
            onMouseDown={(e) => e.stopPropagation()}
            onClick={onClose}
            style={{ padding: '6px 12px', background: '#2d2d30', color: '#e4e4e7', border: '1px solid #3d3d40', borderRadius: 6, cursor: 'pointer' }}
          >
            Close
          </button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>{children}</div>
      </div>
    </div>,
    document.body
  );
}
