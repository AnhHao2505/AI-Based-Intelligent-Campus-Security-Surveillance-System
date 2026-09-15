import React, { useEffect } from 'react';
import { X } from 'lucide-react';
import './Modal.css';

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
  // Lock body scroll and listen for Escape key
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e) => {
      if (e.key === 'Escape' || e.key === 'Esc') {
        onClose?.();
      }
    };

    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      document.body.style.overflow = originalOverflow;
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen, onClose]);

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
      role="dialog"
      aria-modal="true"
    >
      <div className={`ui-modal ui-modal--${size} ${className}`.trim()}>
        {/* Header */}
        <div className="ui-modal__header">
          <div className="ui-modal__header-left">
            {Icon && (
              <div className={`ui-modal__icon-badge ui-modal__icon-badge--${iconVariant}`}>
                {renderIcon()}
              </div>
            )}
            <div className="ui-modal__title-wrap">
              <h3 className="ui-modal__title">{title}</h3>
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
