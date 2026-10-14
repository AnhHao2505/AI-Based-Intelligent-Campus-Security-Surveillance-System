package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogStatsResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraAccessLogServiceTest {

    @Mock
    private CameraAccessLogRepository cameraAccessLogRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private CameraAccessLogService cameraAccessLogService;

    private Area area;
    private User user;
    private UUID areaId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        areaId = UUID.randomUUID();
        userId = UUID.randomUUID();
        area = Area.builder().id(areaId).name("Phòng Lab AI").build();
        user = User.builder().id(userId).userCode("SE170123").fullName("Nguyễn Văn Minh").accessLevel(1).build();
        cameraAccessLogService.clearDebounceCache();
    }

    @Test
    @DisplayName("Ghi log thành công khi truy cập hợp lệ (AUTHORIZED_ACCESS) và gửi qua WebSocket")
    void testRecordAccessLog_Authorized_Success() {
        OffsetDateTime now = OffsetDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(true)
                .source(AccessSource.ACCESS_REQUEST)
                .reason("Có đơn xin quyền đã duyệt")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .areaName("Phòng Lab AI")
                .userCode("SE170123")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE170123")).thenReturn(Optional.of(user));
        when(cameraAccessLogRepository.save(any(CameraAccessLog.class))).thenAnswer(invocation -> {
            CameraAccessLog log = invocation.getArgument(0);
            log.setId(UUID.randomUUID());
            return log;
        });

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, now);

        assertNotNull(result);
        assertEquals("CAM-001", result.getCameraCode());
        assertEquals("SE170123", result.getUserCode());
        assertEquals(AccessResult.AUTHORIZED_ACCESS, result.getAccessResult());
        assertEquals(AccessSource.ACCESS_REQUEST, result.getAccessSource());

        // Verify WebSocket broadcast
        verify(messagingTemplate, times(1))
                .convertAndSend(eq("/topic/cameras/CAM-001/access-logs"), any(CameraAccessLogResponse.class));
    }

    @Test
    @DisplayName("Cơ chế Debounce 30s: Quét lại cùng 1 người tại cùng 1 camera trong 30s sẽ bị bỏ qua không lưu trùng")
    void testRecordAccessLog_Debounce_SkipsDuplicate() {
        OffsetDateTime now = OffsetDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(true)
                .source(AccessSource.ACCESS_LEVEL)
                .reason("Cấp độ phù hợp")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("SE170123")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE170123")).thenReturn(Optional.of(user));
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> {
            CameraAccessLog log = inv.getArgument(0);
            log.setId(UUID.randomUUID());
            return log;
        });

        // Lần 1: Lưu thành công
        CameraAccessLogResponse first = cameraAccessLogService.recordAccessLog(entryResponse, now);
        assertNotNull(first);

        // Lần 2: 5 giây sau (vẫn trong khoảng 30s debounce)
        OffsetDateTime fiveSecondsLater = now.plusSeconds(5);
        CameraAccessLogResponse second = cameraAccessLogService.recordAccessLog(entryResponse, fiveSecondsLater);

        // Lần 2 bị debounce bỏ qua
        assertNull(second);
        verify(cameraAccessLogRepository, times(1)).save(any());
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Phân loại INTRUSION khi xuất hiện ban đêm ngoài giờ (22h - 6h) không có quyền")
    void testRecordAccessLog_NightIntrusion() {
        OffsetDateTime nightTime = OffsetDateTime.of(2026, 10, 14, 23, 30, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Không có quyền ra vào khu vực tại thời điểm này")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("SE170123")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE170123")).thenReturn(Optional.of(user));
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, nightTime);

        assertNotNull(result);
        assertEquals(AccessResult.INTRUSION, result.getAccessResult());
    }

    @Test
    @DisplayName("Phân loại UNKNOWN_PERSON khi người dùng không tồn tại trong hệ thống")
    void testRecordAccessLog_UnknownPerson() {
        OffsetDateTime daytime = OffsetDateTime.of(2026, 10, 14, 14, 0, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Người dùng không tồn tại hoặc đã bị xoá")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("UNKNOWN-FACE")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("UNKNOWN-FACE")).thenReturn(Optional.empty());
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, daytime);

        assertNotNull(result);
        assertEquals(AccessResult.UNKNOWN_PERSON, result.getAccessResult());
    }

    @Test
    @DisplayName("Lấy thống kê lượt quét trong ngày thành công")
    void testGetStatsToday() {
        when(cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetween(eq("CAM-001"), any(), any()))
                .thenReturn(10L);
        when(cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(eq("CAM-001"), any(), any(), eq(AccessResult.AUTHORIZED_ACCESS)))
                .thenReturn(7L);
        when(cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(eq("CAM-001"), any(), any(), eq(AccessResult.UNAUTHORIZED_ACCESS)))
                .thenReturn(2L);
        when(cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(eq("CAM-001"), any(), any(), eq(AccessResult.ACCESS_OUT_OF_TIME)))
                .thenReturn(1L);
        when(cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(eq("CAM-001"), any(), any(), eq(AccessResult.UNKNOWN_PERSON)))
                .thenReturn(0L);
        when(cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(eq("CAM-001"), any(), any(), eq(AccessResult.INTRUSION)))
                .thenReturn(0L);

        CameraAccessLogStatsResponse stats = cameraAccessLogService.getStatsToday("CAM-001");

        assertNotNull(stats);
        assertEquals(10L, stats.getTotalToday());
        assertEquals(7L, stats.getAuthorizedToday());
        assertEquals(2L, stats.getUnauthorizedToday());
        assertEquals(1L, stats.getOutOfTimeToday());
    }

    @Test
    @DisplayName("Phân loại ACCESS_OUT_OF_TIME (Sai giờ) khi người dùng có đơn nhưng đến sớm hoặc hết hạn")
    void testRecordAccessLog_OutOfTime() {
        OffsetDateTime daytime = OffsetDateTime.of(2026, 10, 14, 14, 0, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: 18:00)")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("SE170123")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE170123")).thenReturn(Optional.of(user));
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, daytime);

        assertNotNull(result);
        assertEquals(AccessResult.ACCESS_OUT_OF_TIME, result.getAccessResult());
    }

    @Test
    @DisplayName("Phân loại INTRUSION (Ngoài giờ) khi có sự cố gắn nhãn ngoài giờ kể cả trong giờ giả lập")
    void testRecordAccessLog_AfterHoursReason_ClassifiesAsIntrusion() {
        OffsetDateTime testTime = OffsetDateTime.of(2026, 10, 14, 15, 0, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("[AFTER_HOURS_PRESENCE] Phát hiện đối tượng ngoài giờ quy định tại Lab AI")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("SE170123")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE170123")).thenReturn(Optional.of(user));
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, testTime);

        assertNotNull(result);
        assertEquals(AccessResult.INTRUSION, result.getAccessResult());
    }

    @Test
    @DisplayName("Phân loại UNAUTHORIZED_ACCESS (Sai quyền) khi tài khoản không đủ cấp độ vào khu vực")
    void testRecordAccessLog_UnauthorizedAccess_InsufficientLevel() {
        OffsetDateTime daytime = OffsetDateTime.of(2026, 10, 14, 14, 0, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Cấp độ truy cập không đủ (Khu vực Cấp 2, người dùng Cấp 1)")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("SE170123")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE170123")).thenReturn(Optional.of(user));
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, daytime);

        assertNotNull(result);
        assertEquals(AccessResult.UNAUTHORIZED_ACCESS, result.getAccessResult());
    }
}
