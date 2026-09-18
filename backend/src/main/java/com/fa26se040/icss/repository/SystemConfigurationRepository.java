package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.SystemConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemConfigurationRepository extends JpaRepository<SystemConfiguration, String> {

    List<SystemConfiguration> findAllByOrderByConfigGroupAscDisplayOrderAsc();
}
