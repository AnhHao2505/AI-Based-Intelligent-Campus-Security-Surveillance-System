package com.fa26se040.icss.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.repository.UserRepository;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/{code}")
    public ResponseEntity<UserInfo> getUserByCode(@PathVariable String code) {
        log.info("Received request to get user by code: {}", code);
        return userRepository.findByUserCode(code)
                .map(user -> ResponseEntity.ok(UserInfo.builder()
                        .id(user.getId())
                        .fullName(user.getFullName())
                        .email(user.getEmail())
                        .role(user.getRole() != null ? user.getRole().name() : null)
                        .userCode(user.getUserCode())
                        .build()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
