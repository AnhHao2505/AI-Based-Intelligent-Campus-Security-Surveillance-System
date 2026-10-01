package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityIncidentNullBuildingTest extends AbstractIntegrationTest {

    @Autowired
    private SecurityIncidentRepository incidentRepository;

    @Autowired
    private SecurityIncidentService incidentService;

    @Autowired
    private AreaRepository areaRepository;

    private Area testArea;
    private static final String TEST_BUILDING = "Alpha";

    @BeforeEach
    void setUp() {
        testArea = areaRepository.findAll().stream()
                .filter(a -> a.getDeletedAt() == null && a.getIsActive() != null && a.getIsActive())
                .findFirst()
                .orElseGet(() -> {
                    Area a = Area.builder()
                            .name("Test Area for Incidents " + UUID.randomUUID())
                            .building(TEST_BUILDING)
                            .floor("1")
                            .areaLevel(AreaLevel.PUBLIC)
                            .isActive(true)
                            .version(1L)
                            .build();
                    return areaRepository.saveAndFlush(a);
                });

        SecurityIncident incident = SecurityIncident.builder()
                .cameraCode("CAM-TEST-001")
                .area(testArea)
                .building(TEST_BUILDING)
                .eventType("INTRUSION")
                .detectedAt(OffsetDateTime.now())
                .status(IncidentStatus.NEW)
                .build();
        incidentRepository.saveAndFlush(incident);
    }

    @Test
    @DisplayName("findActiveIncidents(null) should execute on PostgreSQL without lower(bytea) error")
    void testFindActiveIncidents_nullBuilding() {
        List<SecurityIncident> result = incidentRepository.findActiveIncidents(null);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("findActiveIncidents('<building>') should return filtered incidents")
    void testFindActiveIncidents_withBuilding() {
        List<SecurityIncident> result = incidentRepository.findActiveIncidents(TEST_BUILDING);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(i -> TEST_BUILDING.equalsIgnoreCase(i.getBuilding())));
    }

    @Test
    @DisplayName("findIncidents(null, null) should return all incidents")
    void testFindIncidents_nullBuilding_nullStatus() {
        List<SecurityIncident> result = incidentRepository.findIncidents(null, null);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("findIncidents(null, NEW) should return incidents with status NEW")
    void testFindIncidents_nullBuilding_withStatus() {
        List<SecurityIncident> result = incidentRepository.findIncidents(null, IncidentStatus.NEW);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(i -> i.getStatus() == IncidentStatus.NEW));
    }

    @Test
    @DisplayName("Service: whitespace or empty building should be treated as null")
    void testService_emptyOrWhitespaceBuilding_treatedAsNull() {
        List<IncidentDetailResponse> resEmpty = incidentService.getActiveIncidents("");
        assertNotNull(resEmpty);
        assertFalse(resEmpty.isEmpty());

        List<IncidentDetailResponse> resWhitespace = incidentService.getActiveIncidents("   ");
        assertNotNull(resWhitespace);
        assertFalse(resWhitespace.isEmpty());

        List<IncidentDetailResponse> resIncidentsBlank = incidentService.getIncidents("  ", IncidentStatus.NEW);
        assertNotNull(resIncidentsBlank);
        assertFalse(resIncidentsBlank.isEmpty());
    }
}
