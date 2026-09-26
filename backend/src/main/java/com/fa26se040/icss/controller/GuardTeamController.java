package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
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
    public ResponseEntity<ApiResponse<List<GuardTeamDto>>> getAllTeams() {
        return ResponseEntity.ok(ApiResponse.success(guardTeamService.getAllTeams(), "Lấy danh sách đội bảo vệ thành công"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GuardTeamDto>> getTeamById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(guardTeamService.getTeamById(id), "Lấy thông tin đội bảo vệ thành công"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GuardTeamDto>> createTeam(@Valid @RequestBody GuardTeamCreateRequest request) {
        GuardTeamDto created = guardTeamService.createTeam(request);
        return new ResponseEntity<>(ApiResponse.created(created, "Tạo đội bảo vệ mới thành công"), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<GuardTeamDto>> updateTeam(
            @PathVariable UUID id,
            @Valid @RequestBody GuardTeamCreateRequest request
    ) {
        GuardTeamDto updated = guardTeamService.updateTeam(id, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Cập nhật đội bảo vệ thành công"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTeam(@PathVariable UUID id) {
        guardTeamService.deleteTeam(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa đội bảo vệ thành công"));
    }

    @PutMapping("/{id}/members")
    public ResponseEntity<ApiResponse<GuardTeamDto>> assignMembers(
            @PathVariable UUID id,
            @Valid @RequestBody GuardTeamMemberAssignRequest request
    ) {
        GuardTeamDto result = guardTeamService.assignMembers(id, request);
        return ResponseEntity.ok(ApiResponse.success(result, "Phân công thành viên đội bảo vệ thành công"));
    }

    @GetMapping("/unassigned-guards")
    public ResponseEntity<ApiResponse<List<GuardTeamDto.TeamMemberDto>>> getUnassignedGuards() {
        return ResponseEntity.ok(ApiResponse.success(guardTeamService.getAvailableGuardsWithoutTeam(), "Lấy danh sách bảo vệ chưa phân đội thành công"));
    }
}
