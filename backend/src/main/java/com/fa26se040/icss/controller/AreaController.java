package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaDependencyResponse;
import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.dto.area.AreaGeometryResponse;
import com.fa26se040.icss.dto.area.AreaListItemResponse;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.service.AreaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/areas")
@RequiredArgsConstructor
public class AreaController {

    private final AreaService areaService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<Page<AreaListItemResponse>> getAreas(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AreaLevel areaLevel,
            @RequestParam(required = false) String building,
            @RequestParam(required = false, defaultValue = "true") Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code,asc") String sort
    ) {
        int cappedSize = Math.min(Math.max(1, size), 100);
        Sort sortOrder = Sort.by(Sort.Direction.ASC, "code");
        if (sort != null && sort.contains(",")) {
            String[] parts = sort.split(",");
            Sort.Direction direction = parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
            sortOrder = Sort.by(direction, parts[0]);
        }
        Pageable pageable = PageRequest.of(page, cappedSize, sortOrder);
        Page<AreaListItemResponse> result = areaService.getAreas(keyword, areaLevel, building, isActive, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<AreaResponse> getAreaById(@PathVariable UUID id) {
        return ResponseEntity.ok(areaService.getAreaById(id));
    }

    @GetMapping("/{id}/dependencies")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AreaDependencyResponse> getDependencies(@PathVariable UUID id) {
        return ResponseEntity.ok(areaService.getDependencies(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AreaResponse> create(
            @Valid @RequestBody AreaCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AreaResponse response = areaService.create(request, actorEmail);
        URI location = URI.create("/api/areas/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AreaResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody AreaUpdateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AreaResponse response = areaService.update(id, request, actorEmail);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/geometries")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<List<AreaGeometryResponse>> getGeometries(
            @RequestParam String building,
            @RequestParam String floor
    ) {
        return ResponseEntity.ok(areaService.getGeometriesByBuildingAndFloor(building, floor));
    }

    @PatchMapping("/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AreaGeometryResponse> saveGeometry(
            @PathVariable UUID id,
            @RequestBody AreaGeometry geometry,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(areaService.saveGeometry(id, geometry, actorEmail));
    }

    @DeleteMapping("/{id}/geometry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteGeometry(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        areaService.deleteGeometry(id, actorEmail);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        areaService.deactivate(id, actorEmail);
        return ResponseEntity.noContent().build();
    }
}
