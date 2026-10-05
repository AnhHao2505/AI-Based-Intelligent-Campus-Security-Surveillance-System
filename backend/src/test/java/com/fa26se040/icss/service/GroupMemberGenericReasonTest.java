package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tạo đơn nhóm có thành viên bị vô hiệu hoá / đã xoá -> 400 với CÙNG lý do chung như resolve-members
 * ("Không tìm thấy người dùng hợp lệ với mã này"), không lộ là tài khoản tồn tại nhưng bị khoá (CLAUDE.md 9a).
 */
class GroupMemberGenericReasonTest extends Step5bTestSupport {

    private static final String GENERIC_REASON = "Không tìm thấy người dùng hợp lệ với mã này";

    private User member(String tag, boolean active, boolean deleted) {
        return userRepository.save(User.builder()
                .email("gm." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("GM-" + tag.toUpperCase() + "-" + suffix)
                .fullName("Thanh vien " + tag + " " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(2)
                .isActive(active)
                .deletedAt(deleted ? OffsetDateTime.now().minusDays(1) : null)
                .build());
    }

    private MvcResult createGroup(User memberUser) throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime start = OffsetDateTime.now().plusHours(3).truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("areaId", area.getId());
        body.put("startTime", start.toString());
        body.put("endTime", start.plusHours(1).toString());
        body.put("purpose", "Đơn nhóm kiểm lý do chung " + suffix);
        body.put("memberUserCodes", List.of(memberUser.getUserCode()));
        return send(post("/api/access-requests/group"), userL2, body).andReturn();
    }

    private void assertGenericReason(MvcResult r, User memberUser) throws Exception {
        assertEquals(400, status(r), describe(r));
        assertEquals(memberUser.getUserCode() + ": " + GENERIC_REASON, message(r));
        assertFalse(message(r).toLowerCase().contains("vô hiệu"), "Không được nêu tài khoản bị vô hiệu hoá");
    }

    @Test
    @DisplayName("Thành viên bị vô hiệu hoá -> 400, lý do chung")
    void disabledMember_genericReason() throws Exception {
        User disabled = member("off", false, false);
        assertGenericReason(createGroup(disabled), disabled);
    }

    @Test
    @DisplayName("Thành viên đã xoá mềm -> 400, lý do chung")
    void deletedMember_genericReason() throws Exception {
        User deleted = member("del", true, true);
        assertGenericReason(createGroup(deleted), deleted);
    }

    @Test
    @DisplayName("Thành viên NORMAL_USER hợp lệ -> 201 như cũ")
    void validMember_created() throws Exception {
        User ok = member("ok", true, false);
        MvcResult r = createGroup(ok);
        assertEquals(201, status(r), describe(r));
    }
}
