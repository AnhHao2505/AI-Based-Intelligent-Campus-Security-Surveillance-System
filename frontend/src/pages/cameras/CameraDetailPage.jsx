import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Video,
  Info,
  Power,
  PowerOff,
  MapPin,
  Layers,
  Loader2,
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
import CameraGeneralTab from "../../components/camera/CameraGeneralTab";
import CameraStreamTab from "../../components/camera/CameraStreamTab";
import CameraSurveillanceTab, {
  formatImageUrl,
} from "../../components/camera/CameraSurveillanceTab";
import CameraHealthLogs from "../../components/camera/CameraHealthLogs";
import "../../styles/CameraDetailPage.css";

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
  const [capturingSnapshot, setCapturingSnapshot] = useState(false);
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
        installedAt: data.installedAt ? data.installedAt.substring(0, 16) : "",
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
      } else {
        const msg = res?.message || "Không thể kết nối đến camera.";
        setTestResult({ success: false, message: msg });
      }
    } catch (err) {
      console.error("Failed to test connection:", err);
      const msg = err.message || "Lỗi kiểm tra kết nối stream.";
      setTestResult({ success: false, message: msg });
    } finally {
      setTestingConnection(false);
    }
  };

  const handleConnectCamera = async () => {
    setConnecting(true);
    setError(null);
    try {
      const res = await connectStream(id);
      if (res && res.success) {
        setCamera((prev) => ({
          ...prev,
          operationalStatus: res.operationalStatus || "ONLINE",
        }));
        showNotification(
          "Kết nối RTSP thành công! Camera đã chuyển sang trạng thái ONLINE."
        );
        loadLogs();
      } else {
        showError(res?.errorMessage || "Không thể kết nối RTSP stream.");
      }
    } catch (err) {
      console.error("Failed to connect camera:", err);
      showError(err.message || "Không thể kết nối RTSP stream.");
    } finally {
      setConnecting(false);
    }
  };

  const handleCaptureNewSnapshot = async () => {
    setCapturingSnapshot(true);
    setError(null);
    try {
      const res = await connectStream(id);
      if (res && res.success) {
        setCamera((prev) => ({
          ...prev,
          operationalStatus: res.operationalStatus || "ONLINE",
        }));
        showNotification("Đã trích xuất khung hình mới thành công!");
        loadLogs();
        setActiveSnapshotForModal({
          data: res.snapshotBase64,
          width: res.width || 1920,
          height: res.height || 1080,
        });
        setRoiModalOpen(true);
      } else {
        showError(res?.errorMessage || "Không thể chụp snapshot từ camera.");
      }
    } catch (err) {
      console.error("Failed to capture snapshot:", err);
      showError(err.message || "Lỗi chụp snapshot từ camera.");
    } finally {
      setCapturingSnapshot(false);
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
      showError("Chưa có ảnh chụp tham chiếu. Vui lòng bấm 'Chụp ảnh mới'.");
      return;
    }

    setActiveSnapshotForModal({
      data: refUrl,
      width: refWidth,
      height: refHeight,
    });
    setRoiModalOpen(true);
  };

  const handleCheckDrift = async () => {
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
        showNotification(
          "Đã trích xuất khung hình trực tiếp để kiểm tra sai lệch góc quan sát camera."
        );
        loadLogs();
      } else {
        showError(res?.errorMessage || "Không thể lấy khung hình trực tiếp.");
      }
    } catch (err) {
      console.error("Failed to capture live frame for drift check:", err);
      showError(err.message || "Lỗi khi lấy khung hình kiểm tra sai lệch.");
    } finally {
      setRefreshingLive(false);
    }
  };

  const handleCloseCompare = () => {
    setCompareMode(false);
    setLiveSnapshot(null);
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
    try {
      const payload = {
        name: generalForm.name,
        installedAt: generalForm.installedAt
          ? new Date(generalForm.installedAt).toISOString()
          : null,
      };
      const updated = await updateCamera(id, payload);
      setCamera(updated);
      showNotification("Cập nhật thông tin camera thành công!");
    } catch (err) {
      showError(err.message || "Cập nhật thông tin thất bại.");
    } finally {
      setSaving(false);
    }
  };

  const handleStreamSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      const payload = {
        host: streamForm.host,
        port: streamForm.port ? parseInt(streamForm.port, 10) : 554,
        username: streamForm.username,
        mainStreamPath: streamForm.mainStreamPath,
      };
      if (streamForm.credentialRef) {
        payload.credentialRef = streamForm.credentialRef;
      }
      const updated = await upsertStreamConfig(id, payload);
      setCamera((prev) => ({
        ...prev,
        streamConfig: updated,
      }));
      setStreamForm((prev) => ({
        ...prev,
        credentialRef: "",
      }));
      showNotification("Cập nhật cấu hình stream thành công!");
    } catch (err) {
      showError(err.message || "Cập nhật cấu hình stream thất bại.");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="detail-loading-state">
        <Loader2 className="animate-spin" size={40} />
        <p>Đang tải thông tin chi tiết camera...</p>
      </div>
    );
  }

  if (!camera) {
    return (
      <div className="detail-error-state">
        <h2>Không tìm thấy camera</h2>
        <p>Camera này không tồn tại hoặc bạn không có quyền truy cập.</p>
        <button
          className="btn-back"
          onClick={() => navigate("/admin/cameras")}
        >
          <ArrowLeft size={16} /> Quay lại danh sách
        </button>
      </div>
    );
  }

  const isDecommissioned = camera.status === "DECOMMISSIONED";

  return (
    <div className="camera-detail-page">
      {/* Breadcrumb Navigation */}
      <div className="breadcrumb">
        <button
          className="btn-back"
          onClick={() => navigate("/cameras")}
        >
          <ArrowLeft size={16} /> Danh sách Camera
        </button>
        <span className="separator">/</span>
        <span className="current">{camera.name || camera.cameraCode}</span>
      </div>

      {/* Notifications */}
      {successMsg && (
        <div className="success-toast">
          <span>{successMsg}</span>
          <button
            type="button"
            onClick={() => setSuccessMsg(null)}
            style={{
              background: "transparent",
              border: "none",
              color: "inherit",
              cursor: "pointer",
              padding: "0 0.25rem",
            }}
            title="Đóng thông báo"
          >
            ✕
          </button>
        </div>
      )}

      {error && (
        <div className="error-toast">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => setError(null)}
            style={{
              background: "transparent",
              border: "none",
              color: "inherit",
              cursor: "pointer",
              padding: "0 0.25rem",
            }}
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
                {camera.status === "ACTIVE" ? "Đang kích hoạt" : "Đã tắt"}
              </span>
              <span
                className={`status-badge op-badge-${camera.operationalStatus.toLowerCase()}`}
              >
                ● {camera.operationalStatus}
              </span>
              {camera.assignedAreas && camera.assignedAreas.length > 0 ? (
                camera.assignedAreas.map((area) => (
                  <span
                    key={area.id}
                    className="location-tag"
                    title={area.name}
                  >
                    <MapPin
                      size={12}
                      style={{
                        display: "inline",
                        verticalAlign: "middle",
                        marginRight: "4px",
                      }}
                    />
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
          className={`btn-toggle-status ${isDecommissioned ? "btn-status-active" : "btn-status-decommission"
            }`}
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
              className={`tab-btn ${activeTab === "general" ? "tab-btn--active" : ""
                }`}
              onClick={() => setActiveTab("general")}
            >
              <Info size={16} />
              <span>Thông tin chung</span>
            </button>
            <button
              className={`tab-btn ${activeTab === "stream" ? "tab-btn--active" : ""
                }`}
              onClick={() => setActiveTab("stream")}
            >
              <Video size={16} />
              <span>Cấu hình Stream</span>
            </button>
            <button
              className={`tab-btn ${activeTab === "surveillance" ? "tab-btn--active" : ""
                }`}
              onClick={() => setActiveTab("surveillance")}
            >
              <Layers size={16} />
              <span>Vùng Giám Sát</span>
            </button>
          </div>

          <div className="tab-content">
            {/* GENERAL TAB */}
            {activeTab === "general" && (
              <CameraGeneralTab
                camera={camera}
                generalForm={generalForm}
                setGeneralForm={setGeneralForm}
                saving={saving}
                onSubmit={handleGeneralSubmit}
              />
            )}

            {/* STREAM TAB */}
            {activeTab === "stream" && (
              <CameraStreamTab
                camera={camera}
                streamForm={streamForm}
                setStreamForm={setStreamForm}
                saving={saving}
                onSubmit={handleStreamSubmit}
                testingConnection={testingConnection}
                testResult={testResult}
                onTestConnection={handleTestConnection}
                onCloseTestResult={() => setTestResult(null)}
              />
            )}

            {/* SURVEILLANCE / ROI TAB */}
            {activeTab === "surveillance" && (
              <CameraSurveillanceTab
                camera={camera}
                connecting={connecting}
                capturingSnapshot={capturingSnapshot}
                refreshingLive={refreshingLive}
                compareMode={compareMode}
                liveSnapshot={liveSnapshot}
                onConnectCamera={handleConnectCamera}
                onCaptureNewSnapshot={handleCaptureNewSnapshot}
                onEditCurrentRoi={handleEditCurrentRoi}
                onCheckDrift={handleCheckDrift}
                onCloseCompare={handleCloseCompare}
              />
            )}
          </div>
        </div>

        {/* Redesigned Health Logs Component */}
        <CameraHealthLogs
          logs={logs}
          loading={logsLoading}
          page={logPage}
          totalPages={logTotalPages}
          onPageChange={setLogPage}
          onRefresh={loadLogs}
        />
      </div>

      {/* ROI Editor Modal */}
      <RoiEditorModal
        isOpen={roiModalOpen}
        onClose={() => setRoiModalOpen(false)}
        snapshotBase64={activeSnapshotForModal?.data}
        snapshotWidth={activeSnapshotForModal?.width || 1920}
        snapshotHeight={activeSnapshotForModal?.height || 1080}
        initialRoiGeometry={camera?.roiGeometry}
        availableAreas={camera?.assignedAreas || []}
        onSave={handleSaveRoi}
      />
    </div>
  );
}
