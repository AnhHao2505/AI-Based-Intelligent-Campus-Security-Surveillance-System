import re
from src.core.entity import SecurityAlertEvent

def test_security_alert_event_detected_at_format():
    """Kiểm tra detected_at mặc định có đúng chuẩn ISO-8601 UTC và có mili giây: YYYY-MM-DDTHH:MM:SS.sssZ"""
    event = SecurityAlertEvent()
    iso_ms_pattern = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$"
    assert re.match(iso_ms_pattern, event.detected_at), f"detected_at '{event.detected_at}' không đúng định dạng mili giây ISO-8601"
    
    # Kiểm tra khi to_dict()
    data = event.to_dict()
    assert "detected_at" in data
    assert re.match(iso_ms_pattern, data["detected_at"])
    
    # Kiểm tra truyền detected_at tùy chỉnh
    custom_ts = "2026-11-11T08:15:30.123Z"
    custom_event = SecurityAlertEvent(detected_at=custom_ts)
    assert custom_event.detected_at == custom_ts
