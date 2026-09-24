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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.fa26se040.icss.dto.guard.BulkClearShiftsRequest;
import com.fa26se040.icss.dto.guard.BulkClearShiftsResponse;
import com.fa26se040.icss.dto.guard.CapacityCalculateRequest;
import com.fa26se040.icss.dto.guard.CapacityCalculateResponse;
import com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest;
import com.fa26se040.icss.entity.GuardShiftRequest;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.GuardTeamDispatch;
import com.fa26se040.icss.enums.GuardDispatchStatus;
import com.fa26se040.icss.enums.GuardShiftRequestStatus;
import com.fa26se040.icss.repository.GuardShiftRequestRepository;
import com.fa26se040.icss.repository.GuardTeamDispatchRepository;
import com.fa26se040.icss.repository.GuardTeamRepository;
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
    private final GuardTeamRepository teamRepository;
    private final GuardShiftRequestRepository shiftRequestRepository;
    private final GuardTeamDispatchRepository dispatchRepository;

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

    public CapacityCalculateResponse calculateCapacity(CapacityCalculateRequest request) {
        int mDemand = request.getMorningDemand() != null ? request.getMorningDemand() : 0;
        int aDemand = request.getAfternoonDemand() != null ? request.getAfternoonDemand() : 0;
        int nDemand = request.getNightDemand() != null ? request.getNightDemand() : 0;
        int weekdayDaily = mDemand + aDemand + nDemand;

        int weekly;
        boolean customSunday = Boolean.TRUE.equals(request.getHasSundayCustom()) || Boolean.TRUE.equals(request.getHasWeekendCustom());
        if (customSunday) {
            int suMorning = request.getSundayMorningDemand() != null ? request.getSundayMorningDemand()
                    : (request.getWeekendMorningDemand() != null ? request.getWeekendMorningDemand() : mDemand);
            int suAfternoon = request.getSundayAfternoonDemand() != null ? request.getSundayAfternoonDemand()
                    : (request.getWeekendAfternoonDemand() != null ? request.getWeekendAfternoonDemand() : aDemand);
            int suNight = request.getSundayNightDemand() != null ? request.getSundayNightDemand()
                    : (request.getWeekendNightDemand() != null ? request.getWeekendNightDemand() : nDemand);
            int sundayDaily = suMorning + suAfternoon + suNight;
            weekly = (weekdayDaily * 6) + (sundayDaily * 1);
        } else {
            weekly = weekdayDaily * 7;
        }

        int recommended = (int) Math.ceil(weekly / 6.0);
        if (recommended == 0) recommended = 1;

        double avgShifts = Math.round((weekly / (double) recommended) * 100.0) / 100.0;
        double restDays = Math.round((7.0 - avgShifts) * 100.0) / 100.0;

        return CapacityCalculateResponse.builder()
                .dailyTotalShifts(weekdayDaily)
                .weeklyTotalShifts(weekly)
                .recommendedHeadcount(recommended)
                .averageShiftsPerGuard(avgShifts)
                .restDaysPerGuard(restDays)
                .build();
    }

    @Transactional
    public List<GuardShiftDto> generateShiftsFromWizard(WizardGenerateShiftsRequest request) {
        LocalDate effectiveEndDate = request.getEndDate() != null ? request.getEndDate() : request.getStartDate().plusDays(6);
        if (effectiveEndDate.isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("Ngày kết thúc phải lớn hơn hoặc bằng ngày bắt đầu");
        }

        List<User> guards = new ArrayList<>();
        GuardTeam team = null;

        List<UUID> guardIds = request.getMemberGuardIds();
        if ((guardIds == null || guardIds.isEmpty()) && request.getSelectedGuardIds() != null) {
            guardIds = request.getSelectedGuardIds();
        }

        if (request.getTeamId() != null) {
            team = teamRepository.findById(request.getTeamId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tổ đội được chỉ định"));
            guards = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(team.getId());
        } else if (request.getNewTeamName() != null && !request.getNewTeamName().isBlank()) {
            String trimmedName = request.getNewTeamName().trim();
            Optional<GuardTeam> existingOpt = teamRepository.findByTeamNameIgnoreCase(trimmedName);
            if (existingOpt.isPresent()) {
                team = existingOpt.get();
                if (Boolean.FALSE.equals(team.getIsActive())) {
                    team.setIsActive(true);
                    team = teamRepository.save(team);
                }
            } else {
                team = teamRepository.save(GuardTeam.builder()
                        .teamName(trimmedName)
                        .description("Tổ tạo nhanh từ Wizard")
                        .isActive(true)
                        .build());
            }
            if (guardIds != null && !guardIds.isEmpty()) {
                guards = userRepository.findAllById(guardIds);
                if (team != null) {
                    for (User g : guards) {
                        g.setTeam(team);
                    }
                    userRepository.saveAll(guards);
                }
            }
        } else if (guardIds != null && !guardIds.isEmpty()) {
            guards = userRepository.findAllById(guardIds);
        }

        if (guards.isEmpty()) {
            throw new IllegalArgumentException("Không có bảo vệ nào được chọn để lập lịch trực");
        }

        int m = guards.size();
        int nMorning = request.getMorningDemand() != null ? request.getMorningDemand() : 0;
        int nAfternoon = request.getAfternoonDemand() != null ? request.getAfternoonDemand() : 0;
        int nNight = request.getNightDemand() != null ? request.getNightDemand() : 0;

        // Ràng buộc an ninh tối thiểu: 1 ca trong tòa phải có ít nhất 2 người (1 camera + 1 tuần tra)
        int minSecLimit = 2;
        if (nMorning > 0 && nMorning < minSecLimit) {
            throw new IllegalArgumentException("Ràng buộc an ninh tòa nhà: Ca Sáng ngày học & làm việc (T2-T7) phải có tối thiểu 2 người (1 trực camera + 1 tuần tra)");
        }
        if (nAfternoon > 0 && nAfternoon < minSecLimit) {
            throw new IllegalArgumentException("Ràng buộc an ninh tòa nhà: Ca Chiều ngày học & làm việc (T2-T7) phải có tối thiểu 2 người (1 trực camera + 1 tuần tra)");
        }
        if (nNight > 0 && nNight < minSecLimit) {
            throw new IllegalArgumentException("Ràng buộc an ninh tòa nhà: Ca Đêm ngày học & làm việc (T2-T7) phải có tối thiểu 2 người (1 trực camera + 1 tuần tra)");
        }

        boolean customSunday = Boolean.TRUE.equals(request.getHasSundayCustom()) || Boolean.TRUE.equals(request.getHasWeekendCustom());
        Integer suM = request.getSundayMorningDemand() != null ? request.getSundayMorningDemand() : request.getWeekendMorningDemand();
        Integer suA = request.getSundayAfternoonDemand() != null ? request.getSundayAfternoonDemand() : request.getWeekendAfternoonDemand();
        Integer suN = request.getSundayNightDemand() != null ? request.getSundayNightDemand() : request.getWeekendNightDemand();

        if (customSunday) {
            if (suM != null && suM > 0 && suM < minSecLimit) {
                throw new IllegalArgumentException("Ràng buộc an ninh tòa nhà: Ca Sáng Chủ Nhật phải có tối thiểu 2 người (1 trực camera + 1 tuần tra)");
            }
            if (suA != null && suA > 0 && suA < minSecLimit) {
                throw new IllegalArgumentException("Ràng buộc an ninh tòa nhà: Ca Chiều Chủ Nhật phải có tối thiểu 2 người (1 trực camera + 1 tuần tra)");
            }
            if (suN != null && suN > 0 && suN < minSecLimit) {
                throw new IllegalArgumentException("Ràng buộc an ninh tòa nhà: Ca Đêm Chủ Nhật phải có tối thiểu 2 người (1 trực camera + 1 tuần tra)");
            }
        }

        // Tìm khu vực mặc định của tòa nhà nếu có truyền building
        Area targetArea = null;
        if (request.getBuilding() != null && !request.getBuilding().isBlank() && !"ALL".equalsIgnoreCase(request.getBuilding().trim())) {
            List<Area> buildingAreas = areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(request.getBuilding().trim(), "G");
            if (buildingAreas.isEmpty()) {
                buildingAreas = areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(request.getBuilding().trim(), "1");
            }
            if (!buildingAreas.isEmpty()) {
                targetArea = buildingAreas.get(0);
            }
        }

        // Khởi tạo bộ theo dõi tải làm việc cho từng bảo vệ (Fair Load Balancing & Ergonomic Rotation)
        class GuardScheduleState {
            final User guard;
            int totalAssigned = 0;
            int morningCount = 0;
            int afternoonCount = 0;
            int nightCount = 0;
            int consecutiveWorkDays = 0;
            int consecutiveRestDays = 0;
            ShiftType yesterdayShift = null;
            ShiftType todayShift = null;

            GuardScheduleState(User guard) {
                this.guard = guard;
            }

            int getShiftTypeCount(ShiftType type) {
                if (type == ShiftType.SHIFT_MORNING) return morningCount;
                if (type == ShiftType.SHIFT_AFTERNOON) return afternoonCount;
                if (type == ShiftType.SHIFT_NIGHT) return nightCount;
                return 0;
            }

            void recordShift(ShiftType type) {
                this.todayShift = type;
                this.totalAssigned++;
                if (type == ShiftType.SHIFT_MORNING) morningCount++;
                else if (type == ShiftType.SHIFT_AFTERNOON) afternoonCount++;
                else if (type == ShiftType.SHIFT_NIGHT) nightCount++;
            }

            void endDay() {
                if (todayShift != null) {
                    consecutiveWorkDays++;
                    consecutiveRestDays = 0;
                    yesterdayShift = todayShift;
                    todayShift = null;
                } else {
                    consecutiveWorkDays = 0;
                    consecutiveRestDays++;
                    yesterdayShift = null;
                }
            }
        }

        // Lấy lịch sử ca trực của các bảo vệ trong 7 ngày trước startDate để bảo đảm an toàn nghỉ ngơi liên tuần (Cross-week Rest & Fatigue Guard)
        LocalDate historyStartDate = request.getStartDate().minusDays(7);
        LocalDate historyEndDate = request.getStartDate().minusDays(1);
        List<GuardShift> priorShifts = shiftRepository.findByShiftDateBetween(historyStartDate, historyEndDate);

        Map<UUID, Map<LocalDate, GuardShift>> priorShiftsByGuard = new HashMap<>();
        for (GuardShift ps : priorShifts) {
            if (ps.getStatus() != ShiftStatus.CANCELLED && ps.getGuard() != null) {
                priorShiftsByGuard
                        .computeIfAbsent(ps.getGuard().getId(), k -> new HashMap<>())
                        .put(ps.getShiftDate(), ps);
            }
        }

        List<GuardScheduleState> guardStates = guards.stream()
                .map(g -> {
                    GuardScheduleState state = new GuardScheduleState(g);
                    Map<LocalDate, GuardShift> guardHistory = priorShiftsByGuard.getOrDefault(g.getId(), Collections.emptyMap());

                    // 1. Ghi nhận ca trực ngày hôm trước (ví dụ: ca đêm Chủ nhật trước Thứ 2 bắt đầu lịch)
                    GuardShift yesterdayShiftObj = guardHistory.get(historyEndDate);
                    if (yesterdayShiftObj != null) {
                        state.yesterdayShift = yesterdayShiftObj.getShiftType();
                    }

                    // 2. Tính số ngày làm/nghỉ liên tiếp lùi dần từ ngày hôm trước
                    LocalDate d = historyEndDate;
                    if (state.yesterdayShift != null) {
                        while (!d.isBefore(historyStartDate) && guardHistory.containsKey(d)) {
                            state.consecutiveWorkDays++;
                            d = d.minusDays(1);
                        }
                    } else {
                        while (!d.isBefore(historyStartDate) && !guardHistory.containsKey(d)) {
                            state.consecutiveRestDays++;
                            d = d.minusDays(1);
                        }
                    }

                    // 3. Tích lũy số lượng từng loại ca trong tuần trước để luân phiên đồng đều
                    for (GuardShift ps : guardHistory.values()) {
                        if (ps.getShiftType() == ShiftType.SHIFT_MORNING) state.morningCount++;
                        else if (ps.getShiftType() == ShiftType.SHIFT_AFTERNOON) state.afternoonCount++;
                        else if (ps.getShiftType() == ShiftType.SHIFT_NIGHT) state.nightCount++;
                    }

                    return state;
                })
                .collect(Collectors.toList());

        List<GuardShift> createdShifts = new ArrayList<>();
        LocalDate currentDate = request.getStartDate();
        int dayIndex = 0;

        while (!currentDate.isAfter(effectiveEndDate)) {
            int dow = mapToDayOfWeekInt(currentDate.getDayOfWeek());
            boolean isSunday = (currentDate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);

            int dayMorning = (isSunday && customSunday && suM != null) ? suM : nMorning;
            int dayAfternoon = (isSunday && customSunday && suA != null) ? suA : nAfternoon;
            int dayNight = (isSunday && customSunday && suN != null) ? suN : nNight;

            int totalDayDemand = dayMorning + dayAfternoon + dayNight;
            int numRestingToday = Math.max(0, m - totalDayDemand);

            // 1. Chọn trước các nhân viên ĐƯỢC NGHỈ trong ngày hôm nay:
            // Ưu tiên:
            // a. Vừa trực ca Đêm hôm qua (để được nghỉ ngơi hồi phục thể lực)
            // b. Cân bằng tải tuyệt đối: Người có số ca nhiều hơn bắt buộc phải được nghỉ trước
            // c. Nghỉ 2 ngày liền kề (consecutiveRestDays >= 1)
            // d. Người đã làm việc nhiều ngày liên tục
            Set<UUID> restingGuardIdsToday = new HashSet<>();
            if (numRestingToday > 0) {
                List<GuardScheduleState> restCandidates = new ArrayList<>(guardStates);
                restCandidates.sort((a, b) -> {
                    // a. Vừa trực ca đêm hôm qua: Ưu tiên nghỉ ngơi hồi phục thể lực cao nhất
                    boolean aNightYest = (a.yesterdayShift == ShiftType.SHIFT_NIGHT);
                    boolean bNightYest = (b.yesterdayShift == ShiftType.SHIFT_NIGHT);
                    if (aNightYest != bNightYest) {
                        return aNightYest ? -1 : 1;
                    }

                    // b. Người có nhiều ca làm việc tích lũy hơn phải được nghỉ trước để cân bằng tải
                    if (a.totalAssigned != b.totalAssigned) {
                        return Integer.compare(b.totalAssigned, a.totalAssigned);
                    }

                    // c. Ưu tiên nghỉ 2 ngày liền kề (nếu hôm qua đã nghỉ)
                    if (a.consecutiveRestDays != b.consecutiveRestDays) {
                        return Integer.compare(b.consecutiveRestDays, a.consecutiveRestDays);
                    }

                    // d. Đã làm việc nhiều ngày liên tục
                    if (a.consecutiveWorkDays != b.consecutiveWorkDays) {
                        return Integer.compare(b.consecutiveWorkDays, a.consecutiveWorkDays);
                    }

                    return a.guard.getId().compareTo(b.guard.getId());
                });

                for (int i = 0; i < Math.min(numRestingToday, restCandidates.size()); i++) {
                    restingGuardIdsToday.add(restCandidates.get(i).guard.getId());
                }
            }

            // 2. Danh sách nhân sự đi làm trong ngày
            List<GuardScheduleState> workingGuardsToday = guardStates.stream()
                    .filter(s -> !restingGuardIdsToday.contains(s.guard.getId()))
                    .collect(Collectors.toList());

            // 3. Đan xen các ca trực (M, A, N, M, A, N...) để phân bổ người nghỉ đều vào Sáng, Chiều, Đêm
            List<ShiftType> daySlots = new ArrayList<>();
            int mRem = dayMorning;
            int aRem = dayAfternoon;
            int nRem = dayNight;
            while (mRem > 0 || aRem > 0 || nRem > 0) {
                if (mRem > 0) { daySlots.add(ShiftType.SHIFT_MORNING); mRem--; }
                if (aRem > 0) { daySlots.add(ShiftType.SHIFT_AFTERNOON); aRem--; }
                if (nRem > 0) { daySlots.add(ShiftType.SHIFT_NIGHT); nRem--; }
            }

            for (ShiftType shiftType : daySlots) {
                List<GuardScheduleState> availableCandidates = workingGuardsToday.stream()
                        .filter(s -> s.todayShift == null)
                        .collect(Collectors.toList());

                if (availableCandidates.isEmpty()) {
                    break;
                }

                List<GuardScheduleState> eligibleCandidates = availableCandidates;
                // Ràng buộc an toàn sức khỏe tuyệt đối: Ca sáng KHÔNG BAO GIỜ nhận người vừa trực đêm hôm qua nếu có ứng viên khác
                if (shiftType == ShiftType.SHIFT_MORNING) {
                    List<GuardScheduleState> nonNightCandidates = availableCandidates.stream()
                            .filter(s -> s.yesterdayShift != ShiftType.SHIFT_NIGHT)
                            .collect(Collectors.toList());
                    if (!nonNightCandidates.isEmpty()) {
                        eligibleCandidates = nonNightCandidates;
                    }
                }

                eligibleCandidates.sort((a, b) -> {
                    // 1. Ràng buộc an toàn sức khỏe cứng: Ca sáng không nhận người vừa trực đêm hôm qua
                    if (shiftType == ShiftType.SHIFT_MORNING) {
                        boolean aHadNight = (a.yesterdayShift == ShiftType.SHIFT_NIGHT);
                        boolean bHadNight = (b.yesterdayShift == ShiftType.SHIFT_NIGHT);
                        if (aHadNight != bHadNight) {
                            return aHadNight ? 1 : -1;
                        }
                    }

                    // 2. Cân bằng tải tuyệt đối (Min-shift priority)
                    if (a.totalAssigned != b.totalAssigned) {
                        return Integer.compare(a.totalAssigned, b.totalAssigned);
                    }

                    // 3. Ưu tiên nghỉ ngơi: Ca chiều sau đêm hôm qua
                    if (shiftType == ShiftType.SHIFT_AFTERNOON) {
                        boolean aHadNight = (a.yesterdayShift == ShiftType.SHIFT_NIGHT);
                        boolean bHadNight = (b.yesterdayShift == ShiftType.SHIFT_NIGHT);
                        if (aHadNight != bHadNight) {
                            return aHadNight ? 1 : -1;
                        }
                    }

                    // 4. Cân bằng loại ca (Morning / Afternoon / Night)
                    int aTypeCount = a.getShiftTypeCount(shiftType);
                    int bTypeCount = b.getShiftTypeCount(shiftType);
                    if (aTypeCount != bTypeCount) {
                        return Integer.compare(aTypeCount, bTypeCount);
                    }

                    // 5. Cân bằng số ngày làm liên tục
                    if (a.consecutiveWorkDays != b.consecutiveWorkDays) {
                        return Integer.compare(a.consecutiveWorkDays, b.consecutiveWorkDays);
                    }

                    // 6. Ổn định
                    return a.guard.getId().compareTo(b.guard.getId());
                });

                GuardScheduleState chosen = eligibleCandidates.get(0);
                chosen.recordShift(shiftType);
                User guard = chosen.guard;

                LocalTime startTime;
                LocalTime endTime;
                switch (shiftType) {
                    case SHIFT_MORNING:
                        startTime = LocalTime.of(6, 0);
                        endTime = LocalTime.of(14, 0);
                        break;
                    case SHIFT_AFTERNOON:
                        startTime = LocalTime.of(14, 0);
                        endTime = LocalTime.of(22, 0);
                        break;
                    case SHIFT_NIGHT:
                        startTime = LocalTime.of(22, 0);
                        endTime = LocalTime.of(6, 0);
                        break;
                    default:
                        continue;
                }

                boolean exists = shiftRepository.existsByGuardIdAndShiftDateAndStartTime(
                        guard.getId(), currentDate, startTime
                );

                if (!exists) {
                    GuardShift shift = GuardShift.builder()
                            .guard(guard)
                            .shiftDate(currentDate)
                            .shiftType(shiftType)
                            .startTime(startTime)
                            .endTime(endTime)
                            .area(targetArea)
                            .status(ShiftStatus.SCHEDULED)
                            .isOvertime(false)
                            .notes(null)
                            .build();
                    createdShifts.add(shiftRepository.save(shift));

                    if (Boolean.TRUE.equals(request.getSaveAsTemplate()) && dayIndex < 7) {
                        boolean tExists = templateRepository.existsByGuardIdAndDayOfWeekAndStartTimeAndIsActiveTrue(
                                 guard.getId(), dow, startTime
                        );
                        if (!tExists) {
                            GuardScheduleTemplate template = GuardScheduleTemplate.builder()
                                    .guard(guard)
                                    .dayOfWeek(dow)
                                    .shiftType(shiftType)
                                    .startTime(startTime)
                                    .endTime(endTime)
                                    .area(targetArea)
                                    .isActive(true)
                                    .notes(null)
                                    .build();
                            templateRepository.save(template);
                        }
                    }
                }
            }

            // Chốt kết quả ngày và chuẩn bị cho ngày tiếp theo
            for (GuardScheduleState state : guardStates) {
                state.endDay();
            }

            currentDate = currentDate.plusDays(1);
            dayIndex++;
        }

        log.info("Wizard generated {} shifts for {} guards across {} days",
                createdShifts.size(), m, dayIndex);

        return createdShifts.stream().map(this::mapShiftToDto).collect(Collectors.toList());
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
                .isOvertime(Boolean.TRUE.equals(request.getIsOvertime()))
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
        if (request.getIsOvertime() != null) {
            shift.setIsOvertime(request.getIsOvertime());
        }
        shift.setNotes(request.getNotes());

        GuardShift saved = shiftRepository.save(shift);
        return mapShiftToDto(saved);
    }

    @Transactional
    public void deleteShift(UUID id) {
        GuardShift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ca trực cần xóa"));

        if (shift.getStatus() == ShiftStatus.CHECKED_IN || shift.getStatus() == ShiftStatus.COMPLETED) {
            throw new IllegalStateException("Không thể xóa ca trực đang diễn ra hoặc đã hoàn thành");
        }

        // Nullify foreign key references in shiftRequestRepository before deletion to preserve audit history
        List<GuardShiftRequest> linkedRequests = shiftRequestRepository.findAll().stream()
                .filter(r -> (r.getShift() != null && r.getShift().getId().equals(id)) ||
                             (r.getTargetShift() != null && r.getTargetShift().getId().equals(id)))
                .toList();

        for (GuardShiftRequest r : linkedRequests) {
            if (r.getStatus() == GuardShiftRequestStatus.PENDING) {
                r.setStatus(GuardShiftRequestStatus.CANCELLED);
                r.setReviewNotes("Ca trực đã bị Quản lý xóa khỏi lịch phân công.");
            }
            if (r.getShift() != null && r.getShift().getId().equals(id)) {
                r.setShift(null);
            }
            if (r.getTargetShift() != null && r.getTargetShift().getId().equals(id)) {
                r.setTargetShift(null);
            }
            shiftRequestRepository.save(r);
        }

        shiftRepository.delete(shift);
        log.info("Admin/FM deleted shift [{}]", id);
    }

    @Transactional
    public BulkClearShiftsResponse bulkClearShifts(BulkClearShiftsRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("Ngày bắt đầu và ngày kết thúc không được để trống");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("Ngày kết thúc không được trước ngày bắt đầu");
        }

        ShiftStatus targetStatus = Boolean.FALSE.equals(request.getOnlyScheduled()) ? null : ShiftStatus.SCHEDULED;
        String buildingParam = (request.getBuilding() == null || request.getBuilding().isBlank() || "ALL".equalsIgnoreCase(request.getBuilding().trim()))
                ? null : request.getBuilding().trim();

        List<GuardShift> candidateShifts = shiftRepository.findShifts(
                request.getStartDate(),
                request.getEndDate(),
                null,
                buildingParam,
                targetStatus
        );

        if (request.getTeamId() != null) {
            List<User> teamMembers = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(request.getTeamId());
            Set<UUID> memberIds = teamMembers.stream().map(User::getId).collect(Collectors.toSet());

            List<GuardTeamDispatch> dispatches = dispatchRepository.findByToTeamIdAndStatus(request.getTeamId(), GuardDispatchStatus.ACTIVE);
            for (GuardTeamDispatch d : dispatches) {
                if (d.getGuard() != null) {
                    memberIds.add(d.getGuard().getId());
                }
            }

            candidateShifts = candidateShifts.stream()
                    .filter(s -> s.getGuard() != null && memberIds.contains(s.getGuard().getId()))
                    .collect(Collectors.toList());
        }

        List<GuardShift> shiftsToDelete = candidateShifts.stream()
                .filter(s -> s.getStatus() == ShiftStatus.SCHEDULED)
                .collect(Collectors.toList());

        Set<UUID> idsToDelete = shiftsToDelete.stream().map(GuardShift::getId).collect(Collectors.toSet());
        if (!idsToDelete.isEmpty()) {
            List<GuardShiftRequest> linkedRequests = shiftRequestRepository.findAll().stream()
                    .filter(r -> (r.getShift() != null && idsToDelete.contains(r.getShift().getId())) ||
                                 (r.getTargetShift() != null && idsToDelete.contains(r.getTargetShift().getId())))
                    .toList();
            for (GuardShiftRequest r : linkedRequests) {
                if (r.getStatus() == GuardShiftRequestStatus.PENDING) {
                    r.setStatus(GuardShiftRequestStatus.CANCELLED);
                    r.setReviewNotes("Lịch tuần đã được xóa hàng loạt bởi Quản lý.");
                }
                if (r.getShift() != null && idsToDelete.contains(r.getShift().getId())) {
                    r.setShift(null);
                }
                if (r.getTargetShift() != null && idsToDelete.contains(r.getTargetShift().getId())) {
                    r.setTargetShift(null);
                }
                shiftRequestRepository.save(r);
            }
        }

        int count = shiftsToDelete.size();
        if (count > 0) {
            shiftRepository.deleteAll(shiftsToDelete);
        }

        log.info("Bulk cleared {} scheduled shifts from {} to {} (teamId: {}, building: {})",
                count, request.getStartDate(), request.getEndDate(), request.getTeamId(), buildingParam);

        return BulkClearShiftsResponse.builder()
                .clearedCount(count)
                .message(String.format("Đã xóa thành công %d ca trực chưa diễn ra.", count))
                .build();
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
                .isOvertime(s.getIsOvertime())
                .build();
    }
}
