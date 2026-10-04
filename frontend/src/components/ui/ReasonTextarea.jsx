import React, { forwardRef, useId } from 'react';
import { AlertCircle } from 'lucide-react';
import './ReasonTextarea.css';

/**
 * Reusable ReasonTextarea component with standardized inline validation,
 * character count, red error indicators, and ref focusing support (UX-03).
 */
const ReasonTextarea = forwardRef(function ReasonTextarea(
  {
    id,
    label = 'Lý do',
    value = '',
    onChange,
    placeholder = 'Nhập lý do (tối thiểu 10 ký tự, tối đa 500 ký tự)...',
    error,
    hint,
    required = true,
    disabled = false,
    min = 10,
    max = 500,
    rows = 3,
    className = '',
    style,
    ...rest
  },
  ref
) {
  const generatedId = useId();
  const textareaId = id || generatedId;

  const currentLength = (value || '').length;
  const trimmedLength = (value || '').trim().length;

  const isInvalid = Boolean(
    error ||
      (required && trimmedLength > 0 && (trimmedLength < min || trimmedLength > max)) ||
      (required && error) ||
      (!required && trimmedLength > 0 && (trimmedLength < min || trimmedLength > max))
  );

  const displayErrorMessage =
    typeof error === 'string' && error.trim().length > 0
      ? error
      : error
      ? `Lý do phải từ ${min} đến ${max} ký tự (hiện có ${trimmedLength}).`
      : null;

  return (
    <div
      className={`reason-field ${isInvalid || error ? 'reason-field--error' : ''} ${className}`.trim()}
      style={style}
    >
      {label && (
        <div className="reason-field__label-row">
          <label htmlFor={textareaId} className="reason-field__label">
            <span>{label}</span>
            {required && <span className="reason-field__required">*</span>}
          </label>
        </div>
      )}

      <textarea
        ref={ref}
        id={textareaId}
        rows={rows}
        maxLength={max}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        disabled={disabled}
        required={required}
        className="reason-field__textarea"
        {...rest}
      />

      <div className="reason-field__meta-row">
        {displayErrorMessage ? (
          <div className="reason-field__error" role="alert">
            <AlertCircle size={13} className="reason-field__error-icon" />
            <span>{displayErrorMessage}</span>
          </div>
        ) : hint ? (
          <div className="reason-field__hint">{hint}</div>
        ) : (
          <div className="reason-field__hint">
            Tối thiểu {min} ký tự, tối đa {max} ký tự
          </div>
        )}

        <div
          className={`reason-field__counter ${
            (isInvalid || (required && trimmedLength < min)) && currentLength > 0
              ? 'reason-field__counter--error'
              : ''
          }`}
        >
          {currentLength}/{max} ký tự {min > 0 ? `(tối thiểu ${min})` : ''}
        </div>
      </div>
    </div>
  );
});

export default ReasonTextarea;
