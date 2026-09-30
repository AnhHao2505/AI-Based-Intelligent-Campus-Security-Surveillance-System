package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaDependencyResponse;
import com.fa26se040.icss.dto.area.AreaDependencyResponse.Blocker;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaEventScheduleRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Step 6 (BR-AD-08): hàm đánh giá vô hiệu hoá khu vực — DÙNG CHUNG cho xem trước (GET /dependencies)
 * và thực thi POST /deactivate (đánh giá lại sau khi khoá dòng areas). Chỉ đọc.
 */
@Component
@RequiredArgsConstructor
public class AreaDependencyChecker {

    private final CameraRepository cameraRepository;
    private final AreaRepository areaRepository;
    private final AreaEventScheduleRepository eventScheduleRepository;
    private final AreaAssignedPersonnelRepository assignedPersonnelRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final GuestVisitRepository guestVisitRepository;

    /**
     * Kết quả đánh giá. blockers giữ đúng thứ tự: camera (009) -> sự cố mở (051) -> sự kiện đang bật (052)
     * -> lịch chờ (042). Còn blocker thì KHÔNG được thu hồi / huỷ gì.
     */
    public record DeactivationEvaluation(
            List<Blocker> blockers,
            List<AreaAssignedPersonnel> apToRevoke,
            List<AccessRequest> requestsToCancel,
            List<GuestVisit> guestVisitsToCancel,
            List<GuestVisit> guestVisitsToRevoke
    ) {
        public boolean canDeactivate() {
            return blockers.isEmpty();
        }

        /** Lỗi của blocker đầu tiên, cùng mã + thông điệp với xem trước. */
        public AreaException firstBlockerException() {
            Blocker b = blockers.get(0);
            return new AreaException(b.errorCode(), b.message());
        }
    }

    public DeactivationEvaluation evaluate(Area area, OffsetDateTime now) {
        List<Blocker> blockers = new ArrayList<>();

        // BR-AD-06: camera còn gán (chưa xoá) — liệt kê mã + tên
        List<Camera> cameras = cameraRepository.findByAreaIdAndDeletedAtIsNull(area.getId());
        if (!cameras.isEmpty()) {
            String list = cameras.stream()
                    .map(c -> c.getCameraCode() + " – " + c.getName())
                    .collect(Collectors.joining(", "));
            blockers.add(new Blocker(AreaErrorCode.ERR_AREA_009, cameras.size(),
                    "Không thể vô hiệu hoá: còn " + cameras.size() + " camera đang gán (" + list + "). Gỡ camera khỏi khu vực trước"));
        }

        // BR-AD-02: sự cố NEW hoặc CLAIMED
        long openIncidents = areaRepository.countIncidentsByAreaIdAndStatusIn(area.getId(),
                List.of(IncidentStatus.NEW, IncidentStatus.CLAIMED));
        if (openIncidents > 0) {
            blockers.add(blocker(new AreaException(AreaErrorCode.ERR_AREA_051, openIncidents), openIncidents));
        }

        // BR-AD-03: chế độ sự kiện đang bật (open_to_members, chưa quá open_until)
        if (area.isEventActive(now)) {
            blockers.add(blocker(new AreaException(AreaErrorCode.ERR_AREA_052), null));
        }

        // BR-AD-03b: lịch SCHEDULED -> 042 (giữ nguyên thông điệp liệt kê lịch)
        List<AreaEventSchedule> schedules = eventScheduleRepository
                .findByAreaIdAndStatusOrderByStartAtAsc(area.getId(), AreaEventScheduleStatus.SCHEDULED);
        if (!schedules.isEmpty()) {
            blockers.add(blocker(new AreaException(AreaErrorCode.ERR_AREA_042,
                    AreaService.buildPendingSchedulesErrorMessage(schedules)), schedules.size()));
        }

        // BR-AD-04, 05, 09: những gì hệ thống sẽ tự xử lý
        List<AreaAssignedPersonnel> aps = assignedPersonnelRepository.findNotRevokedNotExpired(area.getId(), now);
        List<AccessRequest> requests = accessRequestRepository.findNotEndedByAreaWithParticipants(
                area.getId(), List.of(RequestStatus.PENDING, RequestStatus.APPROVED), now);
        List<GuestVisit> visits = guestVisitRepository.findNotEndedByArea(
                area.getId(), List.of(GuestVisitStatus.PENDING, GuestVisitStatus.APPROVED), now);

        return new DeactivationEvaluation(blockers, aps, requests,
                visits.stream().filter(v -> v.getStatus() == GuestVisitStatus.PENDING).toList(),
                visits.stream().filter(v -> v.getStatus() == GuestVisitStatus.APPROVED).toList());
    }

    public AreaDependencyResponse toResponse(Area area, DeactivationEvaluation e) {
        String note = e.canDeactivate()
                ? "Có thể vô hiệu hoá. Hệ thống sẽ tự thu hồi nhân sự chỉ định, huỷ đơn và lượt khách chưa kết thúc."
                : "Cần xử lý các mục chặn trước khi vô hiệu hoá.";
        return new AreaDependencyResponse(area.getId(), area.getVersion(), e.canDeactivate(), e.blockers(),
                e.apToRevoke().size(), e.requestsToCancel().size(),
                e.guestVisitsToCancel().size(), e.guestVisitsToRevoke().size(),
                List.of(), note);
    }

    private static Blocker blocker(AreaException ex, Object count) {
        return new Blocker(ex.getErrorCode(), count, ex.getMessage());
    }
}
