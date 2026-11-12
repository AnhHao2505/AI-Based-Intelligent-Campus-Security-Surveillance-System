package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.EdgeDeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface EdgeDeviceStatusRepository extends JpaRepository<EdgeDeviceStatus, String> {

    List<EdgeDeviceStatus> findByStatusAndLastSeenBefore(String status, OffsetDateTime cutoff);
}
