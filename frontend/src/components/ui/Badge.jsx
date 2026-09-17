import React from 'react';
import './Badge.css';

/**
 * Standard Badge component
 * @param {Object} props
 * @param {'public'|'semiPrivate'|'private'|'success'|'warning'|'danger'|'neutral'|'brand'} [props.variant='neutral']
 * @param {React.ComponentType|React.ReactNode} [props.icon]
 * @param {boolean} [props.dot=false]
 * @param {string} [props.className='']
 * @param {React.ReactNode} props.children
 */
export default function Badge({
  variant = 'neutral',
  icon: Icon,
  dot = false,
  className = '',
  children,
  ...rest
}) {
  const renderIcon = () => {
    if (!Icon) return null;
    if (React.isValidElement(Icon)) {
      return <span className="ui-badge__icon">{Icon}</span>;
    }
    return (
      <span className="ui-badge__icon">
        <Icon size={12} />
      </span>
    );
  };

  return (
    <span className={`ui-badge ui-badge--${variant} ${className}`.trim()} {...rest}>
      {dot && <span className="ui-badge__dot" />}
      {renderIcon()}
      {children && <span className="ui-badge__label">{children}</span>}
    </span>
  );
}
