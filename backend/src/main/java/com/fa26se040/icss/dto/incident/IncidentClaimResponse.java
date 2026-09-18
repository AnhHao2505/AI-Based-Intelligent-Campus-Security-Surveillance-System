package com.fa26se040.icss.dto.incident;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentClaimResponse {
    private UUID incidentId;
    private String status;
    private String message;
    private UUID claimedById;
    private String claimantName;
    private OffsetDateTime claimedAt;
}
