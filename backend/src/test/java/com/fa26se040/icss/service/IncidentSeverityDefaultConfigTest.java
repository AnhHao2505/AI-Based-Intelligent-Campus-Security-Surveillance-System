package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V90 (BR-SEV-03): INCIDENT_SEVERITY_DEFAULT có nhánh validate riêng (STRING thường chỉ kiểm độ dài). */
@ExtendWith(MockitoExtension.class)
class IncidentSeverityDefaultConfigTest {

    private static final String KEY = ConfigKey.INCIDENT_SEVERITY_DEFAULT.getKey();

    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    @Mock
    private SystemConfigurationChangeLogRepository changeLogRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SystemConfigService systemConfigService;

    private SystemConfiguration config;

    @BeforeEach
    void setUp() {
        config = SystemConfiguration.builder()
                .configKey(KEY)
                .configValue("MEDIUM")
                .dataType("STRING")
                .configGroup("SECURITY")
                .displayOrder(62)
                .editable(true)
                .build();
    }

    @ParameterizedTest(name = "\"{0}\" bị từ chối")
    @DisplayName("Giá trị không phải đúng tên MEDIUM/HIGH/CRITICAL -> 400, câu lỗi liệt kê các mức hợp lệ, không ghi")
    @ValueSource(strings = {"URGENT", "medium", "LOW", "MEDIUM,HIGH"})
    void invalidValue_rejected(String value) {
        when(systemConfigurationRepository.findById(KEY)).thenReturn(Optional.of(config));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> systemConfigService.update(KEY, value, "admin@fpt.edu.vn"));

        assertEquals("Mức độ mặc định của sự cố phải là một trong: MEDIUM, HIGH, CRITICAL", ex.getMessage());
        verify(systemConfigurationRepository, never()).save(any());
    }

    @ParameterizedTest(name = "\"{0}\" được lưu")
    @DisplayName("MEDIUM / HIGH / CRITICAL (có khoảng trắng hai đầu) -> lưu, đọc lại có hiệu lực ngay")
    @ValueSource(strings = {"MEDIUM", "HIGH", " CRITICAL "})
    void validValue_saved(String value) {
        User admin = User.builder().id(UUID.randomUUID()).email("admin@fpt.edu.vn").build();
        when(systemConfigurationRepository.findById(KEY)).thenReturn(Optional.of(config));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(systemConfigurationRepository.save(any(SystemConfiguration.class))).thenAnswer(i -> i.getArgument(0));

        SystemConfigResponse res = systemConfigService.update(KEY, value, admin.getEmail());

        assertEquals(value.trim(), res.configValue());
        assertEquals(value.trim(), systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT));
    }
}
