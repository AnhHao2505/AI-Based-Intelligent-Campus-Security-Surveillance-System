package com.fa26se040.icss.dto.accessrequest;

/**
 * Gợi ý mã thành viên cho ô nhập đơn nhóm (autocomplete). Cố ý CHỈ có mã + họ tên — không email, role, id, cấp
 * (CLAUDE.md mục 9a: không mở đường quét danh bạ).
 */
public record MemberSuggestion(String userCode, String fullName) {
}
