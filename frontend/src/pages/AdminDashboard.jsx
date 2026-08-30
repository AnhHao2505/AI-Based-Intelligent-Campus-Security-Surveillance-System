import { useState } from 'react';
import './AdminDashboard.css';

export default function AdminDashboard() {
  const [selectedZone, setSelectedZone] = useState(null);
  const [currentFloor, setCurrentFloor] = useState('Floor 1');
  const [isEditZoneOpen, setIsEditZoneOpen] = useState(false);
  const [zoneConfig, setZoneConfig] = useState({
    'server-room': {
      name: 'Server Room',
      level: 'LEVEL 3 (PRIVATE)',
      escalationPolicy: 'Outsourced Guard: DENIED',
      preferredGuard: 'INTERNAL_ONLY',
      slaEscalate: '60 Seconds',
      authorizedRoles: ['ADMIN', 'FACILITY_MANAGER'],
    },
  });
  const [editZoneForm, setEditZoneForm] = useState({
    name: '',
    level: '',
    escalationPolicy: '',
    preferredGuard: '',
    slaEscalate: '',
    authorizedRoles: '',
  });
  const [addedUsers, setAddedUsers] = useState([]);
  const [newUserForm, setNewUserForm] = useState({
    name: '',
    id: '',
    role: '',
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
    const currentDetails = getDetailsByZone();
    setEditZoneForm({
      name: currentDetails.name,
      level: currentDetails.level,
      escalationPolicy: currentDetails.escalationPolicy,
      preferredGuard: currentDetails.preferredGuard,
      slaEscalate: currentDetails.slaEscalate,
      authorizedRoles: currentDetails.authorizedRoles.join(', '),
    });
    setIsEditZoneOpen(true);
  };

  const handleFieldChange = (field, value) => {
    setEditZoneForm((prev) => ({
      ...prev,
      [field]: value,
    }));
  };

  const handleSaveZoneConfig = (event) => {
    event.preventDefault();

    const zoneKey = selectedZone || 'server-room';
    const nextZoneConfig = {
      ...zoneConfig,
      [zoneKey]: {
        ...editZoneForm,
        authorizedRoles: editZoneForm.authorizedRoles
          .split(',')
          .map((role) => role.trim())
          .filter(Boolean),
      },
    };

    setZoneConfig(nextZoneConfig);
    setIsEditZoneOpen(false);
  };

  const handleNewUserChange = (field, value) => {
    setNewUserForm((prev) => ({
      ...prev,
      [field]: value,
    }));
  };

  const handleAddUserOneByOne = () => {
    const trimmedName = newUserForm.name.trim();
    const trimmedId = newUserForm.id.trim();
    const trimmedRole = newUserForm.role.trim();

    if (!trimmedName && !trimmedId && !trimmedRole) {
      return;
    }

    const userToAdd = {
      id: trimmedId || `USR-${addedUsers.length + 1}`,
      name: trimmedName || 'Unnamed User',
      role: trimmedRole || 'Viewer',
    };

    setAddedUsers((prev) => [...prev, userToAdd]);
    setNewUserForm({ name: '', id: '', role: '' });
  };

  const handleImportUserFile = () => {
    const fileInput = document.getElementById('zone-user-file-input');
    if (fileInput) {
      fileInput.click();
    }
  };

  const details = getDetailsByZone();

  return (
    <div className="admin-dashboard">
      {/* Header */}
      <header className="admin-dashboard__header">
        <div className="admin-dashboard__header-left">
          <h1 className="admin-dashboard__title">Campus Area Map</h1>
          <p className="admin-dashboard__subtitle">Real-time floor plan & security zones</p>
        </div>
        <div className="admin-dashboard__header-right">
          <span className="admin-dashboard__time">SYS TIME: 06:15:22</span>
          <button className="admin-dashboard__notification">🔔</button>
          <button className="admin-dashboard__settings">⚙</button>
        </div>
      </header>

      {/* Stats Row */}
      <section className="admin-dashboard__stats">
        <div className="stat-card">
          <label className="stat-card__label">TOTAL ZONES</label>
          <strong className="stat-card__value">12</strong>
        </div>
        <div className="stat-card">
          <label className="stat-card__label">HIGH SECURITY (1)</label>
          <strong className="stat-card__value">3</strong>
        </div>
        <div className="stat-card">
          <label className="stat-card__label">ACTIVE INCIDENTS</label>
          <strong className="stat-card__value">0</strong>
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
              className="admin-zone admin-zone--main-lobby"
              onClick={() => handleZoneClick(zones[0])}
            >
              <span className="admin-zone__icon">🏢</span>
              <span className="admin-zone__title">Main Lobby & Corridor</span>
              <span className="admin-zone__meta">Level 1</span>
              <span className="admin-zone__status">Status: Normal</span>
            </div>

            {/* Library Area */}
            <div
              className="admin-zone admin-zone--library"
              onClick={() => handleZoneClick(zones[1])}
            >
              <span className="admin-zone__icon">📚</span>
              <span className="admin-zone__title">Library Area</span>
              <span className="admin-zone__meta">Level 1</span>
            </div>

            {/* AI & Hardware Labs */}
            <div
              className="admin-zone admin-zone--ai-labs"
              onClick={() => handleZoneClick(zones[2])}
            >
              <span className="admin-zone__icon">🤖</span>
              <span className="admin-zone__title">AI & Hardware Labs</span>
              <span className="admin-zone__meta">Level 2</span>
              <span className="admin-zone__alert">●</span>
            </div>

            {/* Staff & Facility */}
            <div
              className="admin-zone admin-zone--staff"
              onClick={() => handleZoneClick(zones[3])}
            >
              <span className="admin-zone__icon">👥</span>
              <span className="admin-zone__title">Staff & Facility Offices</span>
              <span className="admin-zone__meta">Level 2</span>
            </div>

            {/* Server Room */}
            <div
              className="admin-zone admin-zone--server"
              onClick={() => handleZoneClick(zones[4])}
            >
              <span className="admin-zone__icon">🖥</span>
              <span className="admin-zone__title">Server Room</span>
              <span className="admin-zone__meta">Strictly Restricted</span>
            </div>

            {/* Principal Office */}
            <div
              className="admin-zone admin-zone--principal admin-zone--dashed"
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
            <h3 className="zone-details__title">Zone Details</h3>
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
              <p className="zone-details__text">{details.authorizedRoles.join(', ')}</p>
            </div>
          </div>

          <div className="zone-details__card zone-details__card--buttons">
            <button className="zone-action-btn" type="button" onClick={handleOpenEditZone}>
              ✎ Edit Zone Config
            </button>
          </div>
        </aside>
      </div>

      {isEditZoneOpen && (
        <div className="edit-zone-modal-backdrop" onClick={() => setIsEditZoneOpen(false)}>
          <div
            className="edit-zone-modal"
            onClick={(event) => event.stopPropagation()}
            role="dialog"
            aria-modal="true"
          >
            <div className="edit-zone-modal__header">
              <div>
                <p className="edit-zone-modal__eyebrow">Security Setting</p>
                <h3>Edit Zone Config</h3>
              </div>
              <button
                type="button"
                className="edit-zone-modal__close"
                onClick={() => setIsEditZoneOpen(false)}
                aria-label="Close panel"
              >
                ×
              </button>
            </div>

            <div className="edit-zone-modal__body">
              <div className="edit-zone-modal__left">
                <form className="edit-zone-modal__form" onSubmit={handleSaveZoneConfig}>
                  <div className="edit-zone-modal__section-header">
                    <h4>Zone Settings</h4>
                  </div>

                  <div className="edit-zone-grid">
                    <label className="edit-zone-field">
                      <span>Zone Name</span>
                      <input
                        value={editZoneForm.name}
                        onChange={(event) => handleFieldChange('name', event.target.value)}
                      />
                    </label>

                    <label className="edit-zone-field">
                      <span>Level</span>
                      <input
                        value={editZoneForm.level}
                        onChange={(event) => handleFieldChange('level', event.target.value)}
                      />
                    </label>

                    <label className="edit-zone-field edit-zone-field--full">
                      <span>Escalation Policy</span>
                      <input
                        value={editZoneForm.escalationPolicy}
                        onChange={(event) => handleFieldChange('escalationPolicy', event.target.value)}
                      />
                    </label>

                    <label className="edit-zone-field">
                      <span>Preferred Guard</span>
                      <input
                        value={editZoneForm.preferredGuard}
                        onChange={(event) => handleFieldChange('preferredGuard', event.target.value)}
                      />
                    </label>

                    <label className="edit-zone-field">
                      <span>SLA Escalate</span>
                      <input
                        value={editZoneForm.slaEscalate}
                        onChange={(event) => handleFieldChange('slaEscalate', event.target.value)}
                      />
                    </label>

                    <label className="edit-zone-field edit-zone-field--full">
                      <span>Authorized Roles</span>
                      <input
                        value={editZoneForm.authorizedRoles}
                        onChange={(event) => handleFieldChange('authorizedRoles', event.target.value)}
                      />
                    </label>
                  </div>

                  <div className="edit-zone-modal__footer">
                    <button type="button" className="edit-zone-modal__cancel" onClick={() => setIsEditZoneOpen(false)}>
                      Cancel
                    </button>
                    <button type="submit" className="edit-zone-modal__save">
                      Save Changes
                    </button>
                  </div>
                </form>
              </div>

              <div className="edit-zone-modal__user-panel">
                <div className="edit-zone-modal__section-header">
                  <h4>Assigned Users</h4>
                </div>

                <div className="edit-zone-user-form">
                  <label className="edit-zone-field">
                    <span>User Name</span>
                    <input
                      value={newUserForm.name}
                      onChange={(event) => handleNewUserChange('name', event.target.value)}
                    />
                  </label>

                  <label className="edit-zone-field">
                    <span>User ID</span>
                    <input
                      value={newUserForm.id}
                      onChange={(event) => handleNewUserChange('id', event.target.value)}
                    />
                  </label>

                  <label className="edit-zone-field">
                    <span>Role</span>
                    <input
                      value={newUserForm.role}
                      onChange={(event) => handleNewUserChange('role', event.target.value)}
                    />
                  </label>
                </div>

                <div className="edit-zone-modal__user-actions">
                  <button type="button" className="edit-zone-modal__action-btn" onClick={handleAddUserOneByOne}>
                    + Add User One by One
                  </button>
                  <button type="button" className="edit-zone-modal__action-btn edit-zone-modal__action-btn--secondary" onClick={handleImportUserFile}>
                    ⤴ Import User File
                  </button>
                </div>

                <input id="zone-user-file-input" type="file" className="edit-zone-modal__file-input" />

                <div className="edit-zone-table-wrap">
                  <table className="edit-zone-table">
                    <thead>
                      <tr>
                        <th>User Name</th>
                        <th>User ID</th>
                        <th>Role</th>
                      </tr>
                    </thead>
                    <tbody>
                      {addedUsers.length === 0 ? (
                        <tr>
                          <td colSpan="3" className="edit-zone-table__empty">
                            No users added yet
                          </td>
                        </tr>
                      ) : (
                        addedUsers.map((user) => (
                          <tr key={`${user.id}-${user.name}`}>
                            <td>{user.name}</td>
                            <td>{user.id}</td>
                            <td>{user.role}</td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
