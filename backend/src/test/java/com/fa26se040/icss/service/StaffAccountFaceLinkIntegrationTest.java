package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * S4 (20/10): tạo tài khoản đơn lẻ đăng ký khuôn mặt trước khi lưu User; sau khi lưu phải gắn
 * face_data.user_id cho đúng User trong cùng transaction.
 * AI Service giả bằng MockRestServiceServer gắn vào RestTemplate của FaceDataService; MinIO và mail là mock.
 */
class StaffAccountFaceLinkIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private FaceDataService faceDataService;

    @Autowired
    private FaceDataRepository faceDataRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private MinioStorageService minioStorageService;

    @MockBean
    private NotificationService notificationService;

    private MockRestServiceServer aiServer;
    private String userCode;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        aiServer = MockRestServiceServer.bindTo(restTemplate).build();
        userCode = "S4-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        when(minioStorageService.uploadFaceImage(any(), anyString())).thenReturn("face-profiles/" + userCode + ".jpg");
    }

    @AfterEach
    void cleanUp() {
        faceDataRepository.findByCode(userCode).ifPresent(faceDataRepository::delete);
        userRepository.findAll().stream()
                .filter(u -> userCode.equalsIgnoreCase(u.getUserCode()))
                .forEach(userRepository::delete);
    }

    private static String aiSuccessBody(String code) {
        String vector = IntStream.range(0, 512).mapToObj(i -> "0.01").collect(Collectors.joining(","));
        return "{\"success\":true,\"code\":\"" + code + "\",\"face_count\":1,\"embedding_front\":[" + vector + "]}";
    }

    @Test
    @DisplayName("S4: tạo tài khoản đơn lẻ -> face_data.user_id trỏ đúng User vừa tạo")
    void createStaffAccount_linksFaceDataToUser() {
        aiServer.expect(requestTo(org.hamcrest.Matchers.endsWith("/api/v1/faces/process-registration")))
                .andRespond(withSuccess(aiSuccessBody(userCode), MediaType.APPLICATION_JSON));

        StaffAccountCreateRequest request = StaffAccountCreateRequest.builder()
                .fullName("S4 Face Link")
                .userCode(userCode)
                .email(userCode.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.GUARD)
                .faceImage(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .build();

        StaffAccountCreateResponse response = userService.createStaffAccount(request);

        aiServer.verify();
        FaceData faceData = faceDataRepository.findByCode(userCode).orElseThrow();
        assertThat(faceData.getUser()).as("face_data.user_id phải được gắn").isNotNull();
        assertThat(faceData.getUser().getId()).isEqualTo(response.getId());
    }

    @Test
    @DisplayName("Tái tạo tài khoản đã soft-delete với cùng userCode và email")
    void createStaffAccount_afterSoftDelete() {
        aiServer.expect(requestTo(org.hamcrest.Matchers.endsWith("/api/v1/faces/process-registration")))
                .andRespond(withSuccess(aiSuccessBody(userCode), MediaType.APPLICATION_JSON));

        StaffAccountCreateRequest request = StaffAccountCreateRequest.builder()
                .fullName("S4 Face Link")
                .userCode(userCode)
                .email(userCode.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.GUARD)
                .faceImage(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .build();

        StaffAccountCreateResponse response1 = userService.createStaffAccount(request);
        aiServer.verify();

        // Soft delete user1
        com.fa26se040.icss.entity.User user1 = userRepository.findById(response1.getId()).orElseThrow();
        user1.setDeletedAt(java.time.OffsetDateTime.now());
        user1.setIsActive(false);
        userRepository.save(user1);

        // Giờ tạo lại tài khoản với cùng request
        aiServer.reset();
        aiServer.expect(requestTo(org.hamcrest.Matchers.endsWith("/api/v1/faces/process-registration")))
                .andRespond(withSuccess(aiSuccessBody(userCode), MediaType.APPLICATION_JSON));

        StaffAccountCreateResponse response2 = userService.createStaffAccount(request);
        aiServer.verify();

        FaceData faceData = faceDataRepository.findByCode(userCode).orElseThrow();
        assertThat(faceData.getUser()).isNotNull();
        assertThat(faceData.getUser().getId()).isEqualTo(response2.getId());
    }
}
