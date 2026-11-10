package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.ai.AiFaceItemDto;
import com.fa26se040.icss.dto.ai.AiSyncDataDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSyncService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public AiSyncDataDto getAiSyncData() {
        long startTime = System.currentTimeMillis();

        String faceQuery = """
            SELECT fd.code,
                   COALESCE(u.full_name, fd.code) AS full_name,
                   fd.embedding_front::text AS embedding,
                   COALESCE(u.is_active, true) AS is_active,
                   COALESCE(u.role, 'NORMAL_USER') AS role,
                   NULL::bigint AS expires_at
            FROM face_data fd
            JOIN users u ON (fd.user_id = u.id OR fd.code = u.user_code)
            WHERE u.deleted_at IS NULL
            UNION ALL
            SELECT g.id::text AS code,
                   (g.full_name || ' (Khách)') AS full_name,
                   gfe.embedding::text AS embedding,
                   true AS is_active,
                   'GUEST' AS role,
                   (EXTRACT(EPOCH FROM gfe.expires_at) * 1000)::bigint AS expires_at
            FROM guest_face_embeddings gfe
            JOIN guests g ON g.id = gfe.guest_id
            JOIN guest_visits gv ON gv.id = g.visit_id
            WHERE gv.status = 'APPROVED'
              AND g.biometric_status = 'PHOTO_READY'
              AND gfe.expires_at > NOW()
        """;

        List<AiFaceItemDto> faces = jdbcTemplate.query(faceQuery, (rs, rowNum) -> {
            String code = rs.getString("code");
            String fullName = rs.getString("full_name");
            String embStr = rs.getString("embedding");
            boolean isActive = rs.getBoolean("is_active");
            String role = rs.getString("role");
            long expiresAtVal = rs.getLong("expires_at");
            Long expiresAt = rs.wasNull() ? null : expiresAtVal;

            List<Float> embedding = parseEmbedding(embStr);

            return AiFaceItemDto.builder()
                    .code(code)
                    .fullName(fullName)
                    .embedding(embedding)
                    .isActive(isActive)
                    .role(role)
                    .expiresAt(expiresAt)
                    .build();
        });

        String cameraQuery = """
            SELECT UPPER(c.camera_code) AS camera_code,
                   a.name AS area_name,
                   (a.area_level = 'PUBLIC' OR a.area_access_level <= 1) AS is_public
            FROM cameras c
            JOIN areas a ON c.area_id = a.id
            WHERE c.deleted_at IS NULL
        """;

        Map<String, String> cameraAreaMap = new HashMap<>();
        Set<String> publicCameraCodes = new HashSet<>();

        jdbcTemplate.query(cameraQuery, rs -> {
            String cameraCode = rs.getString("camera_code");
            String areaName = rs.getString("area_name");
            boolean isPublic = rs.getBoolean("is_public");

            if (cameraCode != null) {
                String normCode = cameraCode.trim().toUpperCase();
                if (areaName != null) {
                    cameraAreaMap.put(normCode, areaName);
                }
                if (isPublic) {
                    publicCameraCodes.add(normCode);
                }
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.info("Lấy dữ liệu đồng bộ AI hoàn tất: {} faces, {} cameras, {} public cameras trong {} ms",
                faces.size(), cameraAreaMap.size(), publicCameraCodes.size(), duration);

        return AiSyncDataDto.builder()
                .faces(faces)
                .cameraAreaMap(cameraAreaMap)
                .publicCameraCodes(publicCameraCodes)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private List<Float> parseEmbedding(String embStr) {
        if (embStr == null || embStr.isBlank()) {
            return Collections.emptyList();
        }
        String clean = embStr.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = clean.split(",");
        List<Float> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                result.add(Float.parseFloat(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }
}
