package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.User;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Debounce nhật ký ra vào đọc ENTRY_LOG_DEBOUNCE_SECONDS mỗi lần ghi: đổi cấu hình thì hành vi đổi ngay, không cần khởi động lại.
 */
@ExtendWith(MockitoExtension.class)
class CameraAccessLogDebounceConfigTest {

    @Mock private CameraAccessLogRepository cameraAccessLogRepository;
    @Mock private AreaRepository areaRepository;
    @Mock private UserRepository userRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SystemConfigService systemConfigService;

    @InjectMocks private CameraAccessLogService cameraAccessLogService;

    private final OffsetDateTime t0 = OffsetDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneOffset.ofHours(7));
    private CheckCameraEntryResponse entry;

    @BeforeEach
    void setUp() {
        UUID areaId = UUID.randomUUID();
        Area area = Area.builder().id(areaId).name("Khu debounce").build();
        User user = User.builder().id(UUID.randomUUID()).userCode("SE-DEB").fullName("Debounce").accessLevel(1).build();
        entry = CheckCameraEntryResponse.builder()
                .allowed(true).source(AccessSource.ACCESS_LEVEL).reason("Cấp độ phù hợp")
                .cameraCode("CAM-DEB").areaId(areaId).userCode("SE-DEB").build();
        lenient().when(areaRepository.findById(areaId)).thenReturn(Optional.of(area));
        lenient().when(userRepository.findByUserCodeAndDeletedAtIsNull("SE-DEB")).thenReturn(Optional.of(user));
        lenient().when(cameraAccessLogRepository.save(any())).thenAnswer(inv -> {
            CameraAccessLog l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });
        cameraAccessLogService.clearDebounceCache();
    }

    @Test
    @DisplayName("Cấu hình 30s → lượt sau 10s bị bỏ; đổi cấu hình thành 5s → lượt sau 20s được ghi")
    void debounce_followsConfigChange() {
        when(systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS)).thenReturn(30);
        assertNotNull(cameraAccessLogService.recordAccessLog(entry, t0));
        assertNull(cameraAccessLogService.recordAccessLog(entry, t0.plusSeconds(10)));

        when(systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS)).thenReturn(5);
        assertNotNull(cameraAccessLogService.recordAccessLog(entry, t0.plusSeconds(20)));

        verify(cameraAccessLogRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Cấu hình 0 → ghi mọi lượt, kể cả cùng giây")
    void debounce_zero_recordsEveryScan() {
        when(systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS)).thenReturn(0);
        assertNotNull(cameraAccessLogService.recordAccessLog(entry, t0));
        assertNotNull(cameraAccessLogService.recordAccessLog(entry, t0));

        verify(cameraAccessLogRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Cấu hình 120s → lượt sau 60s vẫn bị bỏ (giá trị cũ 30s viết cứng sẽ ghi)")
    void debounce_longerThanOldHardcodedValue() {
        when(systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS)).thenReturn(120);
        assertNotNull(cameraAccessLogService.recordAccessLog(entry, t0));
        assertNull(cameraAccessLogService.recordAccessLog(entry, t0.plusSeconds(60)));

        verify(cameraAccessLogRepository, times(1)).save(any());
    }
}
