"""
Áp cấu hình runtime của AI do Backend gửi xuống (màn Cấu hình hệ thống, nhóm AI_CONFIG).
Một nơi duy nhất cho cả đường MQTT (campus/edge/config/system, EdgeMqttAgent) và đường REST
(/api/v1/system-config/ai-runtime), để hai đường không xử lý lệch nhau.

Khoảng giá trị hợp lệ (min/max) KHÔNG lặp lại ở đây: Backend đã kiểm theo min_value / max_value của bảng
system_configurations khi admin lưu (SystemConfigService.validateValue). AI chỉ chặn giá trị vô nghĩa
(không phải số, không dương, ngưỡng khớp mặt > 1) để không làm hỏng pipeline.

Giá trị hợp lệ được ghi vào settings (pipeline tạo sau đọc lại khi khởi tạo) và áp ngay cho pipeline mặc định
cùng mọi worker đang chạy, không cần khởi động lại.
"""
import logging
import math
from typing import Any, Dict, Iterable, List, Optional, Tuple

from ..config import settings

logger = logging.getLogger(__name__)

# V150: 6 nguồn quyền hợp lệ cho AI_NIGHT_ALLOWED_ACCESS_SOURCES (khớp AccessSource trừ NONE).
VALID_NIGHT_ALLOWED_SOURCES: Tuple[str, ...] = (
    "ASSIGNED_PERSONNEL",
    "ACCESS_REQUEST",
    "GUEST_VISIT",
    "INCIDENT_RESPONSE",
    "ACCESS_LEVEL",
    "OPEN_EVENT",
)
_VALID_NIGHT_ALLOWED_SOURCES_SET = frozenset(VALID_NIGHT_ALLOWED_SOURCES)

# Khoá trong payload -> (thuộc tính settings, đích áp lên pipeline đang chạy).
# Đích: ("engine", tên thuộc tính của FrameAnalysisEngine) | ("pipeline", tên thuộc tính của VideoPipeline)
#     | ("human_detector", tên thuộc tính của HumanDetector) | ("face_detector", tên thuộc tính của FaceDetector) | None
# (None: code đọc thẳng settings ở mỗi lần dùng, ví dụ ngưỡng khớp mặt, timeout / TTL check-entry).
RUNTIME_FIELDS: Dict[str, Tuple[str, Optional[Tuple[str, str]]]] = {
    "face_match_threshold": ("FACE_MATCH_THRESHOLD", None),
    "detection_confidence": ("DEFAULT_DETECTION_CONFIDENCE", ("human_detector", "conf_threshold")),
    "face_confidence": ("DEFAULT_FACE_CONFIDENCE", ("face_detector", "score_threshold")),
    "max_inactive_seconds": ("DEFAULT_TRACK_MAX_INACTIVE_SECONDS", ("engine", "max_inactive_seconds")),
    "unknown_alert_cooldown": ("DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS", ("engine", "unknown_alert_cooldown")),
    "access_check_timeout_seconds": ("ACCESS_CHECK_TIMEOUT_SECONDS", None),
    "access_decision_cache_ttl": ("ACCESS_DECISION_CACHE_TTL", None),
    "access_deny_cache_ttl": ("ACCESS_DENY_CACHE_TTL", None),
    # V125: chính sách cảnh báo
    "identity_grace_seconds": ("ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS", ("engine", "access_grace_period")),
    "unauthorized_alert_cooldown": ("DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS", ("engine", "unauthorized_alert_cooldown")),
    "suspicious_motion_cooldown": ("SUSPICIOUS_MOTION_COOLDOWN_SECONDS", ("pipeline", "_suspicious_motion_cooldown")),
    "suspicious_motion_min_duration": ("SUSPICIOUS_MOTION_MIN_DURATION", ("pipeline", "suspicious_motion_min_duration")),
    # V126: thời hạn lưu trữ video ghi hình (giờ)
    "recording_retention_hours": ("RECORDING_RETENTION_HOURS", None),
    # V127 (origin/main): thời gian giữ thêm (hold) khi đang thấy mặt nhưng chưa khớp, trước khi chốt cảnh báo người lạ
    "identity_hold_seconds": ("ACCESS_RESOLUTION_HOLD_SECONDS", ("engine", "identity_hold_seconds")),
    # V128 (origin/main): khoảng cách tối thiểu giữa 2 cảnh báo người lạ trên cùng một camera
    "camera_unknown_min_interval": ("CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS", ("engine", "camera_unknown_min_interval")),
    # V130: độ trễ bắt đầu và kết thúc ghi hình sự cố an ninh
    "incident_start_delay_seconds": ("INCIDENT_RECORDING_START_DELAY_SECONDS", ("pipeline", "incident_start_delay_seconds")),
    "incident_no_detection_timeout_seconds": ("INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS", ("pipeline", "incident_no_detection_timeout_seconds")),
    # V131: thời lượng mỗi phân đoạn video ghi hình camera định kỳ (giây)
    "recording_segment_seconds": ("RECORDING_SEGMENT_SECONDS", None),
}


