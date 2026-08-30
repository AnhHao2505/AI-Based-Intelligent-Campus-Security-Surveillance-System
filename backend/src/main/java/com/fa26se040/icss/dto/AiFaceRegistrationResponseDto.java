package com.fa26se040.icss.dto;

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
public class AiFaceRegistrationResponseDto {
    private boolean success;
    private String code;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("image_front_url")
    private String imageFrontUrl;

    @JsonProperty("image_left_url")
    private String imageLeftUrl;

    @JsonProperty("image_right_url")
    private String imageRightUrl;

    @JsonProperty("embedding_front")
    private List<Float> embeddingFront;

    @JsonProperty("embedding_left")
    private List<Float> embeddingLeft;

    @JsonProperty("embedding_right")
    private List<Float> embeddingRight;
}
