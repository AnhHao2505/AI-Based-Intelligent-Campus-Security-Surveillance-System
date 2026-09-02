package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaTemporaryUsageResponse;
import com.fa26se040.icss.dto.area.CreateTemporaryUsageRequest;
import com.fa26se040.icss.dto.area.ExtendTemporaryUsageRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaTemporaryUsage;
import com.fa26se040.icss.entity.AreaTemporaryUsageChangeLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.AreaTemporaryUsageChangeLogRepository;
import com.fa26se040.icss.repository.AreaTemporaryUsageRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AreaTemporaryUsageService {

    private final AreaRepository areaRepository;
    private final AreaTemporaryUsageRepository temporaryUsageRepository;
    private final AreaTemporaryUsageChangeLogRepository temporaryUsageChangeLogRepository;
    private final UserRepository userRepository;

    @Transactional
    public AreaTemporaryUsageResponse create(UUID areaId, CreateTemporaryUsageRequest req, String actorEmail) {
        // BR01 — Area must exist, not soft-deleted, and active
        Area area = getValidActiveArea(areaId);

        // BR03 — Time range: endTime > startTime
        if (!req.endTime().isAfter(req.startTime())) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_001);
        }

        // BR04 — Cannot create usage already ended (endTime > now)
        if (!req.endTime().isAfter(OffsetDateTime.now())) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_002);
        }

        // BR05 — No overlap for the same Area: [startTime, endTime)
        boolean hasOverlap = temporaryUsageRepository.existsOverlappingUsage(
                areaId,
                req.startTime(),
                req.endTime()
        );
        if (hasOverlap) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_003);
        }

        UUID actorId = resolveActorId(actorEmail);

        AreaTemporaryUsage usage = AreaTemporaryUsage.builder()
                .area(area)
                .eventName(req.eventName().trim())
                .reason(req.reason() != null ? req.reason().trim() : null)
                .startTime(req.startTime())
                .endTime(req.endTime())
                .createdBy(actorId)
                .updatedBy(actorId)
                .build();

        AreaTemporaryUsage savedUsage = temporaryUsageRepository.save(usage);

        return mapToResponse(savedUsage);
    }

    @Transactional
    public AreaTemporaryUsageResponse extend(UUID areaId, UUID temporaryUsageId, ExtendTemporaryUsageRequest req, String actorEmail) {
        // BR03 — Area must exist and be active
        getValidActiveArea(areaId);

        // BR01 — Temporary usage must exist
        AreaTemporaryUsage usage = temporaryUsageRepository.findById(temporaryUsageId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_TEMP_USAGE_004));

        // BR02 — Temporary usage must belong to the Area in the URL
        if (!usage.getArea().getId().equals(areaId)) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_004);
        }

        // BR04 — Only extending is allowed (newEndTime > currentEndTime)
        if (req.newEndTime() == null || !req.newEndTime().isAfter(usage.getEndTime())) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_005);
        }

        // BR05 — Cannot extend already ended/expired record
        OffsetDateTime now = OffsetDateTime.now();
        if (!usage.getEndTime().isAfter(now)) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_006);
        }

        // BR07 — Extend reason validation
        if (req.reason() == null || req.reason().trim().length() < 10 || req.reason().trim().length() > 255) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_007);
        }

        // BR06 — No overlap with other temporary usages for the same Area (excluding current record)
        boolean hasOverlap = temporaryUsageRepository.existsOverlappingUsageExcludingId(
                areaId,
                usage.getId(),
                usage.getStartTime(),
                req.newEndTime()
        );
        if (hasOverlap) {
            throw new AreaException(AreaErrorCode.ERR_TEMP_USAGE_003);
        }

        UUID actorId = resolveActorId(actorEmail);

        OffsetDateTime oldEndTime = usage.getEndTime();
        usage.setEndTime(req.newEndTime());
        usage.setUpdatedBy(actorId);

        AreaTemporaryUsage savedUsage = temporaryUsageRepository.save(usage);

        // Audit log EXTEND
        AreaTemporaryUsageChangeLog log = AreaTemporaryUsageChangeLog.builder()
                .temporaryUsageId(savedUsage.getId())
                .actorId(actorId)
                .action("EXTEND")
                .oldEndTime(oldEndTime)
                .newEndTime(req.newEndTime())
                .reason(req.reason().trim())
                .build();
        temporaryUsageChangeLogRepository.save(log);

        return mapToResponse(savedUsage);
    }

    private Area getValidActiveArea(UUID areaId) {
        Area area = areaRepository.findByIdAndDeletedAtIsNull(areaId)
                .orElseThrow(() -> new AreaException(AreaErrorCode.ERR_AREA_002));
        if (!Boolean.TRUE.equals(area.getIsActive())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_002);
        }
        return area;
    }

    private UUID resolveActorId(String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
    }

    private AreaTemporaryUsageResponse mapToResponse(AreaTemporaryUsage usage) {
        return new AreaTemporaryUsageResponse(
                usage.getId(),
                usage.getArea().getId(),
                usage.getEventName(),
                usage.getReason(),
                usage.getStartTime(),
                usage.getEndTime(),
                usage.getCreatedBy(),
                usage.getCreatedAt(),
                usage.getUpdatedBy(),
                usage.getUpdatedAt()
        );
    }
}
