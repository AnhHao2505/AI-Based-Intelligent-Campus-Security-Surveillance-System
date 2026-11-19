package com.fa26se040.icss.dto.camera;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordingCompletedEventDto {

    @JsonProperty("camera_code")
    private String cameraCode;

    @JsonProperty("incident_id")
    private String incidentId;

    @JsonProperty("event_id")
    @com.fasterxml.jackson.annotation.JsonAlias({"incident_event_id", "incidentId"})
    private String eventId;

    @JsonProperty("recording_type")
    private String recordingType;

    @JsonProperty("storage_location")
    private String storageLocation;

    @JsonProperty("storage_bucket")
    @com.fasterxml.jackson.annotation.JsonAlias({"bucket_name", "bucket"})
    private String storageBucket;

    @JsonProperty("storage_key")
    @com.fasterxml.jackson.annotation.JsonAlias({"object_key", "key"})
    private String storageKey;

    @JsonProperty("filename")
    private String filename;

    @JsonProperty("start_time")
    private String startTime;

    @JsonProperty("end_time")
    private String endTime;

    @JsonProperty("duration_seconds")
    private Integer durationSeconds;

    @JsonProperty("frame_count")
    private Integer frameCount;

    @JsonProperty("fps")
    private Double fps;

    @JsonProperty("resolution")
    private String resolution;

    @JsonProperty("file_size_bytes")
    private Long fileSizeBytes;

    @JsonProperty("checksum_sha256")
    private String checksumSha256;

    @JsonProperty("deleted_storage_keys")
    private List<String> deletedStorageKeys;
}
