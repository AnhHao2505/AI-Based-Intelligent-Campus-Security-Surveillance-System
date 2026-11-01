package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    @Mock
    private SystemConfigService systemConfigService;

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
        // ENTRY_LOG_DEBOUNCE_SECONDS mặc định (V77) = 30 giây
        lenient().when(systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS)).thenReturn(30);
        // Khung ngoài giờ AI_AFTER_HOUR_START / AI_AFTER_HOUR_END mặc định (V48) = 22:00–06:00
        lenient().when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START)).thenReturn("22:00");
        lenient().when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END)).thenReturn("06:00");
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
        assertEquals(AccessResult.AUTHORIZED_PERSON, result.getAccessResult());
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
        assertEquals(AccessResult.PERSON_INTRUSION, result.getAccessResult());
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
        assertEquals("UNKNOWN-FACE", result.getUserCode());
        assertEquals("Người lạ / Chưa rõ mặt", result.getFullName());
        assertEquals("Phát hiện người chưa đăng ký tại khu vực giám sát.", result.getReasonDetails());
    }

    @Test
    @DisplayName("Tài khoản đã bị xoá mềm (deleted_at != null) khi quét camera được chuẩn hóa hoàn toàn về UNKNOWN_PERSON")
    void testRecordAccessLog_SoftDeletedUser_NormalizedToUnknownPerson() {
        OffsetDateTime daytime = OffsetDateTime.of(2026, 10, 16, 10, 41, 42, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Người dùng không tồn tại")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode("SE194249")
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(userRepository.findByUserCodeAndDeletedAtIsNull("SE194249")).thenReturn(Optional.empty());
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, daytime);

        assertNotNull(result);
        assertEquals(AccessResult.UNKNOWN_PERSON, result.getAccessResult());
        assertEquals("SE194249", result.getUserCode());
        assertEquals("Người lạ / Chưa rõ mặt", result.getFullName());
        assertEquals("Phát hiện người chưa đăng ký tại khu vực giám sát.", result.getReasonDetails());
    }

    @Test
    @DisplayName("Khi AI không phát hiện được mã (userCode null/blank) thì mặc định lưu UNKNOWN")
    void testRecordAccessLog_NullUserCode_DefaultsToUnknown() {
        OffsetDateTime daytime = OffsetDateTime.of(2026, 10, 16, 10, 45, 0, 0, ZoneOffset.ofHours(7));
        CheckCameraEntryResponse entryResponse = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Không nhận diện được khuôn mặt")
                .cameraCode("CAM-001")
                .areaId(areaId)
                .userCode(null)
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CameraAccessLogResponse result = cameraAccessLogService.recordAccessLog(entryResponse, daytime);

        assertNotNull(result);
        assertEquals(AccessResult.UNKNOWN_PERSON, result.getAccessResult());
        assertEquals("UNKNOWN", result.getUserCode());
        assertEquals("Người lạ / Chưa rõ mặt", result.getFullName());
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
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, result.getAccessResult());
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
        assertEquals(AccessResult.PERSON_INTRUSION, result.getAccessResult());
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
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, result.getAccessResult());
    }

    // ===================== Khung ngoài giờ đọc từ cấu hình (AI_AFTER_HOUR_START / AI_AFTER_HOUR_END) =====================

    private static OffsetDateTime vn(int hour, int minute) {
        return OffsetDateTime.of(2026, 10, 14, hour, minute, 0, 0, ZoneOffset.ofHours(7));
    }

    private static CheckCameraEntryResponse deniedNoTimeHint() {
        return CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason("Cấp độ truy cập không đủ (Khu vực Cấp 3, người dùng Cấp 1)")
                .cameraCode("CAM-001")
                .areaId(UUID.randomUUID())
                .userCode("SE170123")
                .build();
    }

    private void afterHours(String start, String end) {
        lenient().when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START)).thenReturn(start);
        lenient().when(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END)).thenReturn(end);
    }

    @Test
    @DisplayName("AH-01: Cấu hình 23:00–05:00, bị từ chối lúc 22:30 → không phải INTRUSION")
    void ah01_before23_NotIntrusion() {
        afterHours("23:00", "05:00");

        AccessResult result = cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(22, 30), true);

        assertEquals(AccessResult.UNAUTHORIZED_PERSON, result);
    }

    @Test
    @DisplayName("AH-02: Cấu hình 23:00–05:00, lúc 23:30 → INTRUSION; mốc 23:00 tính, 05:00 không tính; giờ quy về VN")
    void ah02_overnightWindow_Intrusion() {
        afterHours("23:00", "05:00");

        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(23, 30), true));
        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(23, 0), true));
        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(4, 59), true));
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(5, 0), true));

        // 16:30 UTC = 23:30 giờ VN → INTRUSION; 14:30 UTC = 21:30 giờ VN → không
        OffsetDateTime utc1630 = OffsetDateTime.of(2026, 10, 14, 16, 30, 0, 0, ZoneOffset.UTC);
        OffsetDateTime utc1430 = OffsetDateTime.of(2026, 10, 14, 14, 30, 0, 0, ZoneOffset.UTC);
        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), utc1630, true));
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, cameraAccessLogService.classifyResult(deniedNoTimeHint(), utc1430, true));
    }

    @Test
    @DisplayName("AH-03: Đổi cấu hình giữa 2 lần gọi → lần sau áp giá trị mới")
    void ah03_configChangeTakesEffectOnNextCall() {
        afterHours("23:00", "05:00");
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(22, 30), true));

        afterHours("22:00", "05:00");
        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(22, 30), true));
    }

    @Test
    @DisplayName("AH-04: Khung trong ngày 12:00–13:00 → 12:30 INTRUSION; 11:59, 13:00, 23:30 thì không")
    void ah04_sameDayWindow() {
        afterHours("12:00", "13:00");

        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(12, 30), true));
        assertEquals(AccessResult.PERSON_INTRUSION, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(12, 0), true));
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(11, 59), true));
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(13, 0), true));
        assertEquals(AccessResult.UNAUTHORIZED_PERSON, cameraAccessLogService.classifyResult(deniedNoTimeHint(), vn(23, 30), true));
    }
}
