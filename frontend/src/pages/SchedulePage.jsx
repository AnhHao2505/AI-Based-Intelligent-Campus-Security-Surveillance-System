import { useAuth } from '../context/AuthContext';
import { Calendar, LogOut, Shield, User, Clock } from 'lucide-react';
import './SchedulePage.css';

export default function SchedulePage() {
  const { user, logout } = useAuth();

  const getRoleBadgeClass = (role) => {
    switch (role) {
      case 'INTERNAL_GUARD':
      case 'OUTSOURCED_GUARD':
        return 'badge-guard';
      case 'FACILITY_MANAGER':
        return 'badge-fm';
      case 'ADMIN':
        return 'badge-admin';
      default:
        return 'badge-guard';
    }
  };

  return (
    <div className="schedule-wrapper">
      {/* Top Navigation Bar */}
      <header className="schedule-header">
        <div className="schedule-logo">
          <Shield size={24} color="#2563eb" />
          <span>Campus Security System</span>
        </div>

        <div className="schedule-user-info">
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontWeight: 600, fontSize: '14px' }}>{user?.full_name}</div>
            <div style={{ fontSize: '12px', color: '#64748b' }}>Mã: {user?.user_code}</div>
          </div>
          <span className={`user-badge ${getRoleBadgeClass(user?.role_type)}`}>
            {user?.role_type}
          </span>
          <button className="logout-btn" onClick={logout}>
            <LogOut size={16} />
            <span>Đăng xuất</span>
          </button>
        </div>
      </header>

      {/* Main Content Area Placeholder */}
      <main style={{ padding: '0 24px' }}>
        <div className="schedule-container">
          <div className="schedule-icon-circle">
            <Calendar size={40} />
          </div>

          <h2 className="schedule-title">Lịch Trực Bảo Vệ (Schedule Page)</h2>
          <p className="schedule-description">
            Trang Lịch Trực đang trong trạng thái <strong>Coming Soon (Đang chờ thiết kế Figma chính thức)</strong>. 
            Hệ thống Auth và RBAC đã sẵn sàng điều hướng theo 3 vai trò.
          </p>

          <div className="rbac-status-card">
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
              <User size={18} color="#2563eb" />
              <span>Người dùng hiện tại: <strong>{user?.full_name}</strong></span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
              <Clock size={18} color="#2563eb" />
              <span>Vai trò (RBAC): <strong>{user?.role_type}</strong></span>
            </div>
            <div style={{ fontSize: '13px', color: '#475569', marginTop: '12px', paddingTop: '12px', borderTop: '1px dashed #cbd5e1' }}>
              {user?.role_type === 'INTERNAL_GUARD' && '• Giao diện Guard: Hiển thị danh sách ca trực cá nhân (/duty-shifts/me).'}
              {user?.role_type === 'FACILITY_MANAGER' && '• Giao diện Facility Manager: Hiển thị bảng ma trận lịch trực toàn bộ khu vực.'}
              {user?.role_type === 'ADMIN' && '• Giao diện Admin: Hiển thị bảng ma trận + Nút "Tạo ca trực mới" & Chỉnh sửa.'}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
