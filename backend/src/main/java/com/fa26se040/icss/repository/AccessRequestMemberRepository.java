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

    /** Thành viên trong các đơn do chính requester tạo, đơn mới nhất trước (gợi ý "thành viên gần đây"). */
    @Query("SELECT m FROM AccessRequestMember m JOIN FETCH m.user JOIN m.accessRequest r "
            + "WHERE r.requester.id = :requesterId ORDER BY r.createdAt DESC")
    List<AccessRequestMember> findRecentByRequesterIdWithUser(@Param("requesterId") UUID requesterId, Pageable pageable);
}
