import { useAuth } from "../context/AuthContext";
import { ROLE_LABELS, ROLES } from "../constants/roles";
import { Link } from "react-router-dom";
import { 
  ShieldCheck, 
  Layers, 
  Video, 
  UserRound, 
  ArrowRight,
  Shield,
  Activity,
  Server,
  Bell
} from "lucide-react";
import "./DashboardPage.css";

export default function DashboardPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === ROLES.ADMIN;

  return (
    <div className="dashboard">
      <div className="dashboard-bg">
        <div className="dashboard-bg-orb dashboard-bg-orb--1" />
        <div className="dashboard-bg-orb dashboard-bg-orb--2" />
      </div>

      <main className="dashboard-main">
        <div className="dashboard-welcome">
          <div className="dashboard-welcome__badge">
            <span className="live-dot"></span>
            <span>Hệ Thống Giám Sát Hoạt Động (Real-time Live)</span>
          </div>
          <h1>Xin chào, {user?.fullName || "Quản trị viên"} 👋</h1>
          <p>Hệ thống Giám sát An ninh Thông minh Campus FPTU Tân Uyên (FA26SE040)</p>
        </div>

        {/* Quick Stats Overview */}
        <div className="dashboard-overview-stats">
          <div className="dash-stat-card">
            <div className="dash-stat-icon dash-stat-icon--purple">
              <Layers size={22} />
            </div>
            <div className="dash-stat-info">
              <span className="dash-stat-label">Tổng Khu Vực (Zones)</span>
              <strong className="dash-stat-value">12</strong>
              <span className="dash-stat-sub text-emerald-400">2 Khu vực Tối Mật (L3)</span>
            </div>
          </div>

          <div className="dash-stat-card">
            <div className="dash-stat-icon dash-stat-icon--blue">
              <Video size={22} />
            </div>
            <div className="dash-stat-info">
              <span className="dash-stat-label">Hệ Thống Camera</span>
              <strong className="dash-stat-value">8 / 8</strong>
              <span className="dash-stat-sub text-emerald-400">100% Trực tuyến</span>
            </div>
          </div>

          <div className="dash-stat-card">
            <div className="dash-stat-icon dash-stat-icon--cyan">
              <UserRound size={22} />
            </div>
            <div className="dash-stat-info">
              <span className="dash-stat-label">Hồ Sơ Khuôn Mặt</span>
              <strong className="dash-stat-value">14</strong>
              <span className="dash-stat-sub text-cyan-400">Đã đồng bộ Vector AI</span>
            </div>
          </div>

          <div className="dash-stat-card">
            <div className="dash-stat-icon dash-stat-icon--emerald">
              <ShieldCheck size={22} />
            </div>
            <div className="dash-stat-info">
              <span className="dash-stat-label">Trạng Thái An Ninh</span>
              <strong className="dash-stat-value text-emerald-400">Bình Thường</strong>
              <span className="dash-stat-sub text-slate-400">Không có vi phạm</span>
            </div>
          </div>
        </div>

        {/* Quick Action Modules (for Admin) */}
        {isAdmin && (
          <div className="dashboard-section">
            <h2 className="dashboard-section-title">Các Phân Hệ Quản Trị Hệ Thống</h2>
            <div className="dashboard-modules-grid">
              <Link to="/admin/zones" className="dash-module-card">
                <div className="dash-module-icon dash-module-icon--purple">
                  <Layers size={28} />
                </div>
                <div className="dash-module-content">
                  <h3>Quản Lý Khu Vực</h3>
                  <p>Bản đồ mặt bằng trực quan, cấu hình chính sách leo thang và phân quyền Whitelist cho từng khu vực.</p>
                  <span className="dash-module-link">
                    Mở Quản lý Khu vực <ArrowRight size={16} />
                  </span>
                </div>
              </Link>

              <Link to="/cameras" className="dash-module-card">
                <div className="dash-module-icon dash-module-icon--blue">
                  <Video size={28} />
                </div>
                <div className="dash-module-content">
                  <h3>Quản Lý Camera</h3>
                  <p>Quản trị luồng RTSP / WebRTC, thiết lập vùng AI phát hiện xâm nhập và cảnh báo an ninh thời gian thực.</p>
                  <span className="dash-module-link">
                    Mở Quản lý Camera <ArrowRight size={16} />
                  </span>
                </div>
              </Link>

              <Link to="/admin/faces" className="dash-module-card">
                <div className="dash-module-icon dash-module-icon--cyan">
                  <UserRound size={28} />
                </div>
                <div className="dash-module-content">
                  <h3>Quản Lý Khuôn Mặt</h3>
                  <p>Nạp hồ sơ khuôn mặt đơn lẻ hoặc hàng loạt (.ZIP), tự động trích xuất vector đặc trưng InsightFace.</p>
                  <span className="dash-module-link">
                    Mở Quản lý Khuôn mặt <ArrowRight size={16} />
                  </span>
                </div>
              </Link>
            </div>
          </div>
        )}

        {/* User Info & System Telemetry Cards */}
        <div className="dashboard-grid mt-6">
          <div className="dashboard-card dashboard-card--profile">
            <h3>Thông tin cá nhân & Phiên đăng nhập</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Họ tên</span>
                <span className="dashboard-card__value">{user?.fullName || "—"}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Email</span>
                <span className="dashboard-card__value">{user?.email || "—"}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Mã định danh</span>
                <span className="dashboard-card__value">{user?.userCode || "—"}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Vai trò hệ thống</span>
                <span className="dashboard-card__value dashboard-card__role-badge">
                  {ROLE_LABELS[user?.role] || user?.role || "USER"}
                </span>
              </div>
            </div>
          </div>

          <div className="dashboard-card dashboard-card--system">
            <h3>Thông tin Nền Tảng An Ninh AI</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Backend Engine</span>
                <span className="dashboard-card__value">Spring Boot 3.2.5 (Java 21)</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Mô hình AI Nhận diện</span>
                <span className="dashboard-card__value">InsightFace + YOLOv8</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Cơ sở dữ liệu Vector</span>
                <span className="dashboard-card__value">PostgreSQL 16 + pgvector</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Trạng thái kết nối</span>
                <span className="dashboard-card__value dashboard-card__status dashboard-card__status--ok">
                  ● Đang hoạt động ổn định
                </span>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
