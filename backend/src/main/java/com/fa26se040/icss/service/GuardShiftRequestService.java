package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.AvailableSubstituteDto;
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

        List<AvailableSubstituteDto> availableGuards = new ArrayList<>();

        for (User candidate : teamMembers) {
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
                    .teamName(candidate.getTeam() != null ? candidate.getTeam().getTeamName() : shiftGuard.getTeam().getTeamName())
                    .build());
        }

        return availableGuards;
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
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime shiftStart = java.time.LocalDateTime.of(shift.getShiftDate(), shift.getStartTime());

        if (now.isAfter(shiftStart)) {
            throw new IllegalStateException("Không thể tạo đơn cho ca trực đã diễn ra");
        }

        if (dto.getRequestType() == GuardShiftRequestType.SWAP_SHIFT && now.isAfter(shiftStart.minusHours(2))) {
            throw new IllegalStateException("Yêu cầu nhờ trực thay phải được gửi trước giờ bắt đầu ca ít nhất 2 giờ để kịp thời xác nhận");
        }

        if (requestRepository.existsByShiftIdAndStatus(shift.getId(), GuardShiftRequestStatus.PENDING)) {
            throw new IllegalStateException("Ca trực này đã có một đơn đang chờ phê duyệt. Vui lòng chờ phản hồi trước khi gửi đơn mới.");
        }

        User substituteGuard = null;
        if (dto.getRequestType() == GuardShiftRequestType.SWAP_SHIFT) {
            if (dto.getSubstituteGuardId() == null) {
                throw new IllegalArgumentException("Vui lòng chọn người trực thay cho yêu cầu đổi ca");
            }
            substituteGuard = userRepository.findById(dto.getSubstituteGuardId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin người trực thay"));

            if (substituteGuard.getId().equals(requester.getId())) {
                throw new IllegalArgumentException("Không thể chọn chính mình làm người trực thay");
            }

            // 1-shift/day rule check for substitute
            List<GuardShift> subToday = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                    substituteGuard.getId(), shift.getShiftDate(), ShiftStatus.CANCELLED
            );
            if (!subToday.isEmpty()) {
                throw new IllegalArgumentException("Người trực thay đã có ca trực trong ngày này");
            }

            // Backward Circadian Fatigue Check
            if (shift.getShiftType() == ShiftType.SHIFT_MORNING) {
                List<GuardShift> yest = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                        substituteGuard.getId(), shift.getShiftDate().minusDays(1), ShiftStatus.CANCELLED
                );
                if (yest.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_NIGHT)) {
                    throw new IllegalArgumentException("Người trực thay vừa trực ca đêm hôm trước, không thể nhận ca sáng");
                }
            }

            // Forward Circadian Fatigue Check
            if (shift.getShiftType() == ShiftType.SHIFT_NIGHT) {
                List<GuardShift> tomo = shiftRepository.findByGuardIdAndShiftDateAndStatusNot(
                        substituteGuard.getId(), shift.getShiftDate().plusDays(1), ShiftStatus.CANCELLED
                );
                if (tomo.stream().anyMatch(s -> s.getShiftType() == ShiftType.SHIFT_MORNING)) {
                    throw new IllegalArgumentException("Người trực thay có ca sáng vào ngày hôm sau, không thể nhận ca đêm");
                }
            }
        }

        GuardShift targetShift = null;
        if (dto.getTargetShiftId() != null) {
            targetShift = shiftRepository.findById(dto.getTargetShiftId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực đối ứng"));
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
            if (substitute == null) {
                throw new IllegalStateException("Đơn đổi ca thiếu thông tin người trực thay");
            }

            // Transfer shift to substitute
            shift.setGuard(substitute);
            shift.setNotes(buildNotes(shift.getNotes(), "Trực thay cho " + request.getRequester().getFullName() + " theo đơn #" + request.getId()));
            shiftRepository.save(shift);

            // If 2-way swap
            if (request.getTargetShift() != null) {
                GuardShift target = request.getTargetShift();
                target.setGuard(request.getRequester());
                target.setNotes(buildNotes(target.getNotes(), "Đổi ca từ " + substitute.getFullName() + " theo đơn #" + request.getId()));
                shiftRepository.save(target);
            }

            notifyUser(substitute, "Phân công trực thay",
                    String.format("Bạn được phân công trực thay cho %s vào %s ngày %s.",
                            request.getRequester().getFullName(), shift.getShiftType(), shift.getShiftDate()),
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

                notifyUser(substitute, "Phân công trực thay khẩn cấp",
                        String.format("Bạn được phân công trực thay do %s xin nghỉ vào %s ngày %s.",
                                request.getRequester().getFullName(), shift.getShiftType(), shift.getShiftDate()),
                        request.getId());
            } else {
                // No substitute assigned -> Cancel shift
                shift.setStatus(ShiftStatus.CANCELLED);
                shift.setNotes(buildNotes(shift.getNotes(), "Ca trực bị hủy do " + request.getRequester().getFullName() + " nghỉ phép"));
                shiftRepository.save(shift);
            }
        }

        request.setStatus(GuardShiftRequestStatus.APPROVED);
        request.setReviewedBy(reviewer);
        request.setReviewedAt(OffsetDateTime.now());
        if (reviewDto != null && reviewDto.getReviewNotes() != null) {
            request.setReviewNotes(reviewDto.getReviewNotes().trim());
        }

        GuardShiftRequest saved = requestRepository.save(request);

        notifyUser(request.getRequester(), "Đơn đã được phê duyệt",
                String.format("Đơn %s cho ca trực ngày %s của bạn đã được Quản lý phê duyệt.",
                        request.getRequestType() == GuardShiftRequestType.SWAP_SHIFT ? "đổi ca" : "xin nghỉ",
                        shift.getShiftDate()),
                saved.getId());

        log.info("Request [{}] approved by [{}]", saved.getId(), reviewer.getFullName());
        return mapToDto(saved);
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

        GuardShiftRequest saved = requestRepository.save(request);

        notifyUser(request.getRequester(), "Đơn đã bị từ chối",
                String.format("Đơn %s cho ca trực ngày %s của bạn đã bị từ chối. Lý do: %s",
                        request.getRequestType() == GuardShiftRequestType.SWAP_SHIFT ? "đổi ca" : "xin nghỉ",
                        request.getShift().getShiftDate(),
                        request.getReviewNotes() != null ? request.getReviewNotes() : "Không có lý do cụ thể"),
                saved.getId());

        log.info("Request [{}] rejected by [{}]", saved.getId(), reviewer.getFullName());
        return mapToDto(saved);
    }

    private void notifyUser(User recipient, String title, String message, UUID requestId) {
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

        return GuardShiftRequestResponseDto.builder()
                .id(request.getId())
                .requesterId(req.getId())
                .requesterName(req.getFullName())
                .requesterCode(req.getUserCode())
                .requesterTeamName(req.getTeam() != null ? req.getTeam().getTeamName() : null)
                .shiftId(s.getId())
                .shiftDate(s.getShiftDate())
                .shiftType(s.getShiftType())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .areaName(s.getArea() != null ? s.getArea().getName() : null)
                .requestType(request.getRequestType())
                .substituteGuardId(sub != null ? sub.getId() : null)
                .substituteGuardName(sub != null ? sub.getFullName() : null)
                .substituteGuardCode(sub != null ? sub.getUserCode() : null)
                .targetShiftId(request.getTargetShift() != null ? request.getTargetShift().getId() : null)
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
