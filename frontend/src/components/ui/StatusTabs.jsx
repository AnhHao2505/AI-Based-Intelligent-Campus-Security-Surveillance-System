import React from 'react';
import './StatusTabs.css';

/**
 * Tab trạng thái có badge số đếm — thay cho dãy ô thống kê lặp lại với nút lọc trạng thái.
 *
 * @param {Object} props
 * @param {Array<{key: string, label: string, count?: number|null, tone?: 'default'|'attention'}>} props.items
 *   - count: số hiện trong badge (null/undefined = không hiện badge)
 *   - tone 'attention': tab "cần hành động" (vd Chờ duyệt) — badge nổi màu khi count > 0
 * @param {string} props.value - key của tab đang chọn
 * @param {(key: string) => void} props.onChange
 * @param {string} [props.ariaLabel='Lọc theo trạng thái']
 * @param {string} [props.className='']
 */
export default function StatusTabs({ items, value, onChange, ariaLabel = 'Lọc theo trạng thái', className = '' }) {
  return (
    <div className={`status-tabs ${className}`} role="tablist" aria-label={ariaLabel}>
      {items.map((item) => {
        const active = item.key === value;
        const hasCount = item.count !== undefined && item.count !== null;
        const highlight = item.tone === 'attention' && hasCount && item.count > 0;
        return (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={active}
            className={`status-tabs__tab ${active ? 'status-tabs__tab--active' : ''}`}
            onClick={() => onChange(item.key)}
          >
            <span>{item.label}</span>
            {hasCount && (
              <span
                className={`status-tabs__badge ${highlight ? 'status-tabs__badge--attention' : ''}`}
                aria-label={`${item.count} mục`}
              >
                {item.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
