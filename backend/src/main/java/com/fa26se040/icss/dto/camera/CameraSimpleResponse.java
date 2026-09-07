package com.fa26se040.icss.dto.camera;

import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraSimpleResponse {
    private UUID id;
    private String cameraCode;
    private String name;
    private CameraStatus status;
    private OperationalStatus operationalStatus;
}
