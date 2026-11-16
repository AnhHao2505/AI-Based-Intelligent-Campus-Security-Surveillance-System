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

    /** V129: mã phiên AI của cảnh báo người lạ; IDENTITY_UPDATE gửi lại đúng mã này. */
    @JsonProperty("roi_session_id")
    private String roiSessionId;

    /** Chỉ có ở IDENTITY_UPDATE: AUTHORIZED | UNAUTHORIZED. */
    @JsonProperty("identity_outcome")
    private String identityOutcome;

    /** Chỉ có ở IDENTITY_UPDATE: mã người AI vừa nhận ra. */
    @JsonProperty("user_code")
    private String userCode;

    /** Chỉ có ở IDENTITY_UPDATE: tên người AI vừa nhận ra. */
    @JsonProperty("user_name")
    private String userName;
}
