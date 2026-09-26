package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserPageResponse;
import com.fa26se040.icss.dto.user.ImportBatchSummaryResponse;
import com.fa26se040.icss.dto.user.BatchUserResponse;
import com.fa26se040.icss.dto.user.BatchDeleteResponse;
import com.fa26se040.icss.dto.user.BatchRestoreResponse;
import com.fa26se040.icss.dto.user.UserAccessLevelUpdateRequest;
import com.fa26se040.icss.dto.user.UserSearchResponse;
import com.fa26se040.icss.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping(value = "/normal/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BulkImportResponse>> bulkImportNormalUsers(
            @RequestParam("file") MultipartFile file
    ) {
        log.info("Received request for bulk import normal users with file: {}", file.getOriginalFilename());
        BulkImportResponse response = userService.bulkImportNormalUsers(file);
        return ResponseEntity.ok(ApiResponse.success(response, "Import danh sách người dùng thành công"));
    }

    @GetMapping("/normal/bulk-import/template")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> downloadNormalUserTemplate() {
        byte[] excelData = userService.generateSampleExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample_normal_users.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelData);
    }

    @PostMapping(value = "/staff/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BulkImportResponse>> bulkImportStaffUsers(
            @RequestParam("file") MultipartFile file
    ) {
        log.info("Received request for bulk import staff users with file: {}", file.getOriginalFilename());
        BulkImportResponse response = userService.bulkImportStaffUsers(file);
        return ResponseEntity.ok(ApiResponse.success(response, "Import danh sách cán bộ/nhân viên thành công"));
    }

    @GetMapping("/staff/bulk-import/template")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> downloadStaffUserTemplate() {
        byte[] excelData = userService.generateSampleStaffExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample_staff_users.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelData);
    }

    @PostMapping(value = "/staff-accounts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StaffAccountCreateResponse>> createStaffAccount(
            @Valid @ModelAttribute StaffAccountCreateRequest request
    ) {
        log.info("Request received to create staff account for userCode: {}", request.getUserCode());
        StaffAccountCreateResponse response = userService.createStaffAccount(request);
        return new ResponseEntity<>(ApiResponse.created(response, "Tạo tài khoản nhân viên thành công"), HttpStatus.CREATED);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<UserPageResponse>> getUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        int cappedSize = Math.min(Math.max(1, size), 100);
        Sort sortOrder = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null && sort.contains(",")) {
            String[] parts = sort.split(",");
            Sort.Direction direction = parts[1].equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
            sortOrder = Sort.by(direction, parts[0]);
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize, sortOrder);
        UserPageResponse result = userService.getUsers(keyword, accountType, isActive, pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách người dùng thành công"));
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<UserInfo>> getUserByCode(@PathVariable String code) {
        log.info("Received request to get user by code: {}", code);
        UserInfo userInfo = userService.getUserByCode(code);
        return ResponseEntity.ok(ApiResponse.success(userInfo, "Lấy thông tin người dùng thành công"));
    }

    @PatchMapping("/{id}/toggle-active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserListResponse>> toggleActive(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication != null ? authentication.getName() : null;
        log.info("Admin {} toggling active for user {}", currentUserEmail, id);
        UserListResponse response = userService.toggleActive(id, currentUserEmail);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật trạng thái người dùng thành công"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> softDelete(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication != null ? authentication.getName() : null;
        log.info("Admin {} soft-deleting user {}", currentUserEmail, id);
        userService.softDelete(id, currentUserEmail);
        return ResponseEntity.ok(ApiResponse.success("Xóa người dùng thành công"));
    }

    @GetMapping("/import-batches")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<ImportBatchSummaryResponse>>> getImportBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int cappedSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);
        Page<ImportBatchSummaryResponse> result = userService.getImportBatches(pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách lô import thành công"));
    }

    @GetMapping("/import-batches/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<BatchUserResponse>>> getBatchDetails(
            @PathVariable UUID batchId
    ) {
        log.info("Admin requested details for import batch: {}", batchId);
        List<BatchUserResponse> users = userService.getBatchDetails(batchId);
        return ResponseEntity.ok(ApiResponse.success(users, "Lấy chi tiết lô import thành công"));
    }

    @DeleteMapping("/import-batches/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BatchDeleteResponse>> deleteBatch(
            @PathVariable UUID batchId
    ) {
        log.info("Admin soft-deleting import batch: {}", batchId);
        BatchDeleteResponse response = userService.deleteBatch(batchId);
        return ResponseEntity.ok(ApiResponse.success(response, "Xóa lô import thành công"));
    }

    @PostMapping("/import-batches/{batchId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BatchRestoreResponse>> restoreBatch(
            @PathVariable UUID batchId
    ) {
        log.info("Admin restoring import batch: {}", batchId);
        BatchRestoreResponse response = userService.restoreBatch(batchId);
        return ResponseEntity.ok(ApiResponse.success(response, "Khôi phục lô import thành công"));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Page<UserSearchResponse>>> searchUsers(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int cappedSize = Math.min(Math.max(1, size), 20);
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);
        Page<UserSearchResponse> result = userService.searchUsers(q, pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Tìm kiếm người dùng thành công"));
    }

    @PatchMapping("/{id}/access-level")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<UserSearchResponse>> updateAccessLevel(
            @PathVariable UUID id,
            @Valid @RequestBody UserAccessLevelUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        log.info("Facility Manager [{}] updating access level for user {}: {}", actorEmail, id, request.accessLevel());
        UserSearchResponse response = userService.updateAccessLevel(id, request.accessLevel(), request.reason(), actorEmail);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật cấp độ truy cập thành công"));
    }
}
