package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.FaceData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FaceDataRepository extends JpaRepository<FaceData, UUID> {

    Page<FaceData> findByCodeContainingIgnoreCase(String code, Pageable pageable);

    Optional<FaceData> findByCode(String code);

    Optional<FaceData> findByUserId(UUID userId);

    boolean existsByCode(String code);

    List<FaceData> findTop10ByOrderByCreatedAtDesc();
}
