package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AssignedPersonnelErrorCode;
import com.fa26se040.icss.exception.AssignedPersonnelException;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho AreaAssignedPersonnelService.
 * Các query chồng lấn của repository được giả lập bằng danh sách trong bộ nhớ (store) với đúng
 * điều kiện như JPQL, để kiểm tra ngữ nghĩa BR-AP-03 ở tầng service.
 */
@ExtendWith(MockitoExtension.class)
class AreaAssignedPersonnelServiceTest {

    private static final String FM_EMAIL = "fm@fpt.edu.vn";

    @Mock
    private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccessControlAuditService auditService;

    @InjectMocks
    private AreaAssignedPersonnelService service;

    private User fm;
    private User lecturer;
    private Area lab;
    private List<AreaAssignedPersonnel> store;

    @BeforeEach
    void setUp() {
        fm = User.builder()
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
                .code("LAB-01")
                .name("Phòng Lab 01")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .isActive(true)
                .build();

        store = new ArrayList<>();

        lenient().when(userRepository.findByEmail(FM_EMAIL)).thenReturn(Optional.of(fm));
        lenient().when(areaRepository.findById(lab.getId())).thenReturn(Optional.of(lab));
        lenient().when(assignedPersonnelRepository.findUserByIdForUpdate(lecturer.getId()))
                .thenReturn(Optional.of(lecturer));

        lenient().when(assignedPersonnelRepository.save(any(AreaAssignedPersonnel.class))).thenAnswer(inv -> {
            AreaAssignedPersonnel a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
                store.add(a);
            }
            return a;
        });

        lenient().when(assignedPersonnelRepository.findByIdAndAreaIdWithDetails(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            UUID areaId = inv.getArgument(1);
            return store.stream()
                    .filter(a -> a.getId().equals(id) && a.getArea().getId().equals(areaId))
                    .findFirst();
        });

        lenient().when(assignedPersonnelRepository.findByAreaIdWithDetails(any())).thenAnswer(inv -> {
            UUID areaId = inv.getArgument(0);
            return store.stream().filter(a -> a.getArea().getId().equals(areaId)).toList();
        });

        // Giả lập đúng điều kiện JPQL findOverlappingNotRevoked
        lenient().when(assignedPersonnelRepository.findOverlappingNotRevoked(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    UUID areaId = inv.getArgument(0);
                    UUID userId = inv.getArgument(1);
                    OffsetDateTime from = inv.getArgument(2);
                    OffsetDateTime to = inv.getArgument(3);
                    return store.stream()
                            .filter(a -> a.getArea().getId().equals(areaId) && a.getUser().getId().equals(userId))
                            .filter(a -> a.getRevokedAt() == null)
                            .filter(a -> a.getValidFrom().isBefore(to))
                            .filter(a -> a.getValidTo() == null || a.getValidTo().isAfter(from))
                            .toList();
                });

