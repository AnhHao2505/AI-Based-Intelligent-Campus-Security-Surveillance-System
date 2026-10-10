import React, { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';
import './Modal.css';

// Các Modal đang mở theo thứ tự mở: Esc / bẫy Tab chỉ áp cho Modal trên cùng (Modal lồng Modal không đóng cả hai).
const openModalStack = [];

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Standard Modal component
 * @param {Object} props
 * @param {boolean} props.isOpen
 * @param {Function} props.onClose
 * @param {React.ReactNode} props.title
 * @param {React.ReactNode} [props.subtitle]
 * @param {React.ComponentType|React.ReactNode} [props.icon]
 * @param {'brand'|'warning'|'danger'|'success'} [props.iconVariant='brand']
 * @param {'sm'|'md'|'lg'|'xl'} [props.size='md']
 * @param {React.ReactNode} [props.footer]
 * @param {boolean} [props.closeOnBackdrop=true]
 * @param {string} [props.className='']
 * @param {React.ReactNode} props.children
 */
export default function Modal({
  isOpen,
  onClose,
  title,
  subtitle,
  icon: Icon,
  iconVariant = 'brand',
  size = 'md',
  footer,
  closeOnBackdrop = true,
  className = '',
  children,
}) {
  const titleId = useId();
  const dialogRef = useRef(null);
  // onClose thường là hàm viết tại chỗ (đổi mỗi lần render) -> giữ bằng ref để effect chỉ chạy khi mở / đóng
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  // Khoá cuộn trang, Esc đóng, giữ focus trong popup, trả focus về chỗ cũ khi đóng
  useEffect(() => {
    if (!isOpen) return undefined;

    const token = {};
    openModalStack.push(token);
    const isTopmost = () => openModalStack[openModalStack.length - 1] === token;
    const previouslyFocused = document.activeElement;

    const handleKeyDown = (e) => {
      if (!isTopmost() || e.defaultPrevented) return;
      if (e.key === 'Escape' || e.key === 'Esc') {
        onCloseRef.current?.();
        return;
      }
      if (e.key === 'Tab' && dialogRef.current) {
        const focusable = Array.from(dialogRef.current.querySelectorAll(FOCUSABLE_SELECTOR))
          .filter((el) => el.offsetParent !== null || el === document.activeElement);
        if (focusable.length === 0) {
          e.preventDefault();
          dialogRef.current.focus();
          return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;
        if (e.shiftKey && (active === first || !dialogRef.current.contains(active))) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && (active === last || !dialogRef.current.contains(active))) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', handleKeyDown);

    // Đưa focus vào popup, trừ khi nội dung đã tự focus (autoFocus)
    if (dialogRef.current && !dialogRef.current.contains(document.activeElement)) {
      dialogRef.current.focus({ preventScroll: true });
    }

    return () => {
      document.body.style.overflow = originalOverflow;
      window.removeEventListener('keydown', handleKeyDown);
      const index = openModalStack.indexOf(token);
      if (index !== -1) openModalStack.splice(index, 1);
      if (previouslyFocused && typeof previouslyFocused.focus === 'function' && document.contains(previouslyFocused)) {
        previouslyFocused.focus({ preventScroll: true });
      }
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const handleBackdropClick = (e) => {
    // Only close if clicked directly on the overlay backdrop
    if (e.target === e.currentTarget && closeOnBackdrop) {
      onClose?.();
    }
  };

  const renderIcon = () => {
    if (!Icon) return null;
    if (React.isValidElement(Icon)) {
      return Icon;
    }
    return <Icon size={18} />;
  };

  return (
    <div
      className="ui-modal-backdrop"
      onClick={handleBackdropClick}
    >
      <div
        ref={dialogRef}
        className={`ui-modal ui-modal--${size} ${className}`.trim()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        tabIndex={-1}
      >
        {/* Header */}
        <div className="ui-modal__header">
          <div className="ui-modal__header-left">
            {Icon && (
              <div className={`ui-modal__icon-badge ui-modal__icon-badge--${iconVariant}`}>
                {renderIcon()}
              </div>
            )}
            <div className="ui-modal__title-wrap">
              <h3 className="ui-modal__title" id={titleId}>{title}</h3>
              {subtitle && <p className="ui-modal__subtitle">{subtitle}</p>}
            </div>
          </div>

          <button
            type="button"
            className="ui-modal__close-btn"
            onClick={onClose}
            aria-label="Đóng"
          >
            <X size={18} />
          </button>
        </div>

        {/* Body */}
        <div className="ui-modal__body">
          {children}
        </div>

        {/* Footer */}
        {footer && (
          <div className="ui-modal__footer">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
