import React, { useState, useEffect, useMemo, useCallback } from "react";
import {
  Video,
  Search,
  Filter,
  Calendar,
  Clock,
  Play,
  Download,
  AlertTriangle,
  CheckCircle2,
  HardDrive,
  LayoutGrid,
  List,
  X,
  ExternalLink,
  ShieldAlert,
  Film,
  Camera,
  RotateCcw,
  RefreshCw,
  FolderOpen,
} from "lucide-react";
import PageHeader from "../../components/ui/PageHeader";
import Button from "../../components/ui/Button";
import {
  RECORDING_TIME_RANGES,
  EVENT_TYPE_OPTIONS,
  SECURITY_SEVERITY_LEVELS,
  fetchDiskRecordings,
} from "../../services/recordingService";
import "../../styles/RecordingPage.css";

export default function RecordingPage() {
  const [recordings, setRecordings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Filters state
  const [timeRange, setTimeRange] = useState("all");
  const [customFromDate, setCustomFromDate] = useState("");
  const [customToDate, setCustomToDate] = useState("");
  const [customFromTime, setCustomFromTime] = useState("00:00");
  const [customToTime, setCustomToTime] = useState("23:59");

  const [selectedCameraId, setSelectedCameraId] = useState("");
  const [selectedEventType, setSelectedEventType] = useState("");
  const [searchKeyword, setSearchKeyword] = useState("");

  // View mode: grid or table
  const [viewMode, setViewMode] = useState("grid");

  // Selected video for modal player
  const [activeVideo, setActiveVideo] = useState(null);

  // Tải danh sách video thực tế trên ổ đĩa từ backend
  const loadRecordings = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await fetchDiskRecordings({
        cameraId: selectedCameraId,
        search: searchKeyword,
      });
      setRecordings(data);
    } catch (err) {
      console.error("Lỗi khi tải danh sách video ghi hình:", err);
      setError(err.message || "Không thể kết nối đến máy chủ lưu trữ video.");
    } finally {
      setLoading(false);
    }
  }, [selectedCameraId, searchKeyword]);

  useEffect(() => {
    loadRecordings();
  }, [loadRecordings]);

  // Tự động trích xuất danh sách Camera từ các file ghi nhận được
  const cameraOptions = useMemo(() => {
    const map = new Map();
    recordings.forEach((rec) => {
      if (rec.cameraId && !map.has(rec.cameraId)) {
        map.set(rec.cameraId, `${rec.cameraId} - ${rec.cameraName || rec.cameraId}`);
      }
    });
    return Array.from(map.entries()).map(([value, label]) => ({
      value,
      label,
    }));
  }, [recordings]);

  // Bộ lọc thời gian và loại sự kiện
  const filteredRecordings = useMemo(() => {
    return recordings.filter((item) => {
      // Filter by Camera
      if (selectedCameraId && item.cameraId !== selectedCameraId) {
        return false;
      }

      // Filter by Event Type
      if (selectedEventType && item.eventType !== selectedEventType) {
        return false;
      }

      // Filter by Keyword
      if (searchKeyword.trim()) {
        const kw = searchKeyword.toLowerCase();
        const matches =
          item.cameraId?.toLowerCase().includes(kw) ||
          item.cameraName?.toLowerCase().includes(kw) ||
          item.areaName?.toLowerCase().includes(kw) ||
          item.building?.toLowerCase().includes(kw) ||
          item.filename?.toLowerCase().includes(kw) ||
          (item.notes && item.notes.toLowerCase().includes(kw));
        if (!matches) return false;
      }

      // Filter by Time Range
      if (!item.startTime) return true;
      const itemDate = new Date(item.startTime);
      const now = new Date();

      if (timeRange === "today") {
        const isSameDay =
          itemDate.getFullYear() === now.getFullYear() &&
          itemDate.getMonth() === now.getMonth() &&
          itemDate.getDate() === now.getDate();
        if (!isSameDay) return false;
      } else if (timeRange === "last_24h") {
        const diffMs = now.getTime() - itemDate.getTime();
        if (diffMs < 0 || diffMs > 24 * 60 * 60 * 1000) return false;
      } else if (timeRange === "last_7d") {
        const diffMs = now.getTime() - itemDate.getTime();
        if (diffMs < 0 || diffMs > 7 * 24 * 60 * 60 * 1000) return false;
      } else if (timeRange === "custom") {
        if (customFromDate && customFromTime) {
          const fromDt = new Date(`${customFromDate}T${customFromTime}:00`);
          if (itemDate < fromDt) return false;
        }
        if (customToDate && customToTime) {
          const toDt = new Date(`${customToDate}T${customToTime}:59`);
          if (itemDate > toDt) return false;
        }
      }

      return true;
    });
  }, [
    recordings,
    selectedCameraId,
    selectedEventType,
    searchKeyword,
    timeRange,
    customFromDate,
    customToDate,
    customFromTime,
    customToTime,
  ]);

  // Quick stats
  const totalVideos = filteredRecordings.length;
  const securityEventsCount = filteredRecordings.filter(
    (r) => r.eventType === "SECURITY_EVENT"
  ).length;
  const normalRecordingsCount = filteredRecordings.filter(
    (r) => r.eventType === "NORMAL_RECORDING"
  ).length;

  const totalSizeBytes = useMemo(() => {
    return filteredRecordings.reduce((acc, curr) => acc + (curr.fileSizeBytes || 0), 0);
  }, [filteredRecordings]);

  const formattedTotalStorage = useMemo(() => {
    if (totalSizeBytes === 0) return "0 MB";
    const mb = totalSizeBytes / (1024 * 1024);
    if (mb >= 1024) {
      return `${(mb / 1024).toFixed(2)} GB`;
    }
    return `${mb.toFixed(2)} MB`;
  }, [totalSizeBytes]);

  const handleResetFilters = () => {
    setTimeRange("all");
    setSelectedCameraId("");
    setSelectedEventType("");
    setSearchKeyword("");
    setCustomFromDate("");
    setCustomToDate("");
    setCustomFromTime("00:00");
    setCustomToTime("23:59");
  };

  const formatDisplayTime = (isoString) => {
    if (!isoString) return "—";
    const d = new Date(isoString);
    return `${d.toLocaleDateString("vi-VN")} ${d.toLocaleTimeString("vi-VN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    })}`;
  };

  return (
    <div className="recording-page">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <PageHeader
          title="Kho Lưu Trữ Video Ghi Hình (Video Recordings)"
          description="Quản lý và tra cứu các video ghi hình 1 phút liên tục được lưu trữ tại C:\DATN\...\recording. Tự động chia session chính xác 1 phút khi camera ONLINE."
        />
        <div style={{ marginTop: "24px" }}>
          <Button
            variant="outline"
            size="md"
            onClick={loadRecordings}
            disabled={loading}
            title="Làm mới danh sách từ ổ cứng"
          >
            <RefreshCw
              size={15}
              style={{
                marginRight: "6px",
                animation: loading ? "spin 1s linear infinite" : "none",
              }}
            />
            {loading ? "Đang quét..." : "Làm mới dữ liệu"}
          </Button>
        </div>
      </div>

      {/* Overview Stat Badges */}
      <div className="recording-stats-grid">
        <div className="recording-stat-card">
          <div className="recording-stat-card__icon recording-stat-card__icon--blue">
            <Film size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{totalVideos}</span>
            <span className="recording-stat-card__label">Tổng video tìm thấy</span>
          </div>
        </div>

        <div className="recording-stat-card">
          <div className="recording-stat-card__icon recording-stat-card__icon--green">
            <CheckCircle2 size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{normalRecordingsCount}</span>
            <span className="recording-stat-card__label">Ghi hình định kỳ (1 phút)</span>
          </div>
        </div>

        <div className="recording-stat-card">
          <div className="recording-stat-card__icon recording-stat-card__icon--purple">
            <Camera size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{cameraOptions.length}</span>
            <span className="recording-stat-card__label">Camera có video lưu</span>
          </div>
        </div>

        <div className="recording-stat-card">
          <div className="recording-stat-card__icon recording-stat-card__icon--blue">
            <HardDrive size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{formattedTotalStorage}</span>
            <span className="recording-stat-card__label">Dung lượng ổ đĩa sử dụng</span>
          </div>
        </div>
      </div>

      {/* Multi-Parameter Filter Card */}
      <div className="recording-filter-card">
        <div className="recording-filter-bar">
          {/* Time Range Dropdown */}
          <div className="recording-filter-group">
            <label>
              <Calendar size={14} /> Khoảng thời gian
            </label>
            <select
              value={timeRange}
              onChange={(e) => setTimeRange(e.target.value)}
              aria-label="Lọc theo khoảng thời gian"
            >
              {RECORDING_TIME_RANGES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>

          {/* Camera ID Dropdown */}
          <div className="recording-filter-group">
            <label>
              <Camera size={14} /> Camera
            </label>
            <select
              value={selectedCameraId}
              onChange={(e) => setSelectedCameraId(e.target.value)}
              aria-label="Lọc theo Camera ID"
            >
              <option value="">Tất cả Camera (All Cameras)</option>
              {cameraOptions.map((cam) => (
                <option key={cam.value} value={cam.value}>
                  {cam.label}
                </option>
              ))}
            </select>
          </div>

          {/* Event Type Dropdown */}
          <div className="recording-filter-group">
            <label>
              <Filter size={14} /> Phân loại
            </label>
            <select
              value={selectedEventType}
              onChange={(e) => setSelectedEventType(e.target.value)}
              aria-label="Lọc theo loại sự kiện"
            >
              {EVENT_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          {/* Search Box */}
          <div className="recording-filter-group" style={{ flex: 1.2 }}>
            <label>
              <Search size={14} /> Tìm kiếm
            </label>
            <input
              type="text"
              placeholder="Nhập tên camera, mã file, khu vực..."
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
            />
          </div>

          {/* Reset button */}
          <div style={{ marginTop: "20px" }}>
            <Button
              variant="outline"
              size="md"
              onClick={handleResetFilters}
              title="Đặt lại bộ lọc"
            >
              <RotateCcw size={15} style={{ marginRight: "6px" }} /> Đặt lại
            </Button>
          </div>
        </div>

        {/* Custom Hour to Hour, Date to Date inputs if custom is chosen */}
        {timeRange === "custom" && (
          <div className="recording-custom-time-row">
            <div className="recording-custom-time-group">
              <label>Từ ngày:</label>
              <input
                type="date"
                value={customFromDate}
                onChange={(e) => setCustomFromDate(e.target.value)}
              />
              <label>Giờ:</label>
              <input
                type="time"
                value={customFromTime}
                onChange={(e) => setCustomFromTime(e.target.value)}
              />
            </div>

            <div className="recording-custom-time-group">
              <label>Đến ngày:</label>
              <input
                type="date"
                value={customToDate}
                onChange={(e) => setCustomToDate(e.target.value)}
              />
              <label>Giờ:</label>
              <input
                type="time"
                value={customToTime}
                onChange={(e) => setCustomToTime(e.target.value)}
              />
            </div>
          </div>
        )}
      </div>

      {/* Results Header with View Mode Switch */}
      <div className="recording-results-header">
        <div className="recording-results-count">
          Tìm thấy <strong>{filteredRecordings.length}</strong> video ghi hình trên đĩa
        </div>

        <div className="recording-view-mode-toggle">
          <button
            type="button"
            className={`recording-view-btn ${viewMode === "grid" ? "recording-view-btn--active" : ""}`}
            onClick={() => setViewMode("grid")}
            title="Dạng lưới thẻ (Grid View)"
          >
            <LayoutGrid size={16} />
          </button>
          <button
            type="button"
            className={`recording-view-btn ${viewMode === "table" ? "recording-view-btn--active" : ""}`}
            onClick={() => setViewMode("table")}
            title="Dạng bảng danh sách (Table View)"
          >
            <List size={16} />
          </button>
        </div>
      </div>

      {/* Main Content Area */}
      {loading ? (
        <div
          style={{
            background: "var(--theme-bg-surface)",
            border: "1px dashed var(--theme-border)",
            borderRadius: "12px",
            padding: "60px 24px",
            textAlign: "center",
          }}
        >
          <RefreshCw
            size={40}
            style={{
              color: "#00a3ff",
              marginBottom: "16px",
              animation: "spin 1s linear infinite",
            }}
          />
          <h3 style={{ margin: "0 0 8px 0", color: "var(--theme-text-primary)" }}>
            Đang quét đệ quy các tệp video từ ổ cứng...
          </h3>
          <p style={{ margin: 0, fontSize: "14px", color: "var(--theme-text-secondary)" }}>
            Đang tìm kiếm trong thư mục C:\DATN\...\recording
          </p>
        </div>
      ) : error ? (
        <div
          style={{
            background: "rgba(239, 68, 68, 0.08)",
            border: "1px solid rgba(239, 68, 68, 0.3)",
            borderRadius: "12px",
            padding: "40px 24px",
            textAlign: "center",
          }}
        >
          <AlertTriangle size={40} style={{ color: "#ef4444", marginBottom: "12px" }} />
          <h3 style={{ margin: "0 0 8px 0", color: "#ef4444" }}>Lỗi truy xuất kho video</h3>
          <p style={{ margin: "0 0 16px 0", color: "var(--theme-text-secondary)" }}>{error}</p>
          <Button variant="primary" size="md" onClick={loadRecordings}>
            <RefreshCw size={14} style={{ marginRight: "6px" }} /> Thử lại
          </Button>
        </div>
      ) : filteredRecordings.length === 0 ? (
        <div
          style={{
            background: "var(--theme-bg-surface)",
            border: "1px dashed var(--theme-border)",
            borderRadius: "12px",
            padding: "60px 24px",
            textAlign: "center",
          }}
        >
          <FolderOpen size={48} style={{ color: "var(--theme-text-muted)", marginBottom: "16px" }} />
          <h3 style={{ margin: "0 0 8px 0", color: "var(--theme-text-primary)" }}>
            Chưa có video ghi hình nào trong thư mục recording
          </h3>
          <p
            style={{
              maxWidth: "540px",
              margin: "0 auto 16px auto",
              fontSize: "14px",
              lineHeight: 1.6,
              color: "var(--theme-text-secondary)",
            }}
          >
            Khi bất kỳ camera nào chuyển sang trạng thái <strong>ONLINE</strong>, hệ thống sẽ tự động ghi hình liên tục và cắt thành các phân đoạn chuẩn xác đúng <strong>1 phút/video</strong> lưu tại:
            <br />
            <code>C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\recording\{"{camera_id}"}\...</code>
          </p>
          <Button variant="outline" size="md" onClick={loadRecordings}>
            <RefreshCw size={14} style={{ marginRight: "6px" }} /> Quét lại thư mục
          </Button>
        </div>
      ) : viewMode === "grid" ? (
        <div className="recording-grid">
          {filteredRecordings.map((recording) => {
            const isSecurity = recording.eventType === "SECURITY_EVENT";

            return (
              <div key={recording.id} className="recording-card">
                {/* Thumbnail / Media Container */}
                <div
                  className="recording-card__media"
                  onClick={() => setActiveVideo(recording)}
                >
                  <video
                    src={`${recording.videoUrl}#t=0.001`}
                    preload="metadata"
                    muted
                    playsInline
                    className="recording-card__thumb"
                  />
                  <div className="recording-card__play-overlay">
                    <div className="recording-play-icon-circle">
                      <Play size={22} fill="currentColor" style={{ marginLeft: "3px" }} />
                    </div>
                  </div>

                  {/* Event Type Badge */}
                  <span
                    className={`recording-card__event-badge ${
                      isSecurity ? "recording-badge--security" : "recording-badge--normal"
                    }`}
                  >
                    {isSecurity ? (
                      <>
                        <ShieldAlert size={12} /> Security Event
                      </>
                    ) : (
                      <>
                        <Video size={12} /> 1-Min Segment
                      </>
                    )}
                  </span>

                  {/* Duration Badge */}
                  <span className="recording-card__duration-badge">
                    <Clock size={11} /> {recording.duration}
                  </span>
                </div>

                {/* Card Body */}
                <div className="recording-card__body">
                  <div className="recording-card__cam-info">
                    <div>
                      <span className="recording-card__cam-code">
                        {recording.cameraId}
                      </span>
                      <h4 className="recording-card__cam-name">
                        {recording.cameraName}
                      </h4>
                    </div>
                  </div>

                  <div className="recording-card__meta-list">
                    <div className="recording-card__meta-item">
                      <Calendar size={13} />
                      <span>{formatDisplayTime(recording.startTime)}</span>
                    </div>
                    <div className="recording-card__meta-item">
                      <HardDrive size={13} />
                      <span>
                        {recording.building} ({recording.floor}) — {recording.fileSize}
                      </span>
                    </div>
                  </div>

                  <div className="recording-card__actions">
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={() => setActiveVideo(recording)}
                    >
                      <Play size={13} style={{ marginRight: "4px" }} /> Phát video
                    </Button>

                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        window.open(recording.downloadUrl || recording.videoUrl, "_blank");
                      }}
                      title="Tải video xuống máy"
                    >
                      <Download size={13} />
                    </Button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        /* Table View */
        <div className="recording-table-container">
          <table className="recording-table">
            <thead>
              <tr>
                <th>Xem trước</th>
                <th>Camera ID / Tên</th>
                <th>Tên file trên đĩa</th>
                <th>Thời gian bắt đầu</th>
                <th>Thời lượng</th>
                <th>Dung lượng</th>
                <th>Vị trí</th>
                <th style={{ textAlign: "right" }}>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {filteredRecordings.map((recording) => {
                return (
                  <tr key={recording.id}>
                    <td>
                      <video
                        src={`${recording.videoUrl}#t=0.001`}
                        preload="metadata"
                        muted
                        playsInline
                        className="recording-table-thumb"
                        onClick={() => setActiveVideo(recording)}
                      />
                    </td>
                    <td>
                      <span
                        style={{
                          fontSize: "11px",
                          fontWeight: 700,
                          color: "#00a3ff",
                          marginRight: "6px",
                        }}
                      >
                        {recording.cameraId}
                      </span>
                      <div style={{ fontWeight: 600 }}>{recording.cameraName}</div>
                    </td>
                    <td>
                      <span style={{ fontSize: "12px", fontFamily: "monospace", color: "var(--theme-text-muted)" }}>
                        {recording.filename}
                      </span>
                    </td>
                    <td>{formatDisplayTime(recording.startTime)}</td>
                    <td>
                      <span style={{ fontWeight: 600, color: "#10b981" }}>
                        {recording.duration}
                      </span>
                    </td>
                    <td>{recording.fileSize}</td>
                    <td>
                      {recording.building} ({recording.floor})
                    </td>
                    <td style={{ textAlign: "right" }}>
                      <div style={{ display: "inline-flex", gap: "6px" }}>
                        <Button
                          variant="primary"
                          size="sm"
                          onClick={() => setActiveVideo(recording)}
                        >
                          <Play size={12} />
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => window.open(recording.downloadUrl || recording.videoUrl, "_blank")}
                        >
                          <Download size={12} />
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Video Playback Modal Player */}
      {activeVideo && (
        <div
          className="recording-player-modal-overlay"
          onClick={() => setActiveVideo(null)}
        >
          <div
            className="recording-player-modal"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="recording-player-modal__header">
              <div className="recording-player-modal__title-group">
                <Video size={18} style={{ color: "#00a3ff" }} />
                <div>
                  <div className="recording-player-modal__title">
                    [{activeVideo.cameraId}] {activeVideo.cameraName}
                  </div>
                  <div style={{ fontSize: "12px", color: "#9ca3af" }}>
                    {formatDisplayTime(activeVideo.startTime)} — Thời lượng: {activeVideo.duration} ({activeVideo.fileSize})
                  </div>
                </div>
              </div>

              <button
                type="button"
                className="recording-player-modal__close"
                onClick={() => setActiveVideo(null)}
              >
                <X size={20} />
              </button>
            </div>

            <div className="recording-player-modal__video-wrapper">
              <video
                src={activeVideo.videoUrl}
                controls
                autoPlay
                preload="auto"
                type="video/mp4"
                className="recording-player-modal__video"
              >
                Trình duyệt không hỗ trợ phát thẻ video HTML5.
              </video>
            </div>

            <div className="recording-player-modal__footer">
              <div className="recording-player-modal__meta-grid">
                <div>
                  <strong>Phân loại:</strong> Ghi hình giám sát 1 phút chuẩn xác (CFR 25 FPS)
                </div>
                <div>
                  <strong>Độ phân giải & Codec:</strong> {activeVideo.resolution} — {activeVideo.format}
                </div>
                <div>
                  <strong>Vị trí:</strong> {activeVideo.building} - {activeVideo.areaName}
                </div>
                <div>
                  <strong>Đường dẫn file:</strong> {activeVideo.storagePath}
                </div>
              </div>

              <Button
                variant="primary"
                size="md"
                onClick={() => window.open(activeVideo.downloadUrl || activeVideo.videoUrl, "_blank")}
              >
                <Download size={14} style={{ marginRight: "6px" }} /> Tải xuống file MP4
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
