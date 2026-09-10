import React, { useState, useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import { 
  ShieldAlert, 
  Video, 
  Bell, 
  CheckCircle2, 
  AlertTriangle, 
  Volume2, 
  VolumeX, 
  Radio,
  Clock,
  Loader2,
} from 'lucide-react';
import WebRtcPlayer from '../../components/video/WebRtcPlayer';
import { fetchCameras } from '../../services/cameraService';
import '../../styles/GuardDashboardPage.css';

export default function GuardDashboardPage() {
  const [selectedCamera, setSelectedCamera] = useState('');
  const [cameraLayout, setCameraLayout] = useState(1);
  const [soundEnabled, setSoundEnabled] = useState(true);
  const [wsConnected, setWsConnected] = useState(false);
  const [activeAlerts, setActiveAlerts] = useState([]);
  const [cameraList, setCameraList] = useState([]);
  const [camerasLoading, setCamerasLoading] = useState(true);

  // Tải danh sách camera đang hoạt động từ Backend CSDL
  useEffect(() => {
    async function loadActiveCameras() {
      setCamerasLoading(true);
      try {
        const response = await fetchCameras({ page: 0, size: 50, status: 'ACTIVE' });
        const list = (response?.content || []).map((cam) => ({
          id: cam.id,
          code: (cam.cameraCode || '').toLowerCase().replace(/[^a-z0-9_-]/g, '').trim(),
          cameraCode: cam.cameraCode,
          name: cam.name,
          zone: 'Khuôn viên trường',
          status: cam.operationalStatus || 'ONLINE'
        }));
        setCameraList(list);
        if (list.length > 0) {
          setSelectedCamera((prev) => (prev && list.some((c) => c.code === prev) ? prev : list[0].code));
        }
      } catch (err) {
        console.error('Không thể tải danh sách camera:', err);
      } finally {
        setCamerasLoading(false);
      }
    }

    loadActiveCameras();
  }, []);

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

  const cameraLayoutOptions = [
    { value: 1, label: '1' },
    { value: 4, label: '2x2' },
    { value: 16, label: '4x4' },
    { value: 64, label: '8x8' }
  ];

  const visibleCameras = cameraLayout === 1
    ? [cameraList.find((camera) => camera.code === selectedCamera) || cameraList[0]].filter(Boolean)
    : cameraList.slice(0, cameraLayout);

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
          <div className="camera-layout-toolbar">
            <div className="camera-layout-label">
              <Video size={16} />
              <span>Chế độ hiển thị:</span>
            </div>
            <div className="camera-layout-options" role="group" aria-label="Số camera hiển thị">
              {cameraLayoutOptions.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={`camera-layout-option ${cameraLayout === option.value ? 'active' : ''}`}
                  onClick={() => setCameraLayout(option.value)}
                  aria-pressed={cameraLayout === option.value}
                >
                  {option.label}
                </button>
              ))}
            </div>
            <span className="camera-availability-count">
              {cameraList.length} camera khả dụng
            </span>
          </div>

          <div className={`camera-grid camera-grid--${cameraLayout}`}>
            {camerasLoading ? (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '320px', color: '#64748b', gap: '8px' }}>
                <Loader2 className="animate-spin" size={24} />
                <span>Đang tải danh sách camera...</span>
              </div>
            ) : visibleCameras.length === 0 ? (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '320px', color: '#64748b', gap: '8px' }}>
                <Video size={40} style={{ opacity: 0.4 }} />
                <span>Chưa có camera nào đang hoạt động</span>
              </div>
            ) : (
              visibleCameras.map((camera) => (
                <div className="video-viewport-card" key={camera.code}>
                  <WebRtcPlayer
                    streamPath={camera.code}
                    host="localhost:8889"
                    cameraName={camera.name}
                  />
                </div>
              ))
            )}
          </div>

          {/* Camera Selection Switcher */}
          <div className="camera-switcher-bar">
            <div className="switcher-label">
              <Video size={16} />
              <span>Chuyển luồng Camera:</span>
            </div>
            <div className="camera-tabs-list">
              {camerasLoading ? (
                <span style={{ fontSize: '13px', color: '#64748b' }}>Đang tải...</span>
              ) : cameraList.length === 0 ? (
                <span style={{ fontSize: '13px', color: '#64748b' }}>Không có camera</span>
              ) : (
                cameraList.map((cam) => (
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
                ))
              )}
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
