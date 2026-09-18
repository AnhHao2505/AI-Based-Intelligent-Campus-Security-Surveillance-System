package com.fa26se040.icss.dto.guard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateShiftsResponse {
    private int totalGenerated;
    private List<String> warnings;
}
