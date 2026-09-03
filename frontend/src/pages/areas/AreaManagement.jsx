import { useState } from 'react';
import EditZoneModal from '../../components/EditZoneModal';
import { CheckCircle2, Sliders, Bell, Settings } from 'lucide-react';
import '../../styles/AreaManagement.css';

export default function AdminDashboard() {
  const [selectedZone, setSelectedZone] = useState('server-room');
  const [currentFloor, setCurrentFloor] = useState('Floor 1');
  const [isEditZoneOpen, setIsEditZoneOpen] = useState(false);
  const [toastMessage, setToastMessage] = useState(null);

  const [zoneConfig, setZoneConfig] = useState({
    'main-lobby': {
      id: 'main-lobby',
      name: 'Main Lobby & Corridor',
      code: 'ZN-LOB-01',
      floor: 'Floor 1',
      level: 'Level 1 (Public)',
      color: 'green',
      status: 'Normal',
      escalationPolicy: 'BROADCAST_ALL_GUARDS',
      preferredGuard: 'ALL_SECURITY',
      slaEscalate: '300 Seconds',
      authorizedRoles: ['ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD', 'LECTURER', 'STUDENT', 'STAFF'],
      assignedUsers: []
    },
    'library': {
      id: 'library',
      name: 'Library Area',
      code: 'ZN-LIB-01',
      floor: 'Floor 1',
      level: 'Level 1 (Public)',
      color: 'green',
      status: 'Normal',
      escalationPolicy: 'BROADCAST_ALL_GUARDS',
      preferredGuard: 'INTERNAL_ONLY',
      slaEscalate: '180 Seconds',
      authorizedRoles: ['ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'LECTURER', 'STUDENT', 'STAFF'],
      assignedUsers: []
    },
    'ai-labs': {
      id: 'ai-labs',
      name: 'AI & Hardware Labs',
      code: 'ZN-LAB-02',
      floor: 'Floor 1',
      level: 'Level 2 (Semi-Private)',
      color: 'amber',
      status: 'Monitored',
      escalationPolicy: 'INTERNAL_ONLY_AUTO_ESCALATE',
      preferredGuard: 'INTERNAL_ONLY',
      slaEscalate: '120 Seconds',
      authorizedRoles: ['ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'LECTURER', 'STUDENT'],
      assignedUsers: [
        { id: 'GV-001', name: 'TS. Thân Thị Ngọc Vân', role: 'LECTURER', email: 'vanttn@fpt.edu.vn', schedule: '24/7 Unlimited' },
        { id: 'SE170123', name: 'Nguyễn Tiến Đạt', role: 'STUDENT', email: 'datntse170123@fpt.edu.vn', schedule: 'Lab Hours (08:00 - 21:00)' }
      ]
    },
    'staff-offices': {
      id: 'staff-offices',
      name: 'Staff & Facility Offices',
      code: 'ZN-OFF-01',
      floor: 'Floor 1',
      level: 'Level 2 (Semi-Private)',
      color: 'amber',
      status: 'Monitored',
      escalationPolicy: 'INTERNAL_ONLY_AUTO_ESCALATE',
      preferredGuard: 'INTERNAL_ONLY',
      slaEscalate: '90 Seconds',
      authorizedRoles: ['ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'STAFF'],
      assignedUsers: [
        { id: 'FM-001', name: 'Trần Bình (Quản Lý CSVC)', role: 'FACILITY_MANAGER', email: 'manager.binh@fpt.edu.vn', schedule: '24/7 Unlimited' }
      ]
    },
    'server-room': {
      id: 'server-room',
      name: 'Server Room B204',
      code: 'ZN-SRV-01',
      floor: 'Floor 1',
      level: 'Level 3 (Private)',
      color: 'red',
      status: 'Strictly Restricted',
      escalationPolicy: 'Outsourced Guard: DENIED (Auto-Escalate FM after 60s)',
      preferredGuard: 'INTERNAL_ONLY',
      slaEscalate: '60 Seconds',
      authorizedRoles: ['ADMIN', 'FACILITY_MANAGER'],
      assignedUsers: [
        { id: 'AD-001', name: 'Quản Trị Viên FPTU', role: 'ADMIN', email: 'admin@fpt.edu.vn', schedule: '24/7 Unlimited' },
        { id: 'FM-001', name: 'Trần Bình (Quản Lý CSVC)', role: 'FACILITY_MANAGER', email: 'manager.binh@fpt.edu.vn', schedule: '24/7 Unlimited' },
        { id: 'SEC-001', name: 'Nguyễn Văn An (Bảo Vệ)', role: 'INTERNAL_GUARD', email: 'guard.an@fpt.edu.vn', schedule: 'Shift Hours (06:00 - 22:00)' }
      ]
    },
    'principal-office': {
      id: 'principal-office',
      name: 'Principal Office',
      code: 'ZN-PRN-01',
      floor: 'Floor 1',
      level: 'Level 3 (Private)',
      color: 'red',
      status: 'Strictly Restricted',
      dashed: true,
      escalationPolicy: 'SILENT_ADMIN_ALERT',
      preferredGuard: 'INTERNAL_ONLY',
      slaEscalate: '30 Seconds',
      authorizedRoles: ['ADMIN'],
      assignedUsers: [
        { id: 'AD-001', name: 'Quản Trị Viên FPTU', role: 'ADMIN', email: 'admin@fpt.edu.vn', schedule: '24/7 Unlimited' }
      ]
    },
  });

  const zones = [
    {
      id: 'main-lobby',
      name: 'Main Lobby & Corridor',
      level: 'Level 1',
      status: 'Normal',
      color: 'green',
    },
    {
      id: 'library',
      name: 'Library Area',
      level: 'Level 1',
      status: 'Normal',
      color: 'green',
    },
    {
      id: 'ai-labs',
      name: 'AI & Hardware Labs',
      level: 'Level 2',
      status: 'Normal',
      color: 'amber',
    },
    {
      id: 'staff-offices',
      name: 'Staff & Facility Offices',
      level: 'Level 2',
      status: 'Normal',
      color: 'amber',
    },
    {
      id: 'server-room',
      name: 'Server Room',
      level: 'Level 3',
      status: 'Strictly Restricted',
      color: 'red',
    },
    {
      id: 'principal-office',
      name: 'Principal Office',
      level: 'Level 3 (Private)',
      color: 'red',
      dashed: true,
    },
  ];

  const handleZoneClick = (zone) => {
    setSelectedZone(zone.id);
  };

  const getDetailsByZone = () => {
    const activeZone = selectedZone && zoneConfig[selectedZone];
    return activeZone || zoneConfig['server-room'];
  };

  const handleOpenEditZone = () => {
    setIsEditZoneOpen(true);
  };

  const handleSaveModalConfig = (updatedData) => {
    const zoneKey = selectedZone || 'server-room';
    setZoneConfig((prev) => ({
      ...prev,
      [zoneKey]: {
        ...prev[zoneKey],
        name: updatedData.name,
        code: updatedData.code,
        level: updatedData.level,
        escalationPolicy: updatedData.escalationPolicy,
        preferredGuard: updatedData.preferredGuard,
        slaEscalate: `${updatedData.slaEscalate} Seconds`,
        authorizedRoles: updatedData.authorizedRoles,
        assignedUsers: updatedData.assignedUsers,
      },
    }));

    setIsEditZoneOpen(false);
    setToastMessage(`Đã cập nhật thành công cấu hình và danh sách ${updatedData.assignedUsers?.length || 0} người dùng cho ${updatedData.name}!`);
    setTimeout(() => setToastMessage(null), 5000);
  };

  const details = getDetailsByZone();

  return (
    <div className="admin-dashboard">
      {/* Header */}
      <header className="admin-dashboard__header">
        <div className="admin-dashboard__header-left">
          <h1 className="admin-dashboard__title">Campus Area Map & Access Control</h1>
          <p className="admin-dashboard__subtitle">Real-time floor plan, AI security zones & Whitelist Management</p>
        </div>
        <div className="admin-dashboard__header-right">
          <span className="admin-dashboard__time">SYS TIME: 06:15:22</span>
          <button className="admin-dashboard__notification" aria-label="Notifications">
            <Bell size={18} />
          </button>
          <button className="admin-dashboard__settings" aria-label="Settings">
            <Settings size={18} />
          </button>
        </div>
      </header>

      {/* Toast Notification */}
      {toastMessage && (
        <div className="admin-toast-banner">
          <CheckCircle2 size={18} className="text-emerald-400" />
          <span>{toastMessage}</span>
          <button type="button" className="admin-toast-close" onClick={() => setToastMessage(null)}>×</button>
        </div>
      )}

      {/* Stats Row */}
      <section className="admin-dashboard__stats">
        <div className="stat-card">
          <label className="stat-card__label">TOTAL ZONES</label>
          <strong className="stat-card__value">12</strong>
        </div>
        <div className="stat-card">
          <label className="stat-card__label">HIGH SECURITY (LEVEL 3)</label>
          <strong className="stat-card__value">2</strong>
        </div>
        <div className="stat-card">
          <label className="stat-card__label">WHITELIST USERS</label>
          <strong className="stat-card__value">{details.assignedUsers?.length || 0}</strong>
        </div>
        <div className="stat-card">
          <label className="stat-card__label">MAP VIEW</label>
          <div className="stat-card__floor-selector">
            <button
              className={`floor-btn ${currentFloor === 'Floor 1' ? 'floor-btn--active' : ''}`}
              onClick={() => setCurrentFloor('Floor 1')}
            >
              Floor 1
            </button>
            <button
              className={`floor-btn ${currentFloor === 'Floor 2' ? 'floor-btn--active' : ''}`}
              onClick={() => setCurrentFloor('Floor 2')}
            >
              Floor 2
            </button>
          </div>
        </div>
      </section>

      {/* Main Content */}
      <div className="admin-dashboard__content">
        {/* Map Panel */}
        <div className="admin-dashboard__map-section">
          {/* Legend */}
          <div className="admin-dashboard__legend">
            <span className="legend-item">
              <span className="legend-dot legend-dot--green"></span>
              Level 1 (Public)
            </span>
            <span className="legend-item">
              <span className="legend-dot legend-dot--amber"></span>
              Level 2 (Semi-Private)
            </span>
            <span className="legend-item">
              <span className="legend-dot legend-dot--red"></span>
              Level 3 (Private)
            </span>
          </div>

          {/* Map */}
          <div className="admin-map">
            {/* Main Lobby */}
            <div
              className={`admin-zone admin-zone--main-lobby ${selectedZone === 'main-lobby' ? 'admin-zone--selected' : ''}`}
              onClick={() => handleZoneClick(zones[0])}
            >
              <span className="admin-zone__icon">🏢</span>
              <span className="admin-zone__title">Main Lobby & Corridor</span>
              <span className="admin-zone__meta">Level 1</span>
              <span className="admin-zone__status">Status: Normal</span>
            </div>

            {/* Library Area */}
            <div
              className={`admin-zone admin-zone--library ${selectedZone === 'library' ? 'admin-zone--selected' : ''}`}
              onClick={() => handleZoneClick(zones[1])}
            >
              <span className="admin-zone__icon">📚</span>
              <span className="admin-zone__title">Library Area</span>
              <span className="admin-zone__meta">Level 1</span>
            </div>

            {/* AI & Hardware Labs */}
            <div
              className={`admin-zone admin-zone--ai-labs ${selectedZone === 'ai-labs' ? 'admin-zone--selected' : ''}`}
              onClick={() => handleZoneClick(zones[2])}
            >
              <span className="admin-zone__icon">🤖</span>
              <span className="admin-zone__title">AI & Hardware Labs</span>
              <span className="admin-zone__meta">Level 2</span>
              <span className="admin-zone__alert">●</span>
            </div>

            {/* Staff & Facility */}
            <div
              className={`admin-zone admin-zone--staff ${selectedZone === 'staff-offices' ? 'admin-zone--selected' : ''}`}
              onClick={() => handleZoneClick(zones[3])}
            >
              <span className="admin-zone__icon">👥</span>
              <span className="admin-zone__title">Staff & Facility Offices</span>
              <span className="admin-zone__meta">Level 2</span>
            </div>

            {/* Server Room */}
            <div
              className={`admin-zone admin-zone--server ${selectedZone === 'server-room' ? 'admin-zone--selected' : ''}`}
              onClick={() => handleZoneClick(zones[4])}
            >
              <span className="admin-zone__icon">🖥</span>
              <span className="admin-zone__title">Server Room</span>
              <span className="admin-zone__meta">Strictly Restricted</span>
            </div>

            {/* Principal Office */}
            <div
              className={`admin-zone admin-zone--principal admin-zone--dashed ${selectedZone === 'principal-office' ? 'admin-zone--selected' : ''}`}
              onClick={() => handleZoneClick(zones[5])}
            >
              <span className="admin-zone__icon">👔</span>
              <span className="admin-zone__title">Principal Office</span>
              <span className="admin-zone__meta">Level 3 (Private)</span>
            </div>
          </div>

          {/* Map Tools */}
          <div className="admin-map__tools">
            <button className="map-tool-btn" aria-label="Search">🔍</button>
            <button className="map-tool-btn" aria-label="Filter">⚙</button>
          </div>
        </div>

        {/* Right Sidebar - Zone Details */}
        <aside className="admin-dashboard__sidebar">
          <div className="zone-details__card">
            <div className="zone-details__header-row">
              <h3 className="zone-details__title">Zone Details</h3>
              <span className="zone-details__code-tag">{details.code || 'ZONE'}</span>
            </div>
            <div className="zone-details__row">
              <span className="zone-details__label">{details.name}</span>
              <span className="zone-details__value">{details.level}</span>
            </div>
          </div>

          <div className="zone-details__card zone-details__card--compact">
            <div className="zone-details__section">
              <h5 className="zone-details__section-title">ESCALATION POLICY</h5>
              <p className="zone-details__text">{details.escalationPolicy}</p>
            </div>

            <div className="zone-details__section">
              <h5 className="zone-details__section-title">PREFERRED GUARD</h5>
              <p className="zone-details__text">{details.preferredGuard}</p>
            </div>

            <div className="zone-details__section">
              <h5 className="zone-details__section-title">SLA ESCALATE</h5>
              <p className="zone-details__text">{details.slaEscalate}</p>
            </div>

            <div className="zone-details__section">
              <h5 className="zone-details__section-title">AUTHORIZED ROLES</h5>
              <p className="zone-details__text">{Array.isArray(details.authorizedRoles) ? details.authorizedRoles.join(', ') : details.authorizedRoles}</p>
            </div>

            <div className="zone-details__section">
              <h5 className="zone-details__section-title">WHITELISTED USERS</h5>
              <p className="zone-details__text text-cyan-400 font-bold">
                {details.assignedUsers?.length || 0} người được cấp phép trực tiếp
              </p>
            </div>
          </div>

          <div className="zone-details__card zone-details__card--buttons">
            <button
              className="zone-action-btn zone-action-btn--primary"
              type="button"
              onClick={handleOpenEditZone}
              id="btn-edit-zone-config"
            >
              <div className="zone-action-btn__icon-wrap">
                <Sliders size={18} />
              </div>
              <div className="zone-action-btn__text-wrap">
                <span className="zone-action-btn__title">Chỉnh sửa Cấu hình & Whitelist</span>
                <span className="zone-action-btn__sub">Edit Zone Policy, Whitelist & AI</span>
              </div>
              <span className="zone-action-btn__arrow">→</span>
            </button>
          </div>
        </aside>
      </div>

      {/* Modern High-Tech Edit Zone Configuration Modal with File Upload & Parsing */}
      <EditZoneModal
        isOpen={isEditZoneOpen}
        onClose={() => setIsEditZoneOpen(false)}
        zoneData={details}
        onSave={handleSaveModalConfig}
      />
    </div>
  );
}
