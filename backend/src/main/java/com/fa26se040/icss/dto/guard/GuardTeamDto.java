package com.fa26se040.icss.dto.guard;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardTeamDto {
    private UUID id;
    private String teamName;
    private String description;
    private Boolean isActive;
    private Integer memberCount;
    private List<TeamMemberDto> members;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TeamMemberDto {
        private UUID id;
        private String userCode;
        private String fullName;
        private String email;
    }
}
