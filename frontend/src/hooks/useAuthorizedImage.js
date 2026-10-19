import { useEffect, useState } from 'react';
import { apiFetchBlob } from '../api/apiClient';

const MEDIA_PREFIX = '/api/media/';

/**
 * Ảnh cần JWT: thẻ <img> không gửi được header Authorization nên ảnh /api/media/... được tải bằng
 * apiFetchBlob rồi đổi thành object URL (thu hồi khi đổi src hoặc unmount).
 * `data:` và URL khác giữ nguyên.
 *
 * @param {string|null|undefined} src - URL ảnh (đã qua formatImageUrl nếu là URL MinIO)
 * @returns {{ url: string|null, loading: boolean, error: Error|null }}
 */
export function useAuthorizedImage(src) {
  const isMedia = typeof src === 'string' && src.startsWith(MEDIA_PREFIX);
  // Gắn kết quả với src đã tải để bỏ qua kết quả cũ khi src đổi
  const [result, setResult] = useState({ src: null, url: null, error: null });

  useEffect(() => {
    if (!isMedia) return undefined;

    let cancelled = false;
    let objectUrl = null;
    apiFetchBlob(src)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setResult({ src, url: objectUrl, error: null });
      })
      .catch((err) => {
        if (!cancelled) setResult({ src, url: null, error: err });
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [src, isMedia]);

  if (!isMedia) {
    return { url: src || null, loading: false, error: null };
  }
  if (result.src !== src) {
    return { url: null, loading: true, error: null };
  }
  return { url: result.url, loading: false, error: result.error };
}
