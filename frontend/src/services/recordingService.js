import { getAccessToken } from "./authService";

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

function getHeaders() {
  const token = getAccessToken();
  return {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

export const RECORDING_TIME_RANGES = [
  { value: "all", label: "Tất cả thời gian (All Time)" },
  { value: "today", label: "Hôm nay (Today)" },
  { value: "last_24h", label: "24 giờ qua (Last 24 Hours)" },
  { value: "last_7d", label: "7 ngày qua (Last 7 Days)" },
  { value: "custom", label: "Tùy chỉnh khoảng thời gian (Custom Range)" },
];

export const EVENT_TYPE_OPTIONS = [
  { value: "", label: "Tất cả sự kiện (All Events)" },
  { value: "NORMAL_RECORDING", label: "Ghi hình định kỳ 1 phút (Normal 1-min Segment)" },
  { value: "SECURITY_EVENT", label: "Sự kiện an ninh (Security Event)" },
];

export const SECURITY_SEVERITY_LEVELS = {
  CRITICAL: { label: "Nghiêm trọng", color: "danger", bg: "rgba(239, 68, 68, 0.15)", border: "#ef4444" },
  HIGH: { label: "Cao", color: "warning", bg: "rgba(249, 115, 22, 0.15)", border: "#f97316" },
  MEDIUM: { label: "Trung bình", color: "warning", bg: "rgba(234, 179, 8, 0.15)", border: "#eab308" },
  LOW: { label: "Thông tin", color: "info", bg: "rgba(59, 130, 246, 0.15)", border: "#3b82f6" },
};

/**
 * Lấy danh sách toàn bộ video ghi hình thực tế trên ổ cứng (C:\DATN\...\recording)
 */
export async function fetchDiskRecordings({ cameraId = "", search = "", fromDate = "", toDate = "" } = {}) {
  const params = new URLSearchParams();
  if (cameraId) params.append("cameraId", cameraId);
  if (search) params.append("search", search);
  if (fromDate) params.append("fromDate", fromDate);
  if (toDate) params.append("toDate", toDate);

  const queryStr = params.toString() ? `?${params.toString()}` : "";
  const res = await fetch(`${API_BASE_URL}/api/cameras/recordings${queryStr}`, {
    method: "GET",
    headers: getHeaders(),
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: "Không thể tải danh sách bản ghi" }));
    throw new Error(error.message || `Lỗi HTTP ${res.status}`);
  }

  const json = await res.json();
  const rawList = json.data || [];

  // Chuẩn hoá URL video sang full URL backend
  return rawList.map((item) => {
    const videoUrl = item.videoUrl?.startsWith("http")
      ? item.videoUrl
      : `${API_BASE_URL}${item.videoUrl}`;

    const downloadUrl = item.downloadUrl?.startsWith("http")
      ? item.downloadUrl
      : `${API_BASE_URL}${item.downloadUrl}`;

    return {
      ...item,
      videoUrl,
      downloadUrl,
    };
  });
}
