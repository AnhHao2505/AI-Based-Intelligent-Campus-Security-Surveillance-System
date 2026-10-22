import React from 'react';
import { createPortal } from 'react-dom';
import './PageHeader.css';

/**
 * Khuôn tiêu đề trang dùng chung: tiêu đề + mô tả + vùng nút bên phải.
 * @param {Object} props
 * @param {React.ReactNode} props.title
 * @param {React.ReactNode} [props.description]
 * @param {React.ReactNode} [props.actions]
 * @param {Element|null} [props.actionsContainer] - trang nhúng trong tab: chỉ render vùng nút vào phần tử này (vd hàng thanh tab), bỏ tiêu đề / mô tả; null = phần tử chưa gắn
 * @param {string} [props.className='']
 */
export default function PageHeader({ title, description, actions, actionsContainer, className = '' }) {
  if (actionsContainer !== undefined) {
    return actionsContainer && actions
      ? createPortal(<div className="ui-page-header__actions">{actions}</div>, actionsContainer)
      : null;
  }
  return (
    <header className={`ui-page-header ${className}`.trim()}>
      <div className="ui-page-header__text">
        <h1 className="ui-page-header__title">{title}</h1>
        {description && <p className="ui-page-header__description">{description}</p>}
      </div>
      {actions && <div className="ui-page-header__actions">{actions}</div>}
    </header>
  );
}
