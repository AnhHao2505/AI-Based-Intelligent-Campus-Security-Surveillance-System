import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Settings,
  Video,
  Cpu,
  Activity,
  Info,
  Save,
  Loader2,
  Power,
  PowerOff,
  ChevronLeft,
  ChevronRight,
  HelpCircle,
} from "lucide-react";
import {
  fetchCameraDetail,
  updateCamera,
  decommissionCamera,
  reactivateCamera,
  upsertSpecification,
  upsertStreamConfig,
  upsertAIConfig,
  fetchHealthLogs,
} from "../../services/cameraService";
import "../../styles/CameraDetailPage.css";

export default function CameraDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  // Tabs: 'general' | 'specification' | 'stream' | 'ai'
  const [activeTab, setActiveTab] = useState("general");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);

  // Health Logs State
  const [logs, setLogs] = useState([]);
  const [logPage, setLogPage] = useState(0);
  const [logTotalPages, setLogTotalPages] = useState(0);
  const [logsLoading, setLogsLoading] = useState(false);

  // Form states
  const [camera, setCamera] = useState(null);
  const [generalForm, setGeneralForm] = useState({
    name: "",
    mountingHeight: "",
    orientation: "",
    tiltAngle: "",
    installedAt: "",
  });

  const [specForm, setSpecForm] = useState({
    manufacturer: "",
    model: "",
    serialNumber: "",
    resolution: "",
    fps: "",
    lens: "",
    focalLength: "",
    fieldOfView: "",
    nightVision: false,
    ptzSupported: false,
    weatherProof: false,
    firmwareVersion: "",
  });

  const [streamForm, setStreamForm] = useState({
    protocol: "RTSP",
    host: "",
    port: "",
    username: "",
    credentialRef: "",
    mainStreamPath: "",
    subStreamPath: "",
    retryTimeBeforeAlerting: 3,
    timeoutMs: 5000,
  });

  const [aiForm, setAiForm] = useState({
    personDetectionEnabled: false,
    faceRecognitionEnabled: false,
    faceMatchThreshold: 0.8,
    inferenceFps: 5,
  });

  const loadCameraDetails = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchCameraDetail(id);
      setCamera(data);

      // Init General Form
      setGeneralForm({
        name: data.name || "",
        mountingHeight:
          data.mountingHeight !== null && data.mountingHeight !== undefined
            ? data.mountingHeight.toString()
            : "",
        orientation:
          data.orientation !== null && data.orientation !== undefined
            ? data.orientation.toString()
            : "",
        tiltAngle:
          data.tiltAngle !== null && data.tiltAngle !== undefined
            ? data.tiltAngle.toString()
            : "",
        installedAt: data.installedAt ? data.installedAt.substring(0, 16) : "", // format for datetime-local
      });

      // Init Specification Form
      if (data.specification) {
        setSpecForm({
          manufacturer: data.specification.manufacturer || "",
          model: data.specification.model || "",
          serialNumber: data.specification.serialNumber || "",
          resolution: data.specification.resolution || "",
          fps:
            data.specification.fps !== null &&
              data.specification.fps !== undefined
              ? data.specification.fps.toString()
              : "",
          lens: data.specification.lens || "",
          focalLength: data.specification.focalLength || "",
          fieldOfView:
            data.specification.fieldOfView !== null &&
              data.specification.fieldOfView !== undefined
              ? data.specification.fieldOfView.toString()
              : "",
          nightVision: !!data.specification.nightVision,
          ptzSupported: !!data.specification.ptzSupported,
          weatherProof: !!data.specification.weatherProof,
          firmwareVersion: data.specification.firmwareVersion || "",
        });
      }

      // Init Stream Form
      if (data.streamConfig) {
        setStreamForm({
          protocol: data.streamConfig.protocol || "RTSP",
          host: data.streamConfig.host || "",
          port:
            data.streamConfig.port !== null &&
              data.streamConfig.port !== undefined
              ? data.streamConfig.port.toString()
              : "",
          username: data.streamConfig.username || "",
          credentialRef: data.streamConfig.credentialRef || "",
          mainStreamPath: data.streamConfig.mainStreamPath || "",
          subStreamPath: data.streamConfig.subStreamPath || "",
          retryTimeBeforeAlerting:
            data.streamConfig.retryTimeBeforeAlerting !== null &&
            data.streamConfig.retryTimeBeforeAlerting !== undefined
              ? data.streamConfig.retryTimeBeforeAlerting
              : 3,
          timeoutMs: data.streamConfig.timeoutMs || 5000,
        });
      }

      // Init AI Form
      if (data.aiConfig) {
        setAiForm({
          personDetectionEnabled: !!data.aiConfig.personDetectionEnabled,
          faceRecognitionEnabled: !!data.aiConfig.faceRecognitionEnabled,
          faceMatchThreshold: data.aiConfig.faceMatchThreshold || 0.8,
          inferenceFps: data.aiConfig.inferenceFps || 5,
        });
      }
    } catch (err) {
      console.error("Failed to load camera details:", err);
      setError("Lỗi tải thông tin chi tiết camera.");
    } finally {
      setLoading(false);
    }
  };

  const loadLogs = async () => {
    setLogsLoading(true);
    try {
      const data = await fetchHealthLogs(id, { page: logPage, size: 5 });
      setLogs(data.content || []);
      setLogTotalPages(data.totalPages || 0);
    } catch (err) {
      console.error("Failed to load health logs:", err);
    } finally {
      setLogsLoading(false);
    }
  };

  useEffect(() => {
    loadCameraDetails();
  }, [id]);

  useEffect(() => {
    loadLogs();
  }, [id, logPage]);

  const showNotification = (msg) => {
    setSuccessMsg(msg);
    setTimeout(() => setSuccessMsg(null), 4000);
  };

  const handleToggleStatus = async () => {
    if (!camera) return;
    setSaving(true);
    try {
      let updated;
      if (camera.status === "DECOMMISSIONED") {
        updated = await reactivateCamera(camera.id);
        showNotification("Đã kích hoạt lại camera thành công");
      } else {
        updated = await decommissionCamera(camera.id);
        showNotification("Đã dừng hoạt động camera thành công");
      }
      setCamera(updated);
    } catch (err) {
      setError(err.message || "Thay đổi trạng thái thất bại.");
    } finally {
      setSaving(false);
    }
  };

  const handleGeneralSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const payload = {
        name: generalForm.name,
        mountingHeight: generalForm.mountingHeight
          ? parseFloat(generalForm.mountingHeight)
          : null,
        orientation: generalForm.orientation
          ? parseFloat(generalForm.orientation)
          : null,
        tiltAngle: generalForm.tiltAngle
          ? parseFloat(generalForm.tiltAngle)
          : null,
        installedAt: generalForm.installedAt
          ? new Date(generalForm.installedAt).toISOString()
          : null,
      };

      const updated = await updateCamera(id, payload);
      setCamera(updated);
      showNotification("Đã lưu thông tin chung thành công");
    } catch (err) {
      setError(err.message || "Lỗi lưu thông tin chung");
    } finally {
      setSaving(false);
    }
  };

  const handleSpecSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const payload = {
        manufacturer: specForm.manufacturer || null,
        model: specForm.model || null,
        serialNumber: specForm.serialNumber || null,
        resolution: specForm.resolution || null,
        fps: specForm.fps ? parseInt(specForm.fps, 10) : null,
        lens: specForm.lens || null,
        focalLength: specForm.focalLength || null,
        fieldOfView: specForm.fieldOfView
          ? parseFloat(specForm.fieldOfView)
          : null,
        nightVision: specForm.nightVision,
        ptzSupported: specForm.ptzSupported,
        weatherProof: specForm.weatherProof,
        firmwareVersion: specForm.firmwareVersion || null,
      };

      await upsertSpecification(id, payload);
      showNotification("Đã lưu đặc tả kỹ thuật thành công");
    } catch (err) {
      setError(err.message || "Lỗi lưu đặc tả kỹ thuật");
    } finally {
      setSaving(false);
    }
  };

  const handleStreamSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const payload = {
        protocol: streamForm.protocol,
        host: streamForm.host,
        port: parseInt(streamForm.port, 10),
        username: streamForm.username || null,
        credentialRef: streamForm.credentialRef || null,
        mainStreamPath: streamForm.mainStreamPath,
        subStreamPath: streamForm.subStreamPath || null,
        retryTimeBeforeAlerting: streamForm.retryTimeBeforeAlerting
          ? parseInt(streamForm.retryTimeBeforeAlerting, 10)
          : null,
        timeoutMs: streamForm.timeoutMs
          ? parseInt(streamForm.timeoutMs, 10)
          : null,
      };

      await upsertStreamConfig(id, payload);
      showNotification("Đã lưu cấu hình Stream thành công");
    } catch (err) {
      setError(err.message || "Lỗi lưu cấu hình stream");
    } finally {
      setSaving(false);
    }
  };

  const handleAISubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const payload = {
        personDetectionEnabled: aiForm.personDetectionEnabled,
        faceRecognitionEnabled: aiForm.faceRecognitionEnabled,
        faceMatchThreshold: parseFloat(aiForm.faceMatchThreshold),
        inferenceFps: parseInt(aiForm.inferenceFps, 10),
      };

      await upsertAIConfig(id, payload);
      showNotification("Đã lưu cấu hình AI thành công");
    } catch (err) {
      setError(err.message || "Lỗi lưu cấu hình AI");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="detail-loading-state">
        <Loader2
          className="animate-spin text-blue"
          size={48}
        />
        <span>Đang tải thông tin camera...</span>
      </div>
    );
  }

  if (!camera) {
    return (
      <div className="detail-error-state">
        <h2>Không tìm thấy dữ liệu</h2>
        <button
          onClick={() => navigate("/cameras")}
          className="btn-back"
        >
          <ArrowLeft size={16} /> Quay lại danh sách
        </button>
      </div>
    );
  }

  const isDecommissioned = camera.status === "DECOMMISSIONED";

  return (
    <div className="camera-detail-page">
      {/* Background Ambience */}
      <div className="camera-ambient">
        <div className="camera-ambient__orb camera-ambient__orb--1" />
        <div className="camera-ambient__orb camera-ambient__orb--2" />
      </div>

      {/* Breadcrumb & Navigation */}
      <div className="breadcrumb">
        <button
          onClick={() => navigate("/cameras")}
          className="btn-back"
        >
          <ArrowLeft size={16} />
          <span>Quản lý camera</span>
        </button>
        <span className="separator">/</span>
        <span className="current">{camera.cameraCode}</span>
      </div>

      {successMsg && <div className="success-toast">{successMsg}</div>}
      {error && <div className="error-banner">{error}</div>}

      {/* Main Details Header */}
      <div className="detail-header-card">
        <div className="detail-header-left">
          <div className="camera-icon-wrapper">
            <Video size={28} />
          </div>
          <div>
            <div className="detail-title-row">
              <h2>{camera.name}</h2>
              <span className="font-mono code-tag">{camera.cameraCode}</span>
            </div>
            <div className="detail-badges-row">
              <span
                className={`status-badge badge-${camera.status.toLowerCase()}`}
              >
                {camera.status === "ACTIVE" ? "Đang chạy" : "Đã tắt"}
              </span>
              <span
                className={`status-badge op-badge-${camera.operationalStatus.toLowerCase()}`}
              >
                ● {camera.operationalStatus}
              </span>
              {camera.floor && (
                <span className="location-tag">Tầng {camera.floor}</span>
              )}
              {camera.zoneName && (
                <span className="location-tag">{camera.zoneName}</span>
              )}
            </div>
          </div>
        </div>

        <button
          className={`btn-toggle-status ${isDecommissioned ? "btn-status-active" : "btn-status-decommission"}`}
          onClick={handleToggleStatus}
          disabled={saving}
        >
          {isDecommissioned ? <Power size={18} /> : <PowerOff size={18} />}
          <span>{isDecommissioned ? "Bật Camera" : "Tắt Camera"}</span>
        </button>
      </div>

      {/* Configurations Tabs Grid */}
      <div className="detail-grid">
        <div className="config-card">
          <div className="tabs-navigation">
            <button
              className={`tab-btn ${activeTab === "general" ? "tab-btn--active" : ""}`}
              onClick={() => setActiveTab("general")}
            >
              <Info size={16} />
              <span>Thông tin chung</span>
            </button>
            <button
              className={`tab-btn ${activeTab === "specification" ? "tab-btn--active" : ""}`}
              onClick={() => setActiveTab("specification")}
            >
              <Settings size={16} />
              <span>Đặc tả kỹ thuật</span>
            </button>
            <button
              className={`tab-btn ${activeTab === "stream" ? "tab-btn--active" : ""}`}
              onClick={() => setActiveTab("stream")}
            >
              <Video size={16} />
              <span>Cấu hình Stream</span>
            </button>
            <button
              className={`tab-btn ${activeTab === "ai" ? "tab-btn--active" : ""}`}
              onClick={() => setActiveTab("ai")}
            >
              <Cpu size={16} />
              <span>Cấu hình AI</span>
            </button>
          </div>

          <div className="tab-content">
            {/* GENERAL TAB */}
            {activeTab === "general" && (
              <form
                onSubmit={handleGeneralSubmit}
                className="tab-form"
              >
                <div className="form-grid">
                  <div className="form-group col-span-2">
                    <label>Tên Camera *</label>
                    <input
                      type="text"
                      value={generalForm.name}
                      onChange={(e) =>
                        setGeneralForm({ ...generalForm, name: e.target.value })
                      }
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label>Chiều cao lắp đặt (m)</label>
                    <input
                      type="number"
                      step="0.1"
                      value={generalForm.mountingHeight}
                      onChange={(e) =>
                        setGeneralForm({
                          ...generalForm,
                          mountingHeight: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "0.25rem",
                      }}
                    >
                      Góc quay (độ)
                      <span data-tooltip="Góc hướng quay của camera (0-360)" className="help-icon-wrapper">
                        <HelpCircle size={14} className="help-icon" />
                      </span>
                    </label>
                    <input
                      type="number"
                      step="0.1"
                      value={generalForm.orientation}
                      onChange={(e) =>
                        setGeneralForm({
                          ...generalForm,
                          orientation: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "0.25rem",
                      }}
                    >
                      Góc nghiêng (độ)
                      <span data-tooltip="Góc nghiêng vật lý của camera" className="help-icon-wrapper">
                        <HelpCircle size={14} className="help-icon" />
                      </span>
                    </label>
                    <input
                      type="number"
                      step="0.1"
                      value={generalForm.tiltAngle}
                      onChange={(e) =>
                        setGeneralForm({
                          ...generalForm,
                          tiltAngle: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Ngày lắp đặt</label>
                    <input
                      type="datetime-local"
                      value={generalForm.installedAt}
                      onChange={(e) =>
                        setGeneralForm({
                          ...generalForm,
                          installedAt: e.target.value,
                        })
                      }
                    />
                  </div>
                </div>
                <div className="form-actions">
                  <button
                    type="submit"
                    className="btn-save"
                    disabled={saving}
                  >
                    {saving ? (
                      <Loader2
                        className="animate-spin"
                        size={16}
                      />
                    ) : (
                      <Save size={16} />
                    )}
                    <span>Lưu thông tin</span>
                  </button>
                </div>
              </form>
            )}

            {/* SPECIFICATION TAB */}
            {activeTab === "specification" && (
              <form
                onSubmit={handleSpecSubmit}
                className="tab-form"
              >
                <div className="form-grid">
                  <div className="form-group">
                    <label>Nhà sản xuất</label>
                    <input
                      type="text"
                      placeholder="Hikvision, Dahua..."
                      value={specForm.manufacturer}
                      onChange={(e) =>
                        setSpecForm({
                          ...specForm,
                          manufacturer: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Dòng máy (Model)</label>
                    <input
                      type="text"
                      placeholder="DS-2CD2T47G2-L"
                      value={specForm.model}
                      onChange={(e) =>
                        setSpecForm({ ...specForm, model: e.target.value })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Số Serial (S/N)</label>
                    <input
                      type="text"
                      placeholder="SN123456789"
                      value={specForm.serialNumber}
                      onChange={(e) =>
                        setSpecForm({
                          ...specForm,
                          serialNumber: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Độ phân giải</label>
                    <input
                      type="text"
                      placeholder="1920x1080, 2688x1520"
                      value={specForm.resolution}
                      onChange={(e) =>
                        setSpecForm({ ...specForm, resolution: e.target.value })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Khung hình/giây (FPS)</label>
                    <input
                      type="number"
                      placeholder="25 hoặc 30"
                      value={specForm.fps}
                      onChange={(e) =>
                        setSpecForm({ ...specForm, fps: e.target.value })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Loại ống kính (Lens)</label>
                    <input
                      type="text"
                      placeholder="2.8mm fixed"
                      value={specForm.lens}
                      onChange={(e) =>
                        setSpecForm({ ...specForm, lens: e.target.value })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Tiêu cự (Focal Length)</label>
                    <input
                      type="text"
                      placeholder="2.8mm"
                      value={specForm.focalLength}
                      onChange={(e) =>
                        setSpecForm({
                          ...specForm,
                          focalLength: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Góc nhìn (Field of View - FoV độ)</label>
                    <input
                      type="number"
                      step="0.1"
                      placeholder="107"
                      value={specForm.fieldOfView}
                      onChange={(e) =>
                        setSpecForm({
                          ...specForm,
                          fieldOfView: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Firmware Version</label>
                    <input
                      type="text"
                      placeholder="v5.7.11"
                      value={specForm.firmwareVersion}
                      onChange={(e) =>
                        setSpecForm({
                          ...specForm,
                          firmwareVersion: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group col-span-2 checkbox-row">
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={specForm.nightVision}
                        onChange={(e) =>
                          setSpecForm({
                            ...specForm,
                            nightVision: e.target.checked,
                          })
                        }
                      />
                      <span>Hỗ trợ hồng ngoại (Night Vision)</span>
                    </label>
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={specForm.ptzSupported}
                        onChange={(e) =>
                          setSpecForm({
                            ...specForm,
                            ptzSupported: e.target.checked,
                          })
                        }
                      />
                      <span>Hỗ trợ quay quét (PTZ)</span>
                    </label>
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={specForm.weatherProof}
                        onChange={(e) =>
                          setSpecForm({
                            ...specForm,
                            weatherProof: e.target.checked,
                          })
                        }
                      />
                      <span>Kháng nước/thời tiết (Weatherproof)</span>
                    </label>
                  </div>
                </div>
                <div className="form-actions">
                  <button
                    type="submit"
                    className="btn-save"
                    disabled={saving}
                  >
                    {saving ? (
                      <Loader2
                        className="animate-spin"
                        size={16}
                      />
                    ) : (
                      <Save size={16} />
                    )}
                    <span>Lưu đặc tả</span>
                  </button>
                </div>
              </form>
            )}

            {/* STREAM TAB */}
            {activeTab === "stream" && (
              <form
                onSubmit={handleStreamSubmit}
                className="tab-form"
              >
                <div className="form-grid">
                  <div className="form-group">
                    <label>Giao thức kết nối *</label>
                    <select
                      value={streamForm.protocol}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          protocol: e.target.value,
                        })
                      }
                    >
                      <option value="RTSP">RTSP</option>
                      <option value="RTMP">RTMP</option>
                      <option value="HTTP">HTTP</option>
                      <option value="HTTPS">HTTPS</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label>Địa chỉ IP/Host *</label>
                    <input
                      type="text"
                      placeholder="192.168.1.50"
                      value={streamForm.host}
                      onChange={(e) =>
                        setStreamForm({ ...streamForm, host: e.target.value })
                      }
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label>Cổng kết nối *</label>
                    <input
                      type="number"
                      placeholder="554"
                      value={streamForm.port}
                      onChange={(e) =>
                        setStreamForm({ ...streamForm, port: e.target.value })
                      }
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "0.25rem",
                      }}
                    >
                      Tài khoản camera
                      <span data-tooltip="Tài khoản đăng nhập của camera để xem stream" className="help-icon-wrapper">
                        <HelpCircle size={14} className="help-icon" />
                      </span>
                    </label>
                    <input
                      type="text"
                      placeholder="admin"
                      value={streamForm.username}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          username: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "0.25rem",
                      }}
                    >
                      Mã khoá xác thực (Credential Ref)
                      <span data-tooltip="Khoá bảo mật hoặc mật khẩu kết nối camera" className="help-icon-wrapper">
                        <HelpCircle size={14} className="help-icon" />
                      </span>
                    </label>
                    <input
                      type="text"
                      placeholder="mật khẩu camera hoặc khóa tham chiếu"
                      value={streamForm.credentialRef}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          credentialRef: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group col-span-2">
                    <label>Main Stream Path *</label>
                    <input
                      type="text"
                      placeholder="/Streaming/Channels/101"
                      value={streamForm.mainStreamPath}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          mainStreamPath: e.target.value,
                        })
                      }
                      required
                    />
                  </div>
                  <div className="form-group col-span-2">
                    <label>Sub Stream Path</label>
                    <input
                      type="text"
                      placeholder="/Streaming/Channels/102"
                      value={streamForm.subStreamPath}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          subStreamPath: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Số lần thử lại trước khi cảnh báo</label>
                    <input
                      type="number"
                      placeholder="3"
                      value={streamForm.retryTimeBeforeAlerting}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          retryTimeBeforeAlerting: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Thời gian chờ phản hồi (ms)</label>
                    <input
                      type="number"
                      placeholder="5000"
                      value={streamForm.timeoutMs}
                      onChange={(e) =>
                        setStreamForm({
                          ...streamForm,
                          timeoutMs: e.target.value,
                        })
                      }
                    />
                  </div>
                </div>
                <div className="form-actions">
                  <button
                    type="submit"
                    className="btn-save"
                    disabled={saving}
                  >
                    {saving ? (
                      <Loader2
                        className="animate-spin"
                        size={16}
                      />
                    ) : (
                      <Save size={16} />
                    )}
                    <span>Lưu luồng Stream</span>
                  </button>
                </div>
              </form>
            )}

            {/* AI CONFIG TAB */}
            {activeTab === "ai" && (
              <form
                onSubmit={handleAISubmit}
                className="tab-form"
              >
                <div className="form-grid">
                  <div className="form-group">
                    <label>
                      Ngưỡng khớp khuôn mặt (Face match threshold:{" "}
                      {aiForm.faceMatchThreshold})
                    </label>
                    <div className="slider-wrapper">
                      <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.05"
                        value={aiForm.faceMatchThreshold}
                        onChange={(e) =>
                          setAiForm({
                            ...aiForm,
                            faceMatchThreshold: e.target.value,
                          })
                        }
                      />
                    </div>
                  </div>
                  <div className="form-group">
                    <label>Inference FPS (Tốc độ xử lý AI) *</label>
                    <input
                      type="number"
                      value={aiForm.inferenceFps}
                      onChange={(e) =>
                        setAiForm({ ...aiForm, inferenceFps: e.target.value })
                      }
                      required
                    />
                  </div>
                  <div className="form-group col-span-2 checkbox-row">
                    <label className="checkbox-label toggle-switch">
                      <input
                        type="checkbox"
                        checked={aiForm.personDetectionEnabled}
                        onChange={(e) =>
                          setAiForm({
                            ...aiForm,
                            personDetectionEnabled: e.target.checked,
                          })
                        }
                      />
                      <span>Phát hiện người (Person Detection)</span>
                    </label>
                    <label className="checkbox-label toggle-switch">
                      <input
                        type="checkbox"
                        checked={aiForm.faceRecognitionEnabled}
                        onChange={(e) =>
                          setAiForm({
                            ...aiForm,
                            faceRecognitionEnabled: e.target.checked,
                          })
                        }
                      />
                      <span>Nhận diện khuôn mặt (Face Recognition)</span>
                    </label>
                  </div>
                </div>
                <div className="form-actions">
                  <button
                    type="submit"
                    className="btn-save"
                    disabled={saving}
                  >
                    {saving ? (
                      <Loader2
                        className="animate-spin"
                        size={16}
                      />
                    ) : (
                      <Save size={16} />
                    )}
                    <span>Lưu cấu hình AI</span>
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>

        {/* Health Logs Section */}
        <div className="health-logs-card">
          <div className="card-header">
            <Activity
              size={18}
              className="text-blue"
            />
            <h3>Nhật ký kết nối (Health Logs)</h3>
          </div>

          <div className="logs-container">
            {logsLoading ? (
              <div className="logs-loading">
                <Loader2
                  className="animate-spin"
                  size={24}
                />
              </div>
            ) : logs.length === 0 ? (
              <div className="logs-empty">
                Chưa có lịch sử kết nối của camera này.
              </div>
            ) : (
              <>
                <table className="logs-table">
                  <thead>
                    <tr>
                      <th>Thời gian</th>
                      <th>Trạng thái</th>
                      <th>Độ trễ</th>
                      <th>FPS</th>
                      <th>Lỗi</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logs.map((log) => (
                      <tr key={log.id}>
                        <td className="font-mono text-small">
                          {new Date(log.checkedAt).toLocaleString("vi-VN")}
                        </td>
                        <td>
                          <span
                            className={`status-badge text-small op-badge-${log.status.toLowerCase()}`}
                          >
                            {log.status}
                          </span>
                        </td>
                        <td>
                          {log.latencyMs !== null ? `${log.latencyMs} ms` : "-"}
                        </td>
                        <td>{log.fps !== null ? `${log.fps} fps` : "-"}</td>
                        <td
                          className="text-red font-bold text-small"
                          title={log.errorMessage}
                        >
                          {log.errorCode || "-"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                {logTotalPages > 1 && (
                  <div className="logs-pagination">
                    <button
                      onClick={() => setLogPage((p) => Math.max(0, p - 1))}
                      disabled={logPage === 0}
                      className="pagination-btn-small"
                    >
                      <ChevronLeft size={16} />
                    </button>
                    <span className="log-page-info">
                      Trang {logPage + 1}/{logTotalPages}
                    </span>
                    <button
                      onClick={() =>
                        setLogPage((p) => Math.min(logTotalPages - 1, p + 1))
                      }
                      disabled={logPage === logTotalPages - 1}
                      className="pagination-btn-small"
                    >
                      <ChevronRight size={16} />
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
