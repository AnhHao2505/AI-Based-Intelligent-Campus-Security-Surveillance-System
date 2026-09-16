import { forwardRef } from 'react';

const Button = forwardRef(function Button({ variant = 'primary', className = '', ...props }, ref) {
  return <button ref={ref} className={`ui-button ui-button--${variant} ${className}`} {...props} />;
});

export default Button;