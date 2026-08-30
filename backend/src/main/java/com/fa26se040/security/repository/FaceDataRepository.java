package com.fa26se040.security.repository;

import com.fa26se040.security.entity.FaceData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FaceDataRepository extends JpaRepository<FaceData, UUID> {

    Page<FaceData> findByCodeContainingIgnoreCaseOrFullNameContainingIgnoreCase(
            String code, String fullName, Pageable pageable);

    Optional<FaceData> findByCode(String code);

    boolean existsByCode(String code);

    List<FaceData> findTop10ByOrderByCreatedAtDesc();
}
