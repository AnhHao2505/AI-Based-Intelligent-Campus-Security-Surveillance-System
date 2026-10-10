import React from 'react';
import { Inbox } from 'lucide-react';
import Button from './Button';
import './StateView.css';

/**
 * Trạng thái không có dữ liệu. Chỉ dùng khi tải THÀNH CÔNG mà danh sách rỗng — lỗi API dùng ErrorState.
 * @param {Object} props
 * @param {React.ComponentType|React.ReactNode|null} [props.icon=Inbox] - null để ẩn icon
 * @param {React.ReactNode} [props.title='Chưa có dữ liệu']
 * @param {React.ReactNode} [props.description]
 * @param {React.ReactNode} [props.actionLabel] - có cùng onAction thì hiện nút
 * @param {Function} [props.onAction]
 * @param {React.ComponentType|React.ReactNode} [props.actionIcon]
 * @param {'primary'|'secondary'|'ghost'} [props.actionVariant='primary']
 * @param {'md'|'sm'} [props.size='md']
 * @param {string} [props.className='']
 * @param {React.ReactNode} [props.children] - nội dung thêm dưới mô tả
 */
export default function EmptyState({
  icon: Icon = Inbox,
  title = 'Chưa có dữ liệu',
  description,
  actionLabel,
  onAction,
  actionIcon,
  actionVariant = 'primary',
  size = 'md',
  className = '',
  children,
}) {
  const renderIcon = () => {
    if (!Icon) return null;
    if (React.isValidElement(Icon)) return <span className="ui-state__icon" aria-hidden="true">{Icon}</span>;
    return (
      <span className="ui-state__icon" aria-hidden="true">
        <Icon size={size === 'sm' ? 18 : 22} />
      </span>
    );
  };

  const classes = ['ui-state', 'ui-state--empty', size === 'sm' ? 'ui-state--compact' : '', className]
    .filter(Boolean).join(' ');

  return (
    <div className={classes}>
      {renderIcon()}
      {title && <p className="ui-state__title">{title}</p>}
      {description && <p className="ui-state__text">{description}</p>}
      {children}
      {actionLabel && onAction && (
        <div className="ui-state__action">
          <Button variant={actionVariant} size={size === 'sm' ? 'sm' : 'md'} icon={actionIcon} onClick={onAction}>
            {actionLabel}
          </Button>
        </div>
      )}
    </div>
  );
}
