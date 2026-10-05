package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * BR-RQ-SP-05: huỷ đơn chạy CÙNG transaction với vô hiệu hoá tài khoản — lỗi khi huỷ đơn -> rollback cả hai:
 * tài khoản vẫn hoạt động, đơn giữ APPROVED, không audit, không thông báo.
 * Tách class riêng vì @SpyBean AuditService tạo Spring context riêng.
 * Cách ép lỗi: audit ACCESS_REQUEST / CANCEL của actor SYSTEM nguồn REQUESTER_DEACTIVATION ném RuntimeException.
 */
class RequestCancelOnRequesterDeactivationRollbackTest extends AbstractIntegrationTest {

    @SpyBean private AuditService auditService;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        Mockito.reset(auditService);
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String suffix, String tag, Role role, int level) {
        User u = userRepository.save(User.builder()
                .userCode("BR4R-" + tag.toUpperCase() + "-" + suffix)
                .fullName("BR4 rollback " + tag + " " + suffix)
                .email("br4r-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    @Test
    @DisplayName("Lỗi khi huỷ đơn -> rollback cả thao tác vô hiệu hoá: user vẫn active, đơn APPROVED, không audit, không thông báo")
    void failureWhileCancelling_rollsBackDeactivation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa BR4")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa BR4").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa BR4", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        User admin = user(suffix, "adm", Role.ADMIN, 3);
        User fm = user(suffix, "fm", Role.FACILITY_MANAGER, 3);
        User requester = user(suffix, "req", Role.NORMAL_USER, 2);
        User member = user(suffix, "mem", Role.NORMAL_USER, 2);
        Area area = areaRepository.save(Area.builder()
                .name("BR4 rollback " + suffix).building(building.getName()).floor(floor.getName()).floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED).areaAccessLevel(2).explicitAuthorizationRequired(true)
                .isActive(true).build());
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        AccessRequest req = AccessRequest.builder()
                .area(area).requester(requester).requestType(RequestType.GROUP).purpose("Đơn rollback BR4 " + suffix)
                .startTime(now.minusHours(1)).endTime(now.plusHours(2)).status(RequestStatus.APPROVED)
                .reviewer(fm).reviewedAt(now.minusHours(2)).build();
        req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(member).sponsored(false).build());
        req = accessRequestRepository.save(req);

        AtomicInteger cancelAudits = new AtomicInteger();
        doAnswer(inv -> {
            AuditActor actor = inv.getArgument(8);
            if (inv.getArgument(0) == AuditTargetType.ACCESS_REQUEST && inv.getArgument(1) == AuditAction.CANCEL
                    && actor != null && AccessRequestService.REQUESTER_DEACTIVATION_SOURCE.equals(actor.getActorSource())) {
                cancelAudits.incrementAndGet();
                throw new RuntimeException("BR4: ép lỗi khi hệ thống huỷ đơn");
            }
            return inv.callRealMethod();
        }).when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), nullable(AuditActor.class));

        MvcResult r = mockMvc.perform(patch("/api/users/{id}/toggle-active", requester.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin))).andReturn();

        assertTrue(r.getResponse().getStatus() >= 500, "Lỗi giữa chừng phải trả 5xx: HTTP " + r.getResponse().getStatus());
        assertEquals(1, cancelAudits.get(), "Tiền đề: luồng phải chạy tới bước audit huỷ đơn");
        assertEquals(true, jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, requester.getId()),
                "Vô hiệu hoá phải rollback");
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, req.getId()));
        assertNull(jdbc.queryForObject("SELECT cancel_source FROM access_requests WHERE id = ?", String.class, req.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'CANCEL' AND target_id = ?",
                Integer.class, req.getId().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE reference_id = ? AND type = 'REQUEST_SYSTEM_CANCELLED'",
                Integer.class, req.getId()), "Rollback thì không thông báo");
    }
}
