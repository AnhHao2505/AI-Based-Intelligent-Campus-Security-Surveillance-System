import React, { useId } from 'react';
import { AlertCircle } from 'lucide-react';
import './Input.css';

/**
 * Standard Input component
 * @param {Object} props
 * @param {string} [props.label]
 * @param {string|number} [props.value]
 * @param {Function} [props.onChange]
 * @param {string} [props.placeholder]
 * @param {string} [props.error]
 * @param {string} [props.hint]
 * @param {boolean} [props.required=false]
 * @param {boolean} [props.disabled=false]
 * @param {string} [props.type='text']
 * @param {React.ComponentType|React.ReactNode} [props.icon]
 * @param {string} [props.id]
 * @param {string} [props.name]
 * @param {string} [props.className='']
 */
export default function Input({
  label,
  value,
  onChange,
  placeholder,
  error,
  hint,
  required = false,
  disabled = false,
  type = 'text',
  icon: Icon,
  id,
  name,
  className = '',
  ...rest
}) {
  const generatedId = useId();
  const inputId = id || generatedId;

  const renderIcon = () => {
    if (!Icon) return null;
    if (React.isValidElement(Icon)) {
      return <span className="ui-input__icon-wrapper">{Icon}</span>;
    }
    return (
      <span className="ui-input__icon-wrapper">
        <Icon size={16} />
      </span>
    );
  };

  return (
    <div className={`ui-input-group ${error ? 'ui-input-group--error' : ''} ${disabled ? 'ui-input-group--disabled' : ''} ${className}`.trim()}>
      {label && (
        <label htmlFor={inputId} className="ui-input__label">
          <span>{label}</span>
          {required && <span className="ui-input__required">*</span>}
        </label>
      )}

      <div className={`ui-input__container ${Icon ? 'ui-input__container--has-icon' : ''}`}>
        {renderIcon()}
        <input
          id={inputId}
          name={name}
          type={type}
          value={value}
          onChange={onChange}
          placeholder={placeholder}
          disabled={disabled}
          required={required}
          className="ui-input__field"
          {...rest}
        />
      </div>

      {error ? (
        <div className="ui-input__error" role="alert">
          <AlertCircle size={13} className="ui-input__error-icon" />
          <span>{error}</span>
        </div>
      ) : hint ? (
        <div className="ui-input__hint">{hint}</div>
      ) : null}
    </div>
  );
}
