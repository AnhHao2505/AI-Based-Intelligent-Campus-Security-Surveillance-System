package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.AiServiceUnavailableException;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.exception.FaceDetectionException;
import com.fa26se040.icss.exception.FaceDuplicateException;
import com.fa26se040.icss.exception.FaceErrorCode;
import com.fa26se040.icss.exception.FaceImageException;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * B1, chế độ Cloud-Edge (MinIO staging + RPC MQTT EXTRACT_FACE_EMBEDDING): lỗi ảnh khuôn mặt tài khoản mang cùng mã
 * ERR_FACE_xxx với nhánh HTTP. Không cần DB: FaceDataService dựng bằng tay, edge / MinIO là mock.
 */
@ExtendWith(MockitoExtension.class)
class AccountFaceErrorCodeCloudEdgeTest {

    @Mock private FaceDataRepository faceDataRepository;
    @Mock private UserRepository userRepository;
    @Mock private MinioStorageService minioStorageService;
    @Mock private AiServiceRestTemplate restTemplate;
    @Mock private FaceDuplicateCheckService faceDuplicateCheckService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private EdgeMqttControlService edgeMqttControlService;

    private FaceDataService faceDataService;
    private final MockMultipartFile image =
            new MockMultipartFile("frontImage", "front.jpg", "image/jpeg", "fake-jpg".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        faceDataService = new FaceDataService(faceDataRepository, userRepository, minioStorageService, restTemplate,
                faceDuplicateCheckService, systemConfigService);
        ReflectionTestUtils.setField(faceDataService, "edgeMqttControlService", edgeMqttControlService);
        ReflectionTestUtils.setField(faceDataService, "aiServiceUrl", "");
        lenient().when(systemConfigService.getIntWithinBounds(ConfigKey.USER_FACE_IMAGE_MAX_KB)).thenReturn(350);
        lenient().when(edgeMqttControlService.isConnected()).thenReturn(true);
        lenient().when(minioStorageService.getFacesBucket()).thenReturn("face-profiles");
    }

    private void edgeReplies(EdgeRpcReplyDto reply) {
        when(edgeMqttControlService.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong())).thenReturn(reply);
    }

    private static EdgeRpcReplyDto failure(String errorCode) {
        return EdgeRpcReplyDto.builder().correlationId("c").success(false).errorCode(errorCode).errorMessage("edge detail").build();
    }

    private static EdgeRpcReplyDto faces(int count) {
        return EdgeRpcReplyDto.builder().correlationId("c").success(true).data(Map.of(
                "face_count", count,
                "embedding_front", Collections.nCopies(FaceDuplicateCheckService.EMBEDDING_DIM, 0.1f))).build();
    }

    @Test
    @DisplayName("Edge ERR_NO_FACE_DETECTED -> FaceDetectionException ERR_FACE_005 (422)")
    void edgeNoFace_err005() {
        edgeReplies(failure("ERR_NO_FACE_DETECTED"));
        assertThatThrownBy(() -> faceDataService.registerFace("CE1", image))
                .isInstanceOfSatisfying(FaceDetectionException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FaceErrorCode.ERR_FACE_005);
                    assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(422);
                    assertThat(e.getMessage()).isEqualTo(FaceDataService.NO_FACE_MESSAGE);
                });
    }

    @Test
    @DisplayName("Edge trả face_count = 2 -> ERR_FACE_006 (422)")
    void edgeMultipleFaces_err006() {
        edgeReplies(faces(2));
        assertThatThrownBy(() -> faceDataService.registerFace("CE2", image))
                .isInstanceOfSatisfying(FaceDetectionException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FaceErrorCode.ERR_FACE_006));
    }

    @Test
    @DisplayName("Edge mã khác (ERR_MODEL_NOT_READY) / quá hạn RPC / không tải được staging -> ERR_FACE_008 (503)")
    void edgeUnavailable_err008() {
        edgeReplies(failure("ERR_MODEL_NOT_READY"));
        assertThatThrownBy(() -> faceDataService.registerFace("CE3", image))
                .isInstanceOfSatisfying(AiServiceUnavailableException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FaceErrorCode.ERR_FACE_008);
                    assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(503);
                    assertThat(e.getMessage()).isEqualTo(FaceDataService.AI_SERVICE_ERROR_MESSAGE);
                });

        when(edgeMqttControlService.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong()))
                .thenThrow(new CameraException(CameraErrorCode.values()[0]));
        assertThatThrownBy(() -> faceDataService.registerFace("CE4", image))
                .isInstanceOfSatisfying(AiServiceUnavailableException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FaceErrorCode.ERR_FACE_008);
                    assertThat(e.getMessage()).isEqualTo(FaceDataService.AI_SERVICE_UNAVAILABLE_MESSAGE);
                });

        doThrow(new RuntimeException("minio down")).when(minioStorageService)
                .uploadStagingObject(anyString(), anyString(), any(byte[].class), anyString());
        assertThatThrownBy(() -> faceDataService.registerFace("CE5", image))
                .isInstanceOfSatisfying(AiServiceUnavailableException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FaceErrorCode.ERR_FACE_008));
    }

    @Test
    @DisplayName("FaceErrorCode.of: đọc mã của từng loại lỗi ảnh, lỗi khác rỗng")
    void faceErrorCodeOf() {
        assertThat(FaceErrorCode.of(new FaceImageException(FaceErrorCode.ERR_FACE_003, "x"))).contains(FaceErrorCode.ERR_FACE_003);
        assertThat(FaceErrorCode.of(new FaceDetectionException(FaceErrorCode.ERR_FACE_007, "x"))).contains(FaceErrorCode.ERR_FACE_007);
        assertThat(FaceErrorCode.of(new AiServiceUnavailableException("x"))).contains(FaceErrorCode.ERR_FACE_008);
        assertThat(FaceErrorCode.of(new FaceDuplicateException("x"))).contains(FaceErrorCode.ERR_FACE_001);
        assertThat(FaceErrorCode.of(new IllegalArgumentException("x"))).isEmpty();
        assertThat(FaceDuplicateException.CODE).isEqualTo(FaceErrorCode.ERR_FACE_001.getCode());
    }
}
