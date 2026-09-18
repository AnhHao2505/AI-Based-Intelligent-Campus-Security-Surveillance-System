package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.guard.GenerateShiftsRequest;
import com.fa26se040.icss.dto.guard.GenerateShiftsResponse;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateCreateRequest;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateDto;
import com.fa26se040.icss.service.GuardScheduleService;
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
@RequestMapping("/api/guard-schedules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class GuardScheduleController {

    private final GuardScheduleService guardScheduleService;

    @GetMapping("/templates")
    public ResponseEntity<List<GuardScheduleTemplateDto>> getTemplates(
            @RequestParam(required = false) String building
    ) {
        log.info("Admin fetching guard schedule templates, building: {}", building);
        return ResponseEntity.ok(guardScheduleService.getTemplates(building));
    }

    @PostMapping("/templates")
    public ResponseEntity<GuardScheduleTemplateDto> createTemplate(
            @Valid @RequestBody GuardScheduleTemplateCreateRequest request
    ) {
        log.info("Admin creating guard schedule template for guard [{}]", request.getGuardId());
        GuardScheduleTemplateDto created = guardScheduleService.createTemplate(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<GuardScheduleTemplateDto> updateTemplate(
            @PathVariable UUID id,
            @Valid @RequestBody GuardScheduleTemplateCreateRequest request
    ) {
        log.info("Admin updating guard schedule template [{}]", id);
        return ResponseEntity.ok(guardScheduleService.updateTemplate(id, request));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID id) {
        log.info("Admin deleting guard schedule template [{}]", id);
        guardScheduleService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateShiftsResponse> generateShifts(
            @Valid @RequestBody GenerateShiftsRequest request
    ) {
        log.info("Admin generating shifts from [{}] to [{}] for building [{}]",
                request.getStartDate(), request.getEndDate(), request.getBuilding());
        return ResponseEntity.ok(guardScheduleService.generateShifts(request));
    }
}
