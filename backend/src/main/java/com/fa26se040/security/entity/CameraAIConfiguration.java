package com.fa26se040.security.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "camera_ai_configurations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraAIConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id", nullable = false, unique = true)
    private Camera camera;

    @Column(name = "person_detection_enabled", nullable = false)
    @Builder.Default
    private Boolean personDetectionEnabled = false;

    @Column(name = "face_recognition_enabled", nullable = false)
    @Builder.Default
    private Boolean faceRecognitionEnabled = false;

    @Column(name = "loitering_detection_enabled", nullable = false)
    @Builder.Default
    private Boolean loiteringDetectionEnabled = false;

    @Column(name = "face_match_threshold", nullable = false, precision = 3, scale = 2)
    private BigDecimal faceMatchThreshold;

    @Column(name = "loitering_threshold_seconds", nullable = false)
    private Integer loiteringThresholdSeconds;

    @Column(name = "inference_fps", nullable = false)
    private Integer inferenceFps;

    @Column(name = "model_version", length = 50)
    private String modelVersion;
}
