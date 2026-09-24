package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.GuardTeamCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDto;
import com.fa26se040.icss.dto.guard.GuardTeamMemberAssignRequest;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.GuardTeamRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GuardTeamService {

    private final GuardTeamRepository teamRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<GuardTeamDto> getAllTeams() {
        return teamRepository.findByIsActiveTrueOrderByTeamNameAsc().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public GuardTeamDto getTeamById(UUID id) {
        GuardTeam team = teamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tổ đội bảo vệ"));
        return mapToDto(team);
    }

    @Transactional
    public GuardTeamDto createTeam(GuardTeamCreateRequest request) {
        String trimmedName = request.getTeamName().trim();
        java.util.Optional<GuardTeam> existingOpt = teamRepository.findByTeamNameIgnoreCase(trimmedName);
        if (existingOpt.isPresent()) {
            GuardTeam existing = existingOpt.get();
            if (Boolean.TRUE.equals(existing.getIsActive())) {
                throw new IllegalArgumentException("Tên tổ đội đã tồn tại trong hệ thống");
            } else {
                // Tái kích hoạt đội đã từng bị xóa trước đó
                existing.setIsActive(true);
                existing.setDescription(request.getDescription());
                GuardTeam saved = teamRepository.save(existing);
                log.info("Re-activated previously deleted guard team [{}] with ID [{}]", saved.getTeamName(), saved.getId());
                return mapToDto(saved);
            }
        }

        GuardTeam team = GuardTeam.builder()
                .teamName(trimmedName)
                .description(request.getDescription())
                .isActive(true)
                .weekdayMorningDemand(request.getWeekdayMorningDemand() != null ? request.getWeekdayMorningDemand() : 3)
                .weekdayAfternoonDemand(request.getWeekdayAfternoonDemand() != null ? request.getWeekdayAfternoonDemand() : 4)
                .weekdayNightDemand(request.getWeekdayNightDemand() != null ? request.getWeekdayNightDemand() : 2)
                .sundayMorningDemand(request.getSundayMorningDemand() != null ? request.getSundayMorningDemand() : 2)
                .sundayAfternoonDemand(request.getSundayAfternoonDemand() != null ? request.getSundayAfternoonDemand() : 2)
                .sundayNightDemand(request.getSundayNightDemand() != null ? request.getSundayNightDemand() : 2)
                .hasSundayCustom(request.getHasSundayCustom() != null ? request.getHasSundayCustom() : true)
                .build();

        GuardTeam saved = teamRepository.save(team);
        log.info("Created guard team [{}] with ID [{}]", saved.getTeamName(), saved.getId());
        return mapToDto(saved);
    }

    @Transactional
    public GuardTeamDto updateTeam(UUID id, GuardTeamCreateRequest request) {
        GuardTeam team = teamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tổ đội bảo vệ"));

        String trimmedName = request.getTeamName().trim();
        java.util.Optional<GuardTeam> duplicateOpt = teamRepository.findByTeamNameIgnoreCase(trimmedName);
        if (duplicateOpt.isPresent()) {
            GuardTeam duplicate = duplicateOpt.get();
            if (!duplicate.getId().equals(id)) {
                if (Boolean.TRUE.equals(duplicate.getIsActive())) {
                    throw new IllegalArgumentException("Tên tổ đội đã tồn tại trong hệ thống");
                } else {
                    // Nếu trùng tên với một đội cũ đã xóa, đổi tên đội cũ để giải phóng tên
                    duplicate.setTeamName(trimmedName + "_deleted_" + duplicate.getId().toString().substring(0, 8));
                    teamRepository.save(duplicate);
                }
            }
        }

        team.setTeamName(trimmedName);
        team.setDescription(request.getDescription());
        if (request.getWeekdayMorningDemand() != null) team.setWeekdayMorningDemand(request.getWeekdayMorningDemand());
        if (request.getWeekdayAfternoonDemand() != null) team.setWeekdayAfternoonDemand(request.getWeekdayAfternoonDemand());
        if (request.getWeekdayNightDemand() != null) team.setWeekdayNightDemand(request.getWeekdayNightDemand());
        if (request.getSundayMorningDemand() != null) team.setSundayMorningDemand(request.getSundayMorningDemand());
        if (request.getSundayAfternoonDemand() != null) team.setSundayAfternoonDemand(request.getSundayAfternoonDemand());
        if (request.getSundayNightDemand() != null) team.setSundayNightDemand(request.getSundayNightDemand());
        if (request.getHasSundayCustom() != null) team.setHasSundayCustom(request.getHasSundayCustom());

        GuardTeam saved = teamRepository.save(team);
        log.info("Updated guard team [{}]", saved.getId());
        return mapToDto(saved);
    }

    @Transactional
    public void deleteTeam(UUID id) {
        GuardTeam team = teamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tổ đội bảo vệ"));

        // Soft delete
        team.setIsActive(false);
        teamRepository.save(team);

        // Clear team for assigned users
        List<User> members = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(id);
        for (User u : members) {
            u.setTeam(null);
        }
        userRepository.saveAll(members);
        log.info("Soft deleted guard team [{}] and detached {} members", id, members.size());
    }

    @Transactional
    public GuardTeamDto assignMembers(UUID teamId, GuardTeamMemberAssignRequest request) {
        GuardTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tổ đội bảo vệ"));

        // Update shift demands on team if provided
        if (request.getWeekdayMorningDemand() != null) team.setWeekdayMorningDemand(request.getWeekdayMorningDemand());
        if (request.getWeekdayAfternoonDemand() != null) team.setWeekdayAfternoonDemand(request.getWeekdayAfternoonDemand());
        if (request.getWeekdayNightDemand() != null) team.setWeekdayNightDemand(request.getWeekdayNightDemand());
        if (request.getSundayMorningDemand() != null) team.setSundayMorningDemand(request.getSundayMorningDemand());
        if (request.getSundayAfternoonDemand() != null) team.setSundayAfternoonDemand(request.getSundayAfternoonDemand());
        if (request.getSundayNightDemand() != null) team.setSundayNightDemand(request.getSundayNightDemand());
        if (request.getHasSundayCustom() != null) team.setHasSundayCustom(request.getHasSundayCustom());

        // Detach existing members of this team
        List<User> currentMembers = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamId);
        for (User u : currentMembers) {
            u.setTeam(null);
        }
        userRepository.saveAll(currentMembers);

        // Assign new members
        List<UUID> guardIds = request.getGuardIds();
        if (guardIds != null && !guardIds.isEmpty()) {
            List<User> newMembers = userRepository.findAllById(guardIds);
            for (User u : newMembers) {
                if (u.getRole() != Role.GUARD) {
                    throw new IllegalArgumentException("Người dùng [" + u.getFullName() + "] không phải là Bảo vệ");
                }
                u.setTeam(team);
            }
            userRepository.saveAll(newMembers);
            log.info("Assigned {} guards to team [{}]", newMembers.size(), team.getTeamName());
        }

        GuardTeam saved = teamRepository.save(team);
        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<GuardTeamDto.TeamMemberDto> getAvailableGuardsWithoutTeam() {
        List<User> guardsWithoutTeam = userRepository.findByRoleAndTeamIsNullAndDeletedAtIsNullAndIsActiveTrue(Role.GUARD);
        return guardsWithoutTeam.stream()
                .map(u -> GuardTeamDto.TeamMemberDto.builder()
                        .id(u.getId())
                        .userCode(u.getUserCode())
                        .fullName(u.getFullName())
                        .email(u.getEmail())
                        .build())
                .collect(Collectors.toList());
    }

    private GuardTeamDto mapToDto(GuardTeam team) {
        List<User> members = userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(team.getId());
        List<GuardTeamDto.TeamMemberDto> memberDtos = members.stream()
                .map(u -> GuardTeamDto.TeamMemberDto.builder()
                        .id(u.getId())
                        .userCode(u.getUserCode())
                        .fullName(u.getFullName())
                        .email(u.getEmail())
                        .build())
                .collect(Collectors.toList());

        return GuardTeamDto.builder()
                .id(team.getId())
                .teamName(team.getTeamName())
                .description(team.getDescription())
                .isActive(team.getIsActive())
                .weekdayMorningDemand(team.getWeekdayMorningDemand() != null ? team.getWeekdayMorningDemand() : 3)
                .weekdayAfternoonDemand(team.getWeekdayAfternoonDemand() != null ? team.getWeekdayAfternoonDemand() : 4)
                .weekdayNightDemand(team.getWeekdayNightDemand() != null ? team.getWeekdayNightDemand() : 2)
                .sundayMorningDemand(team.getSundayMorningDemand() != null ? team.getSundayMorningDemand() : 2)
                .sundayAfternoonDemand(team.getSundayAfternoonDemand() != null ? team.getSundayAfternoonDemand() : 2)
                .sundayNightDemand(team.getSundayNightDemand() != null ? team.getSundayNightDemand() : 2)
                .hasSundayCustom(team.getHasSundayCustom() != null ? team.getHasSundayCustom() : true)
                .memberCount(memberDtos.size())
                .members(memberDtos)
                .createdAt(team.getCreatedAt())
                .updatedAt(team.getUpdatedAt())
                .build();
    }
}
