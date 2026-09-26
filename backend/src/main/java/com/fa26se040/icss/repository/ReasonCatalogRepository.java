package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.ReasonCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReasonCatalogRepository extends JpaRepository<ReasonCatalog, UUID> {

    Optional<ReasonCatalog> findByActionTypeAndCode(String actionType, String code);

    Optional<ReasonCatalog> findByCode(String code);

    boolean existsByActionTypeAndCode(String actionType, String code);

    boolean existsByActionTypeAndIsOtherTrue(String actionType);

    Optional<ReasonCatalog> findByActionTypeAndIsOtherTrue(String actionType);

    List<ReasonCatalog> findByActionTypeOrderBySortOrderAscCreatedAtAsc(String actionType);

    List<ReasonCatalog> findByActionTypeAndIsActiveTrueOrderBySortOrderAscCreatedAtAsc(String actionType);

    List<ReasonCatalog> findAllByOrderByActionTypeAscSortOrderAscCreatedAtAsc();

    List<ReasonCatalog> findByIsActiveOrderByActionTypeAscSortOrderAscCreatedAtAsc(Boolean isActive);

    List<ReasonCatalog> findByActionTypeAndIsActiveOrderBySortOrderAscCreatedAtAsc(String actionType, Boolean isActive);
}
