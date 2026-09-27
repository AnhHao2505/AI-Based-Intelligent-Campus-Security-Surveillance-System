package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AuditModuleRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditModuleRoleRepository extends JpaRepository<AuditModuleRole, AuditModuleRole.Id> {

    @Query("SELECT r.id.module FROM AuditModuleRole r WHERE r.id.role = :role")
    List<String> findModulesByRole(@Param("role") String role);
}
