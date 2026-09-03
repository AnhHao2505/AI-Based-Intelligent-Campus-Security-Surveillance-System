import { useState, useEffect } from 'react';
import {
  MapPin,
  Camera,
  Users,
  AlertTriangle,
  Activity,
  Bell,
  Calendar,
  Clock,
  HardDrive,
  Cpu,
  Layers,
  Map as MapIcon,
  Shield,
  Info,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { getAreas } from '../services/areaService';
import '../styles/DashboardPage.css';

export default function DashboardPage() {
  const { user } = useAuth();
  const [areaCount, setAreaCount] = useState(null);
  const [areaLoading, setAreaLoading] = useState(false);

  // Safe RBAC check: only request Area API if role has permission
  useEffect(() => {
    let isMounted = true;
    const isAreaAuthorized =
      user?.role === 'ADMIN' || user?.role === 'FACILITY_MANAGER';

    if (isAreaAuthorized) {
      setAreaLoading(true);
      getAreas({ page: 0, size: 1 })
        .then((res) => {
          if (isMounted && res && typeof res.totalElements === 'number') {
            setAreaCount(res.totalElements);
          }
        })
        .catch(() => {
          if (isMounted) setAreaCount(null);
        })
        .finally(() => {
          if (isMounted) setAreaLoading(false);
        });
    }

    return () => {
      isMounted = false;
    };
  }, [user]);

  // Current formatted date string
  const currentDateStr = new Intl.DateTimeFormat('vi-VN', {
    weekday: 'long',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());

  return (
    <div className="dashboard-page">
      <div className="dashboard-container">
        {/* Header Section */}
        <header className="dashboard-header">
          <div className="dashboard-header__left">
            <div className="dashboard-header__badge">
              <span className="dashboard-header__badge-dot" />
              CAMPUS SURVEILLANCE
            </div>
            <h1 className="dashboard-header__title">Dashboard</h1>
            <p className="dashboard-header__subtitle">
              Tổng quan hệ thống an ninh Campus
            </p>
          </div>
          <div className="dashboard-header__right">
            <div className="dashboard-date-badge">
              <Calendar size={14} />
              <span>{currentDateStr}</span>
            </div>
          </div>
        </header>

        {/* 4 KPI Cards */}
        <section className="dashboard-kpis">
          {/* 1. Total Areas */}
          <article className="kpi-card">
            <div className="kpi-card__top">
              <span className="kpi-card__icon kpi-card__icon--blue">
                <MapPin size={20} />
              </span>
              <span className="kpi-card__badge">Khu vực</span>
            </div>
            <div className="kpi-card__body">
              <span className="kpi-card__label">Total Areas</span>
              <span className="kpi-card__value">
                {areaLoading ? '...' : areaCount !== null ? areaCount : '—'}
              </span>
            </div>
            <div className="kpi-card__footer">
              <span className="kpi-card__subtext">
                {areaCount !== null
                  ? 'Khu vực quản lý an ninh'
                  : 'Data unavailable'}
              </span>
            </div>
          </article>

          {/* 2. Active Cameras */}
          <article className="kpi-card">
            <div className="kpi-card__top">
              <span className="kpi-card__icon kpi-card__icon--emerald">
                <Camera size={20} />
              </span>
              <span className="kpi-card__badge">Camera</span>
            </div>
            <div className="kpi-card__body">
              <span className="kpi-card__label">Active Cameras</span>
              <span className="kpi-card__value">—</span>
            </div>
            <div className="kpi-card__footer">
              <span className="kpi-card__subtext">Data unavailable</span>
            </div>
          </article>

          {/* 3. Face Profiles */}
          <article className="kpi-card">
            <div className="kpi-card__top">
              <span className="kpi-card__icon kpi-card__icon--indigo">
                <Users size={20} />
              </span>
              <span className="kpi-card__badge">Khuôn mặt</span>
            </div>
            <div className="kpi-card__body">
              <span className="kpi-card__label">Face Profiles</span>
              <span className="kpi-card__value">—</span>
            </div>
            <div className="kpi-card__footer">
              <span className="kpi-card__subtext">Data unavailable</span>
            </div>
          </article>

          {/* 4. Active Incidents */}
          <article className="kpi-card">
            <div className="kpi-card__top">
              <span className="kpi-card__icon kpi-card__icon--amber">
                <AlertTriangle size={20} />
              </span>
              <span className="kpi-card__badge">Sự cố</span>
            </div>
            <div className="kpi-card__body">
              <span className="kpi-card__label">Active Incidents</span>
              <span className="kpi-card__value">—</span>
            </div>
            <div className="kpi-card__footer">
              <span className="kpi-card__subtext">Data unavailable</span>
            </div>
          </article>
        </section>

        {/* Main Grid: Campus Security Overview (Mini Map) + System Health */}
        <section className="dashboard-grid-middle">
          {/* Left: Campus Security Overview / Mini Map Container */}
          <article className="dash-card dash-card--map">
            <div className="dash-card__header">
              <div className="dash-card__title-wrap">
                <div className="dash-card__icon-box">
                  <MapIcon size={18} />
                </div>
                <div>
                  <h2 className="dash-card__title">Campus Security Overview</h2>
                  <p className="dash-card__subtitle">
                    Sơ đồ tổng quan phân vùng an ninh khuôn viên
                  </p>
                </div>
              </div>

              {/* Security Level Legend */}
              <div className="security-legend">
                <div className="security-legend__item">
                  <span className="security-legend__dot security-legend__dot--public" />
                  <span>PUBLIC</span>
                </div>
                <div className="security-legend__item">
                  <span className="security-legend__dot security-legend__dot--semi" />
                  <span>SEMI_PRIVATE</span>
                </div>
                <div className="security-legend__item">
                  <span className="security-legend__dot security-legend__dot--private" />
                  <span>PRIVATE</span>
                </div>
              </div>
            </div>

            {/* Map Placeholder Container */}
            <div className="campus-map-placeholder">
              <div className="campus-map-placeholder__grid-bg" />
              <div className="campus-map-placeholder__content">
                <div className="campus-map-placeholder__icon">
                  <Layers size={36} />
                </div>
                <h3 className="campus-map-placeholder__heading">
                  Campus Mini Map
                </h3>
                <p className="campus-map-placeholder__desc">
                  Sơ đồ đa giác trực quan (Floor Plan & Security Zones) sẽ được
                  tích hợp trong tính năng Area Polygon Drawing.
                </p>
                <div className="campus-map-placeholder__status-tag">
                  <Info size={13} />
                  <span>Map container placeholder — Ready for geometry integration</span>
                </div>
              </div>
            </div>
          </article>

          {/* Right: System Health */}
          <article className="dash-card dash-card--health">
            <div className="dash-card__header">
              <div className="dash-card__title-wrap">
                <div className="dash-card__icon-box">
                  <Activity size={18} />
                </div>
                <div>
                  <h2 className="dash-card__title">System Health</h2>
                  <p className="dash-card__subtitle">
                    Trạng thái hoạt động các phân hệ
                  </p>
                </div>
              </div>
            </div>

            <div className="system-health-list">
              <div className="health-row">
                <div className="health-row__left">
                  <span className="health-row__icon">
                    <Camera size={16} />
                  </span>
                  <span className="health-row__name">Camera Network</span>
                </div>
                <span className="health-row__status health-row__status--unavailable">
                  Status unavailable
                </span>
              </div>

              <div className="health-row">
                <div className="health-row__left">
                  <span className="health-row__icon">
                    <Cpu size={16} />
                  </span>
                  <span className="health-row__name">AI Vision Engine</span>
                </div>
                <span className="health-row__status health-row__status--unavailable">
                  Status unavailable
                </span>
              </div>

              <div className="health-row">
                <div className="health-row__left">
                  <span className="health-row__icon">
                    <Shield size={16} />
                  </span>
                  <span className="health-row__name">Face Intelligence</span>
                </div>
                <span className="health-row__status health-row__status--unavailable">
                  Status unavailable
                </span>
              </div>

              <div className="health-row">
                <div className="health-row__left">
                  <span className="health-row__icon">
                    <HardDrive size={16} />
                  </span>
                  <span className="health-row__name">Core Storage & API</span>
                </div>
                <span className="health-row__status health-row__status--unavailable">
                  Status unavailable
                </span>
              </div>
            </div>

            <div className="dash-card__footer-note">
              <Info size={14} />
              <span>Chưa kết nối dịch vụ giám sát thời gian thực</span>
            </div>
          </article>
        </section>

        {/* Bottom Grid: Attention Required + Recent Security Events */}
        <section className="dashboard-grid-bottom">
          {/* Attention Required */}
          <article className="dash-card">
            <div className="dash-card__header">
              <div className="dash-card__title-wrap">
                <div className="dash-card__icon-box dash-card__icon-box--amber">
                  <Bell size={18} />
                </div>
                <div>
                  <h2 className="dash-card__title">Attention Required</h2>
                  <p className="dash-card__subtitle">
                    Cảnh báo cần xử lý ưu tiên
                  </p>
                </div>
              </div>
              <span className="counter-pill counter-pill--unavailable">—</span>
            </div>

            <div className="empty-panel">
              <div className="empty-panel__icon">
                <Bell size={32} />
              </div>
              <h4 className="empty-panel__title">
                Dữ liệu cảnh báo chưa khả dụng
              </h4>
              <p className="empty-panel__desc">
                Hệ thống giám sát và phát hiện cảnh báo an ninh chưa được kết nối.
              </p>
            </div>
          </article>

          {/* Recent Security Events */}
          <article className="dash-card">
            <div className="dash-card__header">
              <div className="dash-card__title-wrap">
                <div className="dash-card__icon-box dash-card__icon-box--blue">
                  <Clock size={18} />
                </div>
                <div>
                  <h2 className="dash-card__title">Recent Security Events</h2>
                  <p className="dash-card__subtitle">
                    Nhật ký sự kiện an ninh gần đây
                  </p>
                </div>
              </div>
            </div>

            <div className="empty-panel">
              <div className="empty-panel__icon">
                <Clock size={32} />
              </div>
              <h4 className="empty-panel__title">
                Chưa có dữ liệu sự kiện gần đây
              </h4>
              <p className="empty-panel__desc">
                Hệ thống ghi nhận sự kiện thời gian thực sẽ được cập nhật.
              </p>
            </div>
          </article>
        </section>
      </div>
    </div>
  );
}
