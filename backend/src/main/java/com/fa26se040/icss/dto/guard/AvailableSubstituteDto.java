package com.fa26se040.icss.dto.guard;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailableSubstituteDto {
    private UUID id;
    private String userCode;
    private String fullName;
    private String email;
    private String teamName;
}
