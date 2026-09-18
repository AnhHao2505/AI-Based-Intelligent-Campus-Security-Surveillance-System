package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.GenerateShiftsRequest;
import com.fa26se040.icss.dto.guard.GenerateShiftsResponse;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateCreateRequest;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateDto;
import com.fa26se040.icss.dto.guard.GuardShiftCreateRequest;
import com.fa26se040.icss.dto.guard.GuardShiftDto;
import com.fa26se040.icss.dto.guard.GuardShiftUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuardScheduleTemplate;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.GuardScheduleTemplateRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardScheduleService {

    private final GuardScheduleTemplateRepository templateRepository;
    private final GuardShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final AreaRepository areaRepository;

    // ==========================================
    // 1. TEMPLATE MANAGEMENT (ADMIN)
    // ==========================================

    @Transactional(readOnly = true)
    public List<GuardScheduleTemplateDto> getTemplates(String building) {
        List<GuardScheduleTemplate> list;
        if (building == null || building.isBlank() || "ALL".equalsIgnoreCase(building.trim())) {
            list = templateRepository.findByIsActiveTrue();
        } else {
            list = templateRepository.findActiveTemplatesByBuilding(building.trim());
        }
        return list.stream().map(this::mapTemplateToDto).collect(Collectors.toList());
    }

    @Transactional
    public GuardScheduleTemplateDto createTemplate(GuardScheduleTemplateCreateRequest request) {
        User guard = userRepository.findById(request.getGuardId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bảo vệ"));

        if (guard.getRole() != Role.GUARD) {
            throw new IllegalArgumentException("Tài khoản được gán phải có vai trò GUARD");
        }

        Area area = null;
        if (request.getAreaId() != null) {
            area = areaRepository.findById(request.getAreaId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực được chỉ định"));
        }

        if (templateRepository.existsByGuardIdAndDayOfWeekAndStartTimeAndIsActiveTrue(
                guard.getId(), request.getDayOfWeek(), request.getStartTime())) {
            throw new IllegalArgumentException("Bảo vệ này đã có lịch mẫu vào thứ và khung giờ này");
        }

        GuardScheduleTemplate template = GuardScheduleTemplate.builder()
                .guard(guard)
                .dayOfWeek(request.getDayOfWeek())
                .shiftType(request.getShiftType())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .area(area)
                .radioChannel(request.getRadioChannel())
                .notes(request.getNotes())
                .isActive(true)
                .build();

        GuardScheduleTemplate saved = templateRepository.save(template);
        return mapTemplateToDto(saved);
    }

    @Transactional
    public GuardScheduleTemplateDto updateTemplate(UUID id, GuardScheduleTemplateCreateRequest request) {
        GuardScheduleTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch mẫu"));

        User guard = userRepository.findById(request.getGuardId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bảo vệ"));

        if (guard.getRole() != Role.GUARD) {
            throw new IllegalArgumentException("Tài khoản được gán phải có vai trò GUARD");
        }

        Area area = null;
        if (request.getAreaId() != null) {
            area = areaRepository.findById(request.getAreaId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực được chỉ định"));
        }

        template.setGuard(guard);
        template.setDayOfWeek(request.getDayOfWeek());
        template.setShiftType(request.getShiftType());
        template.setStartTime(request.getStartTime());
        template.setEndTime(request.getEndTime());
        template.setArea(area);
        template.setRadioChannel(request.getRadioChannel());
        template.setNotes(request.getNotes());

        GuardScheduleTemplate saved = templateRepository.save(template);
        return mapTemplateToDto(saved);
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        GuardScheduleTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lịch mẫu"));
        template.setIsActive(false);
        templateRepository.save(template);
    }

    // ==========================================
    // 2. BULK GENERATION FROM TEMPLATES
    // ==========================================

    @Transactional
    public GenerateShiftsResponse generateShifts(GenerateShiftsRequest request) {
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("Ngày kết thúc phải lớn hơn hoặc bằng ngày bắt đầu");
        }

        List<GuardScheduleTemplate> templates;
        if (request.getBuilding() == null || request.getBuilding().isBlank() || "ALL".equalsIgnoreCase(request.getBuilding().trim())) {
            templates = templateRepository.findByIsActiveTrue();
        } else {
            templates = templateRepository.findActiveTemplatesByBuilding(request.getBuilding().trim());
        }
        int generatedCount = 0;
        List<String> warnings = new ArrayList<>();

        // Group checks: Set of "date_shiftType_building" with security room assigned
        Set<String> coveredSecurityRooms = new HashSet<>();
        Set<String> allBuildingShifts = new HashSet<>();

        LocalDate currentDate = request.getStartDate();
        while (!currentDate.isAfter(request.getEndDate())) {
            // Map Java DayOfWeek to 1-7: Sunday is 1, Monday is 2, ..., Saturday is 7
            int dow = mapToDayOfWeekInt(currentDate.getDayOfWeek());

            for (GuardScheduleTemplate t : templates) {
                if (t.getDayOfWeek() == dow) {
                    boolean exists = shiftRepository.existsByGuardIdAndShiftDateAndStartTime(
                            t.getGuard().getId(), currentDate, t.getStartTime()
                    );

                    if (!exists) {
                        GuardShift shift = GuardShift.builder()
                                .guard(t.getGuard())
                                .shiftDate(currentDate)
                                .shiftType(t.getShiftType())
                                .startTime(t.getStartTime())
                                .endTime(t.getEndTime())
                                .area(t.getArea())
                                .radioChannel(t.getRadioChannel())
                                .status(ShiftStatus.SCHEDULED)
                                .notes(t.getNotes())
                                .build();
                        shiftRepository.save(shift);
                        generatedCount++;
                    }

                    if (t.getArea() != null && t.getArea().getBuilding() != null) {
                        String buildingKey = currentDate + "_" + t.getShiftType() + "_" + t.getArea().getBuilding();
                        allBuildingShifts.add(buildingKey);

                        String areaName = t.getArea().getName().toLowerCase();
                        if (areaName.contains("phòng bảo vệ") || areaName.contains("security room") || areaName.contains("phòng camera")) {
                            coveredSecurityRooms.add(buildingKey);
                        }
                    }
                }
            }
            currentDate = currentDate.plusDays(1);
        }

        // Validate building security room presence rule
        for (String buildingKey : allBuildingShifts) {
            if (!coveredSecurityRooms.contains(buildingKey)) {
                String[] parts = buildingKey.split("_");
                String dateStr = parts[0];
                String shiftTypeStr = parts[1];
                String bldStr = parts.length > 2 ? parts[2] : "";
                warnings.add(String.format("Ngày %s - %s tại %s: Chưa có bảo vệ được phân công tại chốt Phòng bảo vệ/Camera!",
                        dateStr, shiftTypeStr, bldStr));
            }
        }

        return GenerateShiftsResponse.builder()
                .totalGenerated(generatedCount)
                .warnings(warnings)
                .build();
    }

    // ==========================================
    // 3. CONCRETE SHIFTS MANAGEMENT (ADMIN)
    // ==========================================

    @Transactional
    public List<GuardShiftDto> getShifts(LocalDate startDate, LocalDate endDate, UUID guardId, String building, ShiftStatus status) {
        if (startDate == null) startDate = LocalDate.now().minusDays(7);
        if (endDate == null) endDate = LocalDate.now().plusDays(14);

        String normalizedBuilding = (building == null || building.isBlank() || "ALL".equalsIgnoreCase(building.trim()))
                ? null
                : building.trim();

        List<GuardShift> list = shiftRepository.findShifts(startDate, endDate, guardId, normalizedBuilding, status);
        autoMarkAbsentIfOverdue(list);
        return list.stream().map(this::mapShiftToDto).collect(Collectors.toList());
    }

    @Transactional
    public GuardShiftDto createShift(GuardShiftCreateRequest request) {
        User guard = userRepository.findById(request.getGuardId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bảo vệ"));

        if (guard.getRole() != Role.GUARD) {
            throw new IllegalArgumentException("Tài khoản được gán phải có vai trò GUARD");
        }

        if (shiftRepository.existsByGuardIdAndShiftDateAndStartTime(
                guard.getId(), request.getShiftDate(), request.getStartTime())) {
            throw new IllegalArgumentException("Bảo vệ này đã có ca trực vào ngày và giờ này");
        }

        Area area = null;
        if (request.getAreaId() != null) {
            area = areaRepository.findById(request.getAreaId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực được chỉ định"));
        }

        GuardShift shift = GuardShift.builder()
                .guard(guard)
                .shiftDate(request.getShiftDate())
                .shiftType(request.getShiftType())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .area(area)
                .radioChannel(request.getRadioChannel())
                .status(ShiftStatus.SCHEDULED)
                .notes(request.getNotes())
                .build();

        GuardShift saved = shiftRepository.save(shift);
        return mapShiftToDto(saved);
    }

    @Transactional
    public GuardShiftDto updateShift(UUID id, GuardShiftUpdateRequest request) {
        GuardShift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực"));

        User guard = userRepository.findById(request.getGuardId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bảo vệ"));

        Area area = null;
        if (request.getAreaId() != null) {
            area = areaRepository.findById(request.getAreaId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực được chỉ định"));
        }

        shift.setGuard(guard);
        shift.setShiftDate(request.getShiftDate());
        shift.setShiftType(request.getShiftType());
        shift.setStartTime(request.getStartTime());
        shift.setEndTime(request.getEndTime());
        shift.setArea(area);
        shift.setRadioChannel(request.getRadioChannel());
        if (request.getStatus() != null) {
            shift.setStatus(request.getStatus());
        }
        shift.setNotes(request.getNotes());

        GuardShift saved = shiftRepository.save(shift);
        return mapShiftToDto(saved);
    }

    @Transactional
    public void deleteShift(UUID id) {
        shiftRepository.deleteById(id);
    }

    // ==========================================
    // 4. GUARD PERSONAL SHIFTS & ATTENDANCE
    // ==========================================

    @Transactional
    public List<GuardShiftDto> getMyShifts(String guardEmail, LocalDate startDate, LocalDate endDate) {
        User guard = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        if (startDate == null) startDate = LocalDate.now().minusDays(3);
        if (endDate == null) endDate = LocalDate.now().plusDays(7);

        List<GuardShift> list = shiftRepository.findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                guard.getId(), startDate, endDate
        );
        autoMarkAbsentIfOverdue(list);
        return list.stream().map(this::mapShiftToDto).collect(Collectors.toList());
    }

    @Transactional
    public GuardShiftDto checkIn(UUID shiftId, String guardEmail) {
        GuardShift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực"));

        User guard = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        if (!shift.getGuard().getId().equals(guard.getId())) {
            throw new IllegalArgumentException("Bạn không được phép điểm danh cho ca trực của người khác");
        }

        if (shift.getStatus() == ShiftStatus.CHECKED_IN) {
            throw new IllegalStateException("Ca trực đã được nhận trước đó.");
        }
        if (shift.getStatus() == ShiftStatus.COMPLETED) {
            throw new IllegalStateException("Ca trực đã hoàn thành.");
        }
        if (shift.getStatus() == ShiftStatus.ABSENT) {
            throw new IllegalStateException("Ca trực đã bị đánh vắng mặt do quá giờ nhận ca.");
        }
        if (shift.getStatus() == ShiftStatus.CANCELLED) {
            throw new IllegalStateException("Ca trực đã bị hủy.");
        }

        // Sequential Check: Earlier shifts of the guard on the same date must be completed
        List<GuardShift> priorShifts = shiftRepository
                .findByGuardIdAndShiftDateAndStartTimeLessThanOrderByStartTimeAsc(
                        guard.getId(), shift.getShiftDate(), shift.getStartTime()
                );
        for (GuardShift prior : priorShifts) {
            if (prior.getStatus() == ShiftStatus.CHECKED_IN || prior.getStatus() == ShiftStatus.SCHEDULED) {
                throw new IllegalStateException(String.format(
                        "Bạn cần hoàn thành ca trực trước (%s: %s - %s) trước khi nhận ca này.",
                        prior.getShiftType(), prior.getStartTime(), prior.getEndTime()
                ));
            }
        }

        // Time window check: Only allow check-in from [startTime - 5min, startTime + 5min]
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime shiftStart = LocalDateTime.of(shift.getShiftDate(), shift.getStartTime());
        LocalDateTime earliestCheckIn = shiftStart.minusMinutes(5);
        LocalDateTime latestCheckIn = shiftStart.plusMinutes(5);

        if (now.isBefore(earliestCheckIn)) {
            throw new IllegalStateException(String.format(
                    "Chưa đến giờ nhận ca. Bạn chỉ có thể nhận ca từ trước giờ bắt đầu 5 phút (từ %s).",
                    earliestCheckIn.toLocalTime().toString()
            ));
        }

        if (now.isAfter(latestCheckIn)) {
            shift.setStatus(ShiftStatus.ABSENT);
            shiftRepository.save(shift);
            throw new IllegalStateException("Đã quá thời gian nhận ca cho phép (quá 5 phút sau giờ bắt đầu ca). Ca trực đã bị ghi nhận VẮNG MẶT.");
        }

        shift.setStatus(ShiftStatus.CHECKED_IN);
        shift.setCheckInAt(OffsetDateTime.now());
        GuardShift saved = shiftRepository.save(shift);
        log.info("Guard [{}] checked in for shift [{}] on [{}]", guard.getFullName(), shift.getId(), shift.getShiftDate());
        return mapShiftToDto(saved);
    }

    @Transactional
    public GuardShiftDto checkOut(UUID shiftId, String guardEmail) {
        GuardShift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực"));

        User guard = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        if (!shift.getGuard().getId().equals(guard.getId())) {
            throw new IllegalArgumentException("Bạn không được phép điểm danh cho ca trực của người khác");
        }

        if (shift.getStatus() != ShiftStatus.CHECKED_IN) {
            throw new IllegalStateException("Ca trực chưa được nhận hoặc đã kết thúc/hủy.");
        }

        // Check-out window check: Only allow checkout from [endTime, endTime + 5min]
        LocalDateTime now = LocalDateTime.now();
        LocalDate endDate = shift.getEndTime().isBefore(shift.getStartTime())
                ? shift.getShiftDate().plusDays(1)
                : shift.getShiftDate();
        LocalDateTime shiftEnd = LocalDateTime.of(endDate, shift.getEndTime());
        LocalDateTime latestCheckOut = shiftEnd.plusMinutes(5);

        if (now.isBefore(shiftEnd)) {
            throw new IllegalStateException(String.format(
                    "Chưa đến giờ kết thúc ca trực (%s). Bạn chỉ có thể hoàn thành ca từ đúng giờ kết thúc đến sau 5 phút.",
                    shift.getEndTime().toString()
            ));
        }

        if (now.isAfter(latestCheckOut)) {
            throw new IllegalStateException(String.format(
                    "Đã quá thời gian kết thúc ca cho phép (quá 5 phút sau giờ kết thúc ca %s). Vui lòng liên hệ Quản trị viên để được hỗ trợ.",
                    shift.getEndTime().toString()
            ));
        }

        shift.setStatus(ShiftStatus.COMPLETED);
        shift.setCheckOutAt(OffsetDateTime.now());
        GuardShift saved = shiftRepository.save(shift);
        log.info("Guard [{}] checked out from shift [{}] on [{}]", guard.getFullName(), shift.getId(), shift.getShiftDate());
        return mapShiftToDto(saved);
    }

    private void autoMarkAbsentIfOverdue(List<GuardShift> shifts) {
        if (shifts == null || shifts.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (GuardShift shift : shifts) {
            if (shift.getStatus() == ShiftStatus.SCHEDULED) {
                LocalDateTime shiftStart = LocalDateTime.of(shift.getShiftDate(), shift.getStartTime());
                LocalDateTime latestCheckIn = shiftStart.plusMinutes(5);
                if (now.isAfter(latestCheckIn)) {
                    shift.setStatus(ShiftStatus.ABSENT);
                    shiftRepository.save(shift);
                }
            }
        }
    }

    // ==========================================
    // HELPERS & MAPPERS
    // ==========================================

    private int mapToDayOfWeekInt(DayOfWeek dow) {
        return switch (dow) {
            case SUNDAY -> 1;
            case MONDAY -> 2;
            case TUESDAY -> 3;
            case WEDNESDAY -> 4;
            case THURSDAY -> 5;
            case FRIDAY -> 6;
            case SATURDAY -> 7;
        };
    }

    private GuardScheduleTemplateDto mapTemplateToDto(GuardScheduleTemplate t) {
        return GuardScheduleTemplateDto.builder()
                .id(t.getId())
                .guardId(t.getGuard().getId())
                .guardName(t.getGuard().getFullName())
                .guardCode(t.getGuard().getUserCode())
                .dayOfWeek(t.getDayOfWeek())
                .shiftType(t.getShiftType())
                .startTime(t.getStartTime())
                .endTime(t.getEndTime())
                .areaId(t.getArea() != null ? t.getArea().getId() : null)
                .areaName(t.getArea() != null ? t.getArea().getName() : null)
                .building(t.getArea() != null ? t.getArea().getBuilding() : null)
                .radioChannel(t.getRadioChannel())
                .notes(t.getNotes())
                .isActive(t.getIsActive())
                .build();
    }

    private GuardShiftDto mapShiftToDto(GuardShift s) {
        return GuardShiftDto.builder()
                .id(s.getId())
                .guardId(s.getGuard().getId())
                .guardName(s.getGuard().getFullName())
                .guardCode(s.getGuard().getUserCode())
                .shiftDate(s.getShiftDate())
                .shiftType(s.getShiftType())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .areaId(s.getArea() != null ? s.getArea().getId() : null)
                .areaName(s.getArea() != null ? s.getArea().getName() : null)
                .building(s.getArea() != null ? s.getArea().getBuilding() : null)
                .radioChannel(s.getRadioChannel())
                .status(s.getStatus())
                .checkInAt(s.getCheckInAt())
                .checkOutAt(s.getCheckOutAt())
                .notes(s.getNotes())
                .build();
    }
}
