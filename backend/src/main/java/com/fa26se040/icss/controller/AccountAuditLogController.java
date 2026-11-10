package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesscontrol.AuditLogResponse;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PR-AUDIT (B6): tab "Nhật ký" của màn Quản lý tài khoản — nhật ký module ACCOUNT / AUTH / SYSTEM_CONFIG.
 * Chỉ ADMIN (@PreAuthorize); trong service còn giao với audit_module_roles của role người gọi.
 * Lọc: modules (nhóm Tài khoản / Đăng nhập / Cấu hình), actions (nhiều giá trị), subject (mã tài khoản, mã lô, e-mail
 * đăng nhập, khoá cấu hình), subjectUserId (từ nút "Xem nhật ký" của một tài khoản), actor (mã / tên người thực hiện),
 * correlationId (các tài khoản của một lô), from / to. Mặc định sắp thời gian giảm dần.
 */
@RestController
@RequestMapping("/api/users/audit-logs")
@RequiredArgsConstructor
public class AccountAuditLogController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> getAccountAuditLogs(
            @RequestParam(required = false) List<String> modules,
            @RequestParam(required = false) List<AuditAction> actions,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) UUID subjectUserId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(size = 20, sort = "changedAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication
    ) {
        String role = null;
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                if (ga.getAuthority().startsWith("ROLE_")) {
                    role = ga.getAuthority().substring(5);
                    break;
                }
            }
        }
        Page<AuditLogResponse> page = auditService.getAccountAuditLogs(modules, actions, subject, subjectUserId, actor,
                correlationId, from, to, pageable, role);
        return ResponseEntity.ok(ApiResponse.success(page, "Lấy nhật ký tài khoản thành công"));
    }
}
