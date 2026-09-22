package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.user.UserAccessLevelUpdateRequest;
import com.fa26se040.icss.dto.user.UserSearchResponse;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerAccessLevelTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("GET /api/users/search với q 1 ký tự -> 400 Bad Request")
    void searchUsers_ShortQuery_Returns400() throws Exception {
        when(userService.searchUsers(eq("a"), any())).thenThrow(new IllegalArgumentException("Từ khoá tìm kiếm phải có tối thiểu 2 ký tự"));

        mockMvc.perform(get("/api/users/search")
                        .param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Từ khoá tìm kiếm phải có tối thiểu 2 ký tự"));
    }

    @Test
    @DisplayName("GET /api/users/search response CHỈ gồm id, userCode, fullName, role, accessLevel (không có trường nhạy cảm)")
    void searchUsers_ResponseStructure_ContainsOnlyAllowedFields() throws Exception {
        UUID userId = UUID.randomUUID();
        UserSearchResponse userDto = new UserSearchResponse(
                userId,
                "NV001",
                "Nguyễn Văn A",
                Role.GUARD,
                2
        );

        when(userService.searchUsers(eq("NV"), any()))
                .thenReturn(new PageImpl<>(List.of(userDto), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/users/search")
                        .param("q", "NV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.content[0].userCode").value("NV001"))
                .andExpect(jsonPath("$.content[0].fullName").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.content[0].role").value("GUARD"))
                .andExpect(jsonPath("$.content[0].accessLevel").value(2))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.content[0].importBatchId").doesNotExist())
                .andExpect(jsonPath("$.content[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.content[0].faceImage").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /api/users/{id}/access-level với giá trị ngoài khoảng 1..3 -> 400 Bad Request")
    void updateAccessLevel_InvalidRange_Returns400() throws Exception {
        UUID userId = UUID.randomUUID();

        // Level = 0
        String body0 = objectMapper.writeValueAsString(new UserAccessLevelUpdateRequest(0));
        mockMvc.perform(patch("/api/users/{id}/access-level", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body0))
                .andExpect(status().isBadRequest());

        // Level = 4
        String body4 = objectMapper.writeValueAsString(new UserAccessLevelUpdateRequest(4));
        mockMvc.perform(patch("/api/users/{id}/access-level", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body4))
                .andExpect(status().isBadRequest());

        // Level = null
        String bodyNull = "{\"accessLevel\": null}";
        mockMvc.perform(patch("/api/users/{id}/access-level", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyNull))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/users/{id}/access-level hợp lệ (1..3) -> 200 OK")
    void updateAccessLevel_Valid_Returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UserSearchResponse resp = new UserSearchResponse(userId, "NV001", "Nguyễn Văn A", Role.GUARD, 3);
        when(userService.updateAccessLevel(eq(userId), eq(3), any())).thenReturn(resp);

        String body = objectMapper.writeValueAsString(new UserAccessLevelUpdateRequest(3));
        mockMvc.perform(patch("/api/users/{id}/access-level", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.accessLevel").value(3));
    }

    @Test
    @DisplayName("PATCH /api/users/{id}/access-level: FM tự sửa chính mình -> 403 Forbidden")
    void updateAccessLevel_SelfModification_Returns403() throws Exception {
        UUID myId = UUID.randomUUID();
        when(userService.updateAccessLevel(eq(myId), eq(3), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("Bạn không thể tự thay đổi cấp truy cập của chính mình"));

        String body = objectMapper.writeValueAsString(new UserAccessLevelUpdateRequest(3));
        mockMvc.perform(patch("/api/users/{id}/access-level", myId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Bạn không thể tự thay đổi cấp truy cập của chính mình")));
    }
}
