'use client';

import React, { useState } from 'react';
import type { ConstraintOptions, ConstraintRuleRow } from '@/lib/api';

/** Single-select dropdown with a type-to-filter input — for long option lists (customers,
 *  parent products) in the constraint editor. */
export function SearchableSelect({ value, onChange, options, placeholder, disabled, width, tP }: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  placeholder: string;
  disabled?: boolean;
  width?: number;
  tP: (k: string) => string;
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
        <button type="button" title={tP('config.constraintRemove')} onMouseDown={(e) => { e.preventDefault(); onChange(''); }}
          style={{ position: 'absolute', right: 4, top: 2, background: 'none', border: 'none', color: '#71717a', cursor: 'pointer', fontSize: '0.85rem', lineHeight: 1, padding: 0 }}>×</button>
      )}
      {open && !disabled && (
        <div style={{ position: 'absolute', zIndex: 30, top: '100%', left: 0, width: w, maxHeight: 220, overflowY: 'auto', background: '#1f1f22', border: '1px solid #3f3f46', borderRadius: 4, marginTop: 2 }}>
          {shown.length === 0 && <div style={{ padding: '4px 6px', fontSize: '0.75rem', color: '#71717a' }}>{tP('config.constraintNoMatch')}</div>}
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

/** Builder for customer-specific BOM-alternative constraints: four cascading dropdowns
 *  (customer → parent → location → child) + Add, with a removable list of added rules. Used on
 *  the dedicated Constraints page (moved out of the Planning page's inline config form — see
 *  Constraints.kt's own doc for why this became a case-level persisted setting). */
export function ConstraintPicker({
  options,
  constraints,
  onChange,
  defaultCollapsed,
  tP,
}: {
  options: ConstraintOptions;
  constraints: ConstraintRuleRow[];
  onChange: (next: ConstraintRuleRow[]) => void;
  defaultCollapsed?: boolean;
  tP: (k: string) => string;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const [customer, setCustomer] = useState('');
  const [parent, setParent] = useState('');
  const [location, setLocation] = useState('*');
  const [child, setChild] = useState('');

  const parentOpt = options.parents.find((p) => p.parent === parent);
  const locationOpts = parentOpt?.locations ?? [];
  const childOpts = parentOpt?.children ?? [];
  const canAdd = !!(customer && parent && child);

  const selStyle: React.CSSProperties = { padding: '3px 6px', background: '#27272a', border: '1px solid #3d3d40', borderRadius: 4, color: '#fafafa', fontSize: '0.78rem', maxWidth: 240 };
  const btnStyle: React.CSSProperties = { fontSize: '0.7rem', color: '#d4d4d8', background: '#27272a', border: '1px solid #3f3f46', borderRadius: 4, padding: '3px 9px', cursor: 'pointer' };
  const anyLoc = (l: string) => (l === '*' || !l ? tP('config.constraintLocationAny') : l);
  const custLabel = (cid: string) => { const c = options.customers.find((x) => x.customer_id === cid); return c?.description ? `${cid} — ${c.description}` : cid; };

  const addRule = () => {
    if (!canAdd) return;
    const rule: ConstraintRuleRow = { customer_id: customer, parent, location: location || '*', child };
    if (constraints.some((r) => r.customer_id === rule.customer_id && r.parent === rule.parent && r.location === rule.location && r.child === rule.child)) return;
    onChange([...constraints, rule]);
    setChild('');   // keep customer/parent so the user can add sibling rules quickly
  };

  return (
    <div style={{ marginTop: '0.4rem' }}>
      <button type="button" onClick={() => setCollapsed((c) => !c)}
        style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', background: 'none', border: 'none', padding: 0, color: 'inherit', fontSize: '0.875rem', cursor: 'pointer' }}>
        <span style={{ fontSize: '0.7rem', color: '#a1a1aa' }}>{collapsed ? '▸' : '▾'}</span>
        <span>{collapsed ? tP('config.constraintShow') : tP('config.constraintHide')}</span>
        <span style={{ color: '#a1a1aa' }}>{`(${constraints.length})`}</span>
      </button>
      {!collapsed && (
        <div style={{ marginTop: '0.35rem', marginLeft: '1.5rem' }}>
          <div style={{ fontSize: '0.7rem', color: '#71717a', marginBottom: 4 }}>{tP('config.constraintHint')}</div>
          {options.parents.length === 0 ? (
            <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{tP('config.constraintNoAlternatives')}</div>
          ) : (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', marginBottom: 6 }}>
              <SearchableSelect
                value={customer}
                onChange={setCustomer}
                options={options.customers.map((c) => ({ value: c.customer_id, label: custLabel(c.customer_id) }))}
                placeholder={`${tP('config.constraintCustomer')}…`}
                width={220}
                tP={tP}
              />
              <SearchableSelect
                value={parent}
                onChange={(v) => { setParent(v); setLocation('*'); setChild(''); }}
                options={options.parents.map((p) => ({ value: p.parent, label: p.parent }))}
                placeholder={`${tP('config.constraintParent')}…`}
                width={260}
                tP={tP}
              />
              <select value={location} onChange={(e) => setLocation(e.target.value)} disabled={!parent} style={selStyle}>
                <option value="*">{tP('config.constraintLocationAny')}</option>
                {locationOpts.map((l) => <option key={l} value={l}>{l}</option>)}
              </select>
              <select value={child} onChange={(e) => setChild(e.target.value)} disabled={!parent} style={selStyle}>
                <option value="">{tP('config.constraintChild')}…</option>
                {childOpts.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
              <button type="button" onClick={addRule} disabled={!canAdd} style={{ ...btnStyle, opacity: canAdd ? 1 : 0.4, cursor: canAdd ? 'pointer' : 'default' }}>{tP('config.constraintAdd')}</button>
            </div>
          )}
          {constraints.length === 0 ? (
            <div style={{ fontSize: '0.72rem', color: '#71717a' }}>{tP('config.constraintEmpty')}</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {constraints.map((r, i) => (
                <div key={`${r.customer_id}|${r.parent}|${r.location}|${r.child}`} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.76rem' }}>
                  <span style={{ fontFamily: 'monospace', color: '#e4e4e7' }}>
                    {r.customer_id} · {r.parent} @ {anyLoc(r.location)} ⇒ {r.child}
                  </span>
                  <button type="button" title={tP('config.constraintRemove')} onClick={() => onChange(constraints.filter((_, j) => j !== i))}
                    style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer', fontSize: '0.95rem', lineHeight: 1, padding: 0 }}>×</button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
