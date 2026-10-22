import { useEffect, useRef } from 'react';

// Các popup đang mở theo thứ tự mở: Esc / bẫy Tab chỉ áp cho popup trên cùng (popup lồng popup không đóng cả hai).
const openDialogStack = [];

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

const FIELD_SELECTOR = [
  'input:not([disabled]):not([type="hidden"]):not([type="file"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
].join(',');

const isShown = (el) => el.offsetParent !== null || el === document.activeElement;

/**
 * Hành vi chung của popup (dùng cho Modal và các popup tự dựng):
 * khoá cuộn trang, Esc đóng, giữ Tab trong popup, trả focus về chỗ cũ khi đóng.
 *
 * @param {React.RefObject<HTMLElement>} dialogRef phần tử role="dialog" (cần tabIndex={-1})
 * @param {boolean} isOpen
 * @param {Function} onClose
 * @param {{ initialFocus?: 'dialog'|'field' }} [options]
 *   'dialog' (mặc định): focus vào khung popup; 'field': focus ô nhập đầu tiên, không có ô thì focus khung.
 *   Nội dung đã tự focus (autoFocus) thì giữ nguyên.
 */
export default function useDialogA11y(dialogRef, isOpen, onClose, { initialFocus = 'dialog' } = {}) {
  // onClose thường là hàm viết tại chỗ (đổi mỗi lần render) -> giữ bằng ref để effect chỉ chạy khi mở / đóng
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!isOpen) return undefined;

    const token = {};
    openDialogStack.push(token);
    const isTopmost = () => openDialogStack[openDialogStack.length - 1] === token;
    const previouslyFocused = document.activeElement;

    const handleKeyDown = (e) => {
      if (!isTopmost() || e.defaultPrevented) return;
      if (e.key === 'Escape' || e.key === 'Esc') {
        onCloseRef.current?.();
        return;
      }
      if (e.key === 'Tab' && dialogRef.current) {
        const focusable = Array.from(dialogRef.current.querySelectorAll(FOCUSABLE_SELECTOR)).filter(isShown);
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

    const dialog = dialogRef.current;
    if (dialog && !dialog.contains(document.activeElement)) {
      const field = initialFocus === 'field'
        ? Array.from(dialog.querySelectorAll(FIELD_SELECTOR)).find(isShown)
        : null;
      (field || dialog).focus({ preventScroll: true });
    }

    return () => {
      document.body.style.overflow = originalOverflow;
      window.removeEventListener('keydown', handleKeyDown);
      const index = openDialogStack.indexOf(token);
      if (index !== -1) openDialogStack.splice(index, 1);
      if (previouslyFocused && typeof previouslyFocused.focus === 'function' && document.contains(previouslyFocused)) {
        previouslyFocused.focus({ preventScroll: true });
      }
    };
    // dialogRef là ref (không đổi), initialFocus là hằng ở nơi gọi -> thực chất chỉ chạy lại khi mở / đóng
  }, [isOpen, dialogRef, initialFocus]);
}
