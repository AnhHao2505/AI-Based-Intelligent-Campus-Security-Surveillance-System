import { format } from 'date-fns';

/** Nhãn + màu (variant của Badge) cho trạng thái lượt khách. */
export const GUEST_VISIT_STATUS = {
  PENDING: { label: 'Chờ duyệt', variant: 'warning' },
  APPROVED: { label: 'Đã duyệt', variant: 'success' },
  REJECTED: { label: 'Bị từ chối', variant: 'danger' },
  CANCELLED: { label: 'Đã huỷ', variant: 'neutral' },
  REVOKED: { label: 'Bị thu hồi', variant: 'danger' },
  EXPIRED: { label: 'Hết hạn (chưa duyệt)', variant: 'neutral' },
  COMPLETED: { label: 'Đã kết thúc', variant: 'brand' },
};

export function getGuestVisitStatus(status) {
  return GUEST_VISIT_STATUS[status] || { label: status || '—', variant: 'neutral' };
}

/** Trạng thái dữ liệu sinh trắc của từng khách. */
export const GUEST_BIOMETRIC_STATUS = {
  NO_PHOTO: { label: 'Chưa có ảnh', variant: 'neutral' },
  PHOTO_READY: { label: 'Đã có ảnh', variant: 'success' },
  PHOTO_ONLY: { label: 'Có ảnh, chưa nhận diện được', variant: 'warning' },
  DELETED: { label: 'Đã xoá dữ liệu khuôn mặt', variant: 'neutral' },
};

export function getGuestBiometricStatus(status) {
  return GUEST_BIOMETRIC_STATUS[status] || { label: status || '—', variant: 'neutral' };
}

/** Loại khu vực nhận khách (BR-GV-04). */
export const GUEST_ALLOWED_AREA_LEVELS = ['INTERNAL_CONFIDENTIAL', 'CONFIDENTIAL_CONTACT_REQUIRED'];

export function formatDateTime(value) {
  if (!value) return '—';
  try {
    return format(new Date(value), 'HH:mm dd/MM/yyyy');
  } catch {
    return String(value);
  }
}

/** Host còn huỷ được: PENDING / APPROVED và chưa tới giờ kết thúc (BR-GV-09). */
export function canHostCancel(visit, now = new Date()) {
  if (!visit) return false;
  if (visit.status !== 'PENDING' && visit.status !== 'APPROVED') return false;
  return new Date(visit.endTime) > now;
}

/**
 * BR-GV-38: lượt ĐÃ DUYỆT còn khách chưa PHOTO_READY (chưa đăng ký khuôn mặt tại quầy -> camera chưa nhận diện được).
 * Trả { missing, total } để hiện nhãn "Chưa có ảnh (x/y khách)"; null nếu không cần nhãn.
 */
export function getMissingPhotoInfo(visit) {
  if (!visit || visit.status !== 'APPROVED') return null;
  const guests = visit.guests || [];
  const missing = guests.filter((g) => g.biometricStatus !== 'PHOTO_READY').length;
  return missing > 0 ? { missing, total: guests.length } : null;
}
