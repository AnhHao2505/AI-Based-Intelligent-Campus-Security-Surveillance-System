package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUpdateRequest;
import com.fa26se040.icss.dto.systemconfig.SystemConfigUpdateRequest;
import com.fa26se040.icss.dto.user.UserAccessLevelUpdateRequest;
import com.fa26se040.icss.enums.RequestStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class D6PartDTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ==========================================
    // D6-11: Ràng buộc reason 10-500 ký tự trên DTO
    // ==========================================

    @Test
    @DisplayName("D6-11: UserAccessLevelUpdateRequest - reason < 10 ký tự hoặc rỗng -> vi phạm")
    void testUserAccessLevelUpdateRequest_InvalidReason() {
        // 4 chars
        UserAccessLevelUpdateRequest req4 = new UserAccessLevelUpdateRequest(2, "ngắn");
        Set<ConstraintViolation<UserAccessLevelUpdateRequest>> violations4 = validator.validate(req4);
        assertFalse(violations4.isEmpty(), "reason 'ngắn' (4 ký tự) phải vi phạm validation");

        // 9 chars
        UserAccessLevelUpdateRequest req9 = new UserAccessLevelUpdateRequest(2, "123456789");
        Set<ConstraintViolation<UserAccessLevelUpdateRequest>> violations9 = validator.validate(req9);
        assertFalse(violations9.isEmpty(), "reason '123456789' (9 ký tự) phải vi phạm validation");

        // null
        UserAccessLevelUpdateRequest reqNull = new UserAccessLevelUpdateRequest(2, null);
        assertFalse(validator.validate(reqNull).isEmpty(), "reason null phải vi phạm");

        // 10 chars -> valid
        UserAccessLevelUpdateRequest req10 = new UserAccessLevelUpdateRequest(2, "Lý do hợp lệ đủ 10");
        assertTrue(validator.validate(req10).isEmpty(), "reason >= 10 ký tự phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: AreaAccessRulesUpdateRequest - reason 10-500 ký tự")
    void testAreaAccessRulesUpdateRequest_ReasonValidation() {
        AreaAccessRulesUpdateRequest req9 = new AreaAccessRulesUpdateRequest(2, true, "123456789");
        assertFalse(validator.validate(req9).isEmpty(), "reason 9 ký tự phải vi phạm");

        AreaAccessRulesUpdateRequest reqValid = new AreaAccessRulesUpdateRequest(2, true, "Lý do hợp lệ đủ mười ký tự");
        assertTrue(validator.validate(reqValid).isEmpty(), "reason >= 10 ký tự phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: LevelPresetUpdateRequest - reason 10-500 ký tự")
    void testLevelPresetUpdateRequest_ReasonValidation() {
        LevelPresetUpdateRequest req9 = new LevelPresetUpdateRequest(2, true, "123456789", 1L);
        assertFalse(validator.validate(req9).isEmpty(), "reason 9 ký tự phải vi phạm");

        LevelPresetUpdateRequest reqValid = new LevelPresetUpdateRequest(2, true, "Lý do hợp lệ đủ mười ký tự", 1L);
        assertTrue(validator.validate(reqValid).isEmpty(), "reason >= 10 ký tự phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: AssignedPersonnelRevokeRequest - reason 10-500 ký tự")
    void testAssignedPersonnelRevokeRequest_ReasonValidation() {
        AssignedPersonnelRevokeRequest req9 = new AssignedPersonnelRevokeRequest("123456789");
        assertFalse(validator.validate(req9).isEmpty(), "reason 9 ký tự phải vi phạm");

        AssignedPersonnelRevokeRequest reqValid = new AssignedPersonnelRevokeRequest("Lý do hợp lệ đủ mười ký tự");
        assertTrue(validator.validate(reqValid).isEmpty(), "reason >= 10 ký tự phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: AssignedPersonnelCreateRequest - reason bắt buộc 10-500 ký tự")
    void testAssignedPersonnelCreateRequest_ReasonValidation() {
        // null reason
        AssignedPersonnelCreateRequest reqNull = new AssignedPersonnelCreateRequest(
                UUID.randomUUID(), null, null, null, null
        );
        assertFalse(validator.validate(reqNull).isEmpty(), "reason null phải vi phạm");

        // 9 chars
        AssignedPersonnelCreateRequest req9 = new AssignedPersonnelCreateRequest(
                UUID.randomUUID(), null, null, null, "123456789"
        );
        assertFalse(validator.validate(req9).isEmpty(), "reason 9 ký tự phải vi phạm");

        // valid
        AssignedPersonnelCreateRequest reqValid = new AssignedPersonnelCreateRequest(
                UUID.randomUUID(), null, null, null, "Lý do hợp lệ đủ mười ký tự"
        );
        assertTrue(validator.validate(reqValid).isEmpty(), "reason >= 10 ký tự phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: AssignedPersonnelUpdateRequest - reason bắt buộc 10-500 ký tự")
    void testAssignedPersonnelUpdateRequest_ReasonValidation() {
        // null reason
        AssignedPersonnelUpdateRequest reqNull = new AssignedPersonnelUpdateRequest(null, null);
        assertFalse(validator.validate(reqNull).isEmpty(), "reason null phải vi phạm");

        // 9 chars
        AssignedPersonnelUpdateRequest req9 = new AssignedPersonnelUpdateRequest(null, "123456789");
        assertFalse(validator.validate(req9).isEmpty(), "reason 9 ký tự phải vi phạm");

        // valid
        AssignedPersonnelUpdateRequest reqValid = new AssignedPersonnelUpdateRequest(null, "Lý do hợp lệ đủ mười ký tự");
        assertTrue(validator.validate(reqValid).isEmpty(), "reason >= 10 ký tự phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: AccessRequestReviewRequest - khi REJECTED reason bắt buộc 10-500 ký tự; khi APPROVED không bắt buộc")
    void testAccessRequestReviewRequest_RejectionReasonValidation() {
        // REJECTED with null rejectionReason -> invalid
        AccessRequestReviewRequest rejNull = new AccessRequestReviewRequest(RequestStatus.REJECTED, null);
        assertFalse(validator.validate(rejNull).isEmpty(), "REJECTED với rejectionReason null phải vi phạm");

        // REJECTED with 9 chars -> invalid
        AccessRequestReviewRequest rej9 = new AccessRequestReviewRequest(RequestStatus.REJECTED, "123456789");
        assertFalse(validator.validate(rej9).isEmpty(), "REJECTED với rejectionReason 9 ký tự phải vi phạm");

        // REJECTED with >= 10 chars -> valid
        AccessRequestReviewRequest rejValid = new AccessRequestReviewRequest(RequestStatus.REJECTED, "Khu vực đang bảo trì định kỳ");
        assertTrue(validator.validate(rejValid).isEmpty(), "REJECTED với rejectionReason >= 10 ký tự phải hợp lệ");

        // APPROVED with null rejectionReason -> valid
        AccessRequestReviewRequest appNull = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        assertTrue(validator.validate(appNull).isEmpty(), "APPROVED với rejectionReason null phải hợp lệ");
    }

    @Test
    @DisplayName("D6-11: SystemConfigUpdateRequest - không có reason hoặc < 10 ký tự -> vi phạm; >= 10 ký tự -> hợp lệ")
    void testSystemConfigUpdateRequest_ReasonValidation() {
        // null reason
        SystemConfigUpdateRequest reqNull = new SystemConfigUpdateRequest("50", null);
        assertFalse(validator.validate(reqNull).isEmpty(), "System config không có reason phải vi phạm");

        // 4 chars
        SystemConfigUpdateRequest req4 = new SystemConfigUpdateRequest("50", "ngắn");
        assertFalse(validator.validate(req4).isEmpty(), "System config reason 4 ký tự phải vi phạm");

        // 9 chars
        SystemConfigUpdateRequest req9 = new SystemConfigUpdateRequest("50", "123456789");
        assertFalse(validator.validate(req9).isEmpty(), "System config reason 9 ký tự phải vi phạm");

        // valid 10 chars
        SystemConfigUpdateRequest reqValid = new SystemConfigUpdateRequest("50", "Lý do hợp lệ đủ mười ký tự");
        assertTrue(validator.validate(reqValid).isEmpty(), "System config reason >= 10 ký tự phải hợp lệ");
    }
}
