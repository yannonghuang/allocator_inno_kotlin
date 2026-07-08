'use client';

import React, { useState, useMemo } from 'react';

export type Column<T> = {
  key: keyof T | string;
  label: string;
  render?: (row: T) => React.ReactNode;
  sortable?: boolean;
  /** Optional explicit column width (CSS), e.g. "36%" or "120px". */
  width?: string;
  /** Optional custom header content (overrides plain label rendering). The sort button still wraps it when sortable. */
  headerRender?: () => React.ReactNode;
  /**
   * Optional custom sort value. When provided, this is used instead of `row[key]`
   * for comparison. The sort direction is passed so a column can sort by a different
   * field for asc vs desc (e.g. asc by start_time, desc by end_time).
   */
  sortValue?: (row: T, dir: 'asc' | 'desc') => unknown;
};

type Props<T> = {
  columns: Column<T>[];
  rows: T[];
  filterKeys?: (keyof T | string)[];
  defaultSortKey?: keyof T | string;
  idKey: keyof T | string;
  /** Optional: set id on each <tr> for anchor links (e.g. rowId: (r) => `supply-${r.supply_id}`). */
  rowId?: (row: T) => string | undefined;
  /** Optional: row click handler (e.g. open pegging slide-in). */
  onRowClick?: (row: T) => void;
  /** When true, thead stays visible while scrolling the table body. Uses a dedicated scroll wrapper so headers stick in the UI. */
  stickyHeader?: boolean;
  /** Max height of the table scroll area when stickyHeader is true (default 70vh). */
  stickyHeaderScrollMaxHeight?: string;
  /** Optional per-row style (e.g. highlight selected row). */
  rowStyle?: (row: T) => React.CSSProperties | undefined;
  /** Placeholder for the filter input (e.g. "Filter by customer, product…"). */
  filterPlaceholder?: string;
  /**
   * When provided, each row gets a ▶/▼ toggle in a leading column.
   * Return non-null content to show beneath the row when expanded; return null to disable the toggle for that row.
   */
  expandedRowContent?: (row: T) => React.ReactNode;
  /** Cheap predicate deciding whether a row's toggle is shown at all. When it returns false the
   *  ▶/▼ chevron is hidden (the row has nothing to expand) — avoids dead toggles that open empty. */
  canExpandRow?: (row: T) => boolean;
  /** Set of row idKey values that are currently expanded. */
  expandedKeys?: Set<string>;
  /** Called when the user clicks the expand toggle on a row. */
  onToggleExpand?: (rowId: string) => void;
};

