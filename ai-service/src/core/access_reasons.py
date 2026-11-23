"""
Phân loại lý do từ chối do Backend trả về ở check-entry (AccessDecisionService.checkCameraEntry).

Chỉ coi là "danh tính không còn" (xoá khỏi Face RAM Cache, gỡ tên khỏi track, cảnh báo như người lạ) khi Backend
nói rõ NGƯỜI / dữ liệu khuôn mặt không còn. Các câu khác cũng chứa chữ "không tồn tại" nhưng nói về camera hoặc khu vực
("Camera không tồn tại", "Khu vực không tồn tại"): không được coi là mất danh tính, nếu không một camera đã bị xoá
khỏi DB mà AI vẫn chạy sẽ xoá khuôn mặt của mọi người nó nhìn thấy khỏi cache (không nhận diện được trên mọi camera).
"""
from typing import Optional

# Mã denyReason của khách khi dữ liệu sinh trắc đã bị xoá (GuestAccessDecision.Reason.BIOMETRIC_DELETED)
IDENTITY_GONE_DENY_CODES = frozenset({"BIOMETRIC_DELETED"})

# Câu Backend trả khi người dùng nội bộ không còn (không có / đã xoá mềm), có thể kèm tiền tố "[VÀO ROI: ...]"
_USER_GONE_PHRASES = ("người dùng không tồn tại",)
_DELETED_WORDS = ("đã bị xoá", "đã bị xóa")


def is_identity_gone_reason(reason: Optional[str], deny_reason: Optional[str] = None) -> bool:
    """True khi Backend báo người dùng không tồn tại / đã xoá, hoặc dữ liệu sinh trắc của khách đã bị xoá."""
    if deny_reason and str(deny_reason).strip().upper() in IDENTITY_GONE_DENY_CODES:
        return True
    text = (reason or "").lower()
    if not text:
        return False
    if any(p in text for p in _USER_GONE_PHRASES):
        return True
    return "sinh trắc" in text and any(w in text for w in _DELETED_WORDS)
