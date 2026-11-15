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

# Khoá trong payload -> (thuộc tính settings, đích áp lên pipeline đang chạy).
# Đích: ("engine", tên thuộc tính của FrameAnalysisEngine) | ("pipeline", tên thuộc tính của VideoPipeline) | None
# (None: code đọc thẳng settings ở mỗi lần dùng, ví dụ ngưỡng khớp mặt, timeout / TTL check-entry).
RUNTIME_FIELDS: Dict[str, Tuple[str, Optional[Tuple[str, str]]]] = {
    "face_match_threshold": ("FACE_MATCH_THRESHOLD", None),
    "max_inactive_seconds": ("DEFAULT_TRACK_MAX_INACTIVE_SECONDS", ("engine", "max_inactive_seconds")),
    "unknown_alert_cooldown": ("DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS", ("engine", "unknown_alert_cooldown")),
    "access_check_timeout_seconds": ("ACCESS_CHECK_TIMEOUT_SECONDS", None),
    "access_decision_cache_ttl": ("ACCESS_DECISION_CACHE_TTL", None),
    "access_deny_cache_ttl": ("ACCESS_DENY_CACHE_TTL", None),
    # V125: chính sách cảnh báo
    "identity_grace_seconds": ("ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS", ("engine", "access_grace_period")),
    "unauthorized_alert_cooldown": ("DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS", ("engine", "unauthorized_alert_cooldown")),
    "suspicious_motion_cooldown": ("SUSPICIOUS_MOTION_COOLDOWN_SECONDS", ("pipeline", "_suspicious_motion_cooldown")),
    # V126: thời hạn lưu trữ video ghi hình (giờ)
    "recording_retention_hours": ("RECORDING_RETENTION_HOURS", None),
}


def parse_runtime_config(data: Dict[str, Any]) -> Tuple[Dict[str, float], List[str]]:
    """Lọc các khoá runtime có trong payload. Trả (giá trị dùng được, danh sách lỗi)."""
    values: Dict[str, float] = {}
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
        if not math.isfinite(val) or val <= 0:
            errors.append(f"{key}={raw} phải là số dương")
            continue
        if key == "face_match_threshold" and val > 1.0:
            errors.append(f"{key}={raw} phải trong (0, 1]")
            continue
        values[key] = val
    return values, errors


def _apply_to_pipeline(pipeline: Any, values: Dict[str, float]) -> None:
    engine = getattr(pipeline, "analysis_engine", None)
    for key, val in values.items():
        target = RUNTIME_FIELDS[key][1]
        if target is None:
            continue
        kind, attr = target
        obj = engine if kind == "engine" else pipeline
        if obj is not None:
            setattr(obj, attr, val)


def apply_runtime_config(
    data: Dict[str, Any],
    default_pipeline: Any = None,
    workers: Iterable[Any] = (),
) -> Tuple[Dict[str, float], List[str]]:
    """Áp các khoá dùng được vào settings, pipeline mặc định và mọi worker. Khoá sai bị bỏ qua và ghi log."""
    values, errors = parse_runtime_config(data)
    for err in errors:
        logger.warning(f"⚠️ [RuntimeConfig] {err}, bỏ qua")
    if not values:
        return values, errors

    for key, val in values.items():
        setattr(settings, RUNTIME_FIELDS[key][0], val)
    if default_pipeline is not None:
        _apply_to_pipeline(default_pipeline, values)
    for worker in list(workers):
        pipeline = getattr(worker, "pipeline", None)
        if pipeline is not None:
            _apply_to_pipeline(pipeline, values)

    logger.info(f"✅ [RuntimeConfig] Đã áp cấu hình runtime AI: {values}")
    return values, errors


def current_runtime_config() -> Dict[str, float]:
    """Giá trị AI đang dùng cho mọi khoá runtime (đọc từ settings)."""
    return {key: getattr(settings, attr) for key, (attr, _) in RUNTIME_FIELDS.items()}
