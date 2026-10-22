package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaTypeChangePreviewResponse;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q1 (BR-RQ-03/04, BR-TC-08): đổi loại khu vực đánh giá đơn nhóm có thành viên bảo lãnh theo cùng nguồn cấu hình
 * ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES với luồng duyệt đơn. Xem trước (BR-TC-03) và PUT dùng chung kết quả.
 * V83: chỉ khu Bảo mật cho bảo lãnh, nên các ca đổi loại CONTACT <-> INTERNAL cũ chuyển thành Bảo mật <-> Tuyệt mật.
 */
class AreaTypeChangeSponsorshipTest extends Step5bTestSupport {

    private String originalSponsorTypes;
    private Area contactArea;

    @BeforeEach
    void setUpSponsorship() {
        originalSponsorTypes = systemConfigService.getString(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES);
        // Tiền điều kiện về preset (dữ liệu dùng chung của DB test): kỳ vọng bên dưới dựa vào các cấp này
        assertEquals(1, presetLevel(AreaLevel.PUBLIC), "preset PUBLIC phải là cấp 1");
        assertEquals(2, presetLevel(AreaLevel.CONFIDENTIAL), "preset CONFIDENTIAL phải là cấp 2");
        assertEquals(3, presetLevel(AreaLevel.HIGHLY_CONFIDENTIAL), "preset HIGHLY phải là cấp 3");
        // Tầng của Step5bTestSupport ("Tầng test 5b <suffix>", 21 ký tự) vượt giới hạn 20 ký tự khi PUT khu vực
        floor = floorRepository.save(com.fa26se040.icss.entity.Floor.builder()
                .name("Q1 " + suffix)
                .floorOrder(1)
                .building(building)
                .isActive(true)
                .build());
        contactArea = newArea(AreaLevel.CONFIDENTIAL);
    }

