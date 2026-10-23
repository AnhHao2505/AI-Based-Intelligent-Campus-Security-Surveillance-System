package com.fa26se040.icss.validation;

import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.CreateCameraRequest;
import com.fa26se040.icss.dto.camera.UpdateCameraRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kiểm thử chuẩn hóa Validation Message tiếng Việt cho Camera Requests")
class CameraRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("1. Happy Path: CameraStreamConfigRequest hợp lệ không có lỗi validation")
    void testCameraStreamConfigRequest_HappyPath() {
        CameraStreamConfigRequest req = CameraStreamConfigRequest.builder()
                .host("192.168.1.100")
                .port(554)
                .username("admin")
                .password("secret123")
                .mainStreamPath("/live/ch0")
                .subStreamPath("/live/ch1")
                .retryTimeBeforeAlerting(3)
                .timeoutMs(5000)
                .build();

        Set<ConstraintViolation<CameraStreamConfigRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "Cấu hình hợp lệ không được có lỗi validation");
    }

    @Test
    @DisplayName("2. Boundary / Data Validation: CameraStreamConfigRequest kiểm tra biên port")
    void testCameraStreamConfigRequest_BoundaryPort() {
        // Port < 1
        CameraStreamConfigRequest minPortReq = CameraStreamConfigRequest.builder()
                .host("192.168.1.100")
                .port(0)
                .mainStreamPath("/live/ch0")
                .build();

        Set<ConstraintViolation<CameraStreamConfigRequest>> minViolations = validator.validate(minPortReq);
        assertFalse(minViolations.isEmpty());
        assertTrue(minViolations.stream().anyMatch(v -> v.getMessage().contains("Cổng kết nối phải lớn hơn hoặc bằng 1")));

        // Port > 65535
        CameraStreamConfigRequest maxPortReq = CameraStreamConfigRequest.builder()
                .host("192.168.1.100")
                .port(70000)
                .mainStreamPath("/live/ch0")
                .build();

        Set<ConstraintViolation<CameraStreamConfigRequest>> maxViolations = validator.validate(maxPortReq);
        assertFalse(maxViolations.isEmpty());
        assertTrue(maxViolations.stream().anyMatch(v -> v.getMessage().contains("Cổng kết nối không được vượt quá 65535")));
    }

    @Test
    @DisplayName("3. Exception / Domain Error: CameraStreamConfigRequest thiếu trường bắt buộc báo đúng tiếng Việt")
    void testCameraStreamConfigRequest_MissingRequiredFields() {
        CameraStreamConfigRequest req = CameraStreamConfigRequest.builder()
                .host("")
                .port(null)
                .mainStreamPath("")
                .build();

        Set<ConstraintViolation<CameraStreamConfigRequest>> violations = validator.validate(req);
        assertEquals(3, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Địa chỉ IP/Host không được để trống")));
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Cổng kết nối RTSP không được để trống")));
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("Đường dẫn luồng chính không được để trống")));
    }

    @Test
    @DisplayName("4. Happy Path: CreateCameraRequest hợp lệ")
    void testCreateCameraRequest_HappyPath() {
        CreateCameraRequest req = CreateCameraRequest.builder()
                .cameraCode("CAM-001")
                .name("Camera Cổng Chính")
                .areaId(UUID.randomUUID())
                .build();

        Set<ConstraintViolation<CreateCameraRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("5. Exception / Domain Error: CreateCameraRequest và UpdateCameraRequest báo lỗi tiếng Việt")
    void testCameraRequest_VietnameseMessages() {
        // CreateCameraRequest thiếu name, code quá dài
        CreateCameraRequest createReq = CreateCameraRequest.builder()
                .cameraCode("A".repeat(51))
                .name("  ")
                .areaId(null)
                .build();

        Set<ConstraintViolation<CreateCameraRequest>> createViolations = validator.validate(createReq);
        assertTrue(createViolations.stream().anyMatch(v -> v.getMessage().contains("Tên camera không được để trống")));
        assertTrue(createViolations.stream().anyMatch(v -> v.getMessage().contains("Mã camera không được vượt quá 50 ký tự")));
        assertTrue(createViolations.stream().anyMatch(v -> v.getMessage().contains("Camera phải được gán vào một khu vực")));

        // UpdateCameraRequest thiếu name
        UpdateCameraRequest updateReq = UpdateCameraRequest.builder()
                .name("")
                .build();

        Set<ConstraintViolation<UpdateCameraRequest>> updateViolations = validator.validate(updateReq);
        assertTrue(updateViolations.stream().anyMatch(v -> v.getMessage().contains("Tên camera không được để trống")));
    }
}
