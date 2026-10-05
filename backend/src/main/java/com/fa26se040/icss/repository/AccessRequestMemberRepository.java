package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequestMember;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AccessRequestMemberRepository extends JpaRepository<AccessRequestMember, UUID> {

    @Query("SELECT m FROM AccessRequestMember m JOIN FETCH m.user WHERE m.accessRequest.id = :accessRequestId")
    List<AccessRequestMember> findByAccessRequestIdWithUser(@Param("accessRequestId") UUID accessRequestId);

    /**
     * Thành viên (đúng role, đang hoạt động, chưa xoá) trong các đơn do chính requester tạo, đơn mới nhất trước
     * (gợi ý "thành viên gần đây"). Service vẫn lọc lại bằng AccessRequestService.isEligibleMember.
     */
    @Query("SELECT m FROM AccessRequestMember m JOIN FETCH m.user u JOIN m.accessRequest r "
            + "WHERE r.requester.id = :requesterId AND u.role = :role "
            + "AND (u.isActive IS NULL OR u.isActive = true) AND u.deletedAt IS NULL "
            + "ORDER BY r.createdAt DESC")
    List<AccessRequestMember> findRecentByRequesterIdWithUser(@Param("requesterId") UUID requesterId,
                                                              @Param("role") com.fa26se040.icss.enums.Role role,
                                                              Pageable pageable);
}
