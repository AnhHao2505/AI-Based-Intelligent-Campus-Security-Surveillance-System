package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.exception.AiServiceUnavailableException;
import com.fa26se040.icss.exception.FaceDetectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FaceEdgeErrorMappingTest {

    @Test
    @DisplayName("FaceDataService.edgeExtractionFailure: ERR_IMAGE_DECODE -> FaceDetectionException(422), ERR_NO_FACE_DETECTED -> 422, ERR_EDGE_INTERNAL -> 503")
    void edgeExtractionFailure_mapsDedicatedImageDecodeError() {
        RuntimeException noFace = FaceDataService.edgeExtractionFailure(AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE);
        assertInstanceOf(FaceDetectionException.class, noFace);
        assertEquals(FaceDataService.NO_FACE_MESSAGE, noFace.getMessage());

        RuntimeException imageDecode = FaceDataService.edgeExtractionFailure(AiFaceRegistrationErrors.EDGE_IMAGE_DECODE_ERROR_CODE);
        assertInstanceOf(FaceDetectionException.class, imageDecode);
        assertEquals(FaceDataService.IMAGE_UNPROCESSABLE_MESSAGE, imageDecode.getMessage());

        RuntimeException internal = FaceDataService.edgeExtractionFailure("ERR_EDGE_INTERNAL");
        assertInstanceOf(AiServiceUnavailableException.class, internal);
        assertEquals(FaceDataService.AI_SERVICE_ERROR_MESSAGE, internal.getMessage());

        RuntimeException unknown = FaceDataService.edgeExtractionFailure(null);
        assertInstanceOf(AiServiceUnavailableException.class, unknown);
        assertEquals(FaceDataService.AI_SERVICE_ERROR_MESSAGE, unknown.getMessage());
    }

    @Test
    @DisplayName("GuestFaceEmbeddingClient: ERR_IMAGE_DECODE từ edge -> ném IllegalStateException (GuestPhotoService báo ERR_GUEST_035 như HTTP 400)")
    void guestClient_edgeImageDecode_throwsAndCleansStaging() {
        AiServiceRestTemplate restTemplate = mock(AiServiceRestTemplate.class);
        EdgeMqttControlService edge = mock(EdgeMqttControlService.class);
        MinioStorageService minio = mock(MinioStorageService.class);
        when(edge.isConnected()).thenReturn(true);
        when(minio.getFacesBucket()).thenReturn("faces");
        when(edge.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong()))
                .thenReturn(EdgeRpcReplyDto.builder()
                        .success(false)
                        .errorCode(AiFaceRegistrationErrors.EDGE_IMAGE_DECODE_ERROR_CODE)
                        .errorMessage("Không thể giải mã định dạng ảnh hoặc file bị hỏng")
                        .build());

        GuestFaceEmbeddingClient client = new GuestFaceEmbeddingClient(restTemplate);
        ReflectionTestUtils.setField(client, "edgeMqttControlService", edge);
        ReflectionTestUtils.setField(client, "minioStorageService", minio);
        ReflectionTestUtils.setField(client, "aiServiceUrl", "");

        assertThrows(IllegalStateException.class,
                () -> client.extract(new byte[]{1, 2, 3}, "corrupt.jpg", "guest-log"));
        verify(minio).removeObjectSafely(eq("faces"), anyString());
    }
}
