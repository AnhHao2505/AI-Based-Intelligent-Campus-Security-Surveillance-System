package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.OperationalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "camera_health_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraHealthLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id", nullable = false)
    private Camera camera;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OperationalStatus status;

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "fps", precision = 5, scale = 2)
    private BigDecimal fps;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        if (checkedAt == null) {
            checkedAt = OffsetDateTime.now();
        }
    }
}
