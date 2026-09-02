package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.AreaLevelResponse;
import com.fa26se040.icss.service.AreaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/area-levels")
@RequiredArgsConstructor
public class AreaLevelController {

    private final AreaService areaService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AreaLevelResponse>> getAreaLevels() {
        return ResponseEntity.ok(areaService.getAreaLevels());
    }
}
