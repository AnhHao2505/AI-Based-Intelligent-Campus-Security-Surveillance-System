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
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailAndIsActiveTrue(String email);
    Optional<User> findByUserCode(String userCode);
    Optional<User> findByUserCodeAndDeletedAtIsNull(String userCode);

    boolean existsByUserCode(String userCode);
    boolean existsByEmail(String email);

    boolean existsByUserCodeAndDeletedAtIsNull(String userCode);
    boolean existsByEmailAndDeletedAtIsNull(String email);

    @Query("SELECT UPPER(u.userCode) FROM User u WHERE u.deletedAt IS NULL AND UPPER(u.userCode) IN :userCodes")
    Set<String> findExistingUserCodes(@Param("userCodes") Collection<String> userCodes);

    @Query("SELECT LOWER(u.email) FROM User u WHERE u.deletedAt IS NULL AND LOWER(u.email) IN :emails")
    Set<String> findExistingEmails(@Param("emails") Collection<String> emails);

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

    @Query(
        value = """
            SELECT new com.fa26se040.icss.dto.user.ImportBatchSummaryResponse(
                u.importBatchId,
                COUNT(CASE WHEN u.deletedAt IS NULL THEN 1 ELSE null END),
                COUNT(CASE WHEN u.deletedAt IS NOT NULL THEN 1 ELSE null END),
                MIN(u.createdAt)
            )
            FROM User u
            WHERE u.importBatchId IS NOT NULL
            GROUP BY u.importBatchId
            ORDER BY MIN(u.createdAt) DESC
            """,
        countQuery = "SELECT COUNT(DISTINCT u.importBatchId) FROM User u WHERE u.importBatchId IS NOT NULL"
    )
    Page<com.fa26se040.icss.dto.user.ImportBatchSummaryResponse> findImportBatchSummaries(Pageable pageable);

    boolean existsByImportBatchId(UUID importBatchId);

    java.util.List<User> findByImportBatchIdOrderByUserCodeAsc(UUID importBatchId);

    java.util.List<User> findByImportBatchIdAndDeletedAtIsNotNullOrderByUserCodeAsc(UUID importBatchId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE users SET deleted_at = NOW(), is_active = false, updated_at = NOW() WHERE import_batch_id = :batchId AND deleted_at IS NULL", nativeQuery = true)
    int softDeleteByImportBatchId(@Param("batchId") UUID batchId);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.deletedAt IS NULL AND u.id <> :userId AND UPPER(TRIM(u.userCode)) = :userCode")
    boolean existsActiveByUserCodeUpperAndIdNot(@Param("userCode") String userCode, @Param("userId") UUID userId);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.deletedAt IS NULL AND u.id <> :userId AND LOWER(TRIM(u.email)) = :email")
    boolean existsActiveByEmailLowerAndIdNot(@Param("email") String email, @Param("userId") UUID userId);
}


