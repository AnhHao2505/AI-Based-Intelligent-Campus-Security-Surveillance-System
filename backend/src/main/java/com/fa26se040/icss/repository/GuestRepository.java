package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.enums.GuestBiometricStatus;
import com.fa26se040.icss.enums.GuestVisitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuestRepository extends JpaRepository<Guest, UUID> {

    List<Guest> findByVisitIdOrderByCreatedAtAscIdAsc(UUID visitId);

    Optional<Guest> findByIdAndVisitId(UUID id, UUID visitId);

    /** BR-GV-27: khách còn ảnh / embedding mà lượt đã CANCELLED / REVOKED / EXPIRED, hoặc đã quá end + retention. */
    @Query("SELECT g.id FROM Guest g JOIN g.visit v WHERE g.biometricStatus IN :withPhoto "
            + "AND (v.status IN :closedStatuses OR v.endTime <= :endBefore)")
    List<UUID> findIdsNeedingBiometricDeletion(@Param("withPhoto") Collection<GuestBiometricStatus> withPhoto,
                                               @Param("closedStatuses") Collection<GuestVisitStatus> closedStatuses,
                                               @Param("endBefore") OffsetDateTime endBefore);

    @Query("SELECT g.id FROM Guest g WHERE g.pendingDeleteObjectKey IS NOT NULL")
    List<UUID> findIdsWithPendingPhotoDeletion();

    /** BR-GV-29: khách của lượt đã kết thúc trước :endBefore, chưa ẩn danh. */
    @Query("SELECT g.id FROM Guest g JOIN g.visit v WHERE g.anonymizedAt IS NULL AND v.endTime <= :endBefore")
    List<UUID> findIdsToAnonymize(@Param("endBefore") OffsetDateTime endBefore);
}
