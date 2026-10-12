package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.user.UserAccessLevelBulkUpdateResponse;
import com.fa26se040.icss.dto.user.UserAccessLevelBulkUpdateResponse.Item;
import com.fa26se040.icss.dto.user.UserAccessLevelBulkUpdateResponse.Outcome;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BR-AL-28: FM đổi cấp truy cập cho nhiều người trong một lần.
 * <p>
 * Cố ý KHÔNG @Transactional: mỗi người đi qua đúng {@link UserService#updateAccessLevel(UUID, Integer, String, String)}
 * (gọi qua proxy nên mỗi người một transaction riêng) — cùng quy tắc với đổi cấp lẻ (cấp 1–3 ở DTO, không tự đổi cấp mình,
 * không tìm thấy / đã xoá mềm -> 404, không đổi thì không ghi audit, audit USER_ACCESS_LEVEL / UPDATE từng người).
 * Một người lỗi chỉ rollback phần của người đó.
 * <p>
 * Không ghi thêm dòng audit gom cả lô: mọi dòng audit trong cùng request đã chung correlation_id
 * (AuditCorrelationFilter), thêm loại audit mới thì phải thêm cặp vào audit_event_types bằng migration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccessLevelBulkService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final SystemConfigService systemConfigService;

    public UserAccessLevelBulkUpdateResponse updateAccessLevels(List<UUID> userIds, Integer accessLevel, String reason, String actorEmail) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds.stream().filter(Objects::nonNull).toList()));
        // Dùng chung giới hạn số dòng mỗi lần nạp tài khoản (UI-19), không thêm key mới
        int max = systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS);
        if (ids.size() > max) {
            throw new IllegalArgumentException("Mỗi lần chỉ đổi cấp tối đa " + max + " người (đã chọn " + ids.size() + ")");
        }

        Map<UUID, User> before = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<Item> results = new ArrayList<>();
        int updated = 0, unchanged = 0, failed = 0;
        for (UUID id : ids) {
            User u = before.get(id);
            String code = u != null ? u.getUserCode() : null;
            String name = u != null ? u.getFullName() : null;
            Integer oldLevel = u != null ? u.getAccessLevel() : null;
            try {
                var res = userService.updateAccessLevel(id, accessLevel, reason, actorEmail);
                if (Objects.equals(oldLevel, accessLevel)) {
                    unchanged++;
                    results.add(new Item(id, res.userCode(), res.fullName(), Outcome.UNCHANGED, oldLevel, res.accessLevel(),
                            "Đã ở cấp " + accessLevel + ", không thay đổi"));
                } else {
                    updated++;
                    results.add(new Item(id, res.userCode(), res.fullName(), Outcome.UPDATED, oldLevel, res.accessLevel(), null));
                }
            } catch (RuntimeException ex) {
                failed++;
                log.info("Bulk access level: user {} failed: {}", id, ex.getMessage());
                results.add(new Item(id, code, name, Outcome.FAILED, oldLevel, oldLevel, ex.getMessage()));
            }
        }
        return new UserAccessLevelBulkUpdateResponse(ids.size(), updated, unchanged, failed, results);
    }
}
