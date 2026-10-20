package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.EscalationCloseReason;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.scheduler.IncidentEscalationJob;
import com.fa26se040.icss.scheduler.IncidentEscalationJob.Outcome;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leo thang sự cố trên DB test thật (V80): migration, conditional UPDATE (BR-IE-07), job đầu-cuối, resolver (BR-IE-02).
 * Job nền tắt ở profile test (icss.scheduler.incident-escalation-enabled=false); test gọi thẳng processEscalation theo id
 * nên không đụng bản ghi leo thang của test khác.
 *
 * <p>Không phụ thuộc số FM có sẵn trong DB test: chỉ assert trên FM / ADMIN do test tạo. Sau mỗi test dọn notification
 * gắn với sự cố test tạo (kể cả thông báo job gửi cho FM có sẵn trong DB) và user test tạo (kèm ca trực của họ).</p>
 */
class IncidentEscalationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IncidentEscalationJob job;
    @Autowired
    private IncidentRecipientResolver resolver;
    @Autowired
    private IncidentEscalationRepository escalationRepository;
    @Autowired
    private SecurityIncidentRepository incidentRepository;
    @Autowired
    private SystemConfigurationRepository systemConfigurationRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private InAppNotificationService inAppNotificationService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AreaRepository areaRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private FloorRepository floorRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private String suffix;
    private Area area;
    private User fmUser;
    private User adminUser;
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdIncidentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa Beta")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa Beta").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa Beta", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("Phòng leo thang " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());
        fmUser = saveUser("FMIE", Role.FACILITY_MANAGER);
        adminUser = saveUser("ADIE", Role.ADMIN);
    }

    @AfterEach
    void cleanUp() {
        inTx(() -> {
            if (!createdIncidentIds.isEmpty()) {
                entityManager.createQuery("DELETE FROM Notification n WHERE n.referenceId IN :ids")
                        .setParameter("ids", createdIncidentIds).executeUpdate();
            }
            if (!createdUserIds.isEmpty()) {
                entityManager.createQuery("DELETE FROM Notification n WHERE n.recipient.id IN :ids")
                        .setParameter("ids", createdUserIds).executeUpdate();
                entityManager.createQuery("DELETE FROM GuardShift s WHERE s.guard.id IN :ids")
                        .setParameter("ids", createdUserIds).executeUpdate();
                entityManager.createQuery("DELETE FROM User u WHERE u.id IN :ids")
                        .setParameter("ids", createdUserIds).executeUpdate();
            }
            return null;
        });
        createdIncidentIds.clear();
        createdUserIds.clear();
    }

    private User saveUser(String prefix, Role role) {
        return saveUser(prefix, role, true, null);
    }

    private User saveUser(String prefix, Role role, boolean active, OffsetDateTime deletedAt) {
        User user = userRepository.save(User.builder()
                .userCode(prefix + "-" + suffix)
                .fullName(prefix + " " + suffix)
                .email(prefix.toLowerCase() + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(active)
                .deletedAt(deletedAt)
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    private IncidentEscalation saveIncidentWithEscalation(IncidentStatus status, int guardRecipientCount) {
        SecurityIncident incident = incidentRepository.save(SecurityIncident.builder()
                .cameraCode("CAM-IE-" + suffix)
                .area(area)
                .building("TOA_BETA")
                .eventType("INTRUSION")
                .detectedAt(OffsetDateTime.now())
                .status(status)
                .version(0)
                .build());
        createdIncidentIds.add(incident.getId());
        return escalationRepository.save(IncidentEscalation.builder()
                .incident(incident)
                .guardsNotifiedAt(OffsetDateTime.now())
                .guardRecipientCount(guardRecipientCount)
                .build());
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    @Test
    @DisplayName("V80: 2 tham số SECURITY đúng mặc định / khoảng / đơn vị / thứ tự")
    void migration_configKeysSeeded() {
        SystemConfiguration fmMinutes = systemConfigurationRepository.findById("INCIDENT_ESCALATE_FM_MINUTES").orElseThrow();
        assertEquals("5", fmMinutes.getConfigValue());
        assertEquals("INTEGER", fmMinutes.getDataType());
        assertEquals(0, new BigDecimal("1").compareTo(fmMinutes.getMinValue()));
        assertEquals(0, new BigDecimal("60").compareTo(fmMinutes.getMaxValue()));
        assertEquals("phút", fmMinutes.getUnit());
        assertEquals("SECURITY", fmMinutes.getConfigGroup());
        assertEquals(54, fmMinutes.getDisplayOrder());
        assertTrue(fmMinutes.getEditable());

        SystemConfiguration checkSeconds = systemConfigurationRepository.findById("INCIDENT_ESCALATION_CHECK_SECONDS").orElseThrow();
        assertEquals("30", checkSeconds.getConfigValue());
        assertEquals(0, new BigDecimal("10").compareTo(checkSeconds.getMinValue()));
        assertEquals(0, new BigDecimal("120").compareTo(checkSeconds.getMaxValue()));
        assertEquals("giây", checkSeconds.getUnit());
        assertEquals("SECURITY", checkSeconds.getConfigGroup());
        assertEquals(55, checkSeconds.getDisplayOrder());
    }

    @Test
    @DisplayName("V80: bảng incident_escalations ghi / đọc được, 1 bản ghi / sự cố")
    void migration_tableWritable() {
        IncidentEscalation saved = saveIncidentWithEscalation(IncidentStatus.NEW, 2);

        IncidentEscalation reloaded = escalationRepository.findByIncidentId(saved.getIncident().getId()).orElseThrow();
        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(2, reloaded.getGuardRecipientCount());
        assertNotNull(reloaded.getCreatedAt());
        assertNull(reloaded.getClosedAt());
        assertTrue(escalationRepository.findOpenIds().contains(saved.getId()));
    }

    @Test
    @DisplayName("BR-IE-07: conditional UPDATE trả 1 dòng ở lần 1, 0 dòng ở lần 2")
    void conditionalUpdate_secondTimeReturnsZero() {
        IncidentEscalation saved = saveIncidentWithEscalation(IncidentStatus.NEW, 1);
        OffsetDateTime now = OffsetDateTime.now();

        assertEquals(1, inTx(() -> escalationRepository.markEscalatedIfOpen(saved.getId(), now)));
        assertEquals(0, inTx(() -> escalationRepository.markEscalatedIfOpen(saved.getId(), now.plusSeconds(30))));
        assertEquals(0, inTx(() -> escalationRepository.closeIfOpen(saved.getId(), EscalationCloseReason.CLAIMED, now.plusSeconds(60))));

        IncidentEscalation reloaded = escalationRepository.findById(saved.getId()).orElseThrow();
        assertEquals(EscalationCloseReason.ESCALATED, reloaded.getCloseReason());
        assertNotNull(reloaded.getFmEscalatedAt());
        assertEquals(reloaded.getFmEscalatedAt().toInstant(), reloaded.getClosedAt().toInstant());
        assertFalse(escalationRepository.findOpenIds().contains(saved.getId()));
    }

    @Test
    @DisplayName("V84 / S5: mốc báo ADMIN chỉ giành được 1 lần, bản ghi vẫn mở và sau đó vẫn leo thang FM được")
    void adminNoFmMark_onceAndRecordStaysOpen() {
        IncidentEscalation saved = saveIncidentWithEscalation(IncidentStatus.NEW, 1);
        OffsetDateTime now = OffsetDateTime.now();

        assertEquals(1, inTx(() -> escalationRepository.markAdminNoFmNotifiedIfNot(saved.getId(), now)));
        assertEquals(0, inTx(() -> escalationRepository.markAdminNoFmNotifiedIfNot(saved.getId(), now.plusSeconds(30))));

        IncidentEscalation reloaded = escalationRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(reloaded.getAdminNoFmNotifiedAt());
        assertNull(reloaded.getClosedAt());
        assertTrue(escalationRepository.findOpenIds().contains(saved.getId()));

        // Khi đã có FM: bản ghi vẫn giành được quyền báo FM như thường
        assertEquals(1, inTx(() -> escalationRepository.markEscalatedIfOpen(saved.getId(), now.plusSeconds(60))));
    }

    @Test
    @DisplayName("V84: CHECK của notifications nhận INCIDENT_ESCALATION_NO_FM, lưu được cho ADMIN (không bị BR-NT-26 lọc)")
    void noFmNotificationType_storedForAdmin() {
        IncidentEscalation saved = saveIncidentWithEscalation(IncidentStatus.NEW, 1);
        UUID incidentId = saved.getIncident().getId();

        inAppNotificationService.createForUsers(List.of(adminUser), NotificationType.INCIDENT_ESCALATION_NO_FM,
                "Không có Quản lý CSVC nhận leo thang sự cố", "Kiểm thử V84", incidentId,
                IncidentEscalationJob.REF_TYPE_SECURITY_INCIDENT);

        List<Notification> adminInbox = notificationRepository
                .findByRecipientIdOrderByCreatedAtDesc(adminUser.getId(), PageRequest.of(0, 50)).getContent();
        assertEquals(1, adminInbox.stream()
                .filter(n -> incidentId.equals(n.getReferenceId()) && n.getType() == NotificationType.INCIDENT_ESCALATION_NO_FM)
                .count());
    }

    @Test
    @DisplayName("BR-IE-07 / BR-IE-10: 2 lần chạy đồng thời -> 1 lần báo FM, ADMIN không nhận")
    void concurrentRuns_notifyOnce() throws Exception {
        IncidentEscalation saved = saveIncidentWithEscalation(IncidentStatus.NEW, 0);
        UUID incidentId = saved.getIncident().getId();
        OffsetDateTime now = OffsetDateTime.now();

        CountDownLatch start = new CountDownLatch(1);
        Callable<Outcome> run = () -> {
            start.await();
            return job.processEscalation(saved.getId(), now);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = pool.submit(run);
            Future<Outcome> second = pool.submit(run);
            start.countDown();
            List<Outcome> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(o -> o == Outcome.ESCALATED).count(), "outcomes=" + outcomes);
            assertEquals(1, outcomes.stream().filter(o -> o == Outcome.SKIPPED).count(), "outcomes=" + outcomes);
        } finally {
            pool.shutdownNow();
        }

        IncidentEscalation reloaded = escalationRepository.findById(saved.getId()).orElseThrow();
        assertEquals(EscalationCloseReason.ESCALATED, reloaded.getCloseReason());
        // Giá trị phụ thuộc số FM đang có trong DB test -> chỉ kiểm đã ghi; người nhận kiểm qua FM do test tạo
        assertNotNull(reloaded.getFmRecipientCount());

        List<Notification> fmInbox = notificationRepository
                .findByRecipientIdOrderByCreatedAtDesc(fmUser.getId(), PageRequest.of(0, 50)).getContent();
        assertEquals(1, fmInbox.stream()
                .filter(n -> incidentId.equals(n.getReferenceId()) && n.getType() == NotificationType.INCIDENT_ESCALATED)
                .count());
        assertEquals("SECURITY_INCIDENT", fmInbox.stream()
                .filter(n -> incidentId.equals(n.getReferenceId())).findFirst().orElseThrow().getReferenceType());

        List<Notification> adminInbox = notificationRepository
                .findByRecipientIdOrderByCreatedAtDesc(adminUser.getId(), PageRequest.of(0, 50)).getContent();
        assertTrue(adminInbox.stream().noneMatch(n -> incidentId.equals(n.getReferenceId())));
    }

    @Test
    @DisplayName("BR-IE-06: sự cố đã CLAIMED -> job đóng bản ghi với lý do CLAIMED, không có thông báo")
    void claimedIncident_closedWithoutNotification() {
        IncidentEscalation saved = saveIncidentWithEscalation(IncidentStatus.CLAIMED, 1);

        assertEquals(Outcome.CLOSED, job.processEscalation(saved.getId(), OffsetDateTime.now()));

        IncidentEscalation reloaded = escalationRepository.findById(saved.getId()).orElseThrow();
        assertEquals(EscalationCloseReason.CLAIMED, reloaded.getCloseReason());
        assertNull(reloaded.getFmEscalatedAt());
        assertFalse(notificationRepository.existsByReferenceIdAndType(saved.getIncident().getId(), NotificationType.INCIDENT_ESCALATED));
    }

    @Test
    @DisplayName("BR-IE-02 trên DB thật: chỉ ca CHECKED_IN trong khung giờ của tài khoản đang hoạt động; SCHEDULED, ca kẹt hôm qua, "
            + "bảo vệ bị vô hiệu hoá / xoá mềm bị loại; ca qua đêm lúc 02:00 có")
    void resolver_realShifts() {
        // Ngày xa trong tương lai để không lẫn ca của test khác
        LocalDate day = LocalDate.of(2031, 3, 12);
        User onShift = saveUser("GIEA", Role.GUARD);
        User scheduled = saveUser("GIEB", Role.GUARD);
        User stuck = saveUser("GIEC", Role.GUARD);
        User overnight = saveUser("GIED", Role.GUARD);
        User disabled = saveUser("GIEE", Role.GUARD, false, null);
        User softDeleted = saveUser("GIEF", Role.GUARD, true, OffsetDateTime.now());
        inTx(() -> {
            persistShift(onShift, day, "08:00", "12:00", ShiftStatus.CHECKED_IN);
            persistShift(onShift, day, "09:00", "11:00", ShiftStatus.CHECKED_IN);
            persistShift(scheduled, day, "08:00", "12:00", ShiftStatus.SCHEDULED);
            persistShift(stuck, day.minusDays(1), "08:00", "16:00", ShiftStatus.CHECKED_IN);
            persistShift(overnight, day.minusDays(1), "22:00", "06:00", ShiftStatus.CHECKED_IN);
            persistShift(disabled, day, "08:00", "12:00", ShiftStatus.CHECKED_IN);
            persistShift(softDeleted, day, "08:00", "12:00", ShiftStatus.CHECKED_IN);
            persistShift(disabled, day.minusDays(1), "22:00", "06:00", ShiftStatus.CHECKED_IN);
            persistShift(softDeleted, day.minusDays(1), "22:00", "06:00", ShiftStatus.CHECKED_IN);
            return null;
        });
        Set<UUID> ours = Set.of(onShift.getId(), scheduled.getId(), stuck.getId(), overnight.getId(),
                disabled.getId(), softDeleted.getId());

        List<UUID> at1000 = resolver.findOnDutyGuards(OffsetDateTime.of(day, LocalTime.of(10, 0), ZoneOffset.ofHours(7)))
                .stream().map(User::getId).filter(ours::contains).collect(Collectors.toList());
        assertEquals(List.of(onShift.getId()), at1000);

        List<UUID> at0200 = resolver.findOnDutyGuards(OffsetDateTime.of(day, LocalTime.of(2, 0), ZoneOffset.ofHours(7)))
                .stream().map(User::getId).filter(ours::contains).collect(Collectors.toList());
        assertEquals(List.of(overnight.getId()), at0200);
    }

    private void persistShift(User guard, LocalDate date, String start, String end, ShiftStatus status) {
        entityManager.persist(GuardShift.builder()
                .guard(entityManager.getReference(User.class, guard.getId()))
                .shiftDate(date)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .status(status)
                .build());
    }
}
