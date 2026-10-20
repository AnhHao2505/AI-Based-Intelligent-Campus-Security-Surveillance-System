package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Bảng cấp theo loại khu vực. Từ V83 (D2) chỉ để xem: đúng 3 dòng, loại ⇔ cấp do CHECK chk_preset_level_matches_type giữ.
 */
@Service
@RequiredArgsConstructor
public class AreaLevelPresetService {

    private final AreaLevelPresetRepository areaLevelPresetRepository;

    @Transactional(readOnly = true)
    public List<LevelPresetResponse> getAllPresets() {
        return areaLevelPresetRepository.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getAreaLevel().ordinal()))
                .map(this::mapToResponse)
                .toList();
    }

    /**
     * V83 (D2, BR-PR-23): loại ⇔ cấp cố định (Công khai 1, Bảo mật 2, Tuyệt mật 3), bảng preset chỉ để xem.
     * Giữ endpoint PUT/PATCH để client cũ nhận 400 rõ ràng (ERR_AC_005), không sửa gì.
     */
    public LevelPresetResponse updatePreset(AreaLevel areaLevel, LevelPresetUpdateRequest req, String actorEmail) {
        throw new AccessControlException(AccessControlErrorCode.ERR_AC_005);
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
