package com.fa26se040.icss.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "camera_specifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraSpecification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id", nullable = false, unique = true)
    private Camera camera;

    @Column(name = "manufacturer", length = 100)
    private String manufacturer;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "resolution", length = 50)
    private String resolution;

    @Column(name = "fps")
    private Integer fps;

    @Column(name = "lens", length = 100)
    private String lens;

    @Column(name = "focal_length", length = 50)
    private String focalLength;

    @Column(name = "field_of_view", precision = 5, scale = 2)
    private BigDecimal fieldOfView;

    @Column(name = "night_vision")
    private Boolean nightVision;

    @Column(name = "ptz_supported")
    private Boolean ptzSupported;

    @Column(name = "weather_proof")
    private Boolean weatherProof;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;
}
