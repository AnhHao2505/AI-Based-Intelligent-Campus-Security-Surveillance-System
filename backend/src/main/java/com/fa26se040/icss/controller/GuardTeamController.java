package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.guard.GuardTeamCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDto;
import com.fa26se040.icss.dto.guard.GuardTeamMemberAssignRequest;
import com.fa26se040.icss.service.GuardTeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/guard-teams")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
public class GuardTeamController {

    private final GuardTeamService guardTeamService;

    @GetMapping
    public ResponseEntity<List<GuardTeamDto>> getAllTeams() {
        return ResponseEntity.ok(guardTeamService.getAllTeams());
    }

    @GetMapping("/{id}")
    public ResponseEntity<GuardTeamDto> getTeamById(@PathVariable UUID id) {
        return ResponseEntity.ok(guardTeamService.getTeamById(id));
    }

    @PostMapping
    public ResponseEntity<GuardTeamDto> createTeam(@Valid @RequestBody GuardTeamCreateRequest request) {
        GuardTeamDto created = guardTeamService.createTeam(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<GuardTeamDto> updateTeam(
            @PathVariable UUID id,
            @Valid @RequestBody GuardTeamCreateRequest request
    ) {
        return ResponseEntity.ok(guardTeamService.updateTeam(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeam(@PathVariable UUID id) {
        guardTeamService.deleteTeam(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/members")
    public ResponseEntity<GuardTeamDto> assignMembers(
            @PathVariable UUID id,
            @Valid @RequestBody GuardTeamMemberAssignRequest request
    ) {
        return ResponseEntity.ok(guardTeamService.assignMembers(id, request));
    }

    @GetMapping("/unassigned-guards")
    public ResponseEntity<List<GuardTeamDto.TeamMemberDto>> getUnassignedGuards() {
        return ResponseEntity.ok(guardTeamService.getAvailableGuardsWithoutTeam());
    }
}
