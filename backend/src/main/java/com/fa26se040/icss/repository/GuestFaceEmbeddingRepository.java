package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuestFaceEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface GuestFaceEmbeddingRepository extends JpaRepository<GuestFaceEmbedding, UUID> {

    /** BR-GV-17, 27: embedding đã tới expires_at. */
    @Query("SELECT e.guestId FROM GuestFaceEmbedding e WHERE e.expiresAt <= :now")
    List<UUID> findGuestIdsExpiredAt(@Param("now") OffsetDateTime now);
}