    @AfterEach
    void restoreConfigs() {
        if (originalSponsorTypes != null && !originalSponsorTypes.isBlank()) {
            systemConfigService.update(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.name(), originalSponsorTypes, admin.getEmail());
        }
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.name(), "false", admin.getEmail());
    }

    private int presetLevel(AreaLevel level) {
        return presetRepository.findById(level).orElseThrow().getAreaAccessLevel();
    }

    private void setGroupAllowedInHighly(boolean allowed) {
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.name(), String.valueOf(allowed), admin.getEmail());
    }

    /** Đơn nhóm APPROVED chưa kết thúc; members: user -> sponsored. */
    private AccessRequest approvedGroupRequest(Area area, User requester, Map<User, Boolean> members) {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        AccessRequest req = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.GROUP)
                .purpose("Đơn nhóm bảo lãnh Q1 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(2))
                .status(RequestStatus.APPROVED)
                .reviewer(fm)
                .reviewedAt(OffsetDateTime.now().minusHours(1))
                .build();
        members.forEach((u, sponsored) -> req.getMembers().add(
                AccessRequestMember.builder().accessRequest(req).user(u).sponsored(sponsored).build()));
        return accessRequestRepository.save(req);
    }

    private boolean previewCancels(Area area, AreaLevel newLevel, AccessRequest req) {
        AreaTypeChangePreviewResponse preview = areaService.previewTypeChange(area.getId(), newLevel);
        return preview.approvedRequestsToCancel().stream().anyMatch(i -> i.id().equals(req.getId()));
    }

    private void changeTypeOk(Area area, AreaLevel newLevel) throws Exception {
        MvcResult r = changeType(admin, area, newLevel, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));
    }

    private String cancelReason(AccessRequest req) {
        return (String) cancelColumns(req).get("cancel_reason");
    }

    @Test
    @DisplayName("Q1 (V83): thành viên L1 sponsored, đổi Tuyệt mật -> Bảo mật (loại mới cho bảo lãnh) -> đơn KHÔNG bị huỷ")
    void sponsoredMemberKeptWhenNewTypeAllowsSponsorship() throws Exception {
        setGroupAllowedInHighly(true);
        Area highlyArea = newArea(AreaLevel.HIGHLY_CONFIDENTIAL);
        AccessRequest req = approvedGroupRequest(highlyArea, userL3, Map.of(userL1, true));

        assertFalse(previewCancels(highlyArea, AreaLevel.CONFIDENTIAL, req), "Xem trước không được liệt kê đơn");
        changeTypeOk(highlyArea, AreaLevel.CONFIDENTIAL);

        assertEquals(RequestStatus.APPROVED, requestStatus(req));
    }

    @Test
    @DisplayName("Q1 (V83): đổi Bảo mật -> Tuyệt mật (cho đơn nhóm, KHÔNG cho bảo lãnh) -> đơn có thành viên L1 sponsored bị huỷ, lý do nêu bảo lãnh + tên thành viên")
    void sponsoredMemberCancelsWhenNewTypeDisallowsSponsorship() throws Exception {
        setGroupAllowedInHighly(true);
        AccessRequest req = approvedGroupRequest(contactArea, userL3, Map.of(userL1, true));

        assertTrue(previewCancels(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL, req), "Xem trước phải liệt kê đơn");
        changeTypeOk(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL);

        assertEquals(RequestStatus.CANCELLED, requestStatus(req));
        String reason = cancelReason(req);
        assertTrue(reason.contains("bảo lãnh"), reason);
        assertTrue(reason.contains(userL1.getUserCode()), reason);
    }

    @Test
    @DisplayName("Q1 (V83): đổi Bảo mật -> Tuyệt mật, thành viên sponsored đã đủ cấp 3 -> đơn KHÔNG bị huỷ (giống reviewRequest)")
    void sponsoredMemberWithSufficientLevelKeptWhenNewTypeDisallowsSponsorship() throws Exception {
        setGroupAllowedInHighly(true);
        AccessRequest req = approvedGroupRequest(contactArea, userL3, Map.of(userL3b, true));

        assertFalse(previewCancels(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL, req), "Xem trước không được liệt kê đơn");
        changeTypeOk(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL);

        assertEquals(RequestStatus.APPROVED, requestStatus(req));
    }

    @Test
    @DisplayName("Q1: cấp mới (HIGHLY, cấp 3) > cấp người gửi L2 -> đơn bị huỷ vì người gửi không đủ cấp")
    void requesterBelowNewLevelCancels() throws Exception {
        // Cho phép đơn nhóm ở Tuyệt mật để nhánh "không nhận đơn nhóm" không che mất kiểm cấp người gửi
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.name(), "true", admin.getEmail());
        AccessRequest req = approvedGroupRequest(contactArea, userL2, Map.of(userL1, true));

        assertTrue(previewCancels(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL, req));
        changeTypeOk(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL);

        assertEquals(RequestStatus.CANCELLED, requestStatus(req));
        String reason = cancelReason(req);
        assertTrue(reason.contains("không đủ cấp truy cập 3"), reason);
        assertTrue(reason.contains(userL2.getUserCode()), reason);
    }

    @Test
    @DisplayName("Q1 (V83): thành viên KHÔNG sponsored cấp 2 bị kiểm cấp theo loại mới (Tuyệt mật, cấp 3) -> đơn bị huỷ, lý do không nhắc bảo lãnh")
    void nonSponsoredLowMemberStillChecked() throws Exception {
        setGroupAllowedInHighly(true);
        AccessRequest req = approvedGroupRequest(contactArea, userL3, Map.of(userL2, false));

        changeTypeOk(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL);

        assertEquals(RequestStatus.CANCELLED, requestStatus(req));
        String reason = cancelReason(req);
        assertTrue(reason.contains("không đủ cấp truy cập 3"), reason);
        assertTrue(reason.contains(userL2.getUserCode()), reason);
        assertFalse(reason.contains("bảo lãnh"), reason);
    }

    @Test
    @DisplayName("Q1 (V83): đổi ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE qua SystemConfigService -> xem trước đổi Bảo mật -> Tuyệt mật đổi kết quả ngay, không cần restart")
    void groupConfigChangeTakesEffectImmediately() {
        AccessRequest req = approvedGroupRequest(contactArea, userL3, Map.of(userL3b, false));

        setGroupAllowedInHighly(false);
        assertTrue(previewCancels(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL, req), "Tuyệt mật không nhận đơn nhóm");

        setGroupAllowedInHighly(true);
        assertFalse(previewCancels(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL, req));

        setGroupAllowedInHighly(false);
        assertTrue(previewCancels(contactArea, AreaLevel.HIGHLY_CONFIDENTIAL, req));
        assertEquals(RequestStatus.APPROVED, requestStatus(req), "Xem trước chỉ đọc, không đổi đơn");
    }
}
