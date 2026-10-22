import React, { useState, useMemo } from "react";
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
} from "lucide-react";
import PageHeader from "../../components/ui/PageHeader";
import Button from "../../components/ui/Button";
import {
  RECORDING_TIME_RANGES,
  EVENT_TYPE_OPTIONS,
  MOCK_RECORDINGS,
  SECURITY_SEVERITY_LEVELS,
} from "../../services/recordingService";
import "../../styles/RecordingPage.css";

export default function RecordingPage() {
  // Filters state
  const [timeRange, setTimeRange] = useState("today");
  const [customFromDate, setCustomFromDate] = useState("2026-10-22");
  const [customToDate, setCustomToDate] = useState("2026-10-22");
  const [customFromTime, setCustomFromTime] = useState("00:00");
  const [customToTime, setCustomToTime] = useState("23:59");

  const [selectedCameraId, setSelectedCameraId] = useState("");
  const [selectedEventType, setSelectedEventType] = useState("");
  const [searchKeyword, setSearchKeyword] = useState("");

  // View mode: grid or table
  const [viewMode, setViewMode] = useState("grid");

  // Selected video for modal player
  const [activeVideo, setActiveVideo] = useState(null);

  // Extract unique camera list for dropdown
  const cameraOptions = useMemo(() => {
    const map = new Map();
    MOCK_RECORDINGS.forEach((rec) => {
      if (!map.has(rec.cameraId)) {
        map.set(rec.cameraId, `${rec.cameraId} - ${rec.cameraName}`);
      }
    });
    return Array.from(map.entries()).map(([value, label]) => ({
      value,
      label,
    }));
  }, []);

  // Filtered recordings logic
  const filteredRecordings = useMemo(() => {
    return MOCK_RECORDINGS.filter((item) => {
      // Filter by Camera
      if (selectedCameraId && item.cameraId !== selectedCameraId) {
        return false;
      }

      // Filter by Event Type
      if (selectedEventType && item.eventType !== selectedEventType) {
        return false;
      }

      // Filter by Keyword (search in camera, notes, incident code, area)
      if (searchKeyword.trim()) {
        const kw = searchKeyword.toLowerCase();
        const matches =
          item.cameraId.toLowerCase().includes(kw) ||
          item.cameraName.toLowerCase().includes(kw) ||
          item.areaName.toLowerCase().includes(kw) ||
          item.building.toLowerCase().includes(kw) ||
          (item.incidentCode && item.incidentCode.toLowerCase().includes(kw)) ||
          item.notes.toLowerCase().includes(kw);
        if (!matches) return false;
      }

      // Filter by Time Range
      const itemDate = new Date(item.startTime);
      const now = new Date("2026-10-22T09:00:00+07:00"); // anchored system time

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
          const fromDt = new Date(`${customFromDate}T${customFromTime}:00+07:00`);
          if (itemDate < fromDt) return false;
        }
        if (customToDate && customToTime) {
          const toDt = new Date(`${customToDate}T${customToTime}:59+07:00`);
          if (itemDate > toDt) return false;
        }
      }

      return true;
    });
  }, [
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

  const handleResetFilters = () => {
    setTimeRange("today");
    setSelectedCameraId("");
    setSelectedEventType("");
    setSearchKeyword("");
    setCustomFromDate("2026-10-22");
    setCustomToDate("2026-10-22");
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
      <PageHeader
        title="Kho Lưu Trữ Video Ghi Hình (Video Recordings)"
        description="Quản lý và tra cứu video lưu trữ tập trung từ toàn bộ camera trong khuôn viên. Hỗ trợ lọc theo thời gian, thiết bị camera và phân loại sự kiện."
      />

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
          <div className="recording-stat-card__icon recording-stat-card__icon--red">
            <ShieldAlert size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{securityEventsCount}</span>
            <span className="recording-stat-card__label">Sự kiện An ninh (Security)</span>
          </div>
        </div>

        <div className="recording-stat-card">
          <div className="recording-stat-card__icon recording-stat-card__icon--green">
            <CheckCircle2 size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{normalRecordingsCount}</span>
            <span className="recording-stat-card__label">Ghi hình định kỳ (Normal)</span>
          </div>
        </div>

        <div className="recording-stat-card">
          <div className="recording-stat-card__icon recording-stat-card__icon--purple">
            <Camera size={22} />
          </div>
          <div className="recording-stat-card__content">
            <span className="recording-stat-card__value">{cameraOptions.length}</span>
            <span className="recording-stat-card__label">Camera có dữ liệu ghi</span>
          </div>
        </div>
      </div>

      {/* Multi-Parameter Filter Card */}
      <div className="recording-filter-card">
        <div className="recording-filter-bar">
          {/* Time Range Dropdown */}
          <div className="recording-filter-group">
            <label>
              <Calendar size={14} /> Thời gian (Time Range)
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
              <Camera size={14} /> Camera ID / Tên Camera
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
              <Filter size={14} /> Loại sự kiện (Event Type)
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
              <Search size={14} /> Tìm kiếm từ khóa
            </label>
            <input
              type="text"
              placeholder="Nhập mã sự kiện, tên camera, vị trí..."
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
              <label>Từ ngày (From Date):</label>
              <input
                type="date"
                value={customFromDate}
                onChange={(e) => setCustomFromDate(e.target.value)}
              />
              <label>Giờ (From Hour):</label>
              <input
                type="time"
                value={customFromTime}
                onChange={(e) => setCustomFromTime(e.target.value)}
              />
            </div>

            <div className="recording-custom-time-group">
              <label>Đến ngày (To Date):</label>
              <input
                type="date"
                value={customToDate}
                onChange={(e) => setCustomToDate(e.target.value)}
              />
              <label>Giờ (To Hour):</label>
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
          Tìm thấy <strong>{filteredRecordings.length}</strong> video ghi hình phù hợp
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
      {filteredRecordings.length === 0 ? (
        <div
          style={{
            background: "var(--theme-bg-surface)",
            border: "1px dashed var(--theme-border)",
            borderRadius: "12px",
            padding: "48px 24px",
            textAlign: "center",
          }}
        >
          <Video size={48} style={{ color: "var(--theme-text-muted)", marginBottom: "12px" }} />
          <h3 style={{ margin: "0 0 6px 0", color: "var(--theme-text-primary)" }}>
            Không tìm thấy video ghi hình nào
          </h3>
          <p style={{ margin: 0, fontSize: "14px", color: "var(--theme-text-secondary)" }}>
            Vui lòng thử điều chỉnh lại bộ lọc thời gian, camera hoặc loại sự kiện.
          </p>
        </div>
      ) : viewMode === "grid" ? (
        <div className="recording-grid">
          {filteredRecordings.map((recording) => {
            const isSecurity = recording.eventType === "SECURITY_EVENT";
            const sev = recording.severity
              ? SECURITY_SEVERITY_LEVELS[recording.severity]
              : null;

            return (
              <div key={recording.id} className="recording-card">
                {/* Thumbnail / Media Container */}
                <div
                  className="recording-card__media"
                  onClick={() => setActiveVideo(recording)}
                >
                  {recording.videoUrl ? (
                    <video
                      src={`${recording.videoUrl}#t=0.001`}
                      preload="metadata"
                      muted
                      playsInline
                      className="recording-card__thumb"
                    />
                  ) : (
                    <img
                      src={recording.thumbnailUrl}
                      alt={recording.cameraName}
                      className="recording-card__thumb"
                    />
                  )}
                  <div className="recording-card__play-overlay">
                    <div className="recording-play-icon-circle">
                      <Play size={22} fill="currentColor" style={{ marginLeft: "3px" }} />
                    </div>
                  </div>

                  {/* Event Type Badge */}
                  <span
                    className={`recording-card__event-badge ${isSecurity
                      ? "recording-badge--security"
                      : "recording-badge--normal"
                      }`}
                  >
                    {isSecurity ? (
                      <>
                        <ShieldAlert size={12} /> Security Event
                      </>
                    ) : (
                      <>
                        <Video size={12} /> Normal Rec
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
                    {sev && (
                      <span
                        style={{
                          fontSize: "11px",
                          fontWeight: 600,
                          padding: "2px 6px",
                          borderRadius: "4px",
                          background: sev.bg,
                          color: sev.border,
                          border: `1px solid ${sev.border}`,
                        }}
                      >
                        {sev.label}
                      </span>
                    )}
                  </div>

                  <div className="recording-card__meta-list">
                    <div className="recording-card__meta-item">
                      <Calendar size={13} />
                      <span>{formatDisplayTime(recording.startTime)}</span>
                    </div>
                    <div className="recording-card__meta-item">
                      <HardDrive size={13} />
                      <span>{recording.building} ({recording.floor})</span>
                    </div>
                    {recording.hasIncidentTrigger && (
                      <div
                        className="recording-card__meta-item"
                        style={{ color: "#ef4444", fontWeight: 500 }}
                      >
                        <AlertTriangle size={13} />
                        <span>Mã sự cố: {recording.incidentCode}</span>
                      </div>
                    )}
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
                        window.open(recording.videoUrl, "_blank");
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
                <th>Loại sự kiện</th>
                <th>Thời gian ghi hình</th>
                <th>Thời lượng</th>
                <th>Vị trí</th>
                <th style={{ textAlign: "right" }}>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {filteredRecordings.map((recording) => {
                const isSecurity = recording.eventType === "SECURITY_EVENT";

                return (
                  <tr key={recording.id}>
                    <td>
                      {recording.videoUrl ? (
                        <video
                          src={`${recording.videoUrl}#t=0.001`}
                          preload="metadata"
                          muted
                          playsInline
                          className="recording-table-thumb"
                          onClick={() => setActiveVideo(recording)}
                        />
                      ) : (
                        <img
                          src={recording.thumbnailUrl}
                          alt="thumb"
                          className="recording-table-thumb"
                          onClick={() => setActiveVideo(recording)}
                        />
                      )}
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
                      {isSecurity ? (
                        <span
                          style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: "4px",
                            padding: "3px 8px",
                            borderRadius: "6px",
                            fontSize: "12px",
                            fontWeight: 600,
                            background: "rgba(239, 68, 68, 0.15)",
                            color: "#ef4444",
                          }}
                        >
                          <ShieldAlert size={12} /> Security Event
                        </span>
                      ) : (
                        <span
                          style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: "4px",
                            padding: "3px 8px",
                            borderRadius: "6px",
                            fontSize: "12px",
                            fontWeight: 600,
                            background: "rgba(16, 185, 129, 0.15)",
                            color: "#10b981",
                          }}
                        >
                          <Video size={12} /> Normal Rec
                        </span>
                      )}
                    </td>
                    <td>{formatDisplayTime(recording.startTime)}</td>
                    <td>{recording.duration}</td>
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
                          onClick={() => window.open(recording.videoUrl, "_blank")}
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
                    {formatDisplayTime(activeVideo.startTime)} — {activeVideo.duration}
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
                  <strong>Loại sự kiện:</strong>{" "}
                  {activeVideo.eventType === "SECURITY_EVENT"
                    ? "Sự kiện an ninh (Security Event)"
                    : "Ghi hình định kỳ (Normal Recording)"}
                </div>
                <div>
                  <strong>Tòa nhà / Khu vực:</strong> {activeVideo.building} -{" "}
                  {activeVideo.areaName}
                </div>
                <div>
                  <strong>Đường dẫn file:</strong> {activeVideo.storagePath}
                </div>
                {activeVideo.notes && (
                  <div>
                    <strong>Ghi chú:</strong> {activeVideo.notes}
                  </div>
                )}
              </div>

              <Button
                variant="primary"
                size="md"
                onClick={() => window.open(activeVideo.videoUrl, "_blank")}
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
