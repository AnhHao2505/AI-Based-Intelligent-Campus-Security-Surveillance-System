import { useRef } from 'react';
import useDialogA11y from '../../hooks/useDialogA11y';

/**
 * Khung popup cho các trang tự dựng markup modal (giữ class/CSS riêng của trang).
 * Chỉ render khi popup đang mở; thêm role="dialog", Esc đóng, focus ô nhập đầu tiên, giữ Tab trong popup.
 * Popup mới nên dùng Modal; component này để chuyển dần các popup cũ mà không đổi giao diện.
 *
 * @param {Object} props
 * @param {Function} props.onClose gọi khi bấm Esc (tự kiểm trạng thái đang gửi nếu cần)
 * @param {string} [props.labelledBy] id của tiêu đề popup
 * @param {'dialog'|'field'} [props.initialFocus='field']
 * @param {string} [props.className]
 * @param {React.ReactNode} props.children
 */
export default function DialogPanel({ onClose, labelledBy, initialFocus = 'field', className, children, ...rest }) {
  const dialogRef = useRef(null);
  useDialogA11y(dialogRef, true, onClose, { initialFocus });

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby={labelledBy}
      tabIndex={-1}
      className={className}
      {...rest}
    >
      {children}
    </div>
  );
}
