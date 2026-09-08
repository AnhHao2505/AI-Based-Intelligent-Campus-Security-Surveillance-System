package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.user.UserCountsResponse;
import com.fa26se040.icss.dto.user.UserImportResponse;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserUpdateRequest;
import com.fa26se040.icss.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {

    private final UserManagementService userManagementService;

    @GetMapping
    public ResponseEntity<Page<UserListResponse>> getUsers(
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
        Page<UserListResponse> result = userManagementService.getUsers(keyword, accountType, isActive, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/counts")
    public ResponseEntity<UserCountsResponse> getUserCounts() {
        return ResponseEntity.ok(userManagementService.getUserCounts());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserImportResponse> importUsers(@RequestParam("file") MultipartFile file) {
        log.info("Admin importing normal users from file: {}", file.getOriginalFilename());
        UserImportResponse response = userManagementService.importUsers(file);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] csvData = userManagementService.generateSampleCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample_users.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csvData);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserListResponse> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        log.info("Admin updating user {}", id);
        UserListResponse response = userManagementService.updateUser(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/toggle-active")
    public ResponseEntity<UserListResponse> toggleActive(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication != null ? authentication.getName() : null;
        log.info("Admin {} toggling active for user {}", currentUserEmail, id);
        UserListResponse response = userManagementService.toggleActive(id, currentUserEmail);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDelete(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String currentUserEmail = authentication != null ? authentication.getName() : null;
        log.info("Admin {} soft-deleting user {}", currentUserEmail, id);
        userManagementService.softDelete(id, currentUserEmail);
        return ResponseEntity.noContent().build();
    }
}
