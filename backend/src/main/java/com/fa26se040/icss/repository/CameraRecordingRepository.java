package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.CameraRecording;
import com.fa26se040.icss.enums.RecordingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraRecordingRepository extends JpaRepository<CameraRecording, UUID> {

    Optional<CameraRecording> findByStorageKey(String storageKey);

    List<CameraRecording> findAllByStatusOrderByStartTimeDesc(RecordingStatus status);

    List<CameraRecording> findAllByCameraCodeIgnoreCaseAndStatusOrderByStartTimeDesc(
            String cameraCode,
            RecordingStatus status
    );

    List<CameraRecording> findAllByIncident_IdOrderByStartTimeDesc(UUID incidentId);
}
