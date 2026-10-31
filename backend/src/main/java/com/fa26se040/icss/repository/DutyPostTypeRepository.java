package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.DutyPostType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DutyPostTypeRepository extends JpaRepository<DutyPostType, String> {

    List<DutyPostType> findAllByOrderBySortOrderAscCodeAsc();

    List<DutyPostType> findByIsActiveTrueOrderBySortOrderAscCodeAsc();
}
