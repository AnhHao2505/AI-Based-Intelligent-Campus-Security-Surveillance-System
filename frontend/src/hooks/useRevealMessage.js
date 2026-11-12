import { useEffect } from 'react';

/**
 * Banner lỗi / thông báo đặt ở đầu form dài: khi thông báo xuất hiện (hoặc đổi nội dung),
 * cuộn banner vào giữa tầm nhìn và đặt focus để trình đọc màn hình đọc ngay.
 * Nút gửi nằm cuối form nên nếu không cuộn, người dùng không thấy phản hồi (LT-01, đợt kiểm 12/11).
 *
 * Gửi lại cùng một lỗi vẫn cuộn lại được vì các form đều xoá lỗi (null / "") trước khi gửi.
 *
 * @param {React.RefObject<HTMLElement>} ref phần tử banner (cần tabIndex={-1} để nhận focus)
 * @param {string|null|undefined} message nội dung đang hiện; rỗng thì không làm gì
 */
export default function useRevealMessage(ref, message) {
  useEffect(() => {
    if (!message) return;
    const el = ref.current;
    if (!el) return;
    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    el.scrollIntoView?.({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
    el.focus?.({ preventScroll: true });
  }, [ref, message]);
}
