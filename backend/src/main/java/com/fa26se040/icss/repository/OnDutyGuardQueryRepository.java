package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.enums.ShiftStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Truy vấn CHỈ ĐỌC trên guard_shifts phục vụ chọn bảo vệ đang trực nhận cảnh báo sự cố (BR-IE-02).
 *
 * <p>Kế thừa {@link Repository} (không phải JpaRepository) nên không có save/delete: module leo thang
 * không ghi dữ liệu ca trực. Không thay thế {@link GuardShiftRepository} — mọi nghiệp vụ ca trực
 * vẫn đi qua repository đó.</p>
 */
public interface OnDutyGuardQueryRepository extends Repository<GuardShift, UUID> {

    /**
     * Ca có trạng thái {@code status} với ngày ca trong [fromDate, toDate], kèm bảo vệ.
     * Lọc khung giờ (kể cả ca qua đêm) ở {@code IncidentRecipientResolver}.
     */
    @Query("SELECT s FROM GuardShift s " +
           "JOIN FETCH s.guard " +
           "WHERE s.status = :status " +
           "AND s.shiftDate BETWEEN :fromDate AND :toDate " +
           "ORDER BY s.shiftDate ASC, s.startTime ASC")
    List<GuardShift> findByStatusAndShiftDateBetween(@Param("status") ShiftStatus status,
                                                     @Param("fromDate") LocalDate fromDate,
                                                     @Param("toDate") LocalDate toDate);
}
