package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.User;

import java.util.List;
import java.util.UUID;

public record GuardTeamAuditSnapshot(
        UUID id,
        String teamName,
        String description,
        UUID buildingId,
        String buildingName,
        Boolean isActive,
        Integer memberCount,
        List<UUID> memberIds,
        Integer weekdayMorningDemand,
        Integer weekdayAfternoonDemand,
        Integer weekdayNightDemand,
        Integer sundayMorningDemand,
        Integer sundayAfternoonDemand,
        Integer sundayNightDemand,
        Boolean hasSundayCustom
) implements AuditSnapshot {

    public static GuardTeamAuditSnapshot from(GuardTeam team, List<User> members) {
        if (team == null) {
            return null;
        }
        List<UUID> ids = members == null ? List.of() : members.stream()
                .map(User::getId)
                .toList();
        return new GuardTeamAuditSnapshot(
                team.getId(),
                team.getTeamName(),
                team.getDescription(),
                team.getBuilding() != null ? team.getBuilding().getId() : null,
                team.getBuilding() != null ? team.getBuilding().getName() : null,
                team.getIsActive(),
                ids.size(),
                ids,
                team.getWeekdayMorningDemand(),
                team.getWeekdayAfternoonDemand(),
                team.getWeekdayNightDemand(),
                team.getSundayMorningDemand(),
                team.getSundayAfternoonDemand(),
                team.getSundayNightDemand(),
                team.getHasSundayCustom()
        );
    }
}
