package com.fa26se040.security.controller;

import com.fa26se040.security.dto.BulkImportResponseDto;
import com.fa26se040.security.dto.FaceDataResponseDto;
import com.fa26se040.security.service.FaceDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/face-data")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FaceDataController {

    private final FaceDataService faceDataService;

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FaceDataResponseDto> registerFace(
            @RequestParam("code") String code,
            @RequestParam("fullName") String fullName,
            @RequestParam("frontImage") MultipartFile frontImage,
            @RequestParam("leftImage") MultipartFile leftImage,
            @RequestParam("rightImage") MultipartFile rightImage
    ) {
        FaceDataResponseDto result = faceDataService.registerFace(code, fullName, frontImage, leftImage, rightImage);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BulkImportResponseDto> bulkImportFaces(
            @RequestParam("file") MultipartFile zipFile
    ) {
        BulkImportResponseDto result = faceDataService.importBulkZip(zipFile);
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<Page<FaceDataResponseDto>> getAllFaces(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FaceDataResponseDto> result = faceDataService.getAllFaces(keyword, pageable);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFace(@PathVariable UUID id) {
        faceDataService.deleteFace(id);
        return ResponseEntity.noContent().build();
    }
}
