package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GuardChatMessageRepository extends JpaRepository<GuardChatMessage, UUID> {

    @Query("SELECT m FROM GuardChatMessage m " +
           "JOIN FETCH m.sender s " +
           "JOIN FETCH m.receiver r " +
           "WHERE (s.id = :u1 AND r.id = :u2) " +
           "   OR (s.id = :u2 AND r.id = :u1) " +
           "ORDER BY m.createdAt DESC")
    Page<GuardChatMessage> findConversation(@Param("u1") UUID u1, @Param("u2") UUID u2, Pageable pageable);

    @Modifying
    @Query("UPDATE GuardChatMessage m SET m.isRead = true " +
           "WHERE m.sender.id = :senderId AND m.receiver.id = :receiverId AND m.isRead = false")
    int markAsRead(@Param("senderId") UUID senderId, @Param("receiverId") UUID receiverId);

    @Query("SELECT COUNT(m) FROM GuardChatMessage m WHERE m.receiver.id = :receiverId AND m.isRead = false")
    long countUnreadTotal(@Param("receiverId") UUID receiverId);

    @Query("SELECT m.sender.id, COUNT(m) FROM GuardChatMessage m " +
           "WHERE m.receiver.id = :receiverId AND m.isRead = false GROUP BY m.sender.id")
    List<Object[]> countUnreadBySender(@Param("receiverId") UUID receiverId);

    @Query("SELECT m FROM GuardChatMessage m " +
           "JOIN FETCH m.sender s " +
           "JOIN FETCH m.receiver r " +
           "WHERE (s.id = :u1 AND r.id = :u2) OR (s.id = :u2 AND r.id = :u1) " +
           "ORDER BY m.createdAt DESC LIMIT 1")
    GuardChatMessage findLatestMessage(@Param("u1") UUID u1, @Param("u2") UUID u2);
}

