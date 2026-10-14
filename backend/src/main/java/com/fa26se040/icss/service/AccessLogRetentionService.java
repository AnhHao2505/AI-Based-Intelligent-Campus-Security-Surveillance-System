package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Dọn nhật ký ra vào tại camera: xoá log có loggedAt cũ hơn ENTRY_LOG_RETENTION_DAYS ngày.
 * Số ngày đọc mỗi lần chạy qua SystemConfigService (sửa có hiệu lực ngay); cron ở AccessLogRetentionScheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogRetentionService {

    private final CameraAccessLogRepository cameraAccessLogRepository;
    private final SystemConfigService systemConfigService;

    @Transactional
    public int purgeExpired(OffsetDateTime now) {
        int retentionDays = systemConfigService.getInt(ConfigKey.ENTRY_LOG_RETENTION_DAYS);
        if (retentionDays < 1) {
            // Giá trị hỏng (sửa tay trong DB) không được dẫn tới xoá sạch nhật ký
            log.warn("ENTRY_LOG_RETENTION_DAYS = {} không hợp lệ, bỏ qua lần dọn nhật ký ra vào", retentionDays);
            return 0;
        }
        OffsetDateTime cutoff = now.minusDays(retentionDays);
        int deleted = cameraAccessLogRepository.deleteByLoggedAtBefore(cutoff);
        log.info("Đã xoá {} nhật ký ra vào cũ hơn {} ngày (trước {})", deleted, retentionDays, cutoff);
        return deleted;
    }
}
