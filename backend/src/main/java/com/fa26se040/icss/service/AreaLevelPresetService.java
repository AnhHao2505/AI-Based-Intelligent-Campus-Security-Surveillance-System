package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.dto.accesscontrol.snapshot.LevelPresetAuditSnapshot;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AreaLevelPresetService {

    private final AreaLevelPresetRepository areaLevelPresetRepository;
    private final AccessControlAuditService auditService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<LevelPresetResponse> getAllPresets() {
        return areaLevelPresetRepository.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getAreaLevel().ordinal()))
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public LevelPresetResponse updatePreset(AreaLevel areaLevel, LevelPresetUpdateRequest req, String actorEmail) {
        User actor = resolveActor(actorEmail);

        AreaLevelPreset preset = areaLevelPresetRepository.findById(areaLevel)
                .orElseThrow(() -> new AccessControlException(AccessControlErrorCode.ERR_AC_004));

        // BR-PR-03: Optimistic locking version check
        if (!Objects.equals(req.version(), preset.getVersion())) {
            throw new AccessControlException(AccessControlErrorCode.ERR_AC_003);
        }

        // BR-AL-06: Thao tác không làm thay đổi giá trị (new == old) -> không ghi log, trả về trạng thái hiện tại
        if (Objects.equals(preset.getAreaAccessLevel(), req.areaAccessLevel()) &&
                Objects.equals(preset.getExplicitAuthorizationRequired(), req.explicitAuthorizationRequired())) {
            log.info("Preset for {} has not changed, skipping audit log", areaLevel);
            return mapToResponse(preset);
        }

        LevelPresetAuditSnapshot oldSnapshot = new LevelPresetAuditSnapshot(
                preset.getAreaAccessLevel(),
                preset.getExplicitAuthorizationRequired()
        );

        preset.setAreaAccessLevel(req.areaAccessLevel());
        preset.setExplicitAuthorizationRequired(req.explicitAuthorizationRequired());
        preset.setUpdatedBy(actor);
        preset.setUpdatedAt(OffsetDateTime.now());

        // BR-PR-02: Sửa preset CHỈ áp dụng cho khu vực tạo mới. KHÔNG cập nhật areas đã tồn tại.
        AreaLevelPreset saved = areaLevelPresetRepository.save(preset);

        LevelPresetAuditSnapshot newSnapshot = new LevelPresetAuditSnapshot(
                saved.getAreaAccessLevel(),
                saved.getExplicitAuthorizationRequired()
        );

        // BR-AL-01: Ghi log trong cùng transaction
        auditService.record(
                AccessControlTargetType.LEVEL_PRESET,
                AccessControlAction.UPDATE,
                areaLevel.name(),
                null,
                null,
                oldSnapshot,
                newSnapshot,
                req.reason(),
                actor
        );

        log.info("Updated preset for {} to level={}, explicit={} by {}",
                areaLevel, saved.getAreaAccessLevel(), saved.getExplicitAuthorizationRequired(), actorEmail);

        return mapToResponse(saved);
    }

    private User resolveActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
    }

    private LevelPresetResponse mapToResponse(AreaLevelPreset preset) {
        User u = preset.getUpdatedBy();
        return new LevelPresetResponse(
                preset.getAreaLevel(),
                preset.getAreaAccessLevel(),
                preset.getExplicitAuthorizationRequired(),
                preset.getUpdatedAt(),
                u != null ? u.getFullName() : null,
                u != null ? u.getUserCode() : null,
                preset.getVersion()
        );
    }
}
