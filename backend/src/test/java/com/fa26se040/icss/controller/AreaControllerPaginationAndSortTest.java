package com.fa26se040.icss.controller;

import com.fa26se040.icss.config.SecurityConfig;
import com.fa26se040.icss.dto.area.AreaListItemResponse;
import com.fa26se040.icss.security.InternalServiceKeyFilter;
import com.fa26se040.icss.security.JwtAuthenticationFilter;
import com.fa26se040.icss.service.AreaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AreaController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5173",
        "icss.internal.service-key=0123456789abcdef0123456789abcdef"
})
class AreaControllerPaginationAndSortTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private InternalServiceKeyFilter internalServiceKeyFilter;

    @MockBean
    private AreaService areaService;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String role = auth.substring(7).trim();
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        role.toLowerCase() + "@fpt.edu.vn",
                        "password",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(internalServiceKeyFilter).doFilter(any(), any(), any());

        when(areaService.getAreas(any(), any(), any(), any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<AreaListItemResponse>(List.of(), inv.getArgument(4), 0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Happy Path: ADMIN calls GET /api/areas with allowed sort field passes exact Pageable")
    void getAreas_allowedSortAndPagination_succeeds() throws Exception {
        mockMvc.perform(get("/api/areas")
                        .param("page", "2")
                        .param("size", "15")
                        .param("sort", "areaLevel,desc")
                        .header("Authorization", "Bearer ADMIN"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(areaService).getAreas(any(), any(), any(), eq(true), pageableCaptor.capture());

        Pageable captured = pageableCaptor.getValue();
        assertEquals(2, captured.getPageNumber());
        assertEquals(15, captured.getPageSize());
        Sort.Order order = captured.getSort().getOrderFor("areaLevel");
        assertNotNull(order);
        assertEquals(Sort.Direction.DESC, order.getDirection());
    }

    @Test
    @DisplayName("Boundary: negative page and extreme size are clamped cleanly [page >= 0, 1 <= size <= 100]")
    void getAreas_negativePageAndExtremeSize_areClamped() throws Exception {
        mockMvc.perform(get("/api/areas")
                        .param("page", "-5")
                        .param("size", "0")
                        .param("sort", "updatedAt,asc")
                        .header("Authorization", "Bearer FACILITY_MANAGER"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> minCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(areaService).getAreas(any(), any(), any(), eq(true), minCaptor.capture());
        assertEquals(0, minCaptor.getValue().getPageNumber());
        assertEquals(1, minCaptor.getValue().getPageSize());

        mockMvc.perform(get("/api/areas")
                        .param("page", "1")
                        .param("size", "999")
                        .header("Authorization", "Bearer FACILITY_MANAGER"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> maxCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(areaService, org.mockito.Mockito.times(2)).getAreas(any(), any(), any(), eq(true), maxCaptor.capture());
        assertEquals(100, maxCaptor.getAllValues().get(1).getPageSize());
    }

    @ParameterizedTest
    @CsvSource({
            "floorEntity.building.id,desc,DESC",
            "code,asc,ASC",
            "nonExistentField,desc,DESC",
            "password,asc,ASC"
    })
    @DisplayName("Security Allowlist: disallowed or nested sort properties fall back strictly to 'name'")
    void getAreas_disallowedSortProperty_fallsBackToName(String rawSortProp, String rawDir, Sort.Direction expectedDir) throws Exception {
        mockMvc.perform(get("/api/areas")
                        .param("sort", rawSortProp + "," + rawDir)
                        .header("Authorization", "Bearer ADMIN"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(areaService).getAreas(any(), any(), any(), eq(true), pageableCaptor.capture());

        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("name");
        assertNotNull(order, "Disallowed sort property must coerce strictly to 'name'");
        assertEquals(expectedDir, order.getDirection());
    }

    @Test
    @DisplayName("Controller RBAC: GUARD/NORMAL_USER receive 403 Forbidden; unauthenticated receives 401 valid UTF-8 JSON")
    void getAreas_rbacAndUnauthenticatedEntryPoint() throws Exception {
        mockMvc.perform(get("/api/areas")
                        .header("Authorization", "Bearer GUARD"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/areas")
                        .header("Authorization", "Bearer NORMAL_USER"))
                .andExpect(status().isForbidden());

        MvcResult unauthResult = mockMvc.perform(get("/api/areas"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        verify(areaService, never()).getAreas(any(), any(), any(), any(), any());

        String contentType = unauthResult.getResponse().getContentType();
        assertNotNull(contentType);
        assertTrue(contentType.toLowerCase().contains("application/json"));
        JsonNode json = objectMapper.readTree(unauthResult.getResponse().getContentAsString());
        assertEquals("Unauthorized", json.get("error").asText());
        assertTrue(json.has("message"));
    }
}
