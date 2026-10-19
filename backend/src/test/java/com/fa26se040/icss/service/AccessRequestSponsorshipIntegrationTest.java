package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.accessrequest.AccessRequestResponse;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaEventModeUpdateRequest;
import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.dto.systemconfig.SystemConfigUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestMemberRepository;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.AuditLogRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccessRequestSponsorshipIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccessRequestService accessRequestService;

    @Autowired
    private AccessDecisionService accessDecisionService;

    @Autowired
    private AreaService areaService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private AreaLevelPresetService areaLevelPresetService;

    @Autowired
    private AreaLevelPresetRepository areaLevelPresetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private AccessRequestMemberRepository accessRequestMemberRepository;

    @Autowired
    private SystemConfigurationRepository systemConfigurationRepository;

    @Autowired
    private SystemConfigurationChangeLogRepository systemConfigurationChangeLogRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User adminUser;
    private User fmUser;
    private User creatorL2;
    private User creatorL1;
    private User memberL1;
    private User memberL2;
    private User inactiveMember;

    private Area contactArea;
    private Area internalArea;
    private Area highlyArea;

    private String tokenAdmin;
    private String tokenFm;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);

            Building building = buildingRepository.findByNameIgnoreCase("Tòa Sponsor")
                    .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa Sponsor").build()));

            Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa Sponsor", "Tầng 1")
                    .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

            adminUser = userRepository.save(User.builder()
                    .userCode("ADM-" + suffix)
                    .fullName("Admin Sponsor " + suffix)
                    .email("admin-" + suffix + "@fpt.edu.vn")
                    .role(Role.ADMIN)
                    .accessLevel(3)
                    .isActive(true)
                    .build());

            fmUser = userRepository.save(User.builder()
                    .userCode("FM-" + suffix)
                    .fullName("FM Sponsor " + suffix)
                    .email("fm-" + suffix + "@fpt.edu.vn")
                    .role(Role.FACILITY_MANAGER)
                    .accessLevel(3)
                    .isActive(true)
                    .build());

            creatorL2 = userRepository.save(User.builder()
                    .userCode("CRE2-" + suffix)
                    .fullName("Creator Level 2 " + suffix)
                    .email("cre2-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(2)
                    .isActive(true)
                    .build());

            creatorL1 = userRepository.save(User.builder()
                    .userCode("CRE1-" + suffix)
                    .fullName("Creator Level 1 " + suffix)
                    .email("cre1-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(1)
                    .isActive(true)
                    .build());

            memberL1 = userRepository.save(User.builder()
                    .userCode("MEM1-" + suffix)
                    .fullName("Member Level 1 " + suffix)
                    .email("mem1-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(1)
                    .isActive(true)
                    .build());

            memberL2 = userRepository.save(User.builder()
                    .userCode("MEM2-" + suffix)
                    .fullName("Member Level 2 " + suffix)
                    .email("mem2-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(2)
                    .isActive(true)
                    .build());

            inactiveMember = userRepository.save(User.builder()
                    .userCode("INACT-" + suffix)
                    .fullName("Inactive Member " + suffix)
                    .email("inact-" + suffix + "@fpt.edu.vn")
                    .role(Role.NORMAL_USER)
                    .accessLevel(2)
                    .isActive(false)
                    .build());

            contactArea = areaRepository.save(Area.builder()
                    .name("Contact Area " + suffix)
                    .building(building.getName())
                    .floor(floor.getName())
                    .floorEntity(floor)
                    .areaLevel(AreaLevel.CONFIDENTIAL)
                    .areaAccessLevel(2)
                    .isActive(true)
                    .build());

            internalArea = areaRepository.save(Area.builder()
                    .name("Internal Area " + suffix)
                    .building(building.getName())
                    .floor(floor.getName())
                    .floorEntity(floor)
                    .areaLevel(AreaLevel.CONFIDENTIAL)
                    .areaAccessLevel(2)
                    .isActive(true)
                    .build());

            highlyArea = areaRepository.save(Area.builder()
                    .name("Highly Area " + suffix)
                    .building(building.getName())
                    .floor(floor.getName())
                    .floorEntity(floor)
                    .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                    .areaAccessLevel(3)
                    .isActive(true)
                    .build());

            // Ensure sponsor config starts in default state
            systemConfigurationRepository.findById(com.fa26se040.icss.enums.ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.name())
                    .ifPresent(cfg -> {
                        cfg.setConfigValue("CONFIDENTIAL");
                        cfg.setUpdatedBy(null);
                        systemConfigurationRepository.save(cfg);
                    });

            return null;
        });

        tokenAdmin = "Bearer " + jwtTokenProvider.generateToken(adminUser);
        tokenFm = "Bearer " + jwtTokenProvider.generateToken(fmUser);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            // 1. Reset system configuration
            systemConfigurationRepository.findById(com.fa26se040.icss.enums.ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.name())
                    .ifPresent(cfg -> {
                        cfg.setConfigValue("CONFIDENTIAL");
                        cfg.setUpdatedBy(null);
                        systemConfigurationRepository.save(cfg);
                    });

            List<UUID> userIds = List.of(
                    adminUser.getId(), fmUser.getId(), creatorL2.getId(),
                    creatorL1.getId(), memberL1.getId(), memberL2.getId(), inactiveMember.getId()
            );
            List<UUID> areaIds = List.of(contactArea.getId(), internalArea.getId(), highlyArea.getId());

            // 2. Delete change logs referencing test users
            var changeLogs = systemConfigurationChangeLogRepository.findAll().stream()
                    .filter(cl -> cl.getChangedBy() != null && userIds.contains(cl.getChangedBy().getId()))
                    .toList();
            systemConfigurationChangeLogRepository.deleteAll(changeLogs);

            // 3. Delete notifications for test users
            for (UUID uid : userIds) {
                var notifs = notificationRepository.findAll().stream()
                        .filter(n -> n.getRecipient() != null && n.getRecipient().getId().equals(uid))
                        .toList();
                notificationRepository.deleteAll(notifs);
            }

            // 4. Delete access requests and members
            for (UUID aid : areaIds) {
                var reqs = accessRequestRepository.findAll().stream()
                        .filter(r -> r.getArea() != null && r.getArea().getId().equals(aid))
                        .toList();
                for (var r : reqs) {
                    accessRequestMemberRepository.deleteAll(r.getMembers());
                    accessRequestRepository.delete(r);
                }
            }

            // 5. Query audit logs once to see which users/areas cannot be hard-deleted
            var allAudits = auditLogRepository.findAll();
            java.util.Set<UUID> auditedUserIds = new java.util.HashSet<>();
            java.util.Set<UUID> auditedAreaIds = new java.util.HashSet<>();
            for (var al : allAudits) {
                if (al.getChangedBy() != null) auditedUserIds.add(al.getChangedBy().getId());
                if (al.getSubjectUser() != null) auditedUserIds.add(al.getSubjectUser().getId());
                if (al.getArea() != null) auditedAreaIds.add(al.getArea().getId());
            }

            // 6. Delete or deactivate areas
            for (UUID aid : areaIds) {
                if (auditedAreaIds.contains(aid)) {
                    areaRepository.findById(aid).ifPresent(a -> {
                        a.setIsActive(false);
                        areaRepository.save(a);
                    });
                } else {
                    areaRepository.deleteById(aid);
                }
            }

            // 7. Delete or deactivate users
            for (UUID uid : userIds) {
                if (auditedUserIds.contains(uid)) {
                    userRepository.findById(uid).ifPresent(u -> {
                        u.setIsActive(false);
                        userRepository.save(u);
                    });
                } else {
                    userRepository.deleteById(uid);
                }
            }

            return null;
        });
    }

    @Test
    @DisplayName("(a) CONTACT + tạo L2 + thành viên L1 -> chấp nhận, sponsored = true")
    void test_a_contact_creatorL2_memberL1_accepted_sponsoredTrue() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Mục đích làm việc nhóm tại phòng liên hệ trước",
                List.of(memberL1.getUserCode(), memberL2.getUserCode())
        );

        AccessRequestResponse resp = accessRequestService.createGroupRequest(req, creatorL2.getEmail());
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(resp.members()).hasSize(2);

        // memberL1 có accessLevel=1 < requiredLevel=2 -> sponsored = true
        var m1 = resp.members().stream().filter(m -> m.userCode().equals(memberL1.getUserCode())).findFirst().orElseThrow();
        assertThat(m1.sponsored()).isTrue();

        // memberL2 có accessLevel=2 >= requiredLevel=2 -> sponsored = false
        var m2 = resp.members().stream().filter(m -> m.userCode().equals(memberL2.getUserCode())).findFirst().orElseThrow();
        assertThat(m2.sponsored()).isFalse();

        // Assert notification created for FM
        List<Notification> fmNotifs = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient() != null && n.getRecipient().getId().equals(fmUser.getId()) && n.getType() == NotificationType.NEW_REQUEST_PENDING)
                .toList();
        assertThat(fmNotifs).isNotEmpty();
    }

    @Test
    @DisplayName("(b) V83: Bảo mật (Nội bộ cũ) + thành viên L1 -> chấp nhận, sponsored = true (Nội bộ gộp vào Bảo mật, cho bảo lãnh)")
    void test_b_internal_memberL1_acceptedSponsored() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                internalArea.getId(),
                start,
                end,
                "Mục đích họp nội bộ",
                List.of(memberL1.getUserCode())
        );

        AccessRequestResponse resp = accessRequestService.createGroupRequest(req, creatorL2.getEmail());
        assertThat(resp.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(resp.members()).hasSize(1);
        assertThat(resp.members().get(0).sponsored()).isTrue();
    }

    @Test
    @DisplayName("(c) HIGHLY nhóm -> từ chối")
    void test_c_highly_group_rejected() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                highlyArea.getId(),
                start,
                end,
                "Mục đích vào phòng tuyệt mật theo nhóm",
                List.of(memberL2.getUserCode())
        );

        assertThatThrownBy(() -> accessRequestService.createGroupRequest(req, adminUser.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HIGHLY_CONFIDENTIAL");
    }

    @Test
    @DisplayName("(d) thành viên bị khoá hoặc không tồn tại -> từ chối")
    void test_d_inactive_or_nonexistent_member_rejected() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest reqInactive = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Mục đích làm việc",
                List.of(inactiveMember.getUserCode())
        );

        // Lý do chung như resolve-members (không lộ tài khoản tồn tại nhưng bị vô hiệu hoá — CLAUDE.md 9a)
        assertThatThrownBy(() -> accessRequestService.createGroupRequest(reqInactive, creatorL2.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy người dùng hợp lệ với mã này")
                .hasMessageNotContaining("vô hiệu");

        GroupAccessRequestCreateRequest reqNonExistent = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Mục đích làm việc",
                List.of("NON_EXISTENT_CODE_9999")
        );

        // Mã không tồn tại: cùng loại lỗi + cùng câu với mã bị khoá (không dò được mã nào tồn tại — CLAUDE.md 9a)
        assertThatThrownBy(() -> accessRequestService.createGroupRequest(reqNonExistent, creatorL2.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NON_EXISTENT_CODE_9999: Không tìm thấy người dùng hợp lệ với mã này");
    }

    @Test
    @DisplayName("(e) V83: người tạo L1 tạo đơn nhóm vào Bảo mật (cấp 2) -> từ chối: người tạo đơn nhóm phải tự vào được khu bằng cấp")
    void test_e_creatorL1_contact_rejected() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Người tạo L1 muốn tạo đơn nhóm",
                List.of(memberL2.getUserCode())
        );

        assertThatThrownBy(() -> accessRequestService.createGroupRequest(req, creatorL1.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Người tạo đơn nhóm phải tự vào được khu vực bằng cấp truy cập");
    }

    @Test
    @DisplayName("(f) V83: cấu hình bảo lãnh chỉ nhận CONFIDENTIAL; giá trị lạ / chứa PUBLIC / HIGHLY / tên loại cũ -> từ chối, giá trị không đổi")
    void test_f_admin_config_update_sponsor_allowed_types() {
        String key = com.fa26se040.icss.enums.ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.name();
        for (String bad : List.of("UNKNOWN_TYPE_XYZ", "PUBLIC,CONFIDENTIAL", "HIGHLY_CONFIDENTIAL",
                "INTERNAL_CONFIDENTIAL", "CONFIDENTIAL_CONTACT_REQUIRED, CONFIDENTIAL")) {
            assertThatThrownBy(() -> systemConfigService.update(key, bad, "Cập nhật thử", adminUser.getEmail()))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Giá trị hợp lệ duy nhất sau gộp
        systemConfigService.update(key, "CONFIDENTIAL", "Giữ bảo lãnh ở khu Bảo mật", adminUser.getEmail());
        assertThat(systemConfigService.getSponsorAllowedAreaLevels()).containsExactly(AreaLevel.CONFIDENTIAL);
    }

    @Test
    @DisplayName("(g) V83 (BR-RQ-02): người tạo bị hạ cấp trước khi duyệt -> FM vẫn duyệt được, không kiểm lại cấp người tạo")
    void test_g_review_when_creator_demoted_rejected() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Mục đích làm việc nhóm",
                List.of(memberL1.getUserCode())
        );

        AccessRequestResponse created = accessRequestService.createGroupRequest(req, creatorL2.getEmail());

        // Hạ cấp creatorL2 từ 2 xuống 1
        creatorL2.setAccessLevel(1);
        userRepository.save(creatorL2);

        // FM duyệt -> thành công (BR-AL-30 mới chặn đổi cấp qua API khi còn đơn chờ duyệt; ở đây hạ cấp thẳng trong DB)
        AccessRequestReviewRequest reviewReq = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        assertThat(accessRequestService.reviewRequest(created.id(), reviewReq, fmUser.getEmail()).status())
                .isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    @DisplayName("(h) checkEntry thành viên bảo lãnh trong khung giờ -> ALLOWED nguồn ACCESS_REQUEST, ngoài khung -> DENIED")
    void test_h_checkEntry_sponsored_member_in_and_out_of_time() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);

        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                contactArea.getId(),
                start,
                end,
                "Mục đích làm việc nhóm bảo lãnh",
                List.of(memberL1.getUserCode())
        );

        AccessRequestResponse created = accessRequestService.createGroupRequest(req, creatorL2.getEmail());

        // FM duyệt đơn
        AccessRequestReviewRequest reviewReq = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        accessRequestService.reviewRequest(created.id(), reviewReq, fmUser.getEmail());

        // 1. Trong khung giờ (start + 10 phút) -> ALLOWED (nguồn ACCESS_REQUEST)
        AccessDecision insideDecision = accessDecisionService.checkEntry(memberL1.getId(), contactArea.getId(), start.plusMinutes(10));
        assertThat(insideDecision.allowed()).isTrue();
        assertThat(insideDecision.source()).isEqualTo(AccessSource.ACCESS_REQUEST);
        assertThat(insideDecision.sourceRefId()).isEqualTo(created.id());

        // 2. Ngoài khung giờ (start - 10 phút) -> DENIED
        AccessDecision beforeDecision = accessDecisionService.checkEntry(memberL1.getId(), contactArea.getId(), start.minusMinutes(10));
        assertThat(beforeDecision.allowed()).isFalse();

        // 3. Ngoài khung giờ (end + 10 phút) -> DENIED
        AccessDecision afterDecision = accessDecisionService.checkEntry(memberL1.getId(), contactArea.getId(), end.plusMinutes(10));
        assertThat(afterDecision.allowed()).isFalse();

        // 4. Assert notification for creatorL2 when approved
        List<Notification> creatorNotifs = notificationRepository.findAll().stream()
                .filter(n -> n.getRecipient() != null && n.getRecipient().getId().equals(creatorL2.getId()) && n.getType() == NotificationType.REQUEST_APPROVED)
                .toList();
        assertThat(creatorNotifs).isNotEmpty();
    }

    @Test
    @DisplayName("(i) Ma trận RBAC: FM sửa access-rules -> 403, ADMIN -> 400 ERR_AREA_056 (V83), FM sự kiện -> 200, ADMIN sự kiện -> 403")
    void test_i_rbac_matrix() throws Exception {
        // 1. FM sửa access-rules -> 403
        mockMvc.perform(patch("/api/areas/" + contactArea.getId() + "/access-rules")
                        .header("Authorization", tokenFm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "areaAccessLevel": 2,
                                    "explicitAuthorizationRequired": false,
                                    "reason": "FM thử sửa quy tắc truy cập",
                                    "version": %d
                                }
                                """.formatted(contactArea.getVersion())))
                .andExpect(status().isForbidden());

        // 2. ADMIN sửa access-rules -> 400 ERR_AREA_056 (V83: cấp cố định theo loại)
        mockMvc.perform(patch("/api/areas/" + contactArea.getId() + "/access-rules")
                        .header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "areaAccessLevel": 3,
                                    "explicitAuthorizationRequired": false,
                                    "reason": "ADMIN điều chỉnh quy tắc truy cập",
                                    "version": %d
                                }
                                """.formatted(contactArea.getVersion())))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("ERR_AREA_056"));

        // Lấy version mới của internalArea
        Area freshInternal = areaRepository.findById(internalArea.getId()).orElseThrow();

        // 3. ADMIN sửa event-mode -> 403
        mockMvc.perform(patch("/api/areas/" + internalArea.getId() + "/event-mode")
                        .header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "action": "ENABLE",
                                    "openUntil": "%s",
                                    "reasonCode": "SEMINAR",
                                    "note": "Ghi chú sự kiện seminar hội thảo",
                                    "version": %d
                                }
                                """.formatted(OffsetDateTime.now().plusHours(4).toString(), freshInternal.getVersion())))
                .andExpect(status().isForbidden());

        // 4. FM sửa event-mode -> 200
        mockMvc.perform(patch("/api/areas/" + internalArea.getId() + "/event-mode")
                        .header("Authorization", tokenFm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "action": "ENABLE",
                                    "openUntil": "%s",
                                    "reasonCode": "SEMINAR",
                                    "note": "Ghi chú sự kiện seminar hội thảo",
                                    "version": %d
                                }
                                """.formatted(OffsetDateTime.now().plusHours(4).toString(), freshInternal.getVersion())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("(j) V83: sửa cấp / cờ độc lập khỏi loại khu vực -> ERR_AREA_056 (khu) và ERR_AC_005 (preset), câu \"Cấp khu vực cố định theo loại\"")
    void test_j_change_level_independent_from_type_rejected() {
        AreaAccessRulesUpdateRequest badReq = new AreaAccessRulesUpdateRequest(
                3,
                false,
                "Cố tình đổi cấp độc lập khỏi loại khu vực",
                contactArea.getVersion()
        );
        assertThatThrownBy(() -> areaService.updateAccessRules(contactArea.getId(), badReq, adminUser.getEmail()))
                .isInstanceOf(com.fa26se040.icss.exception.AreaException.class)
                .hasMessageContaining("Cấp khu vực cố định theo loại")
                .satisfies(ex -> assertThat(((com.fa26se040.icss.exception.AreaException) ex).getErrorCode())
                        .isEqualTo(com.fa26se040.icss.exception.AreaErrorCode.ERR_AREA_056));
        assertThat(areaRepository.findById(contactArea.getId()).orElseThrow().getAreaAccessLevel()).isEqualTo(2);

        var preset = areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL).orElseThrow();
        LevelPresetUpdateRequest badPresetReq = new LevelPresetUpdateRequest(
                3,
                false,
                "Cố tình sửa cấp của preset",
                preset.getVersion()
        );
        assertThatThrownBy(() -> areaLevelPresetService.updatePreset(AreaLevel.CONFIDENTIAL, badPresetReq, adminUser.getEmail()))
                .isInstanceOf(com.fa26se040.icss.exception.AccessControlException.class)
                .hasMessageContaining("Cấp khu vực cố định theo loại")
                .satisfies(ex -> assertThat(((com.fa26se040.icss.exception.AccessControlException) ex).getErrorCode())
                        .isEqualTo(com.fa26se040.icss.exception.AccessControlErrorCode.ERR_AC_005));
        assertThat(areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL).orElseThrow().getAreaAccessLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("(k) V83 available-areas theo loại đơn: L1 cá nhân thấy Bảo mật, nhóm không thấy gì; L2 cá nhân thấy Tuyệt mật, nhóm thấy Bảo mật; đổi loại Bảo mật -> Tuyệt mật thì khu chuyển danh sách")
    void test_k_available_areas_filters_and_type_change() {
        RequestType ind = RequestType.INDIVIDUAL;
        RequestType grp = RequestType.GROUP;
        java.util.function.BiFunction<User, RequestType, java.util.Set<java.util.UUID>> ids = (u, t) ->
                areaService.getAvailableAreasForRequest(u.getEmail(), t).stream().map(AreaSimpleResponse::id)
                        .collect(java.util.stream.Collectors.toSet());

        assertThat(ids.apply(creatorL1, ind)).contains(contactArea.getId(), internalArea.getId()).doesNotContain(highlyArea.getId());
        assertThat(ids.apply(creatorL1, grp)).doesNotContain(contactArea.getId(), internalArea.getId(), highlyArea.getId());
        assertThat(ids.apply(creatorL2, ind)).contains(highlyArea.getId()).doesNotContain(contactArea.getId(), internalArea.getId());
        assertThat(ids.apply(creatorL2, grp)).contains(contactArea.getId(), internalArea.getId()).doesNotContain(highlyArea.getId());

        // Đổi loại internalArea sang Tuyệt mật: cấp 3; với L2 chuyển từ danh sách nhóm sang danh sách cá nhân
        areaService.update(internalArea.getId(), AreaUpdateRequest.builder()
                .name(internalArea.getName())
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .building(internalArea.getBuilding())
                .floor(internalArea.getFloor())
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .reason("Đổi loại sang Tuyệt mật")
                .version(internalArea.getVersion())
                .build(), adminUser.getEmail());

        assertThat(areaRepository.findById(internalArea.getId()).orElseThrow().getAreaAccessLevel()).isEqualTo(3);
        assertThat(ids.apply(creatorL2, grp)).doesNotContain(internalArea.getId());
        assertThat(ids.apply(creatorL2, ind)).contains(internalArea.getId());
        assertThat(ids.apply(creatorL1, ind)).doesNotContain(internalArea.getId());
    }
}
