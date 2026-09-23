package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.dto.user.UserSearchResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceAccessLevelTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FaceDataService faceDataService;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private UserBulkImportHelper userBulkImportHelper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserAccessLevelHelper userAccessLevelHelper;

    @Mock
    private AccessControlAuditService auditService;

    @InjectMocks
    private UserService userService;

    private MockMultipartFile mockFaceImage;

    @BeforeEach
    void setUp() {
        mockFaceImage = new MockMultipartFile("faceImage", "face.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Tạo GUARD nhận access_level mặc định từ helper (2)")
    void createGuard_AssignsAccessLevelFromConfig() {
        StaffAccountCreateRequest req = new StaffAccountCreateRequest();
        req.setUserCode("GUARD001");
        req.setFullName("Nguyễn Văn Bảo Vệ");
        req.setEmail("guard001@fpt.edu.vn");
        req.setRole(Role.GUARD);
        req.setFaceImage(mockFaceImage);

        when(userRepository.findExistingUserCodes(any())).thenReturn(Set.of());
        when(userRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(passwordEncoder.encode(any())).thenReturn("hashed_pass");
        when(faceDataService.registerFace(any(), any())).thenReturn(FaceDataResponseDto.builder().build());
        when(userAccessLevelHelper.resolveDefaultAccessLevel(Role.GUARD)).thenReturn(2);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        StaffAccountCreateResponse resp = userService.createStaffAccount(req);

        assertNotNull(resp);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(2, captor.getValue().getAccessLevel());
    }

    @Test
    @DisplayName("Tạo FACILITY_MANAGER nhận access_level mặc định từ helper (2)")
    void createFM_AssignsAccessLevelFromConfig() {
        StaffAccountCreateRequest req = new StaffAccountCreateRequest();
        req.setUserCode("FM001");
        req.setFullName("Trần Quản Lý");
        req.setEmail("fm001@fpt.edu.vn");
        req.setRole(Role.FACILITY_MANAGER);
        req.setFaceImage(mockFaceImage);

        when(userRepository.findExistingUserCodes(any())).thenReturn(Set.of());
        when(userRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(passwordEncoder.encode(any())).thenReturn("hashed_pass");
        when(faceDataService.registerFace(any(), any())).thenReturn(FaceDataResponseDto.builder().build());
        when(userAccessLevelHelper.resolveDefaultAccessLevel(Role.FACILITY_MANAGER)).thenReturn(2);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        StaffAccountCreateResponse resp = userService.createStaffAccount(req);

        assertNotNull(resp);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(2, captor.getValue().getAccessLevel());
    }

    @Test
    @DisplayName("searchUsers: q ít hơn 2 ký tự sau khi trim -> ném IllegalArgumentException")
    void searchUsers_ShortQuery_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> userService.searchUsers("a", PageRequest.of(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> userService.searchUsers("  b  ", PageRequest.of(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> userService.searchUsers(null, PageRequest.of(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> userService.searchUsers("", PageRequest.of(0, 10)));
    }

    @Test
    @DisplayName("searchUsers: size > 20 tự động giới hạn về 20")
    void searchUsers_SizeGreaterThan20_CappedAt20() {
        User u = User.builder()
                .id(UUID.randomUUID())
                .userCode("SV001")
                .fullName("Sinh Viên 1")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.searchActiveUsers(eq("SV"), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(u)));

        Page<UserSearchResponse> res = userService.searchUsers("SV", PageRequest.of(0, 50));

        assertNotNull(res);
        assertEquals(1, res.getContent().size());
        assertEquals(20, pageableCaptor.getValue().getPageSize(), "Size phải bị giới hạn về 20");
    }

    @Test
    @DisplayName("updateAccessLevel thành công cập nhật accessLevel khi sửa người khác")
    void updateAccessLevel_Valid_Success() {
        UUID actorId = UUID.randomUUID();
        String actorEmail = "fm@fpt.edu.vn";
        User actor = User.builder().id(actorId).email(actorEmail).role(Role.FACILITY_MANAGER).build();
        when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));

        UUID targetUserId = UUID.randomUUID();
        User targetUser = User.builder()
                .id(targetUserId)
                .userCode("NV001")
                .fullName("Nhân Viên")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();

        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSearchResponse resp = userService.updateAccessLevel(targetUserId, 3, "Nâng quyền nhân viên", actorEmail);

        assertNotNull(resp);
        assertEquals(3, resp.accessLevel());
        assertEquals(3, targetUser.getAccessLevel());
    }

    @Test
    @DisplayName("updateAccessLevel: FM không được tự sửa access level của chính mình -> ném AccessDeniedException")
    void updateAccessLevel_SelfUpdate_ThrowsAccessDeniedException() {
        UUID myId = UUID.randomUUID();
        String myEmail = "fm@fpt.edu.vn";
        User me = User.builder().id(myId).email(myEmail).role(Role.FACILITY_MANAGER).build();
        when(userRepository.findByEmail(myEmail)).thenReturn(Optional.of(me));

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> userService.updateAccessLevel(myId, 3, "Tự nâng quyền", myEmail));

        assertEquals("Bạn không thể tự thay đổi cấp truy cập của chính mình", ex.getMessage());
    }

    @Test
    @DisplayName("updateAccessLevel cho user không tồn tại hoặc đã xóa mềm -> ném ResourceNotFoundException")
    void updateAccessLevel_UserNotFoundOrDeleted_ThrowsNotFound() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.updateAccessLevel(targetId, 2, "Lý do", null));

        User deletedUser = User.builder()
                .id(targetId)
                .userCode("DEL001")
                .deletedAt(OffsetDateTime.now())
                .build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(deletedUser));

        assertThrows(ResourceNotFoundException.class, () -> userService.updateAccessLevel(targetId, 2, "Lý do", null));
    }
}
