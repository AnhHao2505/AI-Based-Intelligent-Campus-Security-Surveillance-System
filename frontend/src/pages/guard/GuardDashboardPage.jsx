import React, { useState, useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import { 
  ShieldAlert, 
  Video, 
  Bell, 
  CheckCircle2, 
  AlertTriangle, 
  Maximize2, 
  Volume2, 
  VolumeX, 
  Radio,
  Eye,
  Clock,
  UserCheck
} from 'lucide-react';
import WebRtcPlayer from '../../components/video/WebRtcPlayer';
import '../../styles/GuardDashboardPage.css';

export default function GuardDashboardPage() {
  const [selectedCamera, setSelectedCamera] = useState('cam01');
  const [soundEnabled, setSoundEnabled] = useState(true);
  const [wsConnected, setWsConnected] = useState(false);
  const [activeAlerts, setActiveAlerts] = useState([]);
  const [cameraList] = useState([
    { code: 'cam01', name: 'Camera 01 - Cửa Server (Phone Live)', zone: 'Tòa Alpha Tầng 2', status: 'ONLINE' },
    { code: 'cam02', name: 'Camera 02 - Cổng CSVC Vùng Cấm', zone: 'Khu CSVC Tân Uyên', status: 'STANDBY' },
    { code: 'cam03', name: 'Camera 03 - Kho Thiết Bị Lab AI', zone: 'Tòa Beta Tầng 1', status: 'STANDBY' }
  ]);

  // Âm thanh cảnh báo
  const playAlertSound = () => {
    if (!soundEnabled) return;
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(800, audioCtx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(400, audioCtx.currentTime + 0.3);
      gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
      gain.gain.linearRampToValueAtTime(0.01, audioCtx.currentTime + 0.3);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start();
      osc.stop(audioCtx.currentTime + 0.3);
    } catch (e) {
      console.warn('Audio Context không được phép tự động phát:', e);
    }
  };

  // Lắng nghe sự kiện cảnh báo an ninh qua WebSocket STOMP từ Backend
  useEffect(() => {
    const stompClient = new Client({
      brokerURL: 'ws://localhost:8080/ws-security',
      reconnectDelay: 4000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log('✅ [WebSocket] Đã kết nối STOMP tới Backend Spring Boot!');
        setWsConnected(true);

        stompClient.subscribe('/topic/security-alerts', (message) => {
          if (message.body) {
            try {
              const incident = JSON.parse(message.body);
              console.log('🔔 [WebSocket Alert Received]:', incident);

              // Chuẩn hóa đường dẫn ảnh chứng cứ từ MinIO
              let snapshot = incident.image_url || '';
              if (snapshot.includes('minio:9000')) {
                snapshot = snapshot.replace('minio:9000', 'localhost:9000');
              } else if (snapshot.startsWith('/storage/')) {
                snapshot = `http://localhost:9000/security-evidence/${snapshot.replace('/storage/', '')}`;
              }

              const newAlert = {
                id: incident.event_id || Date.now(),
                cameraCode: incident.camera_code || 'CAM-001',
                cameraName: 'Cửa Phòng Server (Điện thoại Live)',
                eventType: incident.event_type || 'LOITERING_UNIDENTIFIED',
                message: incident.details || 'Phát hiện đối tượng khả nghi trong vùng cấm',
                duration: incident.duration_seconds ? `${incident.duration_seconds}s` : 'Vừa phát hiện',
                timestamp: new Date().toLocaleTimeString('vi-VN'),
                status: 'PENDING',
                snapshotUrl: snapshot
              };

              setActiveAlerts((prev) => [newAlert, ...prev]);
              playAlertSound();
            } catch (err) {
              console.error('Lỗi parse incident JSON:', err);
            }
          }
        });
      },
      onDisconnect: () => {
        setWsConnected(false);
      },
      onStompError: (frame) => {
        console.warn('STOMP error:', frame.headers['message']);
        setWsConnected(false);
      }
    });

    stompClient.activate();

    return () => {
      stompClient.deactivate();
    };
  }, [soundEnabled]);

  // Xác nhận xử lý cảnh báo
  const handleAcknowledge = (id) => {
    setActiveAlerts((prev) =>
      prev.map((a) => (a.id === id ? { ...a, status: 'RESOLVED' } : a))
    );
  };

  // Nút test bắn cảnh báo giả lập
  const handleSimulateAlert = () => {
    playAlertSound();
    const newAlert = {
      id: Date.now(),
      cameraCode: 'CAM-001',
      cameraName: 'Cửa Phòng Server (Điện thoại Live)',
      eventType: 'UNAUTHORIZED_ACCESS',
      message: 'CẢNH BÁO: Người lạ bước vào vùng cấm phòng Server!',
      duration: '5.0s',
      timestamp: new Date().toLocaleTimeString('vi-VN'),
      status: 'PENDING',
      snapshotUrl: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&auto=format&fit=crop&q=80'
    };
    setActiveAlerts((prev) => [newAlert, ...prev]);
  };

  return (
    <div className="guard-dashboard-container">
      {/* Top Status Banner */}
      <div className="guard-top-header">
        <div className="guard-title-box">
          <div className="live-pulse-badge">
            <Radio className="pulse-icon" size={18} />
            <span>LIVE MONITORING</span>
          </div>
          <h2>Bảng Giám Sát An Ninh Trực Tiếp (Guard Console)</h2>
        </div>

        <div className="guard-header-actions">
          <span className={`ws-status-chip ${wsConnected ? 'connected' : 'disconnected'}`}>
            <span className="dot" />
            {wsConnected ? 'WebSocket Live' : 'Đang kết nối Server...'}
          </span>

          <button 
            type="button" 
            className={`btn-sound-toggle ${soundEnabled ? 'active' : ''}`}
            onClick={() => setSoundEnabled(!soundEnabled)}
            title={soundEnabled ? 'Tắt âm thanh chuông' : 'Bật âm thanh chuông'}
          >
            {soundEnabled ? <Volume2 size={18} /> : <VolumeX size={18} />}
            <span>{soundEnabled ? 'Chuông: BẬT' : 'Chuông: TẮT'}</span>
          </button>

          <button 
            type="button" 
            className="btn-sim-alert"
            onClick={handleSimulateAlert}
            title="Bắn cảnh báo test để kiểm tra chuông và popup"
          >
            <Bell size={16} />
            <span>Test Báo Động</span>
          </button>
        </div>
      </div>

      {/* Main Grid Layout */}
      <div className="guard-main-grid">
        {/* Left Column: Live Video Surveillance */}
        <div className="video-surveillance-panel">
          <div className="video-viewport-card">
            <WebRtcPlayer
              streamPath={selectedCamera}
              host="localhost:8889"
              cameraName={cameraList.find((c) => c.code === selectedCamera)?.name || 'Camera Live'}
            />
          </div>

          {/* Camera Selection Switcher */}
          <div className="camera-switcher-bar">
            <div className="switcher-label">
              <Video size={16} />
              <span>Chuyển luồng Camera:</span>
            </div>
            <div className="camera-tabs-list">
              {cameraList.map((cam) => (
                <button
                  key={cam.code}
                  type="button"
                  className={`cam-tab-btn ${selectedCamera === cam.code ? 'active' : ''}`}
                  onClick={() => setSelectedCamera(cam.code)}
                >
                  <span className={`status-dot ${cam.status === 'ONLINE' ? 'online' : 'standby'}`} />
                  <span className="cam-tab-code">{cam.code.toUpperCase()}</span>
                  <span className="cam-tab-name">{cam.name}</span>
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Right Column: Real-time Incident Feed */}
        <div className="incident-alerts-panel">
          <div className="panel-header">
            <div className="panel-title">
              <ShieldAlert size={20} className="alert-header-icon" />
              <h3>Sự Kiện Cảnh Báo An Ninh</h3>
            </div>
            <span className="incident-count-chip">
              {activeAlerts.filter((a) => a.status === 'PENDING').length} Chưa xử lý
            </span>
          </div>

          <div className="incident-list-scroll">
            {activeAlerts.length === 0 ? (
              <div className="empty-incident-box">
                <CheckCircle2 size={40} className="check-ok-icon" />
                <p>Khu vực an toàn</p>
                <span>Chưa phát hiện vi phạm nào trong vùng cấm</span>
              </div>
            ) : (
              activeAlerts.map((alert) => (
                <div
                  key={alert.id}
                  className={`incident-alert-card ${alert.status === 'PENDING' ? 'pending' : 'resolved'}`}
                >
                  <div className="incident-card-top">
                    <div className="incident-badge">
                      <AlertTriangle size={14} />
                      <span>{alert.eventType}</span>
                    </div>
                    <span className="incident-time">
                      <Clock size={12} /> {alert.timestamp}
                    </span>
                  </div>

                  <p className="incident-message">{alert.message}</p>

                  <div className="incident-details-row">
                    <span className="incident-meta">
                      <strong>Camera:</strong> {alert.cameraCode}
                    </span>
                    <span className="incident-meta">
                      <strong>Lưu trú:</strong> {alert.duration}
                    </span>
                  </div>

                  {/* Snapshot Evidence Thumbnail */}
                  {alert.snapshotUrl && (
                    <div className="incident-snapshot-box">
                      <img src={alert.snapshotUrl} alt="Bằng chứng vi phạm" />
                      <span className="snapshot-tag">Ảnh Chụp MinIO</span>
                    </div>
                  )}

                  <div className="incident-action-box">
                    {alert.status === 'PENDING' ? (
                      <button
                        type="button"
                        className="btn-ack-incident"
                        onClick={() => handleAcknowledge(alert.id)}
                      >
                        <CheckCircle2 size={16} />
                        <span>Xác Nhận Đã Xử Lý</span>
                      </button>
                    ) : (
                      <span className="resolved-status-tag">
                        <CheckCircle2 size={14} /> Đã xử lý & ghi log
                      </span>
                    )}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
