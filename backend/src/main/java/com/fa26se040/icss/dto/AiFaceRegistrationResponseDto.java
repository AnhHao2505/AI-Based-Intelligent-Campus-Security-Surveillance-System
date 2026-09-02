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

    @JsonProperty("embedding_front")
    private List<Float> embeddingFront;
}
