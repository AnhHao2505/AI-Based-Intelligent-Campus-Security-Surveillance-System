import React, { useState, useEffect, useRef, useCallback } from "react";
import { Client } from "@stomp/stompjs";
import {
  Search,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
  Radio,
  FileText,
  BadgeCheck,
  UserCheck,
  KeyRound,
  Calendar,
} from "lucide-react";
import { fetchCameraAccessLogs, fetchCameraAccessLogStats } from "../../services/cameraAccessLogService";
import { LoadingState, EmptyState } from "../ui";
import { ACCESS_RESULT_META } from "../../utils/accessHistoryHelpers";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

const FILTER_CONFIG = [
  { key: "ALL", label: "Tất cả", color: "#64748b", icon: null },
  // Nhãn + icon dùng chung với trang lịch sử ra vào (utils/accessHistoryHelpers); màu giữ nguyên của tab
  { key: "AUTHORIZED_ACCESS", label: ACCESS_RESULT_META.AUTHORIZED_ACCESS.label, color: "#10b981", icon: ACCESS_RESULT_META.AUTHORIZED_ACCESS.icon },
  { key: "UNAUTHORIZED_ACCESS", label: ACCESS_RESULT_META.UNAUTHORIZED_ACCESS.label, color: "#ef4444", icon: ACCESS_RESULT_META.UNAUTHORIZED_ACCESS.icon },
  { key: "ACCESS_OUT_OF_TIME", label: ACCESS_RESULT_META.ACCESS_OUT_OF_TIME.label, color: "#f59e0b", icon: ACCESS_RESULT_META.ACCESS_OUT_OF_TIME.icon },
  { key: "UNKNOWN_PERSON", label: ACCESS_RESULT_META.UNKNOWN_PERSON.label, color: "#8b5cf6", icon: ACCESS_RESULT_META.UNKNOWN_PERSON.icon },
  { key: "INTRUSION", label: ACCESS_RESULT_META.INTRUSION.label, color: "#eab308", icon: ACCESS_RESULT_META.INTRUSION.icon },
];

