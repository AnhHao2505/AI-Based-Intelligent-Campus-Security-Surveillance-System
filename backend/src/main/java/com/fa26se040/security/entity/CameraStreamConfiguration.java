package com.fa26se040.security.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Entity
@Table(name = "camera_stream_configurations")
@Data
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

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 50)
    private StreamProtocol protocol;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "credential_ref", length = 255)
    private String credentialRef;

    @Column(name = "main_stream_url", nullable = false, length = 512)
    private String mainStreamUrl;

    @Column(name = "sub_stream_url", length = 512)
    private String subStreamUrl;

    @Column(name = "stream_enabled", nullable = false)
    @Builder.Default
    private Boolean streamEnabled = true;

    @Column(name = "reconnect_enabled", nullable = false)
    @Builder.Default
    private Boolean reconnectEnabled = true;

    @Column(name = "timeout_ms", nullable = false)
    @Builder.Default
    private Integer timeoutMs = 5000;
}
