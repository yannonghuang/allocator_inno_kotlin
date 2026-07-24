'use client';

import React, { useState } from 'react';

/** Single-select dropdown with a type-to-filter input — for long option lists (products,
 *  locations, customers, parent products, etc.) across the app's config editors/previews. */
export function SearchableSelect({ value, onChange, options, placeholder, disabled, width, clearTitle, noMatchText = 'No match' }: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  placeholder: string;
  disabled?: boolean;
  width?: number;
  clearTitle?: string;
  noMatchText?: string;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const w = width ?? 220;
  const selectedLabel = options.find((o) => o.value === value)?.label ?? '';
  const q = query.trim().toLowerCase();
  const shown = q ? options.filter((o) => o.value.toLowerCase().includes(q) || o.label.toLowerCase().includes(q)) : options;
  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <input
        type="text"
        disabled={disabled}
        value={open ? query : selectedLabel}
        placeholder={placeholder}
        onChange={(e) => { setQuery(e.target.value); if (!open) setOpen(true); }}
        onFocus={() => { setOpen(true); setQuery(''); }}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        style={{ padding: '3px 18px 3px 6px', background: disabled ? '#1f1f22' : '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.78rem', width: w }}
      />
      {value && !open && !disabled && (
        <button type="button" title={clearTitle} onMouseDown={(e) => { e.preventDefault(); onChange(''); }}
          style={{ position: 'absolute', right: 4, top: 2, background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.85rem', lineHeight: 1, padding: 0 }}>×</button>
      )}
      {open && !disabled && (
        <div style={{ position: 'absolute', zIndex: 30, top: '100%', left: 0, width: w, maxHeight: 220, overflowY: 'auto', background: '#1f1f22', border: '1px solid #3f3f46', borderRadius: 4, marginTop: 2 }}>
          {shown.length === 0 && <div style={{ padding: '4px 6px', fontSize: '0.75rem', color: '#71717a' }}>{noMatchText}</div>}
          {shown.slice(0, 300).map((o) => (
            <div key={o.value} onMouseDown={(e) => { e.preventDefault(); onChange(o.value); setOpen(false); setQuery(''); }}
              style={{ padding: '3px 6px', fontSize: '0.78rem', color: '#e4e4e7', cursor: 'pointer', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', background: o.value === value ? '#3730a3' : 'transparent' }}>
              {o.label}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
