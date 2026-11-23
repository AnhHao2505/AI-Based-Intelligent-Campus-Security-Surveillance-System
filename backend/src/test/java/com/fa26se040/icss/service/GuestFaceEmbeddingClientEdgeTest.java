package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * face-gate 6, chế độ Edge + Cloud (ai.service.url trống, MQTT đã kết nối): ảnh khách đi qua RPC EXTRACT_FACE_EMBEDDING.
 * Edge báo ERR_NO_FACE_DETECTED -> 0 khuôn mặt (GuestPhotoService báo ERR_GUEST_034 "phát hiện 0"); lỗi Edge khác vẫn ném
 * ra (-> ERR_GUEST_035). Ảnh staging luôn bị gỡ.
 */
class GuestFaceEmbeddingClientEdgeTest {

    private AiServiceRestTemplate restTemplate;
    private EdgeMqttControlService edge;
    private MinioStorageService minio;
    private GuestFaceEmbeddingClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(AiServiceRestTemplate.class);
        edge = mock(EdgeMqttControlService.class);
        minio = mock(MinioStorageService.class);
        when(edge.isConnected()).thenReturn(true);
        when(minio.getFacesBucket()).thenReturn("faces");
        client = new GuestFaceEmbeddingClient(restTemplate);
        ReflectionTestUtils.setField(client, "edgeMqttControlService", edge);
        ReflectionTestUtils.setField(client, "minioStorageService", minio);
        ReflectionTestUtils.setField(client, "aiServiceUrl", "");
    }

    private void edgeReplies(String errorCode, String errorMessage) {
        when(edge.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong()))
                .thenReturn(EdgeRpcReplyDto.builder().success(false).errorCode(errorCode).errorMessage(errorMessage).build());
    }

    @Test
    @DisplayName("Edge trả ERR_NO_FACE_DETECTED -> Result(0 khuôn mặt, vector rỗng), không gọi HTTP, ảnh staging bị gỡ")
    void edgeNoFace_returnsZeroFaces() {
        edgeReplies("ERR_NO_FACE_DETECTED", "Không phát hiện thấy khuôn mặt rõ nét trong ảnh");

        GuestFaceEmbeddingClient.Result result = client.extract(new byte[]{1, 2, 3}, "guest.jpg", "guest-log");

        assertThat(result.faceCount()).isZero();
        assertThat(result.embedding()).isEmpty();
        verify(minio).removeObjectSafely(eq("faces"), anyString());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Edge trả lỗi khác (model chưa nạp, lỗi nội bộ, không mã) -> vẫn ném lỗi (GuestPhotoService báo ERR_GUEST_035), ảnh staging bị gỡ")
    void edgeOtherError_throws() {
        for (String errorCode : new String[]{"ERR_MODEL_NOT_READY", "ERR_EDGE_INTERNAL", null}) {
            edge = mock(EdgeMqttControlService.class);
            when(edge.isConnected()).thenReturn(true);
            ReflectionTestUtils.setField(client, "edgeMqttControlService", edge);
            edgeReplies(errorCode, "edge lỗi " + errorCode);

            assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "guest.jpg", "guest-log"))
                    .as(String.valueOf(errorCode))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(minio, times(3)).removeObjectSafely(eq("faces"), anyString());
        verify(minio, times(3)).uploadStagingObject(eq("faces"), anyString(), any(), eq("image/jpeg"));
    }
}
