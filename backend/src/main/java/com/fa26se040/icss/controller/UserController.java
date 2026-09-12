package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserPageResponse;
import com.fa26se040.icss.dto.user.ImportBatchSummaryResponse;
import com.fa26se040.icss.dto.user.BatchUserResponse;
import com.fa26se040.icss.dto.user.BatchDeleteResponse;
import com.fa26se040.icss.dto.user.BatchRestoreResponse;
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

import java.nio.charset.StandardCharsets;
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
    public ResponseEntity<BulkImportResponse> bulkImportNormalUsers(
            @RequestParam("file") MultipartFile file
    ) {
        log.info("Received request for bulk import normal users with file: {}", file.getOriginalFilename());
        BulkImportResponse response = userService.bulkImportNormalUsers(file);
        return ResponseEntity.ok(response);
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
    public ResponseEntity<BulkImportResponse> bulkImportStaffUsers(
            @RequestParam("file") MultipartFile file
    ) {
        log.info("Received request for bulk import staff users with file: {}", file.getOriginalFilename());
        BulkImportResponse response = userService.bulkImportStaffUsers(file);
        return ResponseEntity.ok(response);
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
    public ResponseEntity<StaffAccountCreateResponse> createStaffAccount(
            @Valid @ModelAttribute StaffAccountCreateRequest request
    ) {
        log.info("Request received to create staff account for userCode: {}", request.getUserCode());
        StaffAccountCreateResponse response = userService.createStaffAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserPageResponse> getUsers(
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
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{code}")
    public ResponseEntity<UserInfo> getUserByCode(@PathVariable String code) {
        log.info("Received request to get user by code: {}", code);
        UserInfo userInfo = userService.getUserByCode(code);
        return ResponseEntity.ok(userInfo);
    }

    @PatchMapping("/{id}/toggle-active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserListResponse> toggleActive(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication != null ? authentication.getName() : null;
        log.info("Admin {} toggling active for user {}", currentUserEmail, id);
        UserListResponse response = userService.toggleActive(id, currentUserEmail);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> softDelete(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication != null ? authentication.getName() : null;
        log.info("Admin {} soft-deleting user {}", currentUserEmail, id);
        userService.softDelete(id, currentUserEmail);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/import-batches")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<ImportBatchSummaryResponse>> getImportBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int cappedSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);
        Page<ImportBatchSummaryResponse> result = userService.getImportBatches(pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/import-batches/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BatchUserResponse>> getBatchDetails(
            @PathVariable UUID batchId
    ) {
        log.info("Admin requested details for import batch: {}", batchId);
        List<BatchUserResponse> users = userService.getBatchDetails(batchId);
        return ResponseEntity.ok(users);
    }

    @DeleteMapping("/import-batches/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchDeleteResponse> deleteBatch(
            @PathVariable UUID batchId
    ) {
        log.info("Admin soft-deleting import batch: {}", batchId);
        BatchDeleteResponse response = userService.deleteBatch(batchId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/import-batches/{batchId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchRestoreResponse> restoreBatch(
            @PathVariable UUID batchId
    ) {
        log.info("Admin restoring import batch: {}", batchId);
        BatchRestoreResponse response = userService.restoreBatch(batchId);
        return ResponseEntity.ok(response);
    }
}
