package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * BR-AP-28: quyền chỉ định có hiệu lực từ giờ FM chọn; giờ đó đã qua thì tính từ lúc cấp.
 * validFrom được lưu = max(giờ gửi lên, giờ máy chủ lúc lưu), không có dung sai, không có hiệu lực ngược.
 */
@ExtendWith(MockitoExtension.class)
class AreaAssignedPersonnelValidFromTest {

    private static final String FM_EMAIL = "fm@fpt.edu.vn";

    @Mock
    private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AreaAssignedPersonnelService service;

    private User lecturer;
    private Area lab;

    @BeforeEach
    void setUp() {
        User fm = User.builder()
                .id(UUID.randomUUID())
                .email(FM_EMAIL)
                .fullName("Quản lý cơ sở")
                .userCode("FM001")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();

        lecturer = User.builder()
                .id(UUID.randomUUID())
                .email("lecturer@fpt.edu.vn")
                .fullName("Nguyễn Văn Thầy")
                .userCode("GV001")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();

        lab = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Lab 01")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .isActive(true)
                .build();

        lenient().when(userRepository.findByEmail(FM_EMAIL)).thenReturn(Optional.of(fm));
        lenient().when(areaRepository.findById(lab.getId())).thenReturn(Optional.of(lab));
        lenient().when(assignedPersonnelRepository.findUserByIdForUpdate(lecturer.getId()))
                .thenReturn(Optional.of(lecturer));
        lenient().when(assignedPersonnelRepository.save(any(AreaAssignedPersonnel.class))).thenAnswer(inv -> {
            AreaAssignedPersonnel a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
    }

    private AssignedPersonnelResponse create(OffsetDateTime from, OffsetDateTime to) {
        return service.create(lab.getId(),
                new AssignedPersonnelCreateRequest(lecturer.getId(), from, to, "Giảng dạy lab"), FM_EMAIL);
    }

    private AreaAssignedPersonnel savedRecord() {
        ArgumentCaptor<AreaAssignedPersonnel> captor = ArgumentCaptor.forClass(AreaAssignedPersonnel.class);
        verify(assignedPersonnelRepository).save(captor.capture());
        return captor.getValue();
    }

    private AuditSnapshot auditedNewSnapshot() {
        ArgumentCaptor<AuditSnapshot> captor = ArgumentCaptor.forClass(AuditSnapshot.class);
        verify(auditService).record(any(), any(), any(), any(), any(), any(), captor.capture(), any(), any(User.class));
        return captor.getValue();
    }

    private void assertStoredAtServerTime(OffsetDateTime stored, OffsetDateTime before, OffsetDateTime after) {
        assertFalse(stored.isBefore(before), "validFrom lưu " + stored + " không được trước giờ máy chủ " + before);
        assertFalse(stored.isAfter(after), "validFrom lưu " + stored + " không được sau giờ máy chủ " + after);
    }

    @Test
    @DisplayName("BR-AP-28: validFrom ở tương lai -> lưu đúng giờ gửi lên, UPCOMING")
    void create_FutureValidFrom_StoredAsSubmitted() {
        OffsetDateTime from = OffsetDateTime.now().plusHours(2);

        AssignedPersonnelResponse res = create(from, from.plusHours(2));

        assertEquals(from, savedRecord().getValidFrom());
        assertEquals(from, res.validFrom());
        assertEquals(AssignedPersonnelStatus.UPCOMING, res.status());
    }

    @Test
    @DisplayName("BR-AP-28: validFrom lùi 20 giây -> chấp nhận, validFrom lưu = giờ máy chủ (bản ghi, audit, response)")
    void create_TwentySecondsPast_StoredAtServerTime() {
        OffsetDateTime before = OffsetDateTime.now();
        OffsetDateTime to = before.plusDays(1);

        AssignedPersonnelResponse res = create(before.minusSeconds(20), to);
        OffsetDateTime after = OffsetDateTime.now();

        OffsetDateTime stored = savedRecord().getValidFrom();
        assertStoredAtServerTime(stored, before, after);
        assertEquals(stored, res.validFrom());
        assertEquals(AssignedPersonnelStatus.ACTIVE, res.status());
        assertEquals(new AreaAssignmentAuditSnapshot(stored, to, AssignedPersonnelStatus.ACTIVE), auditedNewSnapshot());
    }

    @Test
    @DisplayName("BR-AP-28: validFrom lùi 2 giờ -> chấp nhận (không còn ERR_AP_013), validFrom lưu = giờ máy chủ")
    void create_TwoHoursPast_StoredAtServerTime() {
        OffsetDateTime before = OffsetDateTime.now();
        OffsetDateTime to = before.plusDays(1);

        AssignedPersonnelResponse res = create(before.minusHours(2), to);
        OffsetDateTime after = OffsetDateTime.now();

        OffsetDateTime stored = savedRecord().getValidFrom();
        assertStoredAtServerTime(stored, before, after);
        assertEquals(stored, res.validFrom());
        assertEquals(AssignedPersonnelStatus.ACTIVE, res.status());
        assertEquals(new AreaAssignmentAuditSnapshot(stored, to, AssignedPersonnelStatus.ACTIVE), auditedNewSnapshot());
    }

    @Test
    @DisplayName("BR-AP-28: validFrom bỏ trống -> giữ hành vi cũ, validFrom = giờ máy chủ lúc cấp")
    void create_NullValidFrom_StoredAtServerTime() {
        OffsetDateTime before = OffsetDateTime.now();

        AssignedPersonnelResponse res = create(null, null);
        OffsetDateTime after = OffsetDateTime.now();

        assertStoredAtServerTime(savedRecord().getValidFrom(), before, after);
        assertEquals(AssignedPersonnelStatus.ACTIVE, res.status());
    }
}
