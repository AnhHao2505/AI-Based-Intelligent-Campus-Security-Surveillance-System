package com.fa26se040.security.repository;

import com.fa26se040.security.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailAndIsActiveTrue(String email);
    Optional<User> findByUserCode(String userCode);
}
