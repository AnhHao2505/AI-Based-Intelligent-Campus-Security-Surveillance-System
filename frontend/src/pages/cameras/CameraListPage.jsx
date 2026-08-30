import { Camera, ShieldAlert, Bell, Activity, SlidersHorizontal } from 'lucide-react';
import './CameraListPage.css';

const cameras = [
  { id: 'CAM_01', name: 'Front Gate Entry', quality: '91%', fps: '30 FPS', bitrate: '4G0 Mbps', status: 'ACTIVE' },
  { id: 'CAM_02', name: 'Parking Lot A', quality: '91%', fps: '30 FPS', bitrate: '4G0 Mbps', status: 'ACTIVE' },
  { id: 'CAM_03', name: 'Block B Corridor', quality: '91%', fps: '30 FPS', bitrate: '4G0 Mbps', status: 'ACTIVE' },
  { id: 'CAM_04', name: 'Server Room Entry', quality: '91%', fps: '30 FPS', bitrate: '4G0 Mbps', status: 'ACTIVE' },
];

const streamDirectory = [
  { name: 'CAM_01', label: 'Front Gate Entry', state: 'ACTIVE' },
  { name: 'CAM_02', label: 'Parking Lot A', state: 'ACTIVE' },
  { name: 'CAM_03', label: 'Block B Corridor', state: 'ACTIVE' },
  { name: 'CAM_04', label: 'Server Room Entry', state: 'ACTIVE' },
  { name: 'CAM_05', label: 'Backyard Perimeter', state: 'STANDBY' },
];

const auditLog = [
  { text: 'CAM_04: Intruder bouncing got triggered', level: 'CRITICAL' },
  { text: 'CAM_09: reconnect sequence completed', level: 'INFO' },
  { text: 'Primary disk array temperature warning', level: 'WARNING' },
  { text: 'Admin session initialized by M. Andrew', level: 'INFO' },
];

export default function CameraListPage() {
  return (
    <div className="camera-console-page">
      <div className="camera-console">
        <header className="camera-console__topbar">
          <div className="camera-console__title-wrap">
            <div className="camera-console__title-icon">
              <Camera size={18} />
            </div>
            <span className="camera-console__title">Surveillance Operations Console</span>
            <span className="camera-console__system-tag">SYSTEM ADMIN</span>
          </div>

          <div className="camera-console__header-right">
            <div className="camera-console__status-pill">
              <span className="dot dot--green" />
              AI Engine: ONLINE
            </div>
            <div className="camera-console__status-pill">
              <span className="dot dot--green" />
              Active Cameras: 12 of 12
            </div>
            <div className="camera-console__status-pill">
              <span className="dot dot--amber" />
              Storage: 88%
            </div>
            <div className="camera-console__user-box">
              <span className="camera-console__user-avatar">M</span>
              <span className="camera-console__user-name">M. Andrew</span>
            </div>
          </div>
        </header>

        <main className="camera-console__content">
          <section className="camera-console__feed-grid">
            {cameras.map((camera, index) => (
              <article
                key={camera.id}
                className={`camera-feed ${index === 3 ? 'camera-feed--alert' : ''}`}
              >
                <div className="camera-feed__header">
                  <div className="camera-feed__label-wrap">
                    <span className="camera-feed__dot" />
                    <span>{camera.id}</span>
                    <span className="camera-feed__name">{camera.name}</span>
                  </div>
                  <span className="camera-feed__quality">{camera.quality}</span>
                </div>

                <div className="camera-feed__screen">
                  <div className="camera-feed__screen-no-signal">
                    <span>NO SIGNAL</span>
                  </div>
                </div>

                <div className="camera-feed__footer">
                  <span>{camera.fps}</span>
                  <span>{camera.bitrate}</span>
                  <span>{camera.status}</span>
                </div>
              </article>
            ))}
          </section>

          <aside className="camera-console__sidebar">
            <div className="sidebar-card">
              <h3>Stream Directory</h3>
              <ul>
                {streamDirectory.map((entry) => (
                  <li key={entry.name} className={entry.state === 'STANDBY' ? 'is-standby' : ''}>
                    <span className="checkbox" />
                    <span>{entry.name}</span>
                    <span className="sidebar-card__label">{entry.label}</span>
                    <span className={`state-tag ${entry.state === 'STANDBY' ? 'state-tag--standby' : 'state-tag--active'}`}>
                      {entry.state}
                    </span>
                  </li>
                ))}
              </ul>
            </div>

            <div className="sidebar-card sidebar-card--alert">
              <div className="sidebar-card__header">
                <h3>Critical Audit Log</h3>
                <span className="sidebar-card__icon"><Bell size={14} /></span>
              </div>

              <ul className="audit-list">
                {auditLog.map((entry, index) => (
                  <li key={`${entry.text}-${index}`}>
                    <span className="audit-list__text">{entry.text}</span>
                    <span className={`audit-list__tag audit-list__tag--${entry.level.toLowerCase()}`}>
                      {entry.level}
                    </span>
                  </li>
                ))}
              </ul>
            </div>

            <div className="sidebar-card sidebar-card--actuators">
              <h3>Camera PT Actuators</h3>
              <div className="actuator-buttons">
                <button className="actuator-btn actuator-btn--primary">
                  <Activity size={14} />
                  Auto Focus
                </button>
                <button className="actuator-btn actuator-btn--secondary">
                  <SlidersHorizontal size={14} />
                  Reset PTZ
                </button>
              </div>
            </div>
          </aside>
        </main>

        <footer className="camera-console__footer">
          <button className="footer-btn footer-btn--danger">
            <ShieldAlert size={14} />
            Re-Call All
          </button>

          <div className="footer-grid">
            <span>GRID LAYOUT</span>
            <span className="footer-grid__dims">2 × 2</span>
            <span className="footer-grid__view">3 × 3</span>
          </div>
        </footer>
      </div>
    </div>
  );
}
