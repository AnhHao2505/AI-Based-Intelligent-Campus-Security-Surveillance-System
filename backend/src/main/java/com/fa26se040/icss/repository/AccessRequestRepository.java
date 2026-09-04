package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccessRequestRepository extends JpaRepository<AccessRequest, UUID> {

    @Query("SELECT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.area " +
           "JOIN FETCH ar.requester " +
           "LEFT JOIN FETCH ar.reviewer " +
           "WHERE ar.id = :id")
    Optional<AccessRequest> findByIdWithDetails(@Param("id") UUID id);

    @Query(value = "SELECT ar FROM AccessRequest ar " +
                   "JOIN FETCH ar.area " +
                   "WHERE ar.requester.id = :requesterId " +
                   "AND (:status IS NULL OR ar.status = :status)",
           countQuery = "SELECT COUNT(ar) FROM AccessRequest ar " +
                        "WHERE ar.requester.id = :requesterId " +
                        "AND (:status IS NULL OR ar.status = :status)")
    Page<AccessRequest> findMyRequests(
            @Param("requesterId") UUID requesterId,
            @Param("status") RequestStatus status,
            Pageable pageable
    );

    @Query(value = "SELECT ar FROM AccessRequest ar " +
                   "JOIN FETCH ar.area " +
                   "JOIN FETCH ar.requester " +
                   "WHERE (:status IS NULL OR ar.status = :status)",
           countQuery = "SELECT COUNT(ar) FROM AccessRequest ar " +
                        "WHERE (:status IS NULL OR ar.status = :status)")
    Page<AccessRequest> findAllRequests(
            @Param("status") RequestStatus status,
            Pageable pageable
    );
}
