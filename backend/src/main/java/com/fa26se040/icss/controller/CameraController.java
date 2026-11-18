package com.fa26se040.icss.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.fa26se040.icss.dto.camera.*;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.service.CameraService;
import com.fa26se040.icss.service.CameraConnectionService;
import com.fa26se040.icss.service.CameraStreamLifecycleService;
import com.fa26se040.icss.service.CameraRecordingService;
import com.fa26se040.icss.service.AnnotatedStreamTokenService;
import com.fa26se040.icss.service.RecordingStreamTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraService cameraService;
    private final CameraConnectionService cameraConnectionService;
    private final CameraStreamLifecycleService cameraStreamLifecycleService;
    private final CameraRecordingService cameraRecordingService;
    private final AnnotatedStreamTokenService annotatedStreamTokenService;
    private final RecordingStreamTokenService recordingStreamTokenService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> create(@Valid @RequestBody CreateCameraRequest req) {
        log.info("REST request to create camera: {}", req.getName());
        CameraDetailResponse response = cameraService.createCamera(req);
        return new ResponseEntity<>(ApiResponse.created(response, "Thêm camera mới thành công"), HttpStatus.CREATED);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<Page<CameraListResponse>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CameraStatus status,
            @RequestParam(required = false) OperationalStatus operationalStatus,
            @RequestParam(required = false, defaultValue = "false") Boolean forceSync,
            @PageableDefault(size = 10, sort = "cameraCode", direction = org.springframework.data.domain.Sort.Direction.ASC) Pageable pageable,
            Authentication authentication) {
        boolean isAdmin = hasAdminRole(authentication);
        boolean effectiveForceSync = Boolean.TRUE.equals(forceSync) && isAdmin;
        log.info("REST request to list cameras with filters, forceSync: {} (effective: {})", forceSync, effectiveForceSync);
        Page<CameraListResponse> list = cameraService.listCameras(search, status, operationalStatus, effectiveForceSync, pageable);
        return ResponseEntity.ok(ApiResponse.success(list, "Lấy danh sách camera thành công"));
    }

    private static boolean hasAdminRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a) || "ADMIN".equalsIgnoreCase(a));
    }

    @GetMapping("/all-simple")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<List<CameraSimpleResponse>>> getAllSimple() {
        log.info("REST request to get simple active camera list");
        return ResponseEntity.ok(ApiResponse.success(cameraService.getAllActiveSimple(), "Lấy danh sách camera hoạt động thành công"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> getDetail(@PathVariable UUID id) {
        log.info("REST request to get camera detail for id: {}", id);
        CameraDetailResponse detail = cameraService.getCameraDetail(id);
        return ResponseEntity.ok(ApiResponse.success(detail, "Lấy thông tin chi tiết camera thành công"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> updateInfo(@PathVariable UUID id, @Valid @RequestBody UpdateCameraRequest req) {
        log.info("REST request to update camera: {}", id);
        CameraDetailResponse updated = cameraService.updateCamera(id, req);
        return ResponseEntity.ok(ApiResponse.success(updated, "Cập nhật thông tin camera thành công"));
    }

    @PatchMapping("/{id}/decommission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> decommission(@PathVariable UUID id) {
        log.info("REST request to decommission camera: {}", id);
        cameraService.decommissionCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Đã dừng hoạt động camera thành công"));
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable UUID id) {
        log.info("REST request to reactivate camera: {}", id);
        cameraService.reactivateCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Đã kích hoạt lại camera thành công"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteCamera(@PathVariable UUID id) {
        log.info("REST request to soft delete camera: {}", id);
        cameraService.deleteCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Đã xóa camera thành công"));
    }

    @PutMapping("/{id}/stream-config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraStreamConfigResponse>> upsertStream(@PathVariable UUID id, @Valid @RequestBody CameraStreamConfigRequest req) {
        log.info("REST request to upsert camera stream configuration: {}", id);
        CameraStreamConfigResponse config = cameraService.upsertStreamConfig(id, req);
        return ResponseEntity.ok(ApiResponse.success(config, "Cập nhật cấu hình stream thành công"));
    }

    @PostMapping("/{id}/stream/start")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraStreamLifecycleResponse>> startStream(@PathVariable UUID id) {
        log.info("REST request to start stream and AI worker for camera: {}", id);
        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.startStream(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Kết nối Stream phát trực tiếp thành công"));
    }

    @PostMapping("/{id}/stream/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraStreamLifecycleResponse>> stopStream(@PathVariable UUID id) {
        log.info("REST request to stop stream and AI worker for camera: {}", id);
        CameraStreamLifecycleResponse response = cameraStreamLifecycleService.stopStream(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Đã Ngắt Stream phát trực tiếp của camera"));
    }

    @PostMapping("/{id}/test-connection")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TestConnectionResponse>> testConnection(
            @PathVariable UUID id,
            @RequestBody(required = false) CameraStreamConfigRequest req) {
        log.info("REST request to test RTSP stream connection for camera: {}", id);
        TestConnectionResponse response = cameraConnectionService.testConnection(id, req);
        return ResponseEntity.ok(ApiResponse.success(response, "Kiểm tra kết nối camera hoàn tất"));
    }

    @PutMapping("/{id}/roi")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> updateRoi(
            @PathVariable UUID id,
            @Valid @RequestBody RoiUpdateRequest request) {
        log.info("REST request to update ROI geometry for camera: {}", id);
        CameraDetailResponse response = cameraService.updateRoiGeometry(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật cấu hình vùng giám sát ROI thành công"));
    }

    @PatchMapping("/{id}/map-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> updateMapLocation(
            @PathVariable UUID id,
            @Valid @RequestBody CameraMapLocationRequest request) {
        log.info("REST request to update map location for camera: {}", id);
        CameraDetailResponse response = cameraService.updateMapLocation(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Cập nhật vị trí camera trên bản đồ thành công"));
    }

    @DeleteMapping("/{id}/map-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CameraDetailResponse>> clearMapLocation(@PathVariable UUID id) {
        log.info("REST request to clear map location for camera: {}", id);
        CameraDetailResponse response = cameraService.clearMapLocation(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Đã gỡ vị trí camera khỏi bản đồ"));
    }

    @PostMapping("/stream/cloud-relay")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<String>> toggleCloudRelay(
            @RequestParam String cameraCode,
            @RequestParam(defaultValue = "true") boolean active) {
        cameraStreamLifecycleService.requestCloudRelay(cameraCode, active);
        return ResponseEntity.ok(ApiResponse.success("OK", "Đã gửi lệnh điều phối Cloud Stream Relay qua MQTT"));
    }

    @GetMapping("/recordings")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<List<RecordingVideoResponse>>> listRecordings(
            @RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        log.info("REST request to list video recordings on disk, cameraId: {}, search: {}", cameraId, search);
        List<RecordingVideoResponse> list = cameraRecordingService.listRecordings(cameraId, search, fromDate, toDate);
        return ResponseEntity.ok(ApiResponse.success(list, "Lấy danh sách video ghi hình thành công"));
    }

    /**
     * BE-03 & Security Fix: Stream video ghi hình định kỳ hoặc bằng chứng sự cố an ninh.
     * Bắt buộc có token hợp lệ được cấp bởi {@link #issueRecordingStreamToken} (hoặc phiên đăng nhập hợp lệ).
     * Token được ràng buộc bảo mật với danh tính và thẩm quyền ({@code RecordingStreamTokenService.ALLOWED_AUTHORITIES}).
     */
    @GetMapping("/recordings/stream")
    public ResponseEntity<org.springframework.core.io.Resource> streamRecording(
            @RequestParam String path,
            @RequestParam(required = false, defaultValue = "recording") String source,
            @RequestParam(required = false, defaultValue = "false") boolean download,
            @RequestParam(required = false) String token,
            @RequestHeader HttpHeaders headers,
            Authentication authentication) throws IOException {
        recordingStreamTokenService.verify(path, source, token, authentication);
        log.debug("REST request to stream recording: {}, source: {}, actor: {}",
                path, source, authentication != null ? authentication.getName() : "token-bearer");
        return cameraRecordingService.streamVideo(path, source, headers, download);
    }

    /**
     * BE-03 & Security Fix: Cấp token ngắn hạn mở tệp ghi hình an ninh.
     * Chỉ người dùng có thẩm quyền (ADMIN, GUARD) mới được phép gọi.
     * Token sinh ra được gắn chữ ký số HMAC-SHA256 kèm danh tính và vai trò người gọi.
     */
    @PostMapping("/recordings/stream-token")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<RecordingStreamTokenService.TokenResponse>> issueRecordingStreamToken(
            @Valid @RequestBody RecordingStreamTokenRequest request,
            Authentication authentication) {
        String userEmail = authentication != null ? authentication.getName() : "system";
        String userRole = extractAuthorizedRole(authentication);

        RecordingStreamTokenService.TokenResponse response =
                recordingStreamTokenService.issue(request.path(), request.source(), userEmail, userRole);
        return ResponseEntity.ok(ApiResponse.success(response, "Cấp token xem video ghi hình an ninh thành công"));
    }

    private String extractAuthorizedRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return "GUARD";
        }
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            String auth = ga.getAuthority();
            if (auth != null) {
                String role = auth.startsWith("ROLE_") ? auth.substring(5) : auth;
                if (RecordingStreamTokenService.ALLOWED_AUTHORITIES.contains(role)) {
                    return role;
                }
            }
        }
        return "GUARD";
    }

    @PostMapping("/{cameraCode}/annotated-stream-token")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<AnnotatedStreamTokenService.TokenResponse>> getAnnotatedStreamToken(
            @PathVariable String cameraCode) {
        log.info("REST request to issue annotated stream token for camera: {}", cameraCode);
        AnnotatedStreamTokenService.TokenResponse response = annotatedStreamTokenService.issueForCamera(cameraCode);
        return ResponseEntity.ok(ApiResponse.success(response, "Cấp token xem luồng trực tiếp thành công"));
    }

    /**
     * F-07: Callback xác thực từ MediaMTX (authMethod: http) cho cả WebRTC WHEP và RTSP nội bộ.
     * - WebRTC WHEP (Browser): kiểm tra HMAC stream token trong query string (?token=...).
     * - RTSP nội bộ (AI Service / Edge Relay): bắt buộc user="icss-internal" và password là HMAC-SHA256 hợp lệ.
     */
    @PostMapping("/whep-auth")
    public ResponseEntity<Void> authenticateWhepStream(@RequestBody(required = false) java.util.Map<String, Object> payload) {
        if (payload == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String path = payload.get("path") != null ? String.valueOf(payload.get("path")) : "";
        String query = payload.get("query") != null ? String.valueOf(payload.get("query")) : "";
        String protocol = payload.get("protocol") != null ? String.valueOf(payload.get("protocol")).toLowerCase() : "";
        String action = payload.get("action") != null ? String.valueOf(payload.get("action")).toLowerCase() : "";
        String user = payload.get("user") != null ? String.valueOf(payload.get("user")) : "";
        String password = payload.get("password") != null ? String.valueOf(payload.get("password")) : "";

        String token = extractQueryParam(query, "token");
        if (annotatedStreamTokenService.verifyToken(path, token)) {
            return ResponseEntity.ok().build();
        }

        if ("rtsp".equals(protocol)
                && ("read".equals(action) || "publish".equals(action))
                && com.fa26se040.icss.service.MediaMtxService.INTERNAL_RTSP_USER.equals(user)
                && annotatedStreamTokenService.verifyInternalRtspSecret(path, password)) {
            return ResponseEntity.ok().build();
        }

        if ("api".equals(action)
                && com.fa26se040.icss.service.MediaMtxService.INTERNAL_RTSP_USER.equals(user)
                && annotatedStreamTokenService.verifyInternalApiSecret(password)) {
            return ResponseEntity.ok().build();
        }

        log.debug("MediaMTX auth rejected for path: {}, protocol: {}, action: {}", path, protocol, action);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String clean = query.startsWith("?") ? query.substring(1) : query;
        for (String pair : clean.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}