export function SortFilterTable<T extends Record<string, unknown>>({
  columns,
  rows,
  filterKeys,
  defaultSortKey,
  idKey,
  rowId,
  onRowClick,
  rowStyle,
  stickyHeader = false,
  stickyHeaderScrollMaxHeight = '70vh',
  filterPlaceholder = 'Filter…',
  expandedRowContent,
  canExpandRow,
  expandedKeys,
  onToggleExpand,
}: Props<T>) {
  const [filter, setFilter] = useState('');
  const [sortKey, setSortKey] = useState<keyof T | string | null>(defaultSortKey ?? null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const filtered = useMemo(() => {
    if (!filter.trim() || !filterKeys?.length) return rows;
    const q = filter.trim().toLowerCase();
    return rows.filter((row) =>
      filterKeys.some((k) => String(row[k] ?? '').toLowerCase().includes(q))
    );
  }, [rows, filter, filterKeys]);

  const sorted = useMemo(() => {
    if (!sortKey) return filtered;
    const sortCol = columns.find((c) => c.key === sortKey);
    return [...filtered].sort((a, b) => {
      const va = sortCol?.sortValue ? sortCol.sortValue(a, sortDir) : a[sortKey];
      const vb = sortCol?.sortValue ? sortCol.sortValue(b, sortDir) : b[sortKey];
      const aNum = typeof va === 'number' ? va : Number(va);
      const bNum = typeof vb === 'number' ? vb : Number(vb);
      if (!Number.isNaN(aNum) && !Number.isNaN(bNum)) {
        return sortDir === 'asc' ? aNum - bNum : bNum - aNum;
      }
      const aStr = String(va ?? '');
      const bStr = String(vb ?? '');
      const cmp = aStr.localeCompare(bStr);
      return sortDir === 'asc' ? cmp : -cmp;
    });
  }, [filtered, sortKey, sortDir, columns]);

  const handleSort = (key: keyof T | string) => {
    if (sortKey === key) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      setSortKey(key);
      setSortDir('asc');
    }
  };

  return (
    <div>
      {filterKeys && filterKeys.length > 0 && (
        <div style={{ marginBottom: '0.5rem' }}>
          <input
            type="text"
            placeholder={filterPlaceholder}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            style={{ width: '100%', maxWidth: 320 }}
            aria-label="Filter table rows"
          />
        </div>
      )}
      <div
        style={
          stickyHeader
            ? { overflow: 'auto', maxHeight: stickyHeaderScrollMaxHeight, overflowX: 'auto' as const }
            : { overflowX: 'auto' }
        }
      >
        <table style={stickyHeader ? { borderCollapse: 'collapse' } : undefined}>
          <thead>
            <tr>
              {expandedRowContent && (
                <th
                  style={{
                    width: '1.75rem',
                    ...(stickyHeader ? { position: 'sticky', top: 0, zIndex: 1, background: '#1c1c1e', boxShadow: '0 1px 0 0 #3d3d40' } : {}),
                  }}
                />
              )}
              {columns.map((col) => {
                const baseStyle: React.CSSProperties = stickyHeader
                  ? { position: 'sticky', top: 0, zIndex: 1, background: '#1c1c1e', boxShadow: '0 1px 0 0 #3d3d40' }
                  : {};
                if (col.width) baseStyle.width = col.width;
                const headerContent = col.headerRender ? col.headerRender() : col.label;
                const isSortable = col.sortable !== false;
                // Active column gets a solid arrow; other sortable columns get a faint ↕ so
                // sortability is visually discoverable instead of looking like plain text.
                const sortIndicator = sortKey === col.key ? (sortDir === 'asc' ? '↑' : '↓') : (isSortable ? '↕' : '');
                if (col.headerRender) {
                  // Custom header (e.g. schedule ruler): render at full th width via a
                  // block-level wrapper div (avoids `position: relative` on the table-cell
                  // itself, which has quirky width behavior) and overlay a corner sort
                  // indicator so the headerRender content keeps the column's exact extent.
                  return (
                    <th key={String(col.key)} style={baseStyle}>
                      <div
                        style={{ position: 'relative', display: 'block', width: '100%', cursor: isSortable ? 'pointer' : undefined }}
                        onClick={isSortable ? () => handleSort(col.key) : undefined}
                      >
                        {headerContent}
                        {isSortable && sortIndicator && (
                          <span style={{
                            position: 'absolute', top: 0, right: 4, fontSize: 11,
                            fontWeight: sortKey === col.key ? 'bold' : 'normal',
                            opacity: sortKey === col.key ? 1 : 0.45,
                          }}>{sortIndicator}</span>
                        )}
                      </div>
                    </th>
                  );
                }
                return (
                  <th key={String(col.key)} style={baseStyle}>
                    {isSortable ? (
                      <button
                        type="button"
                        onClick={() => handleSort(col.key)}
                        style={{
                          background: 'none',
                          border: 'none',
                          color: 'inherit',
                          cursor: 'pointer',
                          padding: 0,
                          fontWeight: sortKey === col.key ? 'bold' : 'normal',
                        }}
                      >
                        {headerContent} <span style={{ opacity: sortKey === col.key ? 1 : 0.45 }}>{sortIndicator}</span>
                      </button>
                    ) : (
                      headerContent
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {sorted.map((row, i) => {
              const rId = String(row[idKey] ?? i);
              const rowExpandable = !!expandedRowContent && (!canExpandRow || canExpandRow(row));
              const isExpanded = rowExpandable ? (expandedKeys?.has(rId) ?? false) : false;
              const expandContent = isExpanded ? expandedRowContent?.(row) : null;
              return (
                <React.Fragment key={rId}>
                  <tr
                    id={rowId?.(row)}
                    onClick={onRowClick ? () => onRowClick(row) : undefined}
                    style={{ ...(onRowClick ? { cursor: 'pointer' } : {}), ...rowStyle?.(row) }}
                    role={onRowClick ? 'button' : undefined}
                  >
                    {expandedRowContent && (
                      <td style={{ textAlign: 'center', width: '1.75rem', padding: '0 0.25rem' }}>
                        {rowExpandable && (
                          <button
                            type="button"
                            onClick={(e) => { e.stopPropagation(); onToggleExpand?.(rId); }}
                            style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#3b82f6', fontSize: '0.7rem', padding: '1px 3px', lineHeight: 1 }}
                            title={isExpanded ? 'Collapse supplies' : 'Expand supplies'}
                          >
                            {isExpanded ? '▼' : '▶'}
                          </button>
                        )}
                      </td>
                    )}
                    {columns.map((col) => (
                      <td key={String(col.key)}>
                        {col.render ? col.render(row) : String(row[col.key] ?? '')}
                      </td>
                    ))}
                  </tr>
                  {isExpanded && expandContent != null && (
                    <tr>
                      <td colSpan={columns.length + 1} style={{ padding: 0 }}>
                        {expandContent}
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      <p style={{ fontSize: '0.875rem', color: '#a1a1aa' }}>
        {sorted.length} row{sorted.length !== 1 ? 's' : ''}
        {filter && ` (filtered from ${rows.length})`}
      </p>
    </div>
  );
}
