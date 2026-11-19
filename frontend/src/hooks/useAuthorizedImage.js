import { useEffect, useState } from 'react';
import { apiFetchBlob } from '../api/apiClient';

function isAuthorizedEndpoint(src) {
  if (typeof src !== 'string') return false;
  return src.includes('/api/media/') || src.includes('/api/cameras/recordings/thumbnail');
}

/**
 * Ảnh cần JWT: thẻ <img> không gửi được header Authorization nên ảnh /api/media/... hoặc
 * /api/cameras/recordings/thumbnail được tải bằng apiFetchBlob rồi đổi thành object URL (thu hồi khi đổi src hoặc unmount).
 * `data:` và URL khác giữ nguyên.
 *
 * @param {string|null|undefined} src - URL ảnh (đã qua formatImageUrl nếu là URL MinIO)
 * @returns {{ url: string|null, loading: boolean, error: Error|null }}
 */
export function useAuthorizedImage(src) {
  const isAuthorized = isAuthorizedEndpoint(src);
  // Gắn kết quả với src đã tải để bỏ qua kết quả cũ khi src đổi
  const [result, setResult] = useState({ src: null, url: null, error: null });

  useEffect(() => {
    if (!isAuthorized) return undefined;

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
  }, [src, isAuthorized]);

  if (!isAuthorized) {
    return { url: src || null, loading: false, error: null };
  }
  if (result.src !== src) {
    return { url: null, loading: true, error: null };
  }
  return { url: result.url, loading: false, error: result.error };
}
