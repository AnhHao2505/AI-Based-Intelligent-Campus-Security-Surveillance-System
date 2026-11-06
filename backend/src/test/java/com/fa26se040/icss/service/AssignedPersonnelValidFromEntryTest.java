package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-AP-28: FM chọn validFrom đã qua thì quyền chỉ tính từ lúc cấp. Chứng minh bằng hàm kiểm quyền có sẵn
 * (AccessDecisionService.checkEntry): sự kiện vào khu trước lúc cấp không được tính là có quyền chỉ định.
 * @Transactional: dữ liệu fixture rollback sau mỗi test.
 */
@Transactional
class AssignedPersonnelValidFromEntryTest extends AbstractIntegrationTest {

    @Autowired private AreaAssignedPersonnelService assignedPersonnelService;
    @Autowired private AccessDecisionService accessDecisionService;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    private String suffix;
    private User fm;
    private User target;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa AP validFrom")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa AP validFrom").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa AP validFrom", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm = user("fm", Role.FACILITY_MANAGER);
        target = user("usr", Role.NORMAL_USER);
        // Khu Bảo mật (cấp 2), người được gán cấp 1: chỉ vào được nhờ quyền chỉ định
        area = areaRepository.save(Area.builder()
                .name("AP validFrom " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private User user(String tag, Role role) {
        return userRepository.save(User.builder()
                .userCode("APV-" + tag.toUpperCase() + "-" + suffix)
                .fullName("AP validFrom " + tag + " " + suffix)
                .email("apv-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("BR-AP-28: FM chọn validFrom lùi 2 giờ -> vào khu trước lúc cấp không có quyền, sau lúc cấp có quyền chỉ định")
    void entryBeforeGrant_notAuthorized() {
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime chosenFrom = before.minusHours(2);

        AssignedPersonnelResponse res = assignedPersonnelService.create(area.getId(),
                new AssignedPersonnelCreateRequest(target.getId(), chosenFrom, before.plusDays(1), "Trực phòng"),
                fm.getEmail());

        OffsetDateTime grantedFrom = assignedPersonnelRepository.findById(res.id()).orElseThrow().getValidFrom();
        assertThat(grantedFrom).isAfterOrEqualTo(before);

        // Trong khoảng FM chọn nhưng trước lúc cấp: không được tính là có quyền chỉ định
        AccessDecision beforeGrant = accessDecisionService.checkEntry(target.getId(), area.getId(), before.minusHours(1));
        assertThat(beforeGrant.source()).isNotEqualTo(AccessSource.ASSIGNED_PERSONNEL);
        assertThat(beforeGrant.allowed()).as(beforeGrant.reason()).isFalse();

        // Đối chứng: sau lúc cấp thì có quyền nhờ quyền chỉ định
        AccessDecision afterGrant = accessDecisionService.checkEntry(target.getId(), area.getId(), grantedFrom.plusSeconds(1));
        assertThat(afterGrant.allowed()).as(afterGrant.reason()).isTrue();
        assertThat(afterGrant.source()).isEqualTo(AccessSource.ASSIGNED_PERSONNEL);
    }

    @Test
    @DisplayName("BR-AP-03 + BR-AP-28: đợt cũ kết thúc trong [giờ gửi lên, giờ hiện tại] -> đợt mới không bị ERR_AP_004")
    void oldGrantEndedBetweenChosenFromAndNow_noOverlap() {
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        AreaAssignedPersonnel old = assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area)
                .user(target)
                .validFrom(before.minusHours(3))
                .validTo(before.minusHours(1))
                .createdBy(fm)
                .build());

        // Khoảng gửi lên [now − 2h, ...) chồng với đợt cũ [now − 3h, now − 1h); khoảng được lưu [now, ...) thì không
        AssignedPersonnelResponse res = assignedPersonnelService.create(area.getId(),
                new AssignedPersonnelCreateRequest(target.getId(), before.minusHours(2), before.plusDays(1), "Cấp đợt mới"),
                fm.getEmail());

        AreaAssignedPersonnel created = assignedPersonnelRepository.findById(res.id()).orElseThrow();
        assertThat(created.getValidFrom()).isAfterOrEqualTo(before);
        assertThat(created.getValidFrom()).isAfter(old.getValidTo());
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).hasSize(2);
    }
}