        // Giả lập đúng điều kiện JPQL findOverlappingNotRevokedOpenEnded
        lenient().when(assignedPersonnelRepository.findOverlappingNotRevokedOpenEnded(any(), any(), any()))
                .thenAnswer(inv -> {
                    UUID areaId = inv.getArgument(0);
                    UUID userId = inv.getArgument(1);
                    OffsetDateTime from = inv.getArgument(2);
                    return store.stream()
                            .filter(a -> a.getArea().getId().equals(areaId) && a.getUser().getId().equals(userId))
                            .filter(a -> a.getRevokedAt() == null)
                            .filter(a -> a.getValidTo() == null || a.getValidTo().isAfter(from))
                            .toList();
                });
    }

    private AreaAssignedPersonnel existing(OffsetDateTime from, OffsetDateTime to) {
        AreaAssignedPersonnel a = AreaAssignedPersonnel.builder()
                .id(UUID.randomUUID())
                .area(lab)
                .user(lecturer)
                .validFrom(from)
                .validTo(to)
                .createdBy(fm)
                .createdAt(OffsetDateTime.now().minusDays(10))
                .updatedAt(OffsetDateTime.now().minusDays(10))
                .build();
        store.add(a);
        return a;
    }

    private void assertApError(AssignedPersonnelErrorCode expected, Executable executable) {
        AssignedPersonnelException ex = assertThrows(AssignedPersonnelException.class, executable);
        assertEquals(expected, ex.getErrorCode());
    }

    private AssignedPersonnelCreateRequest createReq(OffsetDateTime from, OffsetDateTime to) {
        return new AssignedPersonnelCreateRequest(lecturer.getId(), from, to, "Giảng dạy lab");
    }

    // ------------------------------------------------------------------ BR-AP-02

    @Test
    @DisplayName("BR-AP-02: tạo mới không có validTo (không thời hạn), validFrom mặc định = now → thành công, ACTIVE")
    void create_NoValidTo_DefaultValidFrom_Success() {
        OffsetDateTime before = OffsetDateTime.now();

        AssignedPersonnelResponse res = service.create(lab.getId(), createReq(null, null), FM_EMAIL);

        assertNotNull(res.id());
        assertEquals(lab.getId(), res.areaId());
        assertEquals(lecturer.getId(), res.user().id());
        assertEquals("GV001", res.user().userCode());
        assertEquals(Role.NORMAL_USER, res.user().role());
        assertNull(res.validTo());
        assertEquals(false, res.validFrom().isBefore(before));
        assertEquals(AssignedPersonnelStatus.ACTIVE, res.status());
        assertEquals("Quản lý cơ sở", res.createdBy());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("BR-AP-02: validTo <= validFrom → ERR_AP_002")
    void create_ValidToNotAfterValidFrom_ThrowsErrAp002() {
        OffsetDateTime from = OffsetDateTime.now().plusHours(5);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_002,
                () -> service.create(lab.getId(), createReq(from, from.minusHours(1)), FM_EMAIL));
        assertApError(AssignedPersonnelErrorCode.ERR_AP_002,
                () -> service.create(lab.getId(), createReq(from, from), FM_EMAIL));
        verify(assignedPersonnelRepository, never()).save(any());
    }

    @Test
    @DisplayName("BR-AP-02: validTo trong quá khứ (dù > validFrom) → ERR_AP_003")
    void create_ValidToInPast_ThrowsErrAp003() {
        OffsetDateTime from = OffsetDateTime.now().minusDays(3);
        OffsetDateTime to = OffsetDateTime.now().minusDays(1);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_003,
                () -> service.create(lab.getId(), createReq(from, to), FM_EMAIL));
        verify(assignedPersonnelRepository, never()).save(any());
    }

    @Test
    @DisplayName("BR-AP-02: gán một lần khung tương lai hợp lệ → thành công, UPCOMING")
    void create_FutureWindow_Success_Upcoming() {
        OffsetDateTime from = OffsetDateTime.now().plusHours(2);

        AssignedPersonnelResponse res = service.create(lab.getId(), createReq(from, from.plusHours(2)), FM_EMAIL);

        assertEquals(AssignedPersonnelStatus.UPCOMING, res.status());
        assertEquals(from, res.validFrom());
    }

    @Test
    @DisplayName("BR-AP-02: sửa validTo về quá khứ → ERR_AP_003")
    void update_ValidToInPast_ThrowsErrAp003() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusDays(5), null);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_003,
                () -> service.updateValidTo(lab.getId(), rec.getId(),
                        new AssignedPersonnelUpdateRequest(OffsetDateTime.now().minusMinutes(1)), FM_EMAIL));
        assertNull(rec.getValidTo());
    }

    @Test
    @DisplayName("BR-AP-02: sửa validTo <= validFrom → ERR_AP_002")
    void update_ValidToNotAfterValidFrom_ThrowsErrAp002() {
        OffsetDateTime from = OffsetDateTime.now().plusDays(2);
        AreaAssignedPersonnel rec = existing(from, from.plusHours(4));

        assertApError(AssignedPersonnelErrorCode.ERR_AP_002,
                () -> service.updateValidTo(lab.getId(), rec.getId(),
                        new AssignedPersonnelUpdateRequest(from.minusHours(1)), FM_EMAIL));
    }

    @Test
    @DisplayName("BR-AP-02/03: gia hạn validTo của chính bản ghi (query trả về chính nó) → thành công")
    void update_ExtendValidTo_ExcludesSelf_Success() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
        OffsetDateTime newTo = OffsetDateTime.now().plusHours(3);

        AssignedPersonnelResponse res = service.updateValidTo(lab.getId(), rec.getId(),
                new AssignedPersonnelUpdateRequest(newTo), FM_EMAIL);

        assertEquals(newTo, res.validTo());
        assertEquals(AssignedPersonnelStatus.ACTIVE, res.status());
        verify(assignedPersonnelRepository).findUserByIdForUpdate(lecturer.getId());
    }

    @Test
    @DisplayName("PATCH: {id} không thuộc {areaId} → ERR_AP_001 (404)")
    void update_RecordNotInArea_ThrowsErrAp001() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusHours(1), null);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_001,
                () -> service.updateValidTo(UUID.randomUUID(), rec.getId(),
                        new AssignedPersonnelUpdateRequest(OffsetDateTime.now().plusDays(1)), FM_EMAIL));
    }

    @Test
    @DisplayName("PATCH revoke: {id} không thuộc {areaId} → ERR_AP_001 (404)")
    void revoke_RecordNotInArea_ThrowsErrAp001() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusHours(1), null);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_001,
                () -> service.revoke(UUID.randomUUID(), rec.getId(),
                        new AssignedPersonnelRevokeRequest("Lý do thu hồi"), FM_EMAIL));
    }

    // ------------------------------------------------------------------ BR-AP-03

    @Test
    @DisplayName("BR-AP-03: chồng lấn với bản ghi vô hạn (validTo NULL) → ERR_AP_004")
    void create_OverlapWithUnlimitedRecord_ThrowsErrAp004() {
        existing(OffsetDateTime.now().minusDays(30), null);
        OffsetDateTime from = OffsetDateTime.now().plusDays(1);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_004,
                () -> service.create(lab.getId(), createReq(from, from.plusHours(2)), FM_EMAIL));
        verify(assignedPersonnelRepository, never()).save(any());
    }

    @Test
    @DisplayName("BR-AP-03: bản mới vô hạn chồng với bản ghi có hạn trong tương lai → ERR_AP_004")
    void create_UnlimitedOverlapsFutureWindow_ThrowsErrAp004() {
        OffsetDateTime from = OffsetDateTime.now().plusDays(2);
        existing(from, from.plusHours(2));

        assertApError(AssignedPersonnelErrorCode.ERR_AP_004,
                () -> service.create(lab.getId(), createReq(null, null), FM_EMAIL));
    }

    @Test
    @DisplayName("BR-AP-03: bản cũ vô hạn nhưng đã thu hồi → không chồng lấn, tạo thành công")
    void create_OldRecordRevoked_NoOverlap_Success() {
        AreaAssignedPersonnel old = existing(OffsetDateTime.now().minusDays(30), null);
        old.setRevokedAt(OffsetDateTime.now().minusDays(1));
        old.setRevokedBy(fm);
        old.setRevokeReason("Chuyển công tác");

        AssignedPersonnelResponse res = service.create(lab.getId(), createReq(null, null), FM_EMAIL);

        assertEquals(AssignedPersonnelStatus.ACTIVE, res.status());
        assertEquals(2, store.size());
    }

    @Test
    @DisplayName("BR-AP-03: khoảng nối tiếp (validTo cũ == validFrom mới) → không chồng lấn, tạo thành công")
    void create_AdjacentRanges_NoOverlap_Success() {
        OffsetDateTime t1 = OffsetDateTime.now().plusHours(1);
        OffsetDateTime t2 = t1.plusHours(2);
        existing(t1, t2);

        AssignedPersonnelResponse res = service.create(lab.getId(), createReq(t2, t2.plusHours(2)), FM_EMAIL);

        assertEquals(t2, res.validFrom());
        assertEquals(2, store.size());
    }

    @Test
    @DisplayName("BR-AP-03: sửa validTo làm chồng sang bản ghi khác → ERR_AP_004")
    void update_ExtendIntoOtherRecord_ThrowsErrAp004() {
        OffsetDateTime now = OffsetDateTime.now();
        AreaAssignedPersonnel first = existing(now.minusHours(1), now.plusHours(1));
        existing(now.plusHours(2), now.plusHours(4));

        assertApError(AssignedPersonnelErrorCode.ERR_AP_004,
                () -> service.updateValidTo(lab.getId(), first.getId(),
                        new AssignedPersonnelUpdateRequest(now.plusHours(3)), FM_EMAIL));
        assertEquals(now.plusHours(1), first.getValidTo());
    }

    @Test
    @DisplayName("BR-AP-03: sửa validTo thành NULL (vô hạn) khi đã có bản ghi tương lai → ERR_AP_004")
    void update_ToUnlimited_OverlapsFuture_ThrowsErrAp004() {
        OffsetDateTime now = OffsetDateTime.now();
        AreaAssignedPersonnel first = existing(now.minusHours(1), now.plusHours(1));
        existing(now.plusDays(1), now.plusDays(1).plusHours(2));

        assertApError(AssignedPersonnelErrorCode.ERR_AP_004,
                () -> service.updateValidTo(lab.getId(), first.getId(),
                        new AssignedPersonnelUpdateRequest(null), FM_EMAIL));
    }

    // ------------------------------------------------------------------ BR-AP-04

    @Test
    @DisplayName("BR-AP-04: user inactive → ERR_AP_006")
    void create_InactiveUser_ThrowsErrAp006() {
        lecturer.setIsActive(false);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_006,
                () -> service.create(lab.getId(), createReq(null, null), FM_EMAIL));
        verify(assignedPersonnelRepository, never()).save(any());
    }

    @Test
    @DisplayName("BR-AP-04: user đã bị xoá mềm (deletedAt != null) → ERR_AP_006")
    void create_SoftDeletedUser_ThrowsErrAp006() {
        lecturer.setDeletedAt(OffsetDateTime.now().minusDays(1));

        assertApError(AssignedPersonnelErrorCode.ERR_AP_006,
                () -> service.create(lab.getId(), createReq(null, null), FM_EMAIL));
    }

    @Test
    @DisplayName("BR-AP-04: user không tồn tại → ERR_AP_005")
    void create_UserNotFound_ThrowsErrAp005() {
        UUID unknown = UUID.randomUUID();
        lenient().when(assignedPersonnelRepository.findUserByIdForUpdate(unknown)).thenReturn(Optional.empty());

        assertApError(AssignedPersonnelErrorCode.ERR_AP_005,
                () -> service.create(lab.getId(),
                        new AssignedPersonnelCreateRequest(unknown, null, null, null), FM_EMAIL));
    }

    @Test
    @DisplayName("BR-AP-04: user active, chưa xoá → gán thành công")
    void create_ActiveUser_Success() {
        AssignedPersonnelResponse res = service.create(lab.getId(), createReq(null, null), FM_EMAIL);

        assertEquals(lecturer.getId(), res.user().id());
        verify(assignedPersonnelRepository).findUserByIdForUpdate(lecturer.getId());
    }

    // ------------------------------------------------------------------ BR-AP-05

    @Test
    @DisplayName("BR-AP-05: area inactive → ERR_AP_007")
    void create_InactiveArea_ThrowsErrAp007() {
        lab.setIsActive(false);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_007,
                () -> service.create(lab.getId(), createReq(null, null), FM_EMAIL));
        verify(assignedPersonnelRepository, never()).save(any());
    }

    @Test
    @DisplayName("BR-AP-05: area đã bị xoá mềm (deletedAt != null) → ERR_AP_007")
    void create_SoftDeletedArea_ThrowsErrAp007() {
        lab.setDeletedAt(OffsetDateTime.now().minusDays(1));

        assertApError(AssignedPersonnelErrorCode.ERR_AP_007,
                () -> service.create(lab.getId(), createReq(null, null), FM_EMAIL));
    }

    @Test
    @DisplayName("BR-AP-05: area active → gán thành công")
    void create_ActiveArea_Success() {
        AssignedPersonnelResponse res = service.create(lab.getId(), createReq(null, null), FM_EMAIL);

        assertEquals(lab.getId(), res.areaId());
    }

    // ------------------------------------------------------------------ BR-AP-08

    @Test
    @DisplayName("BR-AP-08: thu hồi hợp lệ → revokedAt = now, revokedBy = FM, lý do được trim, status REVOKED")
    void revoke_Valid_Success() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusDays(1), null);
        OffsetDateTime before = OffsetDateTime.now();

        AssignedPersonnelResponse res = service.revoke(lab.getId(), rec.getId(),
                new AssignedPersonnelRevokeRequest("  Nghỉ việc  "), FM_EMAIL);

        assertEquals(AssignedPersonnelStatus.REVOKED, res.status());
        assertNotNull(res.revokedAt());
        assertEquals(false, res.revokedAt().isBefore(before));
        assertEquals("Quản lý cơ sở", res.revokedBy());
        assertEquals("Nghỉ việc", res.revokeReason());
        assertEquals(fm, rec.getRevokedBy());
    }

    @Test
    @DisplayName("BR-AP-08: lý do thu hồi rỗng / toàn khoảng trắng → ERR_AP_008")
    void revoke_BlankReason_ThrowsErrAp008() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusDays(1), null);

        assertApError(AssignedPersonnelErrorCode.ERR_AP_008,
                () -> service.revoke(lab.getId(), rec.getId(), new AssignedPersonnelRevokeRequest("   "), FM_EMAIL));
        assertApError(AssignedPersonnelErrorCode.ERR_AP_008,
                () -> service.revoke(lab.getId(), rec.getId(), new AssignedPersonnelRevokeRequest(null), FM_EMAIL));
        assertNull(rec.getRevokedAt());
    }

    @Test
    @DisplayName("BR-AP-08: thu hồi bản ghi đã thu hồi → ERR_AP_009")
    void revoke_AlreadyRevoked_ThrowsErrAp009() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusDays(1), null);
        OffsetDateTime revokedAt = OffsetDateTime.now().minusHours(1);
        rec.setRevokedAt(revokedAt);
        rec.setRevokeReason("Lý do cũ");

        assertApError(AssignedPersonnelErrorCode.ERR_AP_009,
                () -> service.revoke(lab.getId(), rec.getId(), new AssignedPersonnelRevokeRequest("Lần 2"), FM_EMAIL));
        assertEquals(revokedAt, rec.getRevokedAt());
        assertEquals("Lý do cũ", rec.getRevokeReason());
    }

    @Test
    @DisplayName("BR-AP-08: sửa validTo của bản ghi đã thu hồi → ERR_AP_009")
    void update_RevokedRecord_ThrowsErrAp009() {
        AreaAssignedPersonnel rec = existing(OffsetDateTime.now().minusDays(1), null);
        rec.setRevokedAt(OffsetDateTime.now().minusHours(1));
        rec.setRevokeReason("Nghỉ việc");

        assertApError(AssignedPersonnelErrorCode.ERR_AP_009,
                () -> service.updateValidTo(lab.getId(), rec.getId(),
                        new AssignedPersonnelUpdateRequest(OffsetDateTime.now().plusDays(1)), FM_EMAIL));
    }

    // ------------------------------------------------------------------ GET + status

    @Test
    @DisplayName("GET: lọc theo status tính toán ACTIVE / UPCOMING / EXPIRED / REVOKED")
    void getByArea_FilterByComputedStatus() {
        OffsetDateTime now = OffsetDateTime.now();
        existing(now.minusDays(1), null);                                   // ACTIVE
        existing(now.plusDays(1), now.plusDays(2));                         // UPCOMING
        existing(now.minusDays(3), now.minusDays(2));                       // EXPIRED
        AreaAssignedPersonnel revoked = existing(now.minusDays(5), now.minusDays(4));
        revoked.setRevokedAt(now.minusDays(4).minusHours(1));               // REVOKED
        revoked.setRevokeReason("Huỷ");

        assertEquals(4, service.getByArea(lab.getId(), null).size());
        for (AssignedPersonnelStatus s : AssignedPersonnelStatus.values()) {
            List<AssignedPersonnelResponse> filtered = service.getByArea(lab.getId(), s);
            assertEquals(1, filtered.size(), "status " + s);
            assertEquals(s, filtered.get(0).status());
        }
    }

    @Test
    @DisplayName("computeStatus: validTo là mốc loại trừ, tại đúng validTo → EXPIRED")
    void computeStatus_AtValidTo_IsExpired() {
        OffsetDateTime t = OffsetDateTime.now();
        AreaAssignedPersonnel rec = AreaAssignedPersonnel.builder()
                .validFrom(t.minusHours(2))
                .validTo(t)
                .build();

        assertEquals(AssignedPersonnelStatus.EXPIRED, AreaAssignedPersonnelService.computeStatus(rec, t));
        assertEquals(AssignedPersonnelStatus.ACTIVE, AreaAssignedPersonnelService.computeStatus(rec, t.minusSeconds(1)));
        assertEquals(AssignedPersonnelStatus.UPCOMING, AreaAssignedPersonnelService.computeStatus(rec, t.minusHours(3)));
    }

    @Test
    @DisplayName("Gán nhân sự vào khu vực PUBLIC -> ném IllegalArgumentException")
    void create_PublicArea_ThrowsException() {
        Area publicArea = Area.builder()
                .id(UUID.randomUUID())
                .code("PUB-01")
                .name("Sảnh công cộng")
                .areaLevel(AreaLevel.PUBLIC)
                .isActive(true)
                .build();
        when(areaRepository.findById(publicArea.getId())).thenReturn(Optional.of(publicArea));

        AssignedPersonnelCreateRequest req = new AssignedPersonnelCreateRequest(
                lecturer.getId(),
                OffsetDateTime.now(),
                OffsetDateTime.now().plusDays(1),
                "Gán vào sảnh"
        );

        AssignedPersonnelException ex = assertThrows(AssignedPersonnelException.class,
                () -> service.create(publicArea.getId(), req, FM_EMAIL));
        assertEquals(AssignedPersonnelErrorCode.ERR_AP_010, ex.getErrorCode());
    }

    @Test
    @DisplayName("Gán nhân sự vào khu vực INTERNAL_CONFIDENTIAL -> thành công")
    void create_InternalConfidentialArea_Success() {
        Area internalArea = Area.builder()
                .id(UUID.randomUUID())
                .code("INTERNAL-01")
                .name("Phòng ban nội bộ")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .isActive(true)
                .build();
        when(areaRepository.findById(internalArea.getId())).thenReturn(Optional.of(internalArea));

        AssignedPersonnelCreateRequest req = new AssignedPersonnelCreateRequest(
                lecturer.getId(),
                OffsetDateTime.now(),
                OffsetDateTime.now().plusDays(1),
                "Gán vào phòng ban"
        );

        AssignedPersonnelResponse resp = service.create(internalArea.getId(), req, FM_EMAIL);
        assertNotNull(resp);
        assertEquals(lecturer.getId(), resp.user().id());
    }

    @Test
    @DisplayName("Gán nhân sự vào khu vực CONFIDENTIAL_CONTACT_REQUIRED -> thành công")
    void create_ConfidentialContactRequiredArea_Success() {
        Area contactArea = Area.builder()
                .id(UUID.randomUUID())
                .code("CONTACT-01")
                .name("Phòng cần xác nhận")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .isActive(true)
                .build();
        when(areaRepository.findById(contactArea.getId())).thenReturn(Optional.of(contactArea));

        AssignedPersonnelCreateRequest req = new AssignedPersonnelCreateRequest(
                lecturer.getId(),
                OffsetDateTime.now(),
                OffsetDateTime.now().plusDays(1),
                "Gán vào phòng xác nhận"
        );

        AssignedPersonnelResponse resp = service.create(contactArea.getId(), req, FM_EMAIL);
        assertNotNull(resp);
        assertEquals(lecturer.getId(), resp.user().id());
    }
}
