'use client';

import { useState } from 'react';
import { Sidebar, SIDEBAR_WIDTH } from './Sidebar';

export function SidebarLayout({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState(true);

  return (
    <>
      <Sidebar open={open} onToggle={() => setOpen((o) => !o)} />

      {/* Floating open button — only visible when sidebar is collapsed */}
      {!open && (
        <button
          onClick={() => setOpen(true)}
          aria-label="Open sidebar"
          style={{
            position: 'fixed',
            top: 12,
            left: 12,
            zIndex: 101,
            width: 32,
            height: 32,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: '#18181b',
            border: '1px solid #3d3d40',
            borderRadius: 6,
            color: '#a1a1aa',
            cursor: 'pointer',
            fontSize: '1rem',
            padding: 0,
          }}
        >
          ☰
        </button>
      )}

      <main
        style={{
          marginLeft: open ? SIDEBAR_WIDTH : 0,
          padding: '1.5rem 2rem',
          minHeight: '100vh',
          transition: 'margin-left 0.2s ease',
        }}
      >
        {children}
      </main>
    </>
  );
}
