package com.fa26se040.icss.dto.guard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardLocationResponse {
    private UUID guardId;
    private String guardName;
    private String guardEmail;
    private String userCode;
    private String teamName;
    private Double latitude;
    private Double longitude;
    private Double accuracy;
    private Double batteryLevel;
    private Double heading;
    private Double speed;
    private Boolean isInsideGeofence;
    private OffsetDateTime updatedAt;
    private Boolean isFresh;
}
