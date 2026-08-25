package com.fa26se040.icss.security.repository;

import com.fa26se040.icss.security.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByEmailAndIsActiveTrue(String email);

    Optional<User> findByGoogleSub(String googleSub);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email) AND u.canLogin = true AND u.isActive = true AND u.deletedAt IS NULL")
    Optional<User> findActiveAuthorizedUserByEmail(@Param("email") String email);
}