export default function CameraAccessLogsTab({ camera }) {
  const [logs, setLogs] = useState([]);
  const [stats, setStats] = useState({
    totalToday: 0,
    authorizedToday: 0,
    unauthorizedToday: 0,
    outOfTimeToday: 0,
    unknownToday: 0,
    intrusionToday: 0,
  });

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [activeFilter, setActiveFilter] = useState("ALL");
  const [searchTerm, setSearchTerm] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [wsConnected, setWsConnected] = useState(false);

  // Debounce search term input
  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedSearch(searchTerm);
      setPage(0);
    }, 400);
    return () => clearTimeout(handler);
  }, [searchTerm]);

  const loadStats = useCallback(async () => {
    if (!camera?.cameraCode) return;
    try {
      const data = await fetchCameraAccessLogStats(camera.cameraCode);
      if (data) {
        setStats(data);
      }
    } catch (err) {
      console.error("Failed to load access log stats:", err);
    }
  }, [camera?.cameraCode]);

  const loadLogs = useCallback(async (isManualRefresh = false) => {
    if (!camera?.cameraCode) return;
    if (isManualRefresh) setRefreshing(true);
    else setLoading(true);

    try {
      const filterResult = activeFilter === "ALL" ? "" : activeFilter;
      const data = await fetchCameraAccessLogs(camera.cameraCode, {
        page,
        size: 20,
        result: filterResult,
        search: debouncedSearch,
      });

      if (data) {
        setLogs(data.content || []);
        setTotalPages(data.totalPages || 0);
        setTotalElements(data.totalElements || 0);
      }
    } catch (err) {
      console.error("Failed to load camera access logs:", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [camera?.cameraCode, activeFilter, debouncedSearch, page]);

  // Load logs on filter / search / page change
  useEffect(() => {
    loadLogs();
  }, [loadLogs]);

  // Load daily stats on mount and filter changes
  useEffect(() => {
    loadStats();
  }, [loadStats]);

  // STOMP WebSocket Subscription for live camera access logs
  useEffect(() => {
    if (!camera?.cameraCode) return;

    const brokerURL = API_BASE_URL.replace(/^http/, "ws") + "/ws-security";
    const token = localStorage.getItem("accessToken");

    const stompClient = new Client({
      brokerURL,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        setWsConnected(true);
        const destination = `/topic/cameras/${camera.cameraCode}/access-logs`;

        stompClient.subscribe(destination, (message) => {
          if (!message.body) return;
          try {
            const newLog = JSON.parse(message.body);
            handleIncomingLiveLog(newLog);
          } catch (err) {
            console.error("Failed to parse access log WS message:", err);
          }
        });
      },
      onDisconnect: () => setWsConnected(false),
      onWebSocketClose: () => setWsConnected(false),
      onWebSocketError: () => setWsConnected(false),
      onStompError: () => setWsConnected(false),
    });

    stompClient.activate();

    return () => {
      stompClient.deactivate();
    };
  }, [camera?.cameraCode, activeFilter]);

  const handleIncomingLiveLog = (newLog) => {
    // 1. Update stats counter
    setStats((prev) => {
      const next = { ...prev, totalToday: prev.totalToday + 1 };
      if (newLog.accessResult === "AUTHORIZED_ACCESS") next.authorizedToday += 1;
      else if (newLog.accessResult === "UNAUTHORIZED_ACCESS") next.unauthorizedToday += 1;
      else if (newLog.accessResult === "ACCESS_OUT_OF_TIME") next.outOfTimeToday += 1;
      else if (newLog.accessResult === "UNKNOWN_PERSON") next.unknownToday += 1;
      else if (newLog.accessResult === "INTRUSION") next.intrusionToday += 1;
      return next;
    });

    // 2. Prepend to logs feed if current filter matches
    setLogs((prev) => {
      if (activeFilter !== "ALL" && newLog.accessResult !== activeFilter) {
        return prev;
      }
      const itemWithHighlight = { ...newLog, isNew: true };
      return [itemWithHighlight, ...prev.slice(0, 19)];
    });
  };

  const formatLogTime = (isoString) => {
    if (!isoString) return "";
    try {
      const d = new Date(isoString);
      return {
        time: d.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }),
        date: d.toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric" }),
      };
    } catch {
      return { time: isoString, date: "" };
    }
  };

  const getSourceBadge = (source) => {
    switch (source) {
      case "ACCESS_LEVEL":
        return { label: "Cấp độ tài khoản", icon: KeyRound, bg: "rgba(59, 130, 246, 0.12)", color: "#3b82f6" };
      case "ACCESS_REQUEST":
        return { label: "Đơn bảo lãnh", icon: FileText, bg: "rgba(16, 185, 129, 0.12)", color: "#10b981" };
      case "GUEST_VISIT":
        return { label: "Khách ghé thăm", icon: UserCheck, bg: "rgba(245, 158, 11, 0.12)", color: "#f59e0b" };
      case "ASSIGNED_PERSONNEL":
        return { label: "Nhân sự phụ trách", icon: BadgeCheck, bg: "rgba(139, 92, 246, 0.12)", color: "#8b5cf6" };
      case "OPEN_EVENT":
        return { label: "Chế độ sự kiện", icon: Calendar, bg: "rgba(236, 72, 153, 0.12)", color: "#ec4899" };
      default:
        return { label: "Quét thông thường", icon: Radio, bg: "rgba(100, 116, 139, 0.12)", color: "#64748b" };
    }
  };

  const getStatCount = (key) => {
    switch (key) {
      case "ALL":
        return stats.totalToday;
      case "AUTHORIZED_ACCESS":
        return stats.authorizedToday;
      case "UNAUTHORIZED_ACCESS":
        return stats.unauthorizedToday;
      case "ACCESS_OUT_OF_TIME":
        return stats.outOfTimeToday;
      case "UNKNOWN_PERSON":
        return stats.unknownToday;
      case "INTRUSION":
        return stats.intrusionToday;
      default:
        return 0;
    }
  };

  return (
    <div className="camera-access-logs-tab">
      {/* Filter Pills with Counts & Reload Button */}
      <div className="access-filter-pills-row">
        <div className="access-filter-pills-group">
          {FILTER_CONFIG.map((filter) => {
            const Icon = filter.icon;
            const count = getStatCount(filter.key);
            const isActive = activeFilter === filter.key;

            return (
              <button
                key={filter.key}
                type="button"
                className={`access-filter-pill ${isActive ? "access-filter-pill--active" : ""}`}
                onClick={() => {
                  setActiveFilter(filter.key);
                  setPage(0);
                }}
                style={{
                  "--filter-color": filter.color,
                }}
              >
                {Icon && <Icon size={14} className="filter-pill-icon" />}
                <span className="filter-pill-label">{filter.label}</span>
                <span className="filter-pill-badge">{count}</span>
              </button>
            );
          })}
        </div>

        <button
          type="button"
          className="btn-refresh-logs"
          onClick={() => {
            loadLogs(true);
            loadStats();
          }}
          disabled={refreshing}
          title="Làm mới dữ liệu"
          aria-label="Làm mới dữ liệu"
        >
          <RefreshCw size={15} className={refreshing ? "spin-icon" : ""} />
        </button>
      </div>

      {/* Search Input Bar */}
      <div className="access-search-box">
        <Search size={16} className="search-icon" />
        <input
          type="text"
          placeholder="Tìm theo họ tên hoặc mã người dùng (VD: SE170123, Nguyen Van A)..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="search-input"
        />
        {searchTerm && (
          <button
            type="button"
            className="search-clear-btn"
            onClick={() => setSearchTerm("")}
            title="Xóa tìm kiếm"
          >
            ✕
          </button>
        )}
      </div>

      {/* Timeline Feed Container */}
      <div className="access-timeline-container">
        {loading ? (
          <div className="timeline-loading-state">
            <LoadingState message="Đang tải nhật ký ra vào..." />
          </div>
        ) : logs.length === 0 ? (
          <div className="timeline-empty-state">
            <EmptyState
              title="Chưa có lượt quét ra vào nào"
              message={
                searchTerm || activeFilter !== "ALL"
                  ? "Không có sự kiện nào phù hợp với bộ lọc hiện tại."
                  : "Dữ liệu ra vào qua camera này sẽ tự động xuất hiện theo thời gian thực khi có người quét mặt."
              }
            />
          </div>
        ) : (
          <div className="access-timeline-feed">
            {logs.map((item, idx) => {
              const { time, date } = formatLogTime(item.loggedAt);
              const sourceBadge = getSourceBadge(item.accessSource);
              const SourceIcon = sourceBadge.icon;
              const itemColor = item.colorCode || "#10b981";

              return (
                <div
                  key={item.id || idx}
                  className={`timeline-item ${item.isNew ? "timeline-item--new" : ""}`}
                  style={{ "--item-accent": itemColor }}
                >
                  {/* Left Column: Timestamp */}
                  <div className="timeline-item-time">
                    <span className="time-value font-mono">{time}</span>
                    <span className="date-value">{date}</span>
                  </div>

                  {/* Center Column: Node & Track */}
                  <div className="timeline-item-node-col">
                    <div
                      className="timeline-item-dot"
                      style={{
                        borderColor: itemColor,
                        backgroundColor: itemColor,
                        boxShadow: `0 0 10px ${itemColor}66`,
                      }}
                    />
                    <div className="timeline-item-line" />
                  </div>

                  {/* Right Column: Card Content */}
                  <div className="timeline-item-card">
                    <div className="timeline-card-header">
                      <div className="status-badges-group">
                        <span
                          className="access-result-badge"
                          style={{
                            color: itemColor,
                            backgroundColor: `${itemColor}15`,
                            borderColor: `${itemColor}40`,
                          }}
                        >
                          {item.resultDescription || item.accessResult}
                        </span>

                        <span
                          className="access-source-badge"
                          style={{
                            color: sourceBadge.color,
                            backgroundColor: sourceBadge.bg,
                          }}
                        >
                          <SourceIcon size={12} />
                          <span>{sourceBadge.label}</span>
                        </span>
                      </div>

                      {item.userAccessLevel !== null && item.userAccessLevel !== undefined && (
                        <span className="user-level-badge">
                          Level {item.userAccessLevel}
                        </span>
                      )}
                    </div>

                    <div className="timeline-card-body">
                      <div className="person-identity">
                        <h4 className="person-name">
                          {item.accessResult === "UNKNOWN_PERSON"
                            ? (item.fullName && item.fullName !== "Chưa xác định" ? item.fullName : "Người lạ / Chưa rõ mặt")
                            : (item.fullName || (item.userCode?.startsWith("UNKNOWN") ? "Người lạ / Chưa rõ mặt" : "Chưa xác định"))}
                        </h4>
                        <span className="person-code font-mono">
                          [{item.accessResult === "UNKNOWN_PERSON" ? "UNKNOWN" : (item.userCode || "N/A")}]
                        </span>
                        {item.areaName && (
                          <span className="person-area-tag">
                            Khu vực: {item.areaName}
                          </span>
                        )}
                      </div>

                      {item.reasonDetails && (
                        <p className="timeline-card-reason">
                          {item.accessResult === "UNKNOWN_PERSON" && (item.reasonDetails.includes("không tồn tại") || item.reasonDetails.includes("đã bị xoá"))
                            ? "[UNKNOWN_PERSON] Phát hiện đối tượng chưa đăng ký / người lạ (chưa rõ mặt) tại khu vực giám sát."
                            : item.reasonDetails}
                        </p>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Pagination Controls */}
      {totalPages > 1 && (
        <div className="access-timeline-pagination">
          <span className="pagination-info">
            Hiển thị <strong>{logs.length}</strong> / <strong>{totalElements}</strong> lượt quét
          </span>
          <div className="pagination-nav-buttons">
            <button
              type="button"
              className="pagination-btn"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0 || loading}
            >
              <ChevronLeft size={16} />
              <span>Trước</span>
            </button>
            <span className="pagination-page-indicator">
              Trang {page + 1} / {totalPages}
            </span>
            <button
              type="button"
              className="pagination-btn"
              onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1 || loading}
            >
              <span>Sau</span>
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
