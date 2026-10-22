package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V88: chạy NGUYÊN VĂN câu SQL của V88 (đọc từ file migration trên classpath) trên dữ liệu dựng trong một transaction,
 * kiểm xong thì rollback (không để lại thay đổi trên DB test), cùng cách V86RevokeAssignmentsOfInactiveUsersMigrationTest.
 */
class V88FinishReasonBackfillMigrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "db/migration/V88__access_request_finish_reason.sql";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private User user(String tag, Role role) {
        return userRepository.saveAndFlush(User.builder()
                .userCode("V88-" + tag.toUpperCase() + "-" + suffix)
                .fullName("V88 " + tag + " " + suffix)
                .email("v88-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
    }

    private Area area() {
        Building building = buildingRepository.findByNameIgnoreCase("Tòa V88")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa V88").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa V88", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        return areaRepository.saveAndFlush(Area.builder()
                .name("V88 " + suffix)
                .building("Tòa V88")
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private AccessRequest request(Area area, User requester, User reviewer, RequestStatus status) {
        OffsetDateTime start = OffsetDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
        return accessRequestRepository.saveAndFlush(AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn V88 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(4))
                .status(status)
                .reviewer(reviewer)
                .reviewedAt(start.minusHours(1))
                .build());
    }

    private void finishAudit(AccessRequest r, String reason, User actor, OffsetDateTime at) {
        jdbc.update("INSERT INTO audit_logs (id, target_type, action, target_id, area_id, subject_user_id, reason, "
                        + "changed_by, changed_at, actor_type, actor_source, correlation_id) "
                        + "VALUES (gen_random_uuid(), 'ACCESS_REQUEST', 'FINISH', ?, ?, ?, ?, ?, ?, ?, ?, gen_random_uuid())",
                r.getId().toString(), r.getArea().getId(), r.getRequester().getId(), reason,
                actor != null ? actor.getId() : null, at,
                actor != null ? "USER" : "SYSTEM", actor != null ? null : "V88_TEST");
    }

    private Map<String, Object> finishColumns(AccessRequest r) {
        return jdbc.queryForMap("SELECT status, finish_reason, finished_by FROM access_requests WHERE id = ?", r.getId());
    }

    @Test
    @DisplayName("V88 điền bù: đơn FINISHED lấy lý do + người của audit FINISH mới nhất; không audit thì NULL; "
            + "đã có giá trị hoặc không FINISHED thì không đổi; chạy lại không lỗi")
    void backfill_fromLatestFinishAudit() throws Exception {
        String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            try {
                User fm1 = user("fm1", Role.FACILITY_MANAGER);
                User fm2 = user("fm2", Role.FACILITY_MANAGER);
                User requester = user("req", Role.NORMAL_USER);
                Area area = area();

                // 1. FINISHED, hai audit FINISH: lấy bản mới nhất
                AccessRequest twoAudits = request(area, requester, fm1, RequestStatus.FINISHED);
                finishAudit(twoAudits, "Lý do cũ hơn, FM một kết thúc", fm1, now.minusHours(2));
                finishAudit(twoAudits, "Lý do mới nhất, FM hai kết thúc", fm2, now.minusHours(1));

                // 2. FINISHED, không có audit FINISH: để NULL
                AccessRequest noAudit = request(area, requester, fm1, RequestStatus.FINISHED);

                // 3. FINISHED đã có finish_reason (ghi từ ứng dụng sau V88): không ghi đè
                AccessRequest alreadySet = request(area, requester, fm1, RequestStatus.FINISHED);
                jdbc.update("UPDATE access_requests SET finish_reason = ?, finished_by = ? WHERE id = ?",
                        "Đã ghi từ ứng dụng", fm1.getId(), alreadySet.getId());
                finishAudit(alreadySet, "Audit khác không được ghi đè", fm2, now);

                // 4. Không FINISHED nhưng lỡ có audit FINISH: không đụng
                AccessRequest approved = request(area, requester, fm1, RequestStatus.APPROVED);
                finishAudit(approved, "Không được điền vào đơn APPROVED", fm1, now);

                // 5. Audit FINISH do hệ thống (changed_by NULL): lý do điền, người kết thúc NULL
                AccessRequest systemFinished = request(area, requester, fm1, RequestStatus.FINISHED);
                finishAudit(systemFinished, "Hệ thống kết thúc đơn", null, now.minusMinutes(5));

                jdbc.execute(sql);

                Map<String, Object> c1 = finishColumns(twoAudits);
                assertEquals("Lý do mới nhất, FM hai kết thúc", c1.get("finish_reason"));
                assertEquals(fm2.getId(), c1.get("finished_by"));
                assertEquals("FINISHED", c1.get("status"));

                Map<String, Object> c2 = finishColumns(noAudit);
                assertNull(c2.get("finish_reason"));
                assertNull(c2.get("finished_by"));

                Map<String, Object> c3 = finishColumns(alreadySet);
                assertEquals("Đã ghi từ ứng dụng", c3.get("finish_reason"));
                assertEquals(fm1.getId(), c3.get("finished_by"));

                Map<String, Object> c4 = finishColumns(approved);
                assertEquals("APPROVED", c4.get("status"));
                assertNull(c4.get("finish_reason"));
                assertNull(c4.get("finished_by"));

                Map<String, Object> c5 = finishColumns(systemFinished);
                assertEquals("Hệ thống kết thúc đơn", c5.get("finish_reason"));
                assertNull(c5.get("finished_by"));

                // Chạy lại không lỗi, kết quả giữ nguyên
                jdbc.execute(sql);
                assertEquals(c1, finishColumns(twoAudits));
                assertEquals(c3, finishColumns(alreadySet));
                assertEquals(c4, finishColumns(approved));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
