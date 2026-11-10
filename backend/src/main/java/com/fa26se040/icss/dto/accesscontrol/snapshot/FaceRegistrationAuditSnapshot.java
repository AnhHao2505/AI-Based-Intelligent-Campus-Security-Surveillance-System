package com.fa26se040.icss.dto.accesscontrol.snapshot;

/**
 * PR-AUDIT: audit REGISTER_FACE. Chỉ mã và việc lần đầu / thay thế; TUYỆT ĐỐI không có embedding, URL hay object key ảnh.
 */
public record FaceRegistrationAuditSnapshot(
        String userCode,
        Boolean replaced
) implements AuditSnapshot {}
