package com.fa26se040.icss.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSyncDataDto {
    private List<AiFaceItemDto> faces;
    private Map<String, String> cameraAreaMap;
    private Set<String> publicCameraCodes;
    private long timestamp;
}
