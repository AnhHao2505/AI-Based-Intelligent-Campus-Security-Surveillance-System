package com.fa26se040.icss.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailAndIsActiveTrue(String email);
    Optional<User> findByUserCode(String userCode);

    boolean existsByUserCode(String userCode);
    boolean existsByEmail(String email);

    @Query(
        value = """
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND (CAST(:keyword AS string) IS NULL OR :keyword = ''
                   OR LOWER(u.userCode) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            """,
        countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE u.deletedAt IS NULL
              AND (CAST(:keyword AS string) IS NULL OR :keyword = ''
                   OR LOWER(u.userCode) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            """
    )
    Page<User> searchUsers(@Param("keyword") String keyword, Pageable pageable);

    @Query(
        value = """
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND u.role IN :roles
              AND (:isActive IS NULL OR u.isActive = :isActive)
              AND (CAST(:keyword AS string) IS NULL OR :keyword = ''
                   OR LOWER(u.userCode) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            """,
        countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE u.deletedAt IS NULL
              AND u.role IN :roles
              AND (:isActive IS NULL OR u.isActive = :isActive)
              AND (CAST(:keyword AS string) IS NULL OR :keyword = ''
                   OR LOWER(u.userCode) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
            """
    )
    Page<User> searchFilteredUsers(
        @Param("keyword") String keyword,
        @Param("roles") Collection<Role> roles,
        @Param("isActive") Boolean isActive,
        Pageable pageable
    );

    @Query("SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL AND u.role = :role")
    long countByRoleAndDeletedAtIsNull(@Param("role") Role role);

    @Query("SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL AND u.role IN :roles")
    long countByRolesAndDeletedAtIsNull(@Param("roles") Collection<Role> roles);
}


