import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Video,
  Activity,
  Info,
  Save,
  Loader2,
  Power,
  PowerOff,
  ChevronLeft,
  ChevronRight,
  MapPin,
  HelpCircle,
} from "lucide-react";
import {
  fetchCameraDetail,
  updateCamera,
  decommissionCamera,
  reactivateCamera,
  upsertStreamConfig,
  fetchHealthLogs,
} from "../../services/cameraService";
import "../../styles/CameraDetailPage.css";

export default function CameraDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  // Tabs: 'general' | 'stream'
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
    installedAt: "",
  });

  const [streamForm, setStreamForm] = useState({
    host: "",
    port: "",
    username: "",
    credentialRef: "",
    mainStreamPath: "",
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
        installedAt: data.installedAt ? data.installedAt.substring(0, 16) : "", // format for datetime-local
      });

      // Init Stream Form
      if (data.streamConfig) {
        setStreamForm({
          host: data.streamConfig.host || "",
          port:
            data.streamConfig.port !== null &&
              data.streamConfig.port !== undefined
              ? data.streamConfig.port.toString()
              : "",
          username: data.streamConfig.username || "",
          credentialRef: "",
          mainStreamPath: data.streamConfig.mainStreamPath || "",
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

  const showError = (msg) => {
    setError(msg);
    setTimeout(() => setError(null), 6000);
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
      showError(err.message || "Thay đổi trạng thái thất bại.");
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
        installedAt: generalForm.installedAt
          ? new Date(generalForm.installedAt).toISOString()
          : null,
      };

      const updated = await updateCamera(id, payload);
      setCamera(updated);
      showNotification("Đã lưu thông tin chung thành công");
    } catch (err) {
      showError(err.message || "Lỗi lưu thông tin chung");
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
        host: streamForm.host,
        port: parseInt(streamForm.port, 10),
        username: streamForm.username || null,
        credentialRef: streamForm.credentialRef || null,
        password: streamForm.credentialRef || null,
        mainStreamPath: streamForm.mainStreamPath,
      };

      const updatedConfig = await upsertStreamConfig(id, payload);
      setCamera((prev) => (prev ? { ...prev, streamConfig: updatedConfig } : prev));
      showNotification("Đã lưu cấu hình Stream thành công");
    } catch (err) {
      showError(err.message || "Lỗi lưu cấu hình stream");
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
      {error && (
        <div className="error-banner" style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span>{error}</span>
          <button
            onClick={() => setError(null)}
            style={{ background: "transparent", border: "none", color: "inherit", cursor: "pointer", fontSize: "1rem", opacity: 0.8, padding: "0 0.25rem" }}
            title="Đóng thông báo"
          >
            ✕
          </button>
        </div>
      )}

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
              {camera.assignedAreas && camera.assignedAreas.length > 0 ? (
                camera.assignedAreas.map((area) => (
                  <span key={area.id} className="location-tag" title={area.name}>
                    <MapPin size={12} style={{ display: "inline", verticalAlign: "middle", marginRight: "4px" }} />
                    {area.name} ({area.building} - {area.floor})
                  </span>
                ))
              ) : (
                <span className="location-tag" style={{ opacity: 0.7 }}>
                  Chưa gán khu vực
                </span>
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
              className={`tab-btn ${activeTab === "stream" ? "tab-btn--active" : ""}`}
              onClick={() => setActiveTab("stream")}
            >
              <Video size={16} />
              <span>Cấu hình Stream</span>
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
                  <div className="form-group col-span-2">
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
                  <div className="form-group col-span-2">
                    <label style={{ display: "flex", alignItems: "center", gap: "0.4rem" }}>
                      <MapPin size={15} className="text-blue" />
                      <span>Khu vực đang phụ trách</span>
                    </label>
                    <div style={{
                      padding: "0.75rem 1rem",
                      background: "var(--theme-bg-desc)",
                      borderRadius: "8px",
                      border: "1px solid var(--theme-border)",
                      display: "flex",
                      flexWrap: "wrap",
                      gap: "0.5rem",
                      minHeight: "42px",
                      alignItems: "center"
                    }}>
                      {camera.assignedAreas && camera.assignedAreas.length > 0 ? (
                        camera.assignedAreas.map((area) => (
                          <span
                            key={area.id}
                            className="location-tag"
                            style={{ fontSize: "0.85rem", padding: "0.3rem 0.75rem" }}
                          >
                            <strong>{area.code}</strong> - {area.name} ({area.building} - {area.floor})
                          </span>
                        ))
                      ) : (
                        <span style={{ color: "var(--theme-text-muted)", fontSize: "0.875rem" }}>
                          Camera này chưa được gán vào khu vực nào.
                        </span>
                      )}
                    </div>
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

            {/* STREAM TAB */}
            {activeTab === "stream" && (
              <form
                onSubmit={handleStreamSubmit}
                className="tab-form"
              >
                <div className="form-grid">
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
                      Mật khẩu RTSP / Khóa bảo mật
                      <span data-tooltip="Mật khẩu tài khoản camera (được mã hóa AES-256 an toàn)" className="help-icon-wrapper">
                        <HelpCircle size={14} className="help-icon" />
                      </span>
                    </label>
                    <input
                      type="password"
                      placeholder={camera?.streamConfig?.isPasswordConfigured ? "•••••••• (Đã mã hóa và lưu bảo mật)" : "Nhập mật khẩu RTSP"}
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
