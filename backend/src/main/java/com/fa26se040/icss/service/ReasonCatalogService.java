package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.ReasonCatalogAuditSnapshot;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogCreateRequest;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogResponse;
import com.fa26se040.icss.dto.reasoncatalog.ReasonCatalogUpdateRequest;
import com.fa26se040.icss.entity.ReasonCatalog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.ReasonCatalogRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReasonCatalogService {

    private final ReasonCatalogRepository reasonCatalogRepository;
    private final AccessControlAuditService auditService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ReasonCatalogResponse> getAll(String actionType, Boolean active) {
        List<ReasonCatalog> list;
        if (actionType != null && !actionType.isBlank()) {
            String normAction = actionType.trim().toUpperCase();
            if (active != null) {
                list = reasonCatalogRepository.findByActionTypeAndIsActiveOrderBySortOrderAscCreatedAtAsc(normAction, active);
            } else {
                list = reasonCatalogRepository.findByActionTypeOrderBySortOrderAscCreatedAtAsc(normAction);
            }
        } else {
            if (active != null) {
                list = reasonCatalogRepository.findByIsActiveOrderByActionTypeAscSortOrderAscCreatedAtAsc(active);
            } else {
                list = reasonCatalogRepository.findAllByOrderByActionTypeAscSortOrderAscCreatedAtAsc();
            }
        }
        return list.stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ReasonCatalogResponse> getActiveByActionType(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("Loại thao tác không được để trống");
        }
        String normAction = actionType.trim().toUpperCase();
        return reasonCatalogRepository.findByActionTypeAndIsActiveTrueOrderBySortOrderAscCreatedAtAsc(normAction)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public ReasonCatalogResponse create(ReasonCatalogCreateRequest req, String actorEmail) {
        String actionType = req.actionType().trim().toUpperCase();
        String code = req.code().trim().toUpperCase();
        String label = req.label().trim();
        boolean isOther = Boolean.TRUE.equals(req.isOther());
        int sortOrder = req.sortOrder() != null ? req.sortOrder() : 0;

        if (reasonCatalogRepository.existsByActionTypeAndCode(actionType, code)) {
            throw new IllegalArgumentException("Mã lý do '" + code + "' đã tồn tại cho loại thao tác " + actionType);
        }

        if (isOther && reasonCatalogRepository.existsByActionTypeAndIsOtherTrue(actionType)) {
            throw new IllegalArgumentException("Đã tồn tại mục lý do 'Khác' cho loại thao tác " + actionType);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        ReasonCatalog entity = ReasonCatalog.builder()
                .actionType(actionType)
                .code(code)
                .label(label)
                .isOther(isOther)
                .isActive(true)
                .sortOrder(sortOrder)
                .build();

        ReasonCatalog saved = reasonCatalogRepository.save(entity);

        ReasonCatalogAuditSnapshot newSnapshot = new ReasonCatalogAuditSnapshot(
                saved.getCode(),
                saved.getLabel(),
                saved.getActionType(),
                saved.getIsOther(),
                saved.getIsActive(),
                saved.getSortOrder()
        );

        auditService.record(
                AccessControlTargetType.REASON_CATALOG,
                AccessControlAction.CREATE,
                saved.getId().toString(),
                null,
                null,
                null,
                newSnapshot,
                "Tạo mục danh mục lý do: " + code,
                actor
        );

        return mapToResponse(saved);
    }

    @Transactional
    public ReasonCatalogResponse update(UUID id, ReasonCatalogUpdateRequest req, String actorEmail) {
        ReasonCatalog item = reasonCatalogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mục danh mục lý do với ID: " + id));

        String newLabel = req.label().trim();
        Integer newSortOrder = req.sortOrder() != null ? req.sortOrder() : item.getSortOrder();

        boolean unchanged = Objects.equals(newLabel, item.getLabel())
                && Objects.equals(newSortOrder, item.getSortOrder());

        if (unchanged) {
            log.info("ReasonCatalog {} unchanged, skipping audit", id);
            return mapToResponse(item);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        ReasonCatalogAuditSnapshot oldSnapshot = new ReasonCatalogAuditSnapshot(
                item.getCode(),
                item.getLabel(),
                item.getActionType(),
                item.getIsOther(),
                item.getIsActive(),
                item.getSortOrder()
        );

        item.setLabel(newLabel);
        item.setSortOrder(newSortOrder);
        ReasonCatalog saved = reasonCatalogRepository.save(item);

        ReasonCatalogAuditSnapshot newSnapshot = new ReasonCatalogAuditSnapshot(
                saved.getCode(),
                saved.getLabel(),
                saved.getActionType(),
                saved.getIsOther(),
                saved.getIsActive(),
                saved.getSortOrder()
        );

        auditService.record(
                AccessControlTargetType.REASON_CATALOG,
                AccessControlAction.UPDATE,
                saved.getId().toString(),
                null,
                null,
                oldSnapshot,
                newSnapshot,
                "Cập nhật nhãn mục lý do: " + saved.getCode(),
                actor
        );

        return mapToResponse(saved);
    }

    @Transactional
    public ReasonCatalogResponse deactivate(UUID id, String actorEmail) {
        ReasonCatalog item = reasonCatalogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mục danh mục lý do với ID: " + id));

        if (Boolean.TRUE.equals(item.getIsOther())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_029);
        }

        if (!Boolean.TRUE.equals(item.getIsActive())) {
            log.info("ReasonCatalog {} already inactive, skipping audit", id);
            return mapToResponse(item);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        ReasonCatalogAuditSnapshot oldSnapshot = new ReasonCatalogAuditSnapshot(
                item.getCode(),
                item.getLabel(),
                item.getActionType(),
                item.getIsOther(),
                item.getIsActive(),
                item.getSortOrder()
        );

        item.setIsActive(false);
        ReasonCatalog saved = reasonCatalogRepository.save(item);

        ReasonCatalogAuditSnapshot newSnapshot = new ReasonCatalogAuditSnapshot(
                saved.getCode(),
                saved.getLabel(),
                saved.getActionType(),
                saved.getIsOther(),
                saved.getIsActive(),
                saved.getSortOrder()
        );

        auditService.record(
                AccessControlTargetType.REASON_CATALOG,
                AccessControlAction.DEACTIVATE,
                saved.getId().toString(),
                null,
                null,
                oldSnapshot,
                newSnapshot,
                "Ngừng sử dụng mục lý do: " + saved.getCode(),
                actor
        );

        return mapToResponse(saved);
    }

    @Transactional
    public ReasonCatalogResponse reactivate(UUID id, String actorEmail) {
        ReasonCatalog item = reasonCatalogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mục danh mục lý do với ID: " + id));

        if (Boolean.TRUE.equals(item.getIsActive())) {
            log.info("ReasonCatalog {} already active, skipping audit", id);
            return mapToResponse(item);
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));

        ReasonCatalogAuditSnapshot oldSnapshot = new ReasonCatalogAuditSnapshot(
                item.getCode(),
                item.getLabel(),
                item.getActionType(),
                item.getIsOther(),
                item.getIsActive(),
                item.getSortOrder()
        );

        item.setIsActive(true);
        ReasonCatalog saved = reasonCatalogRepository.save(item);

        ReasonCatalogAuditSnapshot newSnapshot = new ReasonCatalogAuditSnapshot(
                saved.getCode(),
                saved.getLabel(),
                saved.getActionType(),
                saved.getIsOther(),
                saved.getIsActive(),
                saved.getSortOrder()
        );

        auditService.record(
                AccessControlTargetType.REASON_CATALOG,
                AccessControlAction.REACTIVATE,
                saved.getId().toString(),
                null,
                null,
                oldSnapshot,
                newSnapshot,
                "Kích hoạt lại mục lý do: " + saved.getCode(),
                actor
        );

        return mapToResponse(saved);
    }

    private ReasonCatalogResponse mapToResponse(ReasonCatalog entity) {
        return new ReasonCatalogResponse(
                entity.getId(),
                entity.getActionType(),
                entity.getCode(),
                entity.getLabel(),
                entity.getIsOther(),
                entity.getIsActive(),
                entity.getSortOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
