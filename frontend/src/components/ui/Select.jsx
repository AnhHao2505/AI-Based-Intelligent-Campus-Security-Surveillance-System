import React, { useId } from 'react';
import { ChevronDown, AlertCircle } from 'lucide-react';
import './Select.css';

/**
 * Standard Select component
 * @param {Object} props
 * @param {string} [props.label]
 * @param {string|number} [props.value]
 * @param {Function} [props.onChange]
 * @param {Array<{value: string|number, label: string, disabled?: boolean}>} [props.options=[]]
 * @param {string} [props.placeholder]
 * @param {string} [props.error]
 * @param {string} [props.hint]
 * @param {boolean} [props.required=false]
 * @param {boolean} [props.disabled=false]
 * @param {string} [props.id]
 * @param {string} [props.name]
 * @param {string} [props.className='']
 */
export default function Select({
  label,
  value,
  onChange,
  options = [],
  placeholder,
  error,
  hint,
  required = false,
  disabled = false,
  id,
  name,
  className = '',
  ...rest
}) {
  const generatedId = useId();
  const selectId = id || generatedId;

  return (
    <div className={`ui-select-group ${error ? 'ui-select-group--error' : ''} ${disabled ? 'ui-select-group--disabled' : ''} ${className}`.trim()}>
      {label && (
        <label htmlFor={selectId} className="ui-select__label">
          <span>{label}</span>
          {required && <span className="ui-select__required">*</span>}
        </label>
      )}

      <div className="ui-select__container">
        <select
          id={selectId}
          name={name}
          value={value}
          onChange={onChange}
          disabled={disabled}
          required={required}
          className="ui-select__field"
          {...rest}
        >
          {placeholder && (
            <option value="" disabled>
              {placeholder}
            </option>
          )}
          {options.map((opt) => (
            <option key={opt.value} value={opt.value} disabled={opt.disabled}>
              {opt.label}
            </option>
          ))}
        </select>
        <span className="ui-select__arrow" pointer-events="none">
          <ChevronDown size={16} />
        </span>
      </div>

      {error ? (
        <div className="ui-select__error" role="alert">
          <AlertCircle size={13} className="ui-select__error-icon" />
          <span>{error}</span>
        </div>
      ) : hint ? (
        <div className="ui-select__hint">{hint}</div>
      ) : null}
    </div>
  );
}
