import React from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import Button from './Button';
import './StateView.css';

/**
 * Trạng thái lỗi khi tải dữ liệu, có nút "Thử lại". Không dùng EmptyState cho lỗi API.
 * @param {Object} props
 * @param {React.ReactNode} [props.title='Không tải được dữ liệu']
 * @param {React.ReactNode} [props.message] - thường là err.message từ apiClient
 * @param {Function} [props.onRetry] - có thì hiện nút thử lại
 * @param {React.ReactNode} [props.retryLabel='Thử lại']
 * @param {boolean} [props.retrying=false] - nút hiện vòng quay và bị khoá
 * @param {'md'|'sm'} [props.size='md']
 * @param {string} [props.className='']
 */
export default function ErrorState({
  title = 'Không tải được dữ liệu',
  message,
  onRetry,
  retryLabel = 'Thử lại',
  retrying = false,
  size = 'md',
  className = '',
}) {
  const classes = ['ui-state', 'ui-state--error', size === 'sm' ? 'ui-state--compact' : '', className]
    .filter(Boolean).join(' ');

  return (
    <div className={classes} role="alert">
      <span className="ui-state__icon" aria-hidden="true">
        <AlertTriangle size={size === 'sm' ? 18 : 22} />
      </span>
      {title && <p className="ui-state__title">{title}</p>}
      {message && <p className="ui-state__text">{message}</p>}
      {onRetry && (
        <div className="ui-state__action">
          <Button
            variant="secondary"
            size={size === 'sm' ? 'sm' : 'md'}
            icon={RefreshCw}
            loading={retrying}
            onClick={onRetry}
          >
            {retryLabel}
          </Button>
        </div>
      )}
    </div>
  );
}
