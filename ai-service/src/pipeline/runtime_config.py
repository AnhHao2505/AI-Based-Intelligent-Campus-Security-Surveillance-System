"""
Áp chính sách cảnh báo của AI do Backend gửi xuống (màn Cấu hình hệ thống, nhóm AI_CONFIG, V125).
Dùng chung cho đường MQTT (campus/edge/config/system, EdgeAgent) và đường REST (/api/v1/system-config/ai-runtime)
để hai đường không xử lý lệch nhau.
Giá trị hợp lệ được ghi vào settings (pipeline tạo sau đọc lại khi khởi tạo) và áp ngay cho pipeline mặc định
cùng mọi worker đang chạy, không cần khởi động lại.
"""
import logging
from typing import Any, Dict, Iterable, List, Tuple

from ..config import settings

logger = logging.getLogger(__name__)

# Khoá trong payload -> (thuộc tính settings, min, max). Khoảng giá trị khớp min_value / max_value của V125.
ALERT_POLICY_FIELDS: Dict[str, Tuple[str, float, float]] = {
    "identity_grace_seconds": ("ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS", 0.5, 5.0),
    "unauthorized_alert_cooldown": ("DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS", 10.0, 300.0),
    "suspicious_motion_cooldown": ("SUSPICIOUS_MOTION_COOLDOWN_SECONDS", 5.0, 300.0),
}


def parse_alert_policy(data: Dict[str, Any]) -> Tuple[Dict[str, float], List[str]]:
    """Lọc các khoá chính sách cảnh báo có trong payload. Trả (giá trị hợp lệ, danh sách lỗi)."""
    values: Dict[str, float] = {}
    errors: List[str] = []
    for key, (_, low, high) in ALERT_POLICY_FIELDS.items():
        raw = data.get(key)
        if raw is None:
            continue
        try:
            val = float(raw)
        except (TypeError, ValueError):
            errors.append(f"{key} không hợp lệ: {raw}")
            continue
        if not (low <= val <= high):
            errors.append(f"{key}={val} ngoài khoảng [{low}, {high}]")
            continue
        values[key] = val
    return values, errors


def _apply_to_pipeline(pipeline: Any, values: Dict[str, float]) -> None:
    engine = getattr(pipeline, "analysis_engine", None)
    if engine is not None:
        if "identity_grace_seconds" in values:
            engine.access_grace_period = values["identity_grace_seconds"]
        if "unauthorized_alert_cooldown" in values:
            engine.unauthorized_alert_cooldown = values["unauthorized_alert_cooldown"]
    if "suspicious_motion_cooldown" in values:
        pipeline._suspicious_motion_cooldown = values["suspicious_motion_cooldown"]


def apply_alert_policy(
    data: Dict[str, Any],
    default_pipeline: Any = None,
    workers: Iterable[Any] = (),
) -> Tuple[Dict[str, float], List[str]]:
    """Áp các khoá hợp lệ vào settings, pipeline mặc định và mọi worker. Khoá sai bị bỏ qua và ghi log."""
    values, errors = parse_alert_policy(data)
    for err in errors:
        logger.warning(f"⚠️ [RuntimeConfig] {err}, bỏ qua")
    if not values:
        return values, errors

    for key, val in values.items():
        setattr(settings, ALERT_POLICY_FIELDS[key][0], val)
    if default_pipeline is not None:
        _apply_to_pipeline(default_pipeline, values)
    for worker in list(workers):
        pipeline = getattr(worker, "pipeline", None)
        if pipeline is not None:
            _apply_to_pipeline(pipeline, values)

    logger.info(f"✅ [RuntimeConfig] Đã áp chính sách cảnh báo AI: {values}")
    return values, errors
