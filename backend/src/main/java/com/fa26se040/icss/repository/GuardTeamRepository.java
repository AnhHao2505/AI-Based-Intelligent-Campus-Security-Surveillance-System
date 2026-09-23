package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardTeamRepository extends JpaRepository<GuardTeam, UUID> {
    List<GuardTeam> findByIsActiveTrueOrderByTeamNameAsc();
    Optional<GuardTeam> findByTeamNameIgnoreCase(String teamName);
    boolean existsByTeamNameIgnoreCase(String teamName);
    boolean existsByTeamNameIgnoreCaseAndIdNot(String teamName, UUID id);
}
