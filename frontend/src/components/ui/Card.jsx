import React from 'react';
import './Card.css';

/**
 * Standard Card component
 * @param {Object} props
 * @param {'sm'|'md'} [props.padding='md']
 * @param {string} [props.accentColor] - Left 3px accent strip color (e.g. '#22c55e', 'var(--brand-blue)')
 * @param {string} [props.className='']
 * @param {Function} [props.onClick]
 * @param {React.ReactNode} [props.header]
 * @param {React.ReactNode} [props.footer]
 * @param {React.ReactNode} props.children
 */
export default function Card({
  padding = 'md',
  accentColor,
  className = '',
  onClick,
  header,
  footer,
  children,
  style,
  ...rest
}) {
  const hasAccent = Boolean(accentColor);
  const cardStyle = {
    ...style,
    ...(hasAccent ? { borderLeftColor: accentColor } : {})
  };

  return (
    <article
      className={`ui-card ui-card--pad-${padding} ${hasAccent ? 'ui-card--has-accent' : ''} ${onClick ? 'ui-card--clickable' : ''} ${className}`.trim()}
      style={cardStyle}
      onClick={onClick}
      {...rest}
    >
      {header && <div className="ui-card__header">{header}</div>}
      <div className="ui-card__body">{children}</div>
      {footer && <div className="ui-card__footer">{footer}</div>}
    </article>
  );
}
