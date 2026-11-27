import { useRef, useCallback } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import { ROLES } from '../../constants/roles';
import { useStompSubscription } from '../../hooks/useStompSubscription';
import Sidebar from './Sidebar';
import GuardChatWidget from '../chat/GuardChatWidget';
import '../../styles/AppLayout.css';

// BR-IE-04: sự cố quá hạn chưa bảo vệ nào tiếp nhận -> báo real-time cho FM
const ESCALATION_TOPIC = '/topic/escalations/facility-managers';
// V93 (BR-DSP-09): mức sự cố có notify_fm_immediately -> báo FM ngay khi sự cố được tạo (kênh riêng, khác leo thang)
const CRITICAL_NOTICE_TOPIC = '/topic/incidents/critical-notices';
// V151: cảnh báo mất / khôi phục kết nối camera livestream (FM, ADMIN, GUARD)
const CAMERA_STATUS_TOPIC = '/topic/cameras/status-alerts';

const SEVERITY_LABELS = { CRITICAL: 'Nghiêm trọng', HIGH: 'Cao', MEDIUM: 'Trung bình' };

function handleCameraStatusAlert(payload) {
  if (!payload) return;
  window.dispatchEvent(new CustomEvent('camera-health-alert', { detail: payload }));

  const camLabel = payload.cameraCode && payload.cameraCode !== payload.cameraName
    ? `${payload.cameraName || payload.cameraCode} (${payload.cameraCode})`
    : (payload.cameraName || payload.cameraCode || 'Camera');
  const locationParts = [
    payload.areaName ? `Khu vực: ${payload.areaName}` : null,
    payload.building ? `Tòa: ${payload.building}` : null,
    payload.floor ? `Tầng: ${payload.floor}` : null,
  ].filter(Boolean);
  const description = [camLabel, ...locationParts].join(' · ');

  if (payload.event === 'CAMERA_OFFLINE') {
    toast.error('Camera mất kết nối đột ngột', {
      id: `camera-offline-${payload.cameraId}`,
      description,
      duration: 15000,
    });
    window.dispatchEvent(new CustomEvent('notification-updated'));
  } else if (payload.event === 'CAMERA_ONLINE') {
    toast.dismiss(`camera-offline-${payload.cameraId}`);
    toast.success('Camera đã kết nối trở lại', {
      id: `camera-online-${payload.cameraId}`,
      description,
      duration: 5000,
    });
  }
}

function showCriticalNoticeToast(payload) {
  const severity = SEVERITY_LABELS[payload?.severity] || payload?.severity || '—';
  toast.error('Sự cố an ninh mức nghiêm trọng vừa xảy ra', {
    id: `incident-critical-${payload?.incidentId}`,
    description: `Mức: ${severity} · Khu vực: ${payload?.areaName || '—'} · Tòa: ${payload?.building || '—'}. `
      + 'Bảo vệ đang được điều phối tiếp nhận.',
    duration: 20000,
  });
}

function showEscalationToast(payload) {
  const minutes = Number(payload?.minutesElapsed);
  const waited = Number.isFinite(minutes) && minutes >= 1 ? `${minutes} phút` : 'dưới 1 phút';
  toast.error('Sự cố an ninh chưa được tiếp nhận', {
    id: `incident-escalated-${payload?.incidentId}`,
    description: `Khu vực: ${payload?.areaName || '—'} · Tòa: ${payload?.building || '—'} · Đã chờ ${waited}. `
      + 'Vui lòng liên hệ trực tiếp bảo vệ qua bộ đàm hoặc điện thoại.',
    duration: 20000,
  });
  // Badge chuông cập nhật ngay, không chờ vòng poll 60 giây
  window.dispatchEvent(new CustomEvent('notification-updated'));
}

export default function AppLayout({ children }) {
  const { user, logout } = useAuth();
  const location = useLocation();
  const layoutRef = useRef(null);
  const userRole = user?.role || user?.role_type;
  const isFmOrAdmin = userRole === ROLES.FACILITY_MANAGER || userRole === ROLES.ADMIN;
  const canReceiveCameraAlerts = isFmOrAdmin || userRole === ROLES.GUARD;
  const canUseGuardChat = userRole === ROLES.GUARD || userRole === ROLES.FACILITY_MANAGER;

  useStompSubscription(isFmOrAdmin ? ESCALATION_TOPIC : null, showEscalationToast);
  useStompSubscription(isFmOrAdmin ? CRITICAL_NOTICE_TOPIC : null, showCriticalNoticeToast);
  useStompSubscription(canReceiveCameraAlerts ? CAMERA_STATUS_TOPIC : null, handleCameraStatusAlert);

  const handleHeadbarStateChange = useCallback((visible, height) => {
    layoutRef.current?.style.setProperty('--headbar-offset', visible && height ? `${height}px` : '0px');
  }, []);

  return (
    <div className="app-layout" ref={layoutRef}>
      <Sidebar user={user} onLogout={logout} onLayoutChange={handleHeadbarStateChange} />
      <main className="app-layout__content">
        <div key={location.pathname} className="canva-page-transition">
          {children ? children : <Outlet />}
        </div>
      </main>
      {canUseGuardChat && <GuardChatWidget />}
    </div>
  );
}
