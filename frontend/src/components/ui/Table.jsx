import React from 'react';
import { Inbox } from 'lucide-react';
import './Table.css';

/**
 * Standard Table component
 * @param {Object} props
 * @param {Array<{
 *   key: string,
 *   title: React.ReactNode,
 *   align?: 'left'|'center'|'right',
 *   width?: string|number,
 *   isCode?: boolean,
 *   render?: (value: any, record: any, index: number) => React.ReactNode
 * }>} props.columns
 * @param {Array<Object>} [props.data=[]]
 * @param {boolean} [props.loading=false]
 * @param {string} [props.emptyText='Không có dữ liệu']
 * @param {React.ComponentType|React.ReactNode} [props.emptyIcon]
 * @param {string|((record: Object, index: number) => string|number)} [props.rowKey='id']
 * @param {(record: Object, index: number) => void} [props.onRowClick]
 * @param {string} [props.className='']
 */
export default function Table({
  columns = [],
  data = [],
  loading = false,
  emptyText = 'Không có dữ liệu',
  emptyIcon: EmptyIcon = Inbox,
  rowKey = 'id',
  onRowClick,
  className = '',
}) {
  const getRowKey = (record, index) => {
    if (typeof rowKey === 'function') return rowKey(record, index);
    return record?.[rowKey] ?? index;
  };

  const renderEmptyIcon = () => {
    if (!EmptyIcon) return null;
    if (React.isValidElement(EmptyIcon)) return EmptyIcon;
    return <EmptyIcon size={36} className="ui-table__empty-icon" />;
  };

  return (
    <div className={`ui-table-wrapper ${className}`.trim()}>
      <table className="ui-table">
        <thead>
          <tr>
            {columns.map((col) => (
              <th
                key={col.key}
                style={{
                  textAlign: col.align || 'left',
                  width: col.width || 'auto',
                }}
                className={`ui-table__th ${col.align ? `ui-table__cell--${col.align}` : ''}`}
              >
                {col.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            // Skeleton loading rows (5 rows) — keeping the table header!
            Array.from({ length: 5 }).map((_, rIdx) => (
              <tr key={`skeleton-${rIdx}`} className="ui-table__skeleton-row">
                {columns.map((col, cIdx) => (
                  <td
                    key={`skeleton-col-${cIdx}`}
                    style={{ textAlign: col.align || 'left' }}
                    className="ui-table__td"
                  >
                    <div
                      className="ui-table__skeleton-bar"
                      style={{
                        width: cIdx === 0 ? '40%' : cIdx === columns.length - 1 ? '50%' : '75%',
                      }}
                    />
                  </td>
                ))}
              </tr>
            ))
          ) : data.length === 0 ? (
            // Empty state — keeping the table header!
            <tr className="ui-table__empty-row">
              <td colSpan={columns.length} className="ui-table__empty-cell">
                <div className="ui-table__empty-container">
                  {renderEmptyIcon()}
                  <p className="ui-table__empty-text">{emptyText}</p>
                </div>
              </td>
            </tr>
          ) : (
            // Data rows
            data.map((record, index) => {
              const isClickable = Boolean(onRowClick);
              return (
                <tr
                  key={getRowKey(record, index)}
                  className={`ui-table__row ${isClickable ? 'ui-table__row--clickable' : ''}`}
                  onClick={() => onRowClick?.(record, index)}
                >
                  {columns.map((col) => {
                    const rawValue = record[col.key];
                    const content = col.render
                      ? col.render(rawValue, record, index)
                      : rawValue;

                    return (
                      <td
                        key={col.key}
                        style={{ textAlign: col.align || 'left' }}
                        className={`ui-table__td ${col.align ? `ui-table__cell--${col.align}` : ''} ${col.isCode ? 'ui-table__cell--code' : ''}`}
                      >
                        {content ?? '—'}
                      </td>
                    );
                  })}
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
