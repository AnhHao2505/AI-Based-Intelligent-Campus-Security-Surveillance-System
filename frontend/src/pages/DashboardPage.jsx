import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
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
  Map as MapIcon,
  Shield,
  Info,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { getAreas } from '../services/areaService';
import '../styles/DashboardPage.css';

export default function DashboardPage() {
  const { user } = useAuth();
  const [areas, setAreas] = useState([]);
  const [areaCount, setAreaCount] = useState(null);
  const [areaLoading, setAreaLoading] = useState(false);

  // Per-card connection flags (FIX 1)
  const [kpiConnection] = useState({
    areas: { isConnected: true },
    cameras: { isConnected: false, reason: 'Cần kết nối module Camera' },
    faceProfiles: { isConnected: false, reason: 'Cần kết nối module Nhận diện' },
    incidents: { isConnected: false, reason: 'Cần kết nối module Camera và AI' },
  });

  // Subsystem statuses (FIX 2)
  const [subsystems] = useState([
    { id: 'cameraNetwork', name: 'Camera Network', status: 'not_connected', icon: Camera },
    { id: 'aiVisionEngine', name: 'AI Vision Engine', status: 'not_connected', icon: Cpu },
    { id: 'faceIntelligence', name: 'Face Intelligence', status: 'not_connected', icon: Shield },
    { id: 'coreStorageApi', name: 'Core Storage & API', status: 'not_connected', icon: HardDrive },
  ]);

  // Data availability flags for panels (FIX 3)
  const [miniMapData] = useState(null);
  const [attentionEvents] = useState([]);
  const [securityLogs] = useState([]);

  // Safe RBAC check: only request Area API if role has permission
  useEffect(() => {
    let isMounted = true;
    const isAreaAuthorized =
      user?.role === 'ADMIN' || user?.role === 'FACILITY_MANAGER';

    if (isAreaAuthorized) {
      setAreaLoading(true);
      getAreas({ page: 0, size: 100 })
        .then((res) => {
          if (isMounted && res) {
            const list = res.content || [];
            setAreas(list);
            if (typeof res.totalElements === 'number') {
              setAreaCount(res.totalElements);
            } else {
              setAreaCount(list.length);
            }
          }
        })
        .catch(() => {
          if (isMounted) {
            setAreas([]);
            setAreaCount(null);
          }
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

  // Derive Area data for FIX 4
  const totalAreas = areas.length > 0 ? areas.length : (areaCount || 0);
  const publicCount = areas.filter((a) => {
    const lvl = a.areaLevel || a.level;
    return lvl === 'PUBLIC' || lvl === 1 || lvl === '1';
  }).length;
  const semiCount = areas.filter((a) => {
    const lvl = a.areaLevel || a.level;
    return lvl === 'SEMI_PRIVATE' || lvl === 2 || lvl === '2';
  }).length;
  const privateCount = areas.filter((a) => {
    const lvl = a.areaLevel || a.level;
    return lvl === 'PRIVATE' || lvl === 3 || lvl === '3';
  }).length;

  const publicPct = totalAreas > 0 ? (publicCount / totalAreas) * 100 : 0;
  const semiPct = totalAreas > 0 ? (semiCount / totalAreas) * 100 : 0;
  const privatePct = totalAreas > 0 ? (privateCount / totalAreas) * 100 : 0;

  const areasWithGeometryCount = areas.filter(
    (a) => a.geometry && ((a.geometry.vertices && a.geometry.vertices.length >= 3) || a.geometry.type)
  ).length;

  // Connected subsystems count (FIX 2)
  const connectedSubsystemsCount = subsystems.filter((s) => s.status === 'connected').length;
  const isAllSubsystemsDisconnected = connectedSubsystemsCount === 0;

  // Placeholder status for FIX 3
  const hasMiniMapData = Boolean(miniMapData && miniMapData.length > 0);
  const hasAttentionData = Boolean(attentionEvents && attentionEvents.length > 0);
  const hasEventsData = Boolean(securityLogs && securityLogs.length > 0);
  const allThreeEmpty = !hasMiniMapData && !hasAttentionData && !hasEventsData;

  // KPI card configs (FIX 1)
  const kpiCards = [
    {
      id: 'areas',
      label: 'Total Areas',
      badge: 'Khu vực',
      icon: MapPin,
      iconVariant: 'blue',
      isConnected: kpiConnection.areas.isConnected,
      value: areaLoading ? '...' : areaCount !== null ? areaCount : '—',
      subtext: areaCount !== null ? 'Khu vực quản lý an ninh' : 'Đang tải dữ liệu...',
      disconnectedReason: '',
    },
    {
      id: 'cameras',
      label: 'Active Cameras',
      badge: 'Camera',
      icon: Camera,
      iconVariant: 'emerald',
      isConnected: kpiConnection.cameras.isConnected,
      value: null,
      subtext: '',
      disconnectedReason: kpiConnection.cameras.reason,
    },
    {
      id: 'faceProfiles',
      label: 'Face Profiles',
      badge: 'Khuôn mặt',
      icon: Users,
      iconVariant: 'indigo',
      isConnected: kpiConnection.faceProfiles.isConnected,
      value: null,
      subtext: '',
      disconnectedReason: kpiConnection.faceProfiles.reason,
    },
    {
      id: 'incidents',
      label: 'Active Incidents',
      badge: 'Sự cố',
      icon: AlertTriangle,
      iconVariant: 'amber',
      isConnected: kpiConnection.incidents.isConnected,
      value: null,
      subtext: '',
      disconnectedReason: kpiConnection.incidents.reason,
    },
  ];

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

        {/* 4 KPI Cards (FIX 1) */}
        <section className="dashboard-kpis">
          {kpiCards.map((card) => {
            const IconComponent = card.icon;
            return (
              <article
                key={card.id}
                className={`kpi-card ${!card.isConnected ? 'kpi-card--disconnected' : ''}`}
              >
                <div className="kpi-card__top">
                  <span className={`kpi-card__icon kpi-card__icon--${card.iconVariant}`}>
                    <IconComponent size={20} />
                  </span>
                  <span className="kpi-card__badge">{card.badge}</span>
                </div>
                <div className="kpi-card__body">
                  <span className="kpi-card__label">{card.label}</span>
                  {card.isConnected ? (
                    <span className="kpi-card__value">{card.value}</span>
                  ) : (
                    <span className="kpi-card__value kpi-card__value--disconnected">
                      Chưa kết nối
                    </span>
                  )}
                </div>
                <div className="kpi-card__footer">
                  <span className="kpi-card__subtext">
                    {card.isConnected ? card.subtext : card.disconnectedReason}
                  </span>
                </div>
              </article>
            );
          })}
        </section>

        {/* Middle Grid: Campus Security Overview + System Health */}
        <section className="dashboard-grid-middle">
          {/* Left: Campus Security Overview (FIX 4) */}
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

              {/* Security Level Legend (Preserved exactly) */}
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

            {/* Compact Area Summary Panel (Surfacing Real Data) */}
            <div className="area-summary-panel">
              {/* Stacked Bar by Security Level */}
              <div
                className="area-stacked-bar"
                role="progressbar"
                aria-label="Tỷ lệ khu vực theo mức an ninh"
              >
                {publicPct > 0 && (
                  <div
                    className="area-stacked-bar__segment area-stacked-bar__segment--public"
                    style={{ width: `${publicPct}%` }}
                    title={`PUBLIC: ${publicCount} khu vực (${publicPct.toFixed(1)}%)`}
                  />
                )}
                {semiPct > 0 && (
                  <div
                    className="area-stacked-bar__segment area-stacked-bar__segment--semi"
                    style={{ width: `${semiPct}%` }}
                    title={`SEMI_PRIVATE: ${semiCount} khu vực (${semiPct.toFixed(1)}%)`}
                  />
                )}
                {privatePct > 0 && (
                  <div
                    className="area-stacked-bar__segment area-stacked-bar__segment--private"
                    style={{ width: `${privatePct}%` }}
                    title={`PRIVATE: ${privateCount} khu vực (${privatePct.toFixed(1)}%)`}
                  />
                )}
              </div>

              {/* Row of three items: coloured dot, level name, count */}
              <div className="area-level-stats">
                <div className="area-level-stat">
                  <span className="area-level-stat__dot area-level-stat__dot--public" />
                  <span className="area-level-stat__name">PUBLIC</span>
                  <span className="area-level-stat__count">{publicCount}</span>
                </div>
                <div className="area-level-stat">
                  <span className="area-level-stat__dot area-level-stat__dot--semi" />
                  <span className="area-level-stat__name">SEMI_PRIVATE</span>
                  <span className="area-level-stat__count">{semiCount}</span>
                </div>
                <div className="area-level-stat">
                  <span className="area-level-stat__dot area-level-stat__dot--private" />
                  <span className="area-level-stat__name">PRIVATE</span>
                  <span className="area-level-stat__count">{privateCount}</span>
                </div>
              </div>

              {/* Progress line for boundaries */}
              <div className="area-boundary-progress">
                <div className="area-boundary-progress__header">
                  <span className="area-boundary-progress__label">
                    {areaLoading
                      ? 'Đang tải dữ liệu...'
                      : `${areasWithGeometryCount}/${totalAreas} khu vực đã có ranh giới`}
                  </span>
                </div>
                <div className="area-progress-bar">
                  <div
                    className="area-progress-bar__fill"
                    style={{
                      width: `${totalAreas > 0 ? (areasWithGeometryCount / totalAreas) * 100 : 0}%`,
                    }}
                  />
                </div>
              </div>

              {/* Bottom right route link */}
              <div className="area-summary-footer">
                <Link to="/admin/areas/map" className="area-summary-link">
                  Xem bản đồ khu vực →
                </Link>
              </div>
            </div>
          </article>

          {/* Right: System Health (FIX 2) */}
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

            {isAllSubsystemsDisconnected ? (
              /* Collapsed view when 0/4 connected */
              <div className="system-health-collapsed">
                <div className="system-health-collapsed__summary">
                  {connectedSubsystemsCount}/{subsystems.length} phân hệ đã kết nối
                </div>
                <div className="system-health-collapsed__items">
                  {subsystems.map((sub) => (
                    <div key={sub.id} className="system-health-collapsed__item">
                      <span
                        className={`system-health-collapsed__dot system-health-collapsed__dot--${sub.status}`}
                      />
                      <span className="system-health-collapsed__name">{sub.name}</span>
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              /* Expanded view when at least 1 is connected */
              <div className="system-health-list">
                {subsystems.map((sub) => {
                  const SubIcon = sub.icon;
                  return (
                    <div key={sub.id} className="health-row">
                      <div className="health-row__left">
                        <span className="health-row__icon">
                          <SubIcon size={16} />
                        </span>
                        <span className="health-row__name">{sub.name}</span>
                      </div>
                      <span
                        className={`health-row__status health-row__status--${sub.status}`}
                      >
                        {sub.status === 'connected' ? 'Connected' : 'Status unavailable'}
                      </span>
                    </div>
                  );
                })}
              </div>
            )}

            <div className="dash-card__footer-note">
              <Info size={14} />
              <span>Chưa kết nối dịch vụ giám sát thời gian thực</span>
            </div>
          </article>
        </section>

        {/* Bottom Section: Unified Strip (FIX 3) or Expanded Panels */}
        {allThreeEmpty ? (
          /* Single horizontal strip replacing the 3 empty placeholders */
          <section className="dashboard-disconnected-strip">
            <Info size={20} className="dashboard-disconnected-strip__icon" />
            <span className="dashboard-disconnected-strip__text">
              Bản đồ, cảnh báo và nhật ký sự kiện sẽ hiển thị khi module Camera và AI Engine được kết nối.
            </span>
          </section>
        ) : (
          /* Panels render as they receive real data */
          <section className="dashboard-grid-bottom">
            {hasAttentionData && (
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
                  <span className="counter-pill">{attentionEvents.length}</span>
                </div>
                {/* Active alert items would render here */}
              </article>
            )}

            {hasEventsData && (
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
                {/* Real event logs would render here */}
              </article>
            )}
          </section>
        )}
      </div>
    </div>
  );
}
