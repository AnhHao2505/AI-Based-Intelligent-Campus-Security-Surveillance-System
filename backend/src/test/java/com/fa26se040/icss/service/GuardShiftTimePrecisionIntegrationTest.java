package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Giờ ca trực (cột TIME) có phần lẻ của giây và trước 07:00: với Hibernate 6.4 + TZ +07, đọc lại từng ném
 * "Invalid value for NanoOfSecond" (java.sql.Time ứng với millis âm). Ngày ca cố định để không phụ thuộc giờ chạy.
 */
class GuardShiftTimePrecisionIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate SHIFT_DATE = LocalDate.of(2020, 1, 15);
    private static final ZoneOffset VN_OFFSET = ZoneOffset.ofHours(7);

    @Autowired private GuardShiftRepository guardShiftRepository;
    @Autowired private IncidentRecipientResolver recipientResolver;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private String suffix;
    private final List<UUID> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM guard_shifts WHERE guard_id = ?", userId);
        }
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        userIds.clear();
    }

    private User saveUser(String prefix, Role role) {
        User user = userRepository.save(User.builder()
                .userCode(prefix + "-" + suffix)
                .fullName(prefix + " " + suffix)
                .email(prefix.toLowerCase() + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    private GuardShift checkedInShift(User guard, ShiftType type, LocalTime start, LocalTime end) {
        return GuardShift.builder()
                .guard(guard)
                .shiftDate(SHIFT_DATE)
                .shiftType(type)
                .startTime(start)
                .endTime(end)
                .status(ShiftStatus.CHECKED_IN)
                .build();
    }

    private GuardShift readInNewTransaction(UUID shiftId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                guardShiftRepository.findById(shiftId).orElseThrow());
    }

    @Test
    @DisplayName("T1. Giờ có phần lẻ của giây trước 07:00 -> lưu, đọc lại, cập nhật không lỗi; giờ bị cắt về giây")
    void fractionalSecondTimesBeforeSevenAm_persistAndReadBack() {
        User guard = saveUser("GTP1", Role.GUARD);
        GuardShift saved = guardShiftRepository.saveAndFlush(checkedInShift(guard, ShiftType.SHIFT_MORNING,
                LocalTime.of(5, 30, 15, 123_000_000), LocalTime.of(6, 45, 59, 987_000_000)));

        GuardShift reloaded = readInNewTransaction(saved.getId());
        assertEquals(LocalTime.of(5, 30, 15), reloaded.getStartTime());
        assertEquals(LocalTime.of(6, 45, 59), reloaded.getEndTime());

        reloaded.setStartTime(LocalTime.of(4, 10, 20, 555_000_000));
        guardShiftRepository.saveAndFlush(reloaded);

        GuardShift updated = readInNewTransaction(saved.getId());
        assertEquals(LocalTime.of(4, 10, 20), updated.getStartTime());
    }

    @Test
    @DisplayName("T2. Ca qua đêm 22:00–06:00 -> 01:00 hôm sau đang trực, 06:00 hôm sau hết ca")
    void overnightShift_viaDatabase() {
        User guard = saveUser("GTP2", Role.GUARD);
        guardShiftRepository.saveAndFlush(checkedInShift(guard, ShiftType.SHIFT_NIGHT,
                LocalTime.of(22, 0), LocalTime.of(6, 0)));

        List<UUID> atOne = recipientResolver.findOnDutyGuards(
                OffsetDateTime.of(LocalDate.of(2020, 1, 16), LocalTime.of(1, 0), VN_OFFSET))
                .stream().map(User::getId).toList();
        assertTrue(atOne.contains(guard.getId()), "01:00 hôm sau phải còn trong ca, onDuty=" + atOne);

        List<UUID> atSix = recipientResolver.findOnDutyGuards(
                OffsetDateTime.of(LocalDate.of(2020, 1, 16), LocalTime.of(6, 0), VN_OFFSET))
                .stream().map(User::getId).toList();
        assertFalse(atSix.contains(guard.getId()), "06:00 hôm sau đã hết ca, onDuty=" + atSix);
    }
}
