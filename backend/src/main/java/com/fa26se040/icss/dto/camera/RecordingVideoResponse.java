package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingVideoResponse {
    private String id;
    private String cameraId;
    private String cameraName;
    private String areaName;
    private String building;
    private String floor;
    private String eventType;
    private String eventTypeName;
    private String severity;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String duration;
    private String fileSize;
    private long fileSizeBytes;
    private String resolution;
    private String format;
    private String storagePath;
    private String relativePath;
    private String videoUrl;
    private String downloadUrl;
    private String filename;
    private String notes;
}
