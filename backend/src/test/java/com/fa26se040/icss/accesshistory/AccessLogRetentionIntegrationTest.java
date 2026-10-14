package com.fa26se040.icss.accesshistory;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.service.AccessLogRetentionService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job dọn nhật ký ra vào: xoá log cũ hơn ENTRY_LOG_RETENTION_DAYS (đọc từ system_configurations), giữ log còn trong hạn.
 * @Transactional: lần xoá và fixture rollback sau test, không đụng dữ liệu thật của DB test dùng chung.
 */
@Transactional
class AccessLogRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraAccessLogRepository cameraAccessLogRepository;
    @Autowired private AccessLogRetentionService accessLogRetentionService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private JdbcTemplate jdbc;

    private String suffix;
    private OffsetDateTime now;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa lịch sử ra vào")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa lịch sử ra vào").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa lịch sử ra vào", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("AH retention " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private CameraAccessLog log(OffsetDateTime at) {
        return cameraAccessLogRepository.saveAndFlush(CameraAccessLog.builder()
                .cameraCode("AH-RET-" + suffix)
                .area(area)
                .userCode("UNKNOWN")
                .accessResult(AccessResult.UNKNOWN_PERSON)
                .loggedAt(at)
                .build());
    }

    private boolean existsInDb(CameraAccessLog l) {
        // Đọc thẳng DB: bulk DELETE không gỡ entity khỏi persistence context nên findById vẫn trả bản cũ
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM camera_access_logs WHERE id = ?", Integer.class, l.getId());
        return n != null && n > 0;
    }

    @Test
    @DisplayName("Xoá log cũ hơn N ngày, giữ log còn trong hạn (N đọc từ cấu hình)")
    void purge_deletesOnlyExpired() {
        int days = systemConfigService.getInt(ConfigKey.ENTRY_LOG_RETENTION_DAYS);
        CameraAccessLog expired = log(now.minusDays(days + 1L));
        CameraAccessLog kept = log(now.minusDays(days - 1L));
        CameraAccessLog recent = log(now.minusHours(1));

        int deleted = accessLogRetentionService.purgeExpired(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(existsInDb(expired)).isFalse();
        assertThat(existsInDb(kept)).isTrue();
        assertThat(existsInDb(recent)).isTrue();
    }
}
