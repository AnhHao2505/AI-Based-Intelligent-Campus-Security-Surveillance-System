import React from 'react';
import { Loader2 } from 'lucide-react';
import './Button.css'; // keyframes ui-btn-spin dùng chung với nút đang tải
import './StateView.css';

/**
 * Trạng thái đang tải (vòng quay + chữ).
 * @param {Object} props
 * @param {React.ReactNode} [props.text='Đang tải...']
 * @param {'md'|'sm'} [props.size='md'] - 'sm' gọn hơn, dùng trong ô / popup nhỏ
 * @param {boolean} [props.inline=false] - nằm ngang (vòng quay cạnh chữ), không căn giữa khối
 * @param {string} [props.className='']
 */
export default function LoadingState({ text = 'Đang tải...', size = 'md', inline = false, className = '' }) {
  const classes = [
    'ui-state',
    'ui-state--loading',
    size === 'sm' ? 'ui-state--compact' : '',
    inline ? 'ui-state--inline' : '',
    className,
  ].filter(Boolean).join(' ');

  return (
    <div className={classes} role="status" aria-live="polite">
      <Loader2 className="ui-state__spinner" size={size === 'sm' ? 18 : 28} aria-hidden="true" />
      {text && <p className="ui-state__text">{text}</p>}
    </div>
  );
}
