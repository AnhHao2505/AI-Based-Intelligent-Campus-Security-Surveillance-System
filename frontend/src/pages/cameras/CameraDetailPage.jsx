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
  Wifi,
  Layers,
  Scan,
  Radio,
  Camera as CameraIcon,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  X,
} from "lucide-react";
import {
  fetchCameraDetail,
  updateCamera,
  decommissionCamera,
  reactivateCamera,
  upsertStreamConfig,
  fetchHealthLogs,
  connectStream,
  testConnection,
  updateRoiGeometry,
} from "../../services/cameraService";
import RoiEditorModal from "../../components/camera/RoiEditorModal";
import "../../styles/CameraDetailPage.css";

function formatImageUrl(url) {
  if (!url) return "";
  if (url.includes("minio:9000")) {
    return url.replace("minio:9000", "localhost:9000");
  }
  return url;
}

export default function CameraDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  // Tabs: 'general' | 'stream' | 'surveillance'
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

  // Stream Tab - Test Connection State
  const [testingConnection, setTestingConnection] = useState(false);
  const [testResult, setTestResult] = useState(null);

  // Surveillance Tab - ROI, Reference Snapshot & Drift Detection State
  const [connecting, setConnecting] = useState(false);
  const [refreshingLive, setRefreshingLive] = useState(false);
  const [liveSnapshot, setLiveSnapshot] = useState(null);
  const [compareMode, setCompareMode] = useState(false);
  const [roiModalOpen, setRoiModalOpen] = useState(false);
  const [activeSnapshotForModal, setActiveSnapshotForModal] = useState(null);

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
    setTimeout(() => setError(null), 5000);
  };

  const handleTestConnection = async () => {
    setTestingConnection(true);
    setTestResult(null);
    try {
      const res = await testConnection(id);
      if (res && res.success) {
        setTestResult({
          success: true,
          message: res.message || "Kết nối RTSP thành công!",
          latencyMs: res.latencyMs,
        });
        showNotification(res.message || "Kiểm tra kết nối RTSP thành công!");
      } else {
        const msg = res?.message || "Không thể kết nối đến camera.";
        setTestResult({ success: false, message: msg });
        showError(msg);
      }
    } catch (err) {
      console.error("Failed to test connection:", err);
      const msg = err.message || "Lỗi kiểm tra kết nối stream.";
      setTestResult({ success: false, message: msg });
      showError(msg);
    } finally {
      setTestingConnection(false);
    }
  };

  const handleCaptureNewSnapshot = async () => {
    setConnecting(true);
    setError(null);
    try {
      const res = await connectStream(id);
      if (res && res.success) {
        setCamera((prev) => ({
          ...prev,
          operationalStatus: res.operationalStatus || "ONLINE",
        }));
        showNotification("Kết nối RTSP thành công! Đã trích xuất khung hình mới.");
        loadLogs();
        setActiveSnapshotForModal({
          data: res.snapshotBase64,
          width: res.width || 1920,
          height: res.height || 1080,
        });
        setRoiModalOpen(true);
      } else {
        showError(res?.errorMessage || "Không thể kết nối RTSP để chụp snapshot.");
      }
    } catch (err) {
      console.error("Failed to capture snapshot:", err);
      showError(err.message || "Không thể kết nối RTSP stream.");
    } finally {
      setConnecting(false);
    }
  };

  const handleEditCurrentRoi = () => {
    const rawRefUrl =
      camera?.roiGeometry?.reference_snapshot_url ||
      camera?.roiGeometry?.referenceSnapshotUrl;
    const refUrl = formatImageUrl(rawRefUrl);
    const refWidth =
      camera?.roiGeometry?.reference_snapshot_width ||
      camera?.roiGeometry?.referenceSnapshotWidth ||
      1920;
    const refHeight =
      camera?.roiGeometry?.reference_snapshot_height ||
      camera?.roiGeometry?.referenceSnapshotHeight ||
      1080;

    if (!refUrl) {
      showError("Chưa có ảnh chụp tham chiếu. Vui lòng bấm 'Kết nối & Chụp mới'.");
      return;
    }

    setActiveSnapshotForModal({
      data: refUrl,
      width: refWidth,
      height: refHeight,
    });
    setRoiModalOpen(true);
  };

  const handleRefreshForComparison = async () => {
    setRefreshingLive(true);
    setError(null);
    try {
      const res = await connectStream(id);
      if (res && res.success) {
        setLiveSnapshot(res);
        setCompareMode(true);
        setCamera((prev) => ({
          ...prev,
          operationalStatus: res.operationalStatus || "ONLINE",
        }));
        showNotification("Đã lấy khung hình trực tiếp để đối chiếu góc quay camera.");
        loadLogs();
      } else {
        showError(res?.errorMessage || "Không thể lấy khung hình trực tiếp.");
      }
    } catch (err) {
      console.error("Failed to refresh snapshot for comparison:", err);
      showError(err.message || "Lỗi khi lấy khung hình đối chiếu.");
    } finally {
      setRefreshingLive(false);
    }
  };

  const handleApplyLiveSnapshotToRoi = () => {
    if (!liveSnapshot?.snapshotBase64) return;
    setActiveSnapshotForModal({
      data: liveSnapshot.snapshotBase64,
      width: liveSnapshot.width || 1920,
      height: liveSnapshot.height || 1080,
    });
    setRoiModalOpen(true);
  };

  const handleSaveRoi = async (roiGeometry, snapshotMeta) => {
    const updated = await updateRoiGeometry(id, roiGeometry, snapshotMeta);
    setCamera((prev) => ({
      ...prev,
      roiGeometry: updated.roiGeometry || roiGeometry,
    }));
    setCompareMode(false);
    setLiveSnapshot(null);
    showNotification("Cấu hình vùng giám sát (ROI) đã được lưu thành công!");
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
          <span>{isDecommissioned ? "Bật camera" : "Tắt camera"}</span>
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
            <button
              className={`tab-btn ${activeTab === "surveillance" ? "tab-btn--active" : ""}`}
              onClick={() => setActiveTab("surveillance")}
            >
              <Layers size={16} />
              <span>Vùng Giám Sát</span>
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
                    <label>Tên camera *</label>
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

                {/* Test Connection Result Alert */}
                {testResult && (
                  <div
                    className={`test-conn-alert ${testResult.success ? "test-conn-alert--success" : "test-conn-alert--error"}`}
                  >
                    {testResult.success ? (
                      <CheckCircle2 size={18} className="text-emerald-400 shrink-0" />
                    ) : (
                      <XCircle size={18} className="text-rose-400 shrink-0" />
                    )}
                    <div className="test-conn-alert-content">
                      <span>{testResult.message}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setTestResult(null)}
                      className="test-conn-alert-close"
                      title="Đóng"
                    >
                      <X size={14} />
                    </button>
                  </div>
                )}

                <div className="form-actions stream-form-actions">
                  <button
                    type="button"
                    className="btn-test-stream"
                    onClick={handleTestConnection}
                    disabled={testingConnection || saving}
                    title="Kiểm tra tín hiệu luồng RTSP mà không thay đổi trạng thái hoạt động"
                  >
                    {testingConnection ? (
                      <>
                        <Loader2 className="animate-spin" size={16} />
                        <span>Đang thử kết nối...</span>
                      </>
                    ) : (
                      <>
                        <Radio size={16} />
                        <span>Thử kết nối</span>
                      </>
                    )}
                  </button>

                  <button
                    type="submit"
                    className="btn-save"
                    disabled={saving || testingConnection}
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

            {/* SURVEILLANCE / ROI TAB */}
            {activeTab === "surveillance" && (() => {
              const rawRefUrl =
                camera?.roiGeometry?.reference_snapshot_url ||
                camera?.roiGeometry?.referenceSnapshotUrl;
              const refUrl = formatImageUrl(rawRefUrl);
              const refWidth =
                camera?.roiGeometry?.reference_snapshot_width ||
                camera?.roiGeometry?.referenceSnapshotWidth ||
                1920;
              const refHeight =
                camera?.roiGeometry?.reference_snapshot_height ||
                camera?.roiGeometry?.referenceSnapshotHeight ||
                1080;
              const refCapturedAt =
                camera?.roiGeometry?.reference_captured_at ||
                camera?.roiGeometry?.referenceCapturedAt;
              const roiPolygons = camera?.roiGeometry?.polygons || [];

              return (
                <div className="roi-config-section">
                  <div className="roi-section-header">
                    <div className="roi-section-info">
                      <h4>
                        <Layers size={20} color="#38bdf8" />
                        Vùng Giám Sát & Ảnh Tham Chiếu
                      </h4>
                      <p>
                        Quản lý các vùng phát hiện xâm nhập (ROI). Ảnh chụp tham chiếu được lưu trữ bền vững trên MinIO để đối chiếu góc quan sát của camera và phát hiện lệch khung hình.
                      </p>
                    </div>

                    <div className="roi-action-bar">
                      <button
                        type="button"
                        className="btn-connect"
                        onClick={handleCaptureNewSnapshot}
                        disabled={connecting || refreshingLive}
                        title="Kết nối camera qua RTSP và lấy khung hình mới để thiết lập hoặc thay thế ROI"
                      >
                        {connecting ? (
                          <>
                            <Loader2 className="animate-spin" size={16} />
                            <span>Đang kết nối RTSP...</span>
                          </>
                        ) : (
                          <>
                            <CameraIcon size={16} />
                            <span>Kết nối & Chụp mới</span>
                          </>
                        )}
                      </button>

                      {refUrl && (
                        <>
                          <button
                            type="button"
                            className="btn-open-editor"
                            onClick={handleEditCurrentRoi}
                            disabled={connecting || refreshingLive}
                            title="Chỉnh sửa các polygon ROI trên ảnh tham chiếu hiện tại"
                          >
                            <Scan size={16} />
                            <span>Sửa vùng ROI</span>
                          </button>

                          <button
                            type="button"
                            className={`btn-refresh-compare ${compareMode ? "btn-refresh-compare--active" : ""}`}
                            onClick={handleRefreshForComparison}
                            disabled={refreshingLive || connecting}
                            title="Lấy khung hình thực tế hiện tại để so sánh xem camera có bị lệch góc quay không"
                          >
                            {refreshingLive ? (
                              <>
                                <Loader2 className="animate-spin" size={16} />
                                <span>Đang đối chiếu...</span>
                              </>
                            ) : (
                              <>
                                <RefreshCw size={16} />
                                <span>Đối chiếu góc quay</span>
                              </>
                            )}
                          </button>
                        </>
                      )}
                    </div>
                  </div>

                  {/* Drift Warning Banner in Compare Mode */}
                  {compareMode && liveSnapshot && (
                    <div className="drift-warning-banner">
                      <div className="drift-warning-icon">
                        <AlertTriangle size={22} />
                      </div>
                      <div className="drift-warning-content">
                        <h5>Kiểm Tra Độ Lệch Khung Hình Camera</h5>
                        <p>
                          Đối chiếu giữa <strong>Ảnh tham chiếu gốc (khi tạo ROI)</strong> và <strong>Khung hình thực tế hiện tại</strong>.
                          Nếu camera bị xoay hoặc thay đổi góc quan sát, các polygon ROI có thể không còn khớp với thực tế.
                        </p>
                      </div>
                      <div className="drift-warning-actions">
                        <button
                          type="button"
                          className="btn-apply-drift-snapshot"
                          onClick={handleApplyLiveSnapshotToRoi}
                        >
                          <CameraIcon size={15} />
                          <span>Cập nhật ROI với khung hình mới</span>
                        </button>
                        <button
                          type="button"
                          className="btn-close-compare"
                          onClick={() => {
                            setCompareMode(false);
                            setLiveSnapshot(null);
                          }}
                        >
                          <X size={15} />
                          <span>Đóng đối chiếu</span>
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Dual Comparison Mode or Single Reference Preview */}
                  {compareMode && liveSnapshot ? (
                    <div className="roi-compare-grid">
                      {/* Left: Reference Snapshot */}
                      <div className="roi-compare-card">
                        <div className="roi-compare-header">
                          <div className="roi-compare-badge roi-compare-badge--ref">
                            <span>Ảnh tham chiếu gốc (MinIO)</span>
                          </div>
                          <span className="roi-compare-time">
                            {refCapturedAt
                              ? new Date(refCapturedAt).toLocaleString("vi-VN")
                              : "Gốc"}
                          </span>
                        </div>

                        <div className="roi-preview-wrapper">
                          <div className="roi-preview-stage">
                            <img
                              src={refUrl}
                              alt="Reference Snapshot"
                              className="roi-preview-img"
                            />
                            {roiPolygons.length > 0 && (
                              <svg
                                viewBox={`0 0 ${refWidth} ${refHeight}`}
                                preserveAspectRatio="none"
                                className="roi-preview-svg"
                              >
                                {roiPolygons.map((poly, idx) => {
                                  const pts = (poly.vertices || [])
                                    .map((v) => `${v.x * refWidth},${v.y * refHeight}`)
                                    .join(" ");
                                  return (
                                    <polygon
                                      key={idx}
                                      points={pts}
                                      className="roi-preview-poly roi-preview-poly--ref"
                                    >
                                      <title>{poly.label || `Vùng ${idx + 1}`}</title>
                                    </polygon>
                                  );
                                })}
                              </svg>
                            )}
                          </div>
                        </div>

                        <div className="roi-preview-meta">
                          <span className="roi-meta-badge">
                            Độ phân giải: {refWidth}x{refHeight}
                          </span>
                          <span>{roiPolygons.length} vùng ROI đã lưu</span>
                        </div>
                      </div>

                      {/* Right: Live Snapshot */}
                      <div className="roi-compare-card">
                        <div className="roi-compare-header">
                          <div className="roi-compare-badge roi-compare-badge--live">
                            <span className="pulse-dot" />
                            <span>Khung hình trực tiếp (Vừa chụp)</span>
                          </div>
                          <span className="roi-compare-time">
                            {liveSnapshot.latencyMs ? `${liveSnapshot.latencyMs}ms` : "Trực tiếp"}
                          </span>
                        </div>

                        <div className="roi-preview-wrapper">
                          <div className="roi-preview-stage">
                            <img
                              src={liveSnapshot.snapshotBase64}
                              alt="Live Snapshot"
                              className="roi-preview-img"
                            />
                            {roiPolygons.length > 0 && (
                              <svg
                                viewBox={`0 0 ${liveSnapshot.width || 1920} ${liveSnapshot.height || 1080}`}
                                preserveAspectRatio="none"
                                className="roi-preview-svg"
                              >
                                {roiPolygons.map((poly, idx) => {
                                  const sw = liveSnapshot.width || 1920;
                                  const sh = liveSnapshot.height || 1080;
                                  const pts = (poly.vertices || [])
                                    .map((v) => `${v.x * sw},${v.y * sh}`)
                                    .join(" ");
                                  return (
                                    <polygon
                                      key={idx}
                                      points={pts}
                                      className="roi-preview-poly roi-preview-poly--drift"
                                    >
                                      <title>{poly.label || `Vùng ${idx + 1}`}</title>
                                    </polygon>
                                  );
                                })}
                              </svg>
                            )}
                          </div>
                        </div>

                        <div className="roi-preview-meta">
                          <span className="roi-meta-badge" style={{ color: "#34d399" }}>
                            <Wifi size={14} /> RTSP ({liveSnapshot.width || 1920}x{liveSnapshot.height || 1080})
                          </span>
                          <span style={{ color: "#f59e0b", fontWeight: 600 }}>
                            Kiểm tra góc quan sát có bị xê dịch
                          </span>
                        </div>
                      </div>
                    </div>
                  ) : (
                    /* Single Reference Preview */
                    <div className="roi-preview-container">
                      {refUrl ? (
                        <>
                          <div className="roi-preview-wrapper">
                            <div className="roi-preview-stage">
                              <img
                                src={refUrl}
                                alt="Camera ROI Reference Snapshot"
                                className="roi-preview-img"
                              />
                              {roiPolygons.length > 0 && (
                                <svg
                                  viewBox={`0 0 ${refWidth} ${refHeight}`}
                                  preserveAspectRatio="none"
                                  className="roi-preview-svg"
                                >
                                  {roiPolygons.map((poly, idx) => {
                                    const pts = (poly.vertices || [])
                                      .map((v) => `${v.x * refWidth},${v.y * refHeight}`)
                                      .join(" ");
                                    return (
                                      <polygon
                                        key={idx}
                                        points={pts}
                                        className="roi-preview-poly"
                                      >
                                        <title>{poly.label || `Vùng ${idx + 1}`}</title>
                                      </polygon>
                                    );
                                  })}
                                </svg>
                              )}
                            </div>
                          </div>
                          <div className="roi-preview-meta">
                            <span className="roi-meta-badge">
                              <CheckCircle2 size={14} className="text-emerald-400" />
                              Ảnh tham chiếu ROI ({refWidth}x{refHeight})
                            </span>
                            <span>
                              Thời gian lưu:{" "}
                              {refCapturedAt
                                ? new Date(refCapturedAt).toLocaleString("vi-VN")
                                : "Đã lưu trên MinIO"}
                            </span>
                          </div>
                        </>
                      ) : (
                        <div className="roi-preview-placeholder">
                          <Layers size={44} />
                          <p style={{ fontWeight: 600, fontSize: "1rem", color: "var(--theme-text-primary)" }}>
                            Chưa cấu hình vùng giám sát
                          </p>
                          <p>
                            Camera này chưa có ảnh tham chiếu và vùng ROI. Bấm nút{" "}
                            <strong>"Kết nối & Chụp mới"</strong> để kết nối luồng RTSP và bắt đầu khoanh vùng giám sát an ninh.
                          </p>
                        </div>
                      )}
                    </div>
                  )}

                  {/* Existing ROI Polygons Summary */}
                  {roiPolygons.length > 0 && (
                    <div className="roi-summary-section">
                      <h5 style={{ fontSize: "0.875rem", fontWeight: 700, marginBottom: "0.5rem", color: "var(--theme-text-primary)" }}>
                        Vùng giám sát hiện hành ({roiPolygons.length} vùng)
                      </h5>
                      <div className="roi-summary-list">
                        {roiPolygons.map((poly, idx) => (
                          <div key={idx} className="roi-summary-card">
                            <div className="roi-summary-card-header">
                              <div className="roi-summary-title">
                                <span className="roi-summary-index">{idx + 1}</span>
                                <span>{poly.label || `Vùng ${idx + 1}`}</span>
                              </div>
                              <span style={{ fontSize: "0.75rem", color: "var(--theme-text-muted)" }}>
                                {poly.vertices ? poly.vertices.length : 0} đỉnh
                              </span>
                            </div>
                            <div className="roi-rules-tags">
                              {(poly.alert_rules || poly.alertRules || ["INTRUSION_DETECTION"]).map((rule, rIdx) => (
                                <span key={rIdx} className="roi-badge-rule">
                                  {rule}
                                </span>
                              ))}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })()}
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
                <span>Đang tải nhật ký...</span>
              </div>
            ) : logs.length === 0 ? (
              <div className="empty-logs">Chưa có nhật ký kết nối nào.</div>
            ) : (
              <div className="logs-list">
                {logs.map((log) => (
                  <div
                    key={log.id}
                    className="log-item"
                  >
                    <div className="log-status-indicator">
                      <span
                        className={`status-dot ${log.status === "ONLINE" ? "status-dot--online" : "status-dot--offline"}`}
                      />
                    </div>
                    <div className="log-content">
                      <div className="log-row">
                        <span className="log-status">{log.status}</span>
                        <span className="log-time">
                          {new Date(log.checkedAt).toLocaleString("vi-VN")}
                        </span>
                      </div>
                      {log.latencyMs !== null && log.latencyMs !== undefined && (
                        <div className="log-meta">
                          <span>Độ trễ: {log.latencyMs}ms</span>
                          {log.fps && <span>FPS: {log.fps}</span>}
                        </div>
                      )}
                      {log.errorMessage && (
                        <div className="log-error">{log.errorMessage}</div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {logTotalPages > 1 && (
            <div className="logs-pagination">
              <button
                onClick={() => setLogPage((p) => Math.max(0, p - 1))}
                disabled={logPage === 0}
                className="btn-page"
              >
                <ChevronLeft size={16} />
              </button>
              <span className="page-indicator">
                {logPage + 1} / {logTotalPages}
              </span>
              <button
                onClick={() =>
                  setLogPage((p) => Math.min(logTotalPages - 1, p + 1))
                }
                disabled={logPage >= logTotalPages - 1}
                className="btn-page"
              >
                <ChevronRight size={16} />
              </button>
            </div>
          )}
        </div>
      </div>

      {/* ROI Editor Modal */}
      <RoiEditorModal
        isOpen={roiModalOpen}
        onClose={() => setRoiModalOpen(false)}
        snapshotBase64={activeSnapshotForModal?.data}
        snapshotWidth={activeSnapshotForModal?.width || 1920}
        snapshotHeight={activeSnapshotForModal?.height || 1080}
        initialRoiGeometry={camera?.roiGeometry}
        onSave={handleSaveRoi}
      />
    </div>
  );
}
