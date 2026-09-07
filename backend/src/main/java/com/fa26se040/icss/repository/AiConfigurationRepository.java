package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AiConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiConfigurationRepository extends JpaRepository<AiConfiguration, UUID> {
    Optional<AiConfiguration> findFirstByOrderByIdAsc();
}
