package com.fa26se040.icss.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "camera_stream_configurations")
@Getter
@Setter
@ToString(exclude = {"camera"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraStreamConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id", nullable = false, unique = true)
    private Camera camera;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "credential_ref", length = 255)
    private String credentialRef;

    @Column(name = "main_stream_path", nullable = false, length = 512)
    private String mainStreamPath;

    @Column(name = "sub_stream_path", length = 512)
    private String subStreamPath;

    @Column(name = "retries_before_alert", nullable = false)
    @Builder.Default
    private Integer retryTimeBeforeAlerting = 3;

    @Column(name = "timeout_ms", nullable = false)
    @Builder.Default
    private Integer timeoutMs = 5000;
}
