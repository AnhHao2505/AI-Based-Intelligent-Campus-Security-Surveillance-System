package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.GuardTeamDispatchCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDispatchDto;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.GuardTeamDispatch;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.GuardDispatchStatus;
import com.fa26se040.icss.repository.GuardTeamDispatchRepository;
import com.fa26se040.icss.repository.GuardTeamRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardTeamDispatchService {

    private final GuardTeamDispatchRepository dispatchRepository;
    private final GuardTeamRepository teamRepository;
    private final UserRepository userRepository;
    private final com.fa26se040.icss.repository.GuardShiftRepository shiftRepository;
    private final com.fa26se040.icss.repository.AreaRepository areaRepository;

    @Transactional(readOnly = true)
    public List<com.fa26se040.icss.dto.guard.AvailableSubstituteDto> getAvailableGuardsForDispatch(
            UUID toTeamId,
            LocalDate startDate,
            LocalDate endDate,
            com.fa26se040.icss.enums.ShiftType shiftType
    ) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Ngày bắt đầu và ngày kết thúc không được để trống");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Ngày kết thúc không được trước ngày bắt đầu");
        }

        List<User> allGuards = userRepository.findActiveUsersByRole(com.fa26se040.icss.enums.Role.GUARD);
        List<com.fa26se040.icss.dto.guard.AvailableSubstituteDto> result = new ArrayList<>();

        for (User candidate : allGuards) {
            // 1. Kiểm tra trùng đợt điều động tăng cường khác
            List<GuardTeamDispatch> overlappingDispatches = dispatchRepository.findOverlappingDispatches(
                    candidate.getId(), startDate, endDate, shiftType
            );
            if (!overlappingDispatches.isEmpty()) {
                continue;
            }

            // 2. Kiểm tra trùng ca trực gốc (GuardShift) và ca trực liên tiếp (circadian fatigue)
            List<com.fa26se040.icss.entity.GuardShift> candidateShifts = shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                    candidate.getId(), startDate.minusDays(1), endDate.plusDays(1)
            );
            List<com.fa26se040.icss.entity.GuardShift> activeShifts = candidateShifts.stream()
                    .filter(s -> s.getStatus() != com.fa26se040.icss.enums.ShiftStatus.CANCELLED)
                    .toList();

            boolean hasConflict = false;

            // Quy tắc 1: Không được có bất kỳ ca trực nào trong các ngày từ startDate đến endDate
            LocalDate curr = startDate;
            while (!curr.isAfter(endDate)) {
                LocalDate checkDate = curr;
                boolean hasShiftOnDate = activeShifts.stream().anyMatch(s -> s.getShiftDate().equals(checkDate));
                if (hasShiftOnDate) {
                    hasConflict = true;
                    break;
                }
                curr = curr.plusDays(1);
            }

            if (hasConflict) {
                continue;
            }

            // Quy tắc 2A: Nghỉ ngơi liền kề lùi (Backward check)
            // Nếu ca tăng cường là Ca Sáng (hoặc Cả Ngày), ngày hôm trước (startDate - 1) không được trực Ca Đêm (kết thúc lúc 06:00)
            if (shiftType == null || shiftType == com.fa26se040.icss.enums.ShiftType.SHIFT_MORNING) {
                boolean nightShiftYesterday = activeShifts.stream().anyMatch(s ->
                        s.getShiftDate().equals(startDate.minusDays(1)) && s.getShiftType() == com.fa26se040.icss.enums.ShiftType.SHIFT_NIGHT
                );
                if (nightShiftYesterday) {
                    continue;
                }
            }

            // Quy tắc 2B: Nghỉ ngơi liền kề tiến (Forward check)
            // Nếu ca tăng cường là Ca Đêm (hoặc Cả Ngày), ngày hôm sau (endDate + 1) không được có lịch Ca Sáng (bắt đầu lúc 06:00)
            if (shiftType == null || shiftType == com.fa26se040.icss.enums.ShiftType.SHIFT_NIGHT) {
                boolean morningShiftTomorrow = activeShifts.stream().anyMatch(s ->
                        s.getShiftDate().equals(endDate.plusDays(1)) && s.getShiftType() == com.fa26se040.icss.enums.ShiftType.SHIFT_MORNING
                );
                if (morningShiftTomorrow) {
                    continue;
                }
            }

            result.add(com.fa26se040.icss.dto.guard.AvailableSubstituteDto.builder()
                    .id(candidate.getId())
                    .userCode(candidate.getUserCode())
                    .fullName(candidate.getFullName())
                    .email(candidate.getEmail())
                    .teamName(candidate.getTeam() != null ? candidate.getTeam().getTeamName() : "Chưa phân đội")
                    .build());
        }

        return result;
    }

    @Transactional
    public List<GuardTeamDispatchDto> createDispatches(GuardTeamDispatchCreateRequest request, String creatorEmail) {
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("Ngày kết thúc tăng cường không được trước ngày bắt đầu");
        }

        GuardTeam toTeam = teamRepository.findById(request.getToTeamId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đội nhận tăng cường với ID: " + request.getToTeamId()));

        User creator = null;
        if (creatorEmail != null && !creatorEmail.isBlank()) {
            creator = userRepository.findByEmail(creatorEmail).orElse(null);
        }

        List<GuardTeamDispatch> createdList = new ArrayList<>();

        for (UUID guardId : request.getGuardIds()) {
            User guard = userRepository.findById(guardId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bảo vệ với ID: " + guardId));

            // Kiểm tra trùng lặp đợt điều động
            List<GuardTeamDispatch> overlaps = dispatchRepository.findOverlappingDispatches(
                    guardId, request.getStartDate(), request.getEndDate(), request.getShiftType()
            );

            if (!overlaps.isEmpty()) {
                String shiftLabel = request.getShiftType() != null ? " (" + request.getShiftType() + ")" : "";
                throw new IllegalArgumentException(
                        "Bảo vệ " + guard.getFullName() + " (" + guard.getUserCode() + ") " +
                        "đã có lịch điều động tăng cường khác" + shiftLabel + " trong khoảng thời gian từ " +
                        request.getStartDate() + " đến " + request.getEndDate()
                );
            }

            // Kiểm tra trùng ca trực gốc và ca trực liên tiếp
            List<com.fa26se040.icss.entity.GuardShift> guardShifts = shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                    guardId, request.getStartDate().minusDays(1), request.getEndDate().plusDays(1)
            );
            List<com.fa26se040.icss.entity.GuardShift> activeShifts = guardShifts.stream()
                    .filter(s -> s.getStatus() != com.fa26se040.icss.enums.ShiftStatus.CANCELLED)
                    .toList();

            LocalDate curr = request.getStartDate();
            while (!curr.isAfter(request.getEndDate())) {
                LocalDate checkDate = curr;
                java.util.Optional<com.fa26se040.icss.entity.GuardShift> shiftOnDate = activeShifts.stream()
                        .filter(s -> s.getShiftDate().equals(checkDate))
                        .findFirst();
                if (shiftOnDate.isPresent()) {
                    throw new IllegalArgumentException(
                            "Bảo vệ " + guard.getFullName() + " (" + guard.getUserCode() + ") " +
                            "đã có lịch trực (" + shiftOnDate.get().getShiftType() + ") vào ngày " + checkDate
                    );
                }
                curr = curr.plusDays(1);
            }

            if (request.getShiftType() == null || request.getShiftType() == com.fa26se040.icss.enums.ShiftType.SHIFT_MORNING) {
                boolean nightBefore = activeShifts.stream().anyMatch(s ->
                        s.getShiftDate().equals(request.getStartDate().minusDays(1)) && s.getShiftType() == com.fa26se040.icss.enums.ShiftType.SHIFT_NIGHT
                );
                if (nightBefore) {
                    throw new IllegalArgumentException(
                            "Bảo vệ " + guard.getFullName() + " (" + guard.getUserCode() + ") " +
                            "trực ca đêm ngày " + request.getStartDate().minusDays(1) + ", không thể trực ca sáng tiếp theo (0h nghỉ ngơi)"
                    );
                }
            }

            if (request.getShiftType() == null || request.getShiftType() == com.fa26se040.icss.enums.ShiftType.SHIFT_NIGHT) {
                boolean morningAfter = activeShifts.stream().anyMatch(s ->
                        s.getShiftDate().equals(request.getEndDate().plusDays(1)) && s.getShiftType() == com.fa26se040.icss.enums.ShiftType.SHIFT_MORNING
                );
                if (morningAfter) {
                    throw new IllegalArgumentException(
                            "Bảo vệ " + guard.getFullName() + " (" + guard.getUserCode() + ") " +
                            "có lịch trực ca sáng ngày " + request.getEndDate().plusDays(1) + ", không thể trực ca đêm trước đó (0h nghỉ ngơi)"
                    );
                }
            }

            GuardTeamDispatch dispatch = GuardTeamDispatch.builder()
                    .guard(guard)
                    .fromTeam(guard.getTeam())
                    .toTeam(toTeam)
                    .startDate(request.getStartDate())
                    .endDate(request.getEndDate())
                    .shiftType(request.getShiftType())
                    .reason(request.getReason() != null ? request.getReason().trim() : "")
                    .status(GuardDispatchStatus.ACTIVE)
                    .createdBy(creator)
                    .build();

            createdList.add(dispatchRepository.save(dispatch));

            // Tự động sinh ca trực GuardShift tương ứng cho bảo vệ để hiển thị trên Lịch và Mobile App
            String buildingCode = toTeam.getDescription();
            com.fa26se040.icss.entity.Area targetArea = null;
            if (buildingCode != null && !buildingCode.isBlank()) {
                targetArea = areaRepository.findAll().stream()
                        .filter(a -> a.getDeletedAt() == null && buildingCode.equalsIgnoreCase(a.getBuilding()))
                        .findFirst().orElse(null);
            }
            if (targetArea == null) {
                targetArea = areaRepository.findAll().stream()
                        .filter(a -> a.getDeletedAt() == null)
                        .findFirst().orElse(null);
            }

            List<com.fa26se040.icss.enums.ShiftType> shiftTypesToCreate = new ArrayList<>();
            if (request.getShiftType() != null) {
                shiftTypesToCreate.add(request.getShiftType());
            } else {
                shiftTypesToCreate.add(com.fa26se040.icss.enums.ShiftType.SHIFT_MORNING);
            }

            String reasonText = (request.getReason() != null && !request.getReason().isBlank())
                    ? request.getReason().trim()
                    : "Sự kiện";

            for (com.fa26se040.icss.enums.ShiftType st : shiftTypesToCreate) {
                java.time.LocalTime startTime;
                java.time.LocalTime endTime;
                switch (st) {
                    case SHIFT_MORNING -> {
                        startTime = java.time.LocalTime.of(6, 0);
                        endTime = java.time.LocalTime.of(14, 0);
                    }
                    case SHIFT_AFTERNOON -> {
                        startTime = java.time.LocalTime.of(14, 0);
                        endTime = java.time.LocalTime.of(22, 0);
                    }
                    case SHIFT_NIGHT -> {
                        startTime = java.time.LocalTime.of(22, 0);
                        endTime = java.time.LocalTime.of(6, 0);
                    }
                    default -> {
                        startTime = java.time.LocalTime.of(6, 0);
                        endTime = java.time.LocalTime.of(14, 0);
                    }
                }

                LocalDate shiftDate = request.getStartDate();
                while (!shiftDate.isAfter(request.getEndDate())) {
                    boolean exists = shiftRepository.existsByGuardIdAndShiftDateAndStartTime(
                            guardId, shiftDate, startTime
                    );
                    if (!exists) {
                        com.fa26se040.icss.entity.GuardShift shift = com.fa26se040.icss.entity.GuardShift.builder()
                                .guard(guard)
                                .shiftDate(shiftDate)
                                .shiftType(st)
                                .startTime(startTime)
                                .endTime(endTime)
                                .area(targetArea)
                                .status(com.fa26se040.icss.enums.ShiftStatus.SCHEDULED)
                                .isOvertime(true)
                                .notes("⚡ Điều động tăng cường: " + reasonText)
                                .build();
                        shiftRepository.save(shift);
                    }
                    shiftDate = shiftDate.plusDays(1);
                }
            }
        }

        log.info("Created [{}] temporary guard dispatches and shifts to team [{}] ({}) from [{}] to [{}] shift [{}]",
                createdList.size(), toTeam.getTeamName(), toTeam.getId(), request.getStartDate(), request.getEndDate(), request.getShiftType());

        return createdList.stream()
                .map(GuardTeamDispatchDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void autoExpireDispatches() {
        LocalDate today = LocalDate.now();
        List<GuardTeamDispatch> expired = dispatchRepository.findAllByStatusAndEndDateBefore(GuardDispatchStatus.ACTIVE, today);
        if (!expired.isEmpty()) {
            for (GuardTeamDispatch d : expired) {
                d.setStatus(GuardDispatchStatus.COMPLETED);
            }
            dispatchRepository.saveAll(expired);
            log.info("Auto-expired [{}] guard team dispatches past end date", expired.size());
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void scheduledAutoExpireDispatches() {
        autoExpireDispatches();
    }

    @Transactional
    public List<GuardTeamDispatchDto> getDispatches(UUID teamId, LocalDate date, LocalDate startDate, LocalDate endDate) {
        // Tự động hoàn tất các đợt điều động đã quá ngày kết thúc
        autoExpireDispatches();

        List<GuardTeamDispatch> list;

        if (teamId != null) {
            list = dispatchRepository.findActiveDispatchesByToTeamId(teamId, LocalDate.now());
        } else if (startDate != null && endDate != null) {
            list = dispatchRepository.findDispatchesInDateRange(startDate, endDate, GuardDispatchStatus.ACTIVE);
        } else if (date != null) {
            list = dispatchRepository.findDispatchesOnDate(date, GuardDispatchStatus.ACTIVE);
        } else {
            LocalDate today = LocalDate.now();
            list = dispatchRepository.findDispatchesOnDate(today, GuardDispatchStatus.ACTIVE);
        }

        return list.stream()
                .map(GuardTeamDispatchDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public GuardTeamDispatchDto cancelDispatch(UUID dispatchId, String userEmail) {
        GuardTeamDispatch dispatch = dispatchRepository.findById(dispatchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đợt điều động với ID: " + dispatchId));

        dispatch.setStatus(GuardDispatchStatus.CANCELLED);
        GuardTeamDispatch saved = dispatchRepository.save(dispatch);

        // Xóa các ca trực tăng cường đã tạo nếu còn ở trạng thái SCHEDULED
        List<com.fa26se040.icss.entity.GuardShift> candidateShifts = shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                dispatch.getGuard().getId(), dispatch.getStartDate(), dispatch.getEndDate()
        );
        for (com.fa26se040.icss.entity.GuardShift s : candidateShifts) {
            if (s.getStatus() == com.fa26se040.icss.enums.ShiftStatus.SCHEDULED
                    && Boolean.TRUE.equals(s.getIsOvertime())
                    && s.getNotes() != null && s.getNotes().contains("⚡ Điều động tăng cường")) {
                shiftRepository.delete(s);
            }
        }

        log.info("Cancelled temporary dispatch [{}] of guard [{}] from team [{}] to team [{}] by [{}]",
                dispatchId, dispatch.getGuard().getFullName(),
                dispatch.getFromTeam() != null ? dispatch.getFromTeam().getTeamName() : "None",
                dispatch.getToTeam().getTeamName(), userEmail);

        return GuardTeamDispatchDto.fromEntity(saved);
    }
}
