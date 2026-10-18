import { Outlet } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import { useUiStore } from '../../store/useUiStore';
import { ROLES } from '../../constants/roles';
import { useStompSubscription } from '../../hooks/useStompSubscription';
import Sidebar from './Sidebar';
import '../../styles/AppLayout.css';

// BR-IE-04: sự cố quá hạn chưa bảo vệ nào tiếp nhận -> báo real-time cho FM
const ESCALATION_TOPIC = '/topic/escalations/facility-managers';

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
  const sidebarCollapsed = useUiStore((state) => state.sidebarCollapsed);
  const isFacilityManager = (user?.role || user?.role_type) === ROLES.FACILITY_MANAGER;

  useStompSubscription(isFacilityManager ? ESCALATION_TOPIC : null, showEscalationToast);

  return (
    <div className="app-layout">
      <Sidebar user={user} onLogout={logout} />
      <div className={`app-layout__content ${sidebarCollapsed ? 'app-layout__content--collapsed' : ''}`}>
        {children ? children : <Outlet />}
      </div>
    </div>
  );
}
