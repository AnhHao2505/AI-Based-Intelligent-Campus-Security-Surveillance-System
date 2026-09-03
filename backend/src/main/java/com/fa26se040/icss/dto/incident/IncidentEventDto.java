package com.fa26se040.icss.dto.incident;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentEventDto {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("camera_code")
    private String cameraCode;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("track_id")
    private Integer trackId;

    @JsonProperty("duration_seconds")
    private Double durationSeconds;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("image_url")
    private String imageUrl;

    @JsonProperty("detected_at")
    private String detectedAt;

    @JsonProperty("details")
    private String details;

    @JsonProperty("location")
    private Map<String, Object> location;
}
