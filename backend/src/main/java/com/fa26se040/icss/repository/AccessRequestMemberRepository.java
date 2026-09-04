package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequestMember;
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
}