def _parse_night_allowed_sources(raw: Any) -> Tuple[Optional[List[str]], Optional[str]]:
    """Kiểm danh sách nguồn quyền miễn giờ giới nghiêm. Sai kiểu/rỗng/giá trị lạ -> báo lỗi để giữ nguyên giá trị cũ."""
    if not isinstance(raw, list):
        return None, f"night_allowed_access_sources không hợp lệ (cần danh sách): {raw!r}"
    if len(raw) == 0:
        return None, "night_allowed_access_sources không được rỗng"
    selected = set()
    for item in raw:
        if not isinstance(item, str):
            return None, f"night_allowed_access_sources chứa phần tử sai kiểu: {item!r}"
        token = item.strip().upper()
        if token not in _VALID_NIGHT_ALLOWED_SOURCES_SET:
            return None, f"night_allowed_access_sources chứa nguồn không hợp lệ: {item!r}"
        selected.add(token)
    ordered = [src for src in VALID_NIGHT_ALLOWED_SOURCES if src in selected]
    if not ordered:
        return None, "night_allowed_access_sources không được rỗng"
    return ordered, None


def parse_runtime_config(data: Dict[str, Any]) -> Tuple[Dict[str, Any], List[str]]:
    """Lọc các khoá runtime có trong payload. Trả (giá trị dùng được, danh sách lỗi)."""
    values: Dict[str, Any] = {}
    errors: List[str] = []
    for key in RUNTIME_FIELDS:
        raw = data.get(key)
        if raw is None:
            continue
        if isinstance(raw, bool):
            errors.append(f"{key} không hợp lệ: {raw}")
            continue
        try:
            val = float(raw)
        except (TypeError, ValueError):
            errors.append(f"{key} không hợp lệ: {raw}")
            continue
        # Cho phép các tham số độ trễ sự cố bằng 0 (ghi hình ngay lập tức hoặc ngắt ngay khi hết sự cố)
        if key in ("incident_start_delay_seconds", "incident_no_detection_timeout_seconds"):
            if not math.isfinite(val) or val < 0:
                errors.append(f"{key}={raw} phải là số không âm (>= 0)")
                continue
        elif not math.isfinite(val) or val <= 0:
            errors.append(f"{key}={raw} phải là số dương")
            continue
        if key == "face_match_threshold" and (val < 0.50 or val > 0.65):
            errors.append(f"{key}={raw} phải trong [0.50, 0.65]")
            continue
        if key in ("detection_confidence", "face_confidence") and (val < 0.30 or val > 0.65):
            errors.append(f"{key}={raw} phải trong [0.30, 0.65]")
            continue
        values[key] = val

    if "night_allowed_access_sources" in data and data.get("night_allowed_access_sources") is not None:
        parsed_sources, src_err = _parse_night_allowed_sources(data.get("night_allowed_access_sources"))
        if src_err:
            errors.append(src_err)
        elif parsed_sources:
            values["night_allowed_access_sources"] = parsed_sources

    return values, errors


def _apply_to_pipeline(pipeline: Any, values: Dict[str, Any]) -> None:
    engine = getattr(pipeline, "analysis_engine", None)
    human_detector = getattr(pipeline, "human_detector", None)
    face_detector = getattr(pipeline, "face_detector", None)
    for key, val in values.items():
        if key not in RUNTIME_FIELDS:
            continue
        target = RUNTIME_FIELDS[key][1]
        if target is None:
            continue
        kind, attr = target
        if kind == "engine":
            obj = engine
        elif kind == "human_detector":
            obj = human_detector
        elif kind == "face_detector":
            obj = face_detector
        else:
            obj = pipeline
        if obj is not None:
            setattr(obj, attr, val)


def apply_runtime_config(
    data: Dict[str, Any],
    default_pipeline: Any = None,
    workers: Iterable[Any] = (),
) -> Tuple[Dict[str, Any], List[str]]:
    """Áp các khoá dùng được vào settings, pipeline mặc định và mọi worker. Khoá sai bị bỏ qua và ghi log."""
    values, errors = parse_runtime_config(data)
    for err in errors:
        logger.warning(f"⚠️ [RuntimeConfig] {err}, bỏ qua")
    if not values:
        return values, errors

    for key, val in values.items():
        if key == "night_allowed_access_sources":
            setattr(settings, "NIGHT_ALLOWED_ACCESS_SOURCES", list(val))
        else:
            setattr(settings, RUNTIME_FIELDS[key][0], val)
    if default_pipeline is not None:
        _apply_to_pipeline(default_pipeline, values)

    seg_seconds = values.get("recording_segment_seconds")
    for worker in list(workers):
        pipeline = getattr(worker, "pipeline", None)
        if pipeline is not None:
            _apply_to_pipeline(pipeline, values)
        recorder = getattr(worker, "recorder", None)
        if recorder is not None and seg_seconds is not None:
            try:
                recorder.set_segment_seconds(int(seg_seconds))
            except Exception as rec_err:
                logger.warning(f"⚠️ [RuntimeConfig] Không thể cập nhật recorder worker: {rec_err}")

    logger.info(f"✅ [RuntimeConfig] Đã áp cấu hình runtime AI: {values}")
    return values, errors


def current_runtime_config() -> Dict[str, Any]:
    """Giá trị AI đang dùng cho mọi khoá runtime (đọc từ settings)."""
    result: Dict[str, Any] = {key: getattr(settings, attr) for key, (attr, _) in RUNTIME_FIELDS.items()}
    result["night_allowed_access_sources"] = list(getattr(settings, "NIGHT_ALLOWED_ACCESS_SOURCES", []))
    return result
