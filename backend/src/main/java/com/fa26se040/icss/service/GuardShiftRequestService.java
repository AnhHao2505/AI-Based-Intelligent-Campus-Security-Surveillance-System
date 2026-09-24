package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.AvailableSubstituteDto;
import com.fa26se040.icss.dto.guard.AvailableSwapShiftDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestCreateDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestResponseDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestReviewDto;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardShiftRequest;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuardShiftRequestRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GuardShiftRequestService {

    public static final String REF_TYPE_SHIFT_REQUEST = "GUARD_SHIFT_REQUEST";

    private final GuardShiftRequestRepository requestRepository;
    private final GuardShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    /**
     * Finds guards who have a day off (OFF) on shiftId date and satisfy circadian fatigue.
     * Used by Manager (FM) when assigning a replacement for LEAVE_REQUEST.
     */
    @Transactional(readOnly = true)
    public List<AvailableSubstituteDto> getAvailableSubstitutes(UUID shiftId, String requesterEmail) {
        GuardShift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực"));

        User requester = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        User shiftGuard = shift.getGuard();
        boolean isShiftOwner = shiftGuard != null && shiftGuard.getId().equals(requester.getId());
        boolean isManager = requester.getRole() == Role.ADMIN || requester.getRole() == Role.FACILITY_MANAGER;

        if (!isShiftOwner && !isManager) {
            throw new IllegalArgumentException("Bạn chỉ có thể tìm người trực thay cho ca trực của chính mình");
        }

        if (shiftGuard == null || shiftGuard.getTeam() == null) {
            log.warn("Shift guard does not belong to any team");
            return new ArrayList<>();
        }

        UUID teamId = shiftGuard.getTeam().getId();
        List<User> teamMembers = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamId);
        List<User> unassignedGuards = userRepository.findByRoleAndTeamIsNullAndDeletedAtIsNullAndIsActiveTrue(Role.GUARD);

        List<User> allCandidates = new ArrayList<>(teamMembers);
        allCandidates.addAll(unassignedGuards);

        List<AvailableSubstituteDto> availableGuards = new ArrayList<>();

        for (User candidate : allCandidates) {
            if (candidate.getId().equals(shiftGuard.getId())) {
                continue;
            }

            // Rule 1: Strict 1-shift/day. Candidate must have NO active shifts on that day.
            List<GuardShift> candidateShiftsToday = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                    candidate.getId(), shift.getShiftDate(), ShiftStatus.CANCELLED
            );
            if (!candidateShiftsToday.isEmpty()) {
                continue;
            }

            // Rule 2A: Backward Circadian Fatigue Check. If target shift is Morning (06:00),
            // candidate must NOT have worked previous day's Night shift (which ends at 06:00).
            if (shift.getShiftType() == ShiftType.SHIFT_MORNING) {
                LocalDate previousDay = shift.getShiftDate().minusDays(1);
                List<GuardShift> candidateShiftsYesterday = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                        candidate.getId(), previousDay, ShiftStatus.CANCELLED
                );
                boolean workedNightYesterday = candidateShiftsYesterday.stream()
                        .anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT);
                if (workedNightYesterday) {
                    continue;
                }
            }

            // Rule 2B: Forward Circadian Fatigue Check. If target shift is Night (22:00 - 06:00),
            // candidate must NOT have scheduled Morning shift (06:00 - 14:00) on the following day (shiftDate + 1),
            // otherwise candidate would work from 22:00 to 14:00 with 0h rest!
            if (shift.getShiftType() == ShiftType.SHIFT_NIGHT) {
                LocalDate nextDay = shift.getShiftDate().plusDays(1);
                List<GuardShift> candidateShiftsTomorrow = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                        candidate.getId(), nextDay, ShiftStatus.CANCELLED
                );
                boolean hasMorningTomorrow = candidateShiftsTomorrow.stream()
                        .anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING);
                if (hasMorningTomorrow) {
                    continue;
                }
            }

            availableGuards.add(AvailableSubstituteDto.builder()
                    .id(candidate.getId())
                    .userCode(candidate.getUserCode())
                    .fullName(candidate.getFullName())
                    .email(candidate.getEmail())
                    .teamName(candidate.getTeam() != null ? candidate.getTeam().getTeamName() : "Chưa phân đội")
                    .build());
        }

        return availableGuards;
    }

    /**
     * Finds candidate shifts of colleagues that can be mutually exchanged (2-way swap)
     * with the current shift Sa.
     */
    @Transactional(readOnly = true)
    public List<AvailableSwapShiftDto> getAvailableSwapShifts(UUID shiftId, String requesterEmail) {
        GuardShift sa = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực"));

        User requester = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        User guardA = sa.getGuard();
        boolean isShiftOwner = guardA != null && guardA.getId().equals(requester.getId());
        boolean isManager = requester.getRole() == Role.ADMIN || requester.getRole() == Role.FACILITY_MANAGER;

        if (!isShiftOwner && !isManager) {
            throw new IllegalArgumentException("Bạn chỉ có thể tìm ca đổi cho ca trực của chính mình");
        }

        if (guardA == null || guardA.getTeam() == null) {
            log.warn("Shift guard does not belong to any team");
            return new ArrayList<>();
        }

        UUID teamId = guardA.getTeam().getId();
        List<User> teamMembers = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamId);
        List<User> unassignedGuards = userRepository.findByRoleAndTeamIsNullAndDeletedAtIsNullAndIsActiveTrue(Role.GUARD);

        List<User> allCandidates = new ArrayList<>(teamMembers);
        allCandidates.addAll(unassignedGuards);

        LocalDate today = LocalDate.now();
        LocalDate windowEnd = sa.getShiftDate().plusWeeks(2);
        LocalDate windowStart = today.isBefore(sa.getShiftDate().minusDays(3)) ? today : sa.getShiftDate().minusDays(3);

        LocalDate dateA = sa.getShiftDate();
        ShiftType typeA = sa.getShiftType();

        List<AvailableSwapShiftDto> result = new ArrayList<>();

        for (User guardB : allCandidates) {
            if (guardB.getId().equals(guardA.getId())) {
                continue;
            }

            // Fetch candidate shifts of guard B
            List<GuardShift> bShifts = shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                    guardB.getId(), windowStart, windowEnd
            );

            for (GuardShift sb : bShifts) {
                if (sb.getStatus() != ShiftStatus.SCHEDULED) {
                    continue;
                }
                if (sb.getId().equals(sa.getId())) {
                    continue;
                }

                // Check lead time: Sb must not have passed
                LocalDateTime sbStart = LocalDateTime.of(sb.getShiftDate(), sb.getStartTime());
                if (LocalDateTime.now().isAfter(sbStart)) {
                    continue;
                }

                LocalDate dateB = sb.getShiftDate();
                ShiftType typeB = sb.getShiftType();

                // Validation 1: Same day swap
                if (dateA.equals(dateB)) {
                    // Cannot swap identical shift type on the same day
                    if (typeA == typeB) {
                        continue;
                    }
                    // Circadian check for A taking Sb (typeB) on dateA
                    if (typeB == ShiftType.SHIFT_MORNING) {
                        List<GuardShift> aYest = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardA.getId(), dateA.minusDays(1), ShiftStatus.CANCELLED
                        );
                        if (aYest.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT)) {
                            continue;
                        }
                    }
                    if (typeB == ShiftType.SHIFT_NIGHT) {
                        List<GuardShift> aTomo = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardA.getId(), dateA.plusDays(1), ShiftStatus.CANCELLED
                        );
                        if (aTomo.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING)) {
                            continue;
                        }
                    }

                    // Circadian check for B taking Sa (typeA) on dateA
                    if (typeA == ShiftType.SHIFT_MORNING) {
                        List<GuardShift> bYest = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardB.getId(), dateA.minusDays(1), ShiftStatus.CANCELLED
                        );
                        if (bYest.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT)) {
                            continue;
                        }
                    }
                    if (typeA == ShiftType.SHIFT_NIGHT) {
                        List<GuardShift> bTomo = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardB.getId(), dateA.plusDays(1), ShiftStatus.CANCELLED
                        );
                        if (bTomo.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING)) {
                            continue;
                        }
                    }
                } else {
                    // Validation 2: Different days swap (dateA != dateB)
                    // Guard A must be OFF on dateB (no active shifts on dateB)
                    List<GuardShift> aShiftsOnDateB = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                            guardA.getId(), dateB, ShiftStatus.CANCELLED
                    );
                    if (!aShiftsOnDateB.isEmpty()) {
                        continue;
                    }

                    // Guard B must be OFF on dateA (no active shifts on dateA)
                    List<GuardShift> bShiftsOnDateA = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                            guardB.getId(), dateA, ShiftStatus.CANCELLED
                    );
                    if (!bShiftsOnDateA.isEmpty()) {
                        continue;
                    }

                    // Circadian check for A taking Sb (dateB, typeB)
                    if (typeB == ShiftType.SHIFT_MORNING) {
                        List<GuardShift> aYest = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardA.getId(), dateB.minusDays(1), ShiftStatus.CANCELLED
                        );
                        boolean workedNight = aYest.stream()
                                .filter(s -> !s.getId().equals(sa.getId())) // exclude Sa if Sa was moved
                                .anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT);
                        if (workedNight) continue;
                    }
                    if (typeB == ShiftType.SHIFT_NIGHT) {
                        List<GuardShift> aTomo = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardA.getId(), dateB.plusDays(1), ShiftStatus.CANCELLED
                        );
                        boolean hasMorning = aTomo.stream()
                                .filter(s -> !s.getId().equals(sa.getId()))
                                .anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING);
                        if (hasMorning) continue;
                    }

                    // Circadian check for B taking Sa (dateA, typeA)
                    if (typeA == ShiftType.SHIFT_MORNING) {
                        List<GuardShift> bYest = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardB.getId(), dateA.minusDays(1), ShiftStatus.CANCELLED
                        );
                        boolean workedNight = bYest.stream()
                                .filter(s -> !s.getId().equals(sb.getId())) // exclude Sb if Sb was moved
                                .anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT);
                        if (workedNight) continue;
                    }
                    if (typeA == ShiftType.SHIFT_NIGHT) {
                        List<GuardShift> bTomo = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                                guardB.getId(), dateA.plusDays(1), ShiftStatus.CANCELLED
                        );
                        boolean hasMorning = bTomo.stream()
                                .filter(s -> !s.getId().equals(sb.getId()))
                                .anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING);
                        if (hasMorning) continue;
                    }
                }

                // Add valid swap option
                result.add(AvailableSwapShiftDto.builder()
                        .targetShiftId(sb.getId())
                        .guardId(guardB.getId())
                        .userCode(guardB.getUserCode())
                        .fullName(guardB.getFullName())
                        .email(guardB.getEmail())
                        .teamName(guardB.getTeam() != null ? guardB.getTeam().getTeamName() : "Chưa phân đội")
                        .shiftDate(sb.getShiftDate())
                        .shiftType(sb.getShiftType())
                        .shiftTypeName(getShiftTypeName(sb.getShiftType()))
                        .startTime(sb.getStartTime())
                        .endTime(sb.getEndTime())
                        .areaName(sb.getArea() != null ? sb.getArea().getName() : null)
                        .building(sb.getArea() != null ? sb.getArea().getBuilding() : null)
                        .build());
            }
        }

        return result;
    }

    private String getShiftTypeName(ShiftType type) {
        if (type == null) return "";
        return switch (type) {
            case SHIFT_MORNING -> "Ca Sáng (06:00 - 14:00)";
            case SHIFT_AFTERNOON -> "Ca Chiều (14:00 - 22:00)";
            case SHIFT_NIGHT -> "Ca Đêm (22:00 - 06:00)";
        };
    }

    @Transactional
    public GuardShiftRequestResponseDto createRequest(GuardShiftRequestCreateDto dto, String requesterEmail) {
        User requester = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        GuardShift shift = shiftRepository.findById(dto.getShiftId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực"));

        if (!shift.getGuard().getId().equals(requester.getId())) {
            throw new IllegalArgumentException("Bạn chỉ có thể tạo đơn cho ca trực của chính mình");
        }

        if (shift.getStatus() != ShiftStatus.SCHEDULED) {
            throw new IllegalStateException("Chỉ có thể xin đổi ca hoặc nghỉ phép cho ca trực ở trạng thái ĐÃ LÊN LỊCH");
        }

        // Lead time checks
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime shiftStart = LocalDateTime.of(shift.getShiftDate(), shift.getStartTime());

        if (now.isAfter(shiftStart)) {
            throw new IllegalStateException("Không thể tạo đơn cho ca trực đã diễn ra");
        }

        if (dto.getRequestType() == GuardShiftRequestType.SWAP_SHIFT && now.isAfter(shiftStart.minusHours(2))) {
            throw new IllegalStateException("Yêu cầu đổi ca phải được gửi trước giờ bắt đầu ca ít nhất 2 giờ để kịp thời xác nhận");
        }

        if (requestRepository.existsByShiftIdAndStatus(shift.getId(), GuardShiftRequestStatus.PENDING)) {
            throw new IllegalStateException("Ca trực này đã có một đơn đang chờ phê duyệt. Vui lòng chờ phản hồi trước khi gửi đơn mới.");
        }

        User substituteGuard = null;
        GuardShift targetShift = null;

        if (dto.getRequestType() == GuardShiftRequestType.SWAP_SHIFT) {
            if (dto.getTargetShiftId() == null) {
                throw new IllegalArgumentException("Vui lòng chọn ca trực của đồng nghiệp để thực hiện đổi ca");
            }

            targetShift = shiftRepository.findById(dto.getTargetShiftId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực đối ứng của đồng nghiệp"));

            substituteGuard = targetShift.getGuard();
            if (substituteGuard == null) {
                throw new IllegalArgumentException("Ca trực đối ứng chưa có người đảm nhiệm");
            }

            if (substituteGuard.getId().equals(requester.getId())) {
                throw new IllegalArgumentException("Không thể chọn ca trực của chính mình để đổi");
            }

            if (targetShift.getStatus() != ShiftStatus.SCHEDULED) {
                throw new IllegalStateException("Ca trực đối ứng phải ở trạng thái ĐÃ LÊN LỊCH");
            }

            // Lead time check for target shift
            LocalDateTime targetStart = LocalDateTime.of(targetShift.getShiftDate(), targetShift.getStartTime());
            if (now.isAfter(targetStart)) {
                throw new IllegalStateException("Ca trực đối ứng đã diễn ra, không thể đổi");
            }
        }

        GuardShiftRequest request = GuardShiftRequest.builder()
                .requester(requester)
                .shift(shift)
                .requestType(dto.getRequestType())
                .substituteGuard(substituteGuard)
                .targetShift(targetShift)
                .shiftDateSnapshot(shift.getShiftDate())
                .shiftTypeSnapshot(shift.getShiftType() != null ? shift.getShiftType().name() : null)
                .startTimeSnapshot(shift.getStartTime())
                .endTimeSnapshot(shift.getEndTime())
                .reason(dto.getReason().trim())
                .status(GuardShiftRequestStatus.PENDING)
                .build();

        GuardShiftRequest saved = requestRepository.save(request);
        log.info("Guard [{}] submitted [{}] request [{}] for shift [{}] on [{}]",
                requester.getFullName(), saved.getRequestType(), saved.getId(), shift.getId(), shift.getShiftDate());

        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<GuardShiftRequestResponseDto> getMyRequests(String guardEmail) {
        User requester = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        return requestRepository.findByRequesterIdOrderByCreatedAtDesc(requester.getId()).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<GuardShiftRequestResponseDto> getAllRequests(GuardShiftRequestStatus status, UUID teamId, LocalDate startDate, LocalDate endDate) {
        return requestRepository.findRequests(status, teamId, startDate, endDate).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public GuardShiftRequestResponseDto approveRequest(UUID requestId, GuardShiftRequestReviewDto reviewDto, String reviewerEmail) {
        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người duyệt"));

        GuardShiftRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn yêu cầu"));

        if (request.getStatus() != GuardShiftRequestStatus.PENDING) {
            throw new IllegalStateException("Đơn yêu cầu này đã được xử lý trước đó");
        }

        GuardShift shift = request.getShift();

        if (request.getRequestType() == GuardShiftRequestType.SWAP_SHIFT) {
            User substitute = request.getSubstituteGuard();
            GuardShift target = request.getTargetShift();

            if (substitute == null) {
                throw new IllegalStateException("Đơn đổi ca thiếu thông tin người trực thay");
            }

            // 1. Transfer shift Sa to substitute B
            shift.setGuard(substitute);
            shift.setNotes(buildNotes(shift.getNotes(), "Đổi ca: Chuyển từ " + request.getRequester().getFullName() + " sang " + substitute.getFullName() + " theo đơn #" + request.getId()));
            shiftRepository.save(shift);

            // 2. Transfer target shift Sb to requester A (2-way mutual swap)
            if (target != null) {
                target.setGuard(request.getRequester());
                target.setNotes(buildNotes(target.getNotes(), "Đổi ca: Chuyển từ " + substitute.getFullName() + " sang " + request.getRequester().getFullName() + " theo đơn #" + request.getId()));
                shiftRepository.save(target);

                notifyUser(request.getRequester(), "Đổi ca trực thành công",
                        String.format("Yêu cầu đổi ca đã được duyệt. Bạn tiếp nhận ca %s ngày %s của %s.",
                                target.getShiftType(), target.getShiftDate(), substitute.getFullName()),
                        request.getId());
            }

            notifyUser(substitute, "Hoán đổi ca trực",
                    String.format("Đơn đổi ca đã được phê duyệt. Bạn tiếp nhận ca %s ngày %s của %s.",
                            shift.getShiftType(), shift.getShiftDate(), request.getRequester().getFullName()),
                    request.getId());

        } else if (request.getRequestType() == GuardShiftRequestType.LEAVE_REQUEST) {
            // FM optionally provided substitute during approval
            if (reviewDto != null && reviewDto.getSubstituteGuardId() != null) {
                User substitute = userRepository.findById(reviewDto.getSubstituteGuardId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người trực thay được chỉ định"));

                // Validate substitute is not working today
                List<GuardShift> subToday = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                        substitute.getId(), shift.getShiftDate(), ShiftStatus.CANCELLED
                );
                if (!subToday.isEmpty()) {
                    throw new IllegalArgumentException("Người trực thay đã có ca trực trong ngày này");
                }
                // Backward Circadian Check
                if (shift.getShiftType() == ShiftType.SHIFT_MORNING) {
                    List<GuardShift> yest = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                            substitute.getId(), shift.getShiftDate().minusDays(1), ShiftStatus.CANCELLED
                    );
                    if (yest.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT)) {
                        throw new IllegalArgumentException("Người trực thay vừa trực ca đêm hôm trước, không thể nhận ca sáng");
                    }
                }
                // Forward Circadian Check
                if (shift.getShiftType() == ShiftType.SHIFT_NIGHT) {
                    List<GuardShift> tomo = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                            substitute.getId(), shift.getShiftDate().plusDays(1), ShiftStatus.CANCELLED
                    );
                    if (tomo.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING)) {
                        throw new IllegalArgumentException("Người trực thay có ca sáng vào ngày hôm sau, không thể nhận ca đêm");
                    }
                }

                shift.setGuard(substitute);
                shift.setNotes(buildNotes(shift.getNotes(), "Trực thay do " + request.getRequester().getFullName() + " nghỉ phép"));
                shiftRepository.save(shift);
                request.setSubstituteGuard(substitute);

                notifyUser(substitute, "Phân công trực thay",
                        String.format("Bạn được Quản lý phân công trực thay do %s xin nghỉ vào %s ngày %s.",
                                request.getRequester().getFullName(), shift.getShiftType(), shift.getShiftDate()),
                        request.getId());
            } else {
                // No substitute assigned -> Cancel shift
                shift.setStatus(ShiftStatus.CANCELLED);
                shift.setNotes(buildNotes(shift.getNotes(), "Ca trực bị hủy do " + request.getRequester().getFullName() + " nghỉ phép"));
                shiftRepository.save(shift);
            }

            notifyUser(request.getRequester(), "Đơn xin nghỉ đã được duyệt",
                    String.format("Đơn xin nghỉ ca %s ngày %s của bạn đã được Quản lý phê duyệt.",
                            shift.getShiftType(), shift.getShiftDate()),
                    request.getId());
        }

        request.setStatus(GuardShiftRequestStatus.APPROVED);
        request.setReviewedBy(reviewer);
        request.setReviewedAt(OffsetDateTime.now());
        if (reviewDto != null && reviewDto.getReviewNotes() != null) {
            request.setReviewNotes(reviewDto.getReviewNotes().trim());
        }

        GuardShiftRequest updated = requestRepository.save(request);
        log.info("Request [{}] approved by [{}]", requestId, reviewer.getFullName());

        return mapToDto(updated);
    }

    @Transactional
    public GuardShiftRequestResponseDto rejectRequest(UUID requestId, GuardShiftRequestReviewDto reviewDto, String reviewerEmail) {
        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người duyệt"));

        GuardShiftRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn yêu cầu"));

        if (request.getStatus() != GuardShiftRequestStatus.PENDING) {
            throw new IllegalStateException("Đơn yêu cầu này đã được xử lý trước đó");
        }

        request.setStatus(GuardShiftRequestStatus.REJECTED);
        request.setReviewedBy(reviewer);
        request.setReviewedAt(OffsetDateTime.now());
        if (reviewDto != null && reviewDto.getReviewNotes() != null) {
            request.setReviewNotes(reviewDto.getReviewNotes().trim());
        }

        GuardShiftRequest updated = requestRepository.save(request);
        log.info("Request [{}] rejected by [{}]", requestId, reviewer.getFullName());

        notifyUser(request.getRequester(), "Yêu cầu ca trực bị từ chối",
                String.format("Đơn %s cho ca %s ngày %s của bạn đã bị từ chối.",
                        request.getRequestType() == GuardShiftRequestType.SWAP_SHIFT ? "đổi ca" : "xin nghỉ",
                        request.getShift().getShiftType(), request.getShift().getShiftDate()),
                request.getId());

        return mapToDto(updated);
    }

    private void notifyUser(User recipient, String title, String message, UUID requestId) {
        if (recipient == null) return;
        try {
            Notification notification = Notification.builder()
                    .recipient(recipient)
                    .type(NotificationType.REQUEST_APPROVED)
                    .title(title)
                    .message(message)
                    .referenceId(requestId)
                    .referenceType(REF_TYPE_SHIFT_REQUEST)
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);
        } catch (Exception e) {
            log.warn("Failed to create in-app notification for user [{}]: {}", recipient.getId(), e.getMessage());
        }
    }

    private String buildNotes(String existingNotes, String appendNote) {
        if (existingNotes == null || existingNotes.isBlank()) {
            return appendNote;
        }
        return existingNotes + " | " + appendNote;
    }

    private GuardShiftRequestResponseDto mapToDto(GuardShiftRequest request) {
        GuardShift s = request.getShift();
        User req = request.getRequester();
        User sub = request.getSubstituteGuard();
        User rev = request.getReviewedBy();
        GuardShift ts = request.getTargetShift();

        LocalDate shiftDate = s != null ? s.getShiftDate() : request.getShiftDateSnapshot();
        ShiftType shiftType = s != null ? s.getShiftType() : (request.getShiftTypeSnapshot() != null ? ShiftType.valueOf(request.getShiftTypeSnapshot()) : null);
        LocalTime startTime = s != null ? s.getStartTime() : request.getStartTimeSnapshot();
        LocalTime endTime = s != null ? s.getEndTime() : request.getEndTimeSnapshot();
        String areaName = s != null && s.getArea() != null ? s.getArea().getName() : null;

        // Calculate isEmergency: true if submitted < 24h before shift start
        boolean isEmergency = false;
        if (shiftDate != null && startTime != null) {
            LocalDateTime shiftStart = LocalDateTime.of(shiftDate, startTime);
            LocalDateTime created = request.getCreatedAt() != null ? request.getCreatedAt().toLocalDateTime() : LocalDateTime.now();
            isEmergency = created.plusHours(24).isAfter(shiftStart);
        }

        return GuardShiftRequestResponseDto.builder()
                .id(request.getId())
                .requesterId(req != null ? req.getId() : null)
                .requesterName(req != null ? req.getFullName() : null)
                .requesterCode(req != null ? req.getUserCode() : null)
                .requesterTeamName(req != null && req.getTeam() != null ? req.getTeam().getTeamName() : null)
                .shiftId(s != null ? s.getId() : null)
                .shiftDate(shiftDate)
                .shiftType(shiftType)
                .startTime(startTime)
                .endTime(endTime)
                .areaName(areaName)
                .requestType(request.getRequestType())
                .isEmergency(isEmergency)
                .substituteGuardId(sub != null ? sub.getId() : null)
                .substituteGuardName(sub != null ? sub.getFullName() : null)
                .substituteGuardCode(sub != null ? sub.getUserCode() : null)
                .targetShiftId(ts != null ? ts.getId() : null)
                .targetShiftDate(ts != null ? ts.getShiftDate() : null)
                .targetShiftType(ts != null ? ts.getShiftType() : null)
                .targetStartTime(ts != null ? ts.getStartTime() : null)
                .targetEndTime(ts != null ? ts.getEndTime() : null)
                .targetAreaName(ts != null && ts.getArea() != null ? ts.getArea().getName() : null)
                .reason(request.getReason())
                .status(request.getStatus())
                .reviewedById(rev != null ? rev.getId() : null)
                .reviewedByName(rev != null ? rev.getFullName() : null)
                .reviewedAt(request.getReviewedAt())
                .reviewNotes(request.getReviewNotes())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .build();
    }
}
