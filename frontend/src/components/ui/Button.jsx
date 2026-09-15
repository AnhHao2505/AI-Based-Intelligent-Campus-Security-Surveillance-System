import React from 'react';
import { Loader2 } from 'lucide-react';
import './Button.css';

/**
 * Standard Button component
 * @param {Object} props
 * @param {'primary'|'secondary'|'danger'|'ghost'} [props.variant='primary']
 * @param {'sm'|'md'} [props.size='md']
 * @param {React.ComponentType|React.ReactNode} [props.icon]
 * @param {boolean} [props.loading=false]
 * @param {boolean} [props.disabled=false]
 * @param {Function} [props.onClick]
 * @param {'button'|'submit'|'reset'} [props.type='button']
 * @param {string} [props.className='']
 * @param {React.ReactNode} props.children
 */
export default function Button({
  variant = 'primary',
  size = 'md',
  icon: Icon,
  loading = false,
  disabled = false,
  onClick,
  type = 'button',
  className = '',
  children,
  ...rest
}) {
  const isDisabled = disabled || loading;

  const renderIcon = () => {
    if (loading) {
      return <Loader2 className="ui-btn__spinner" size={size === 'sm' ? 14 : 16} />;
    }
    if (!Icon) return null;
    if (React.isValidElement(Icon)) {
      return <span className="ui-btn__icon">{Icon}</span>;
    }
    // Component type
    return (
      <span className="ui-btn__icon">
        <Icon size={size === 'sm' ? 14 : 16} />
      </span>
    );
  };

  return (
    <button
      type={type}
      className={`ui-btn ui-btn--${variant} ui-btn--${size} ${loading ? 'ui-btn--loading' : ''} ${className}`.trim()}
      onClick={onClick}
      disabled={isDisabled}
      {...rest}
    >
      {renderIcon()}
      {children && <span className="ui-btn__content">{children}</span>}
    </button>
  );
}
