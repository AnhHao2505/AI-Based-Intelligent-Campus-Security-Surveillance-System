package com.fa26se040.icss.controller;

import com.fa26se040.icss.service.MinioStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MinioStorageService minioStorageService;

    @Test
    @DisplayName("GET /api/media/{bucket}/** - Bucket hợp lệ trả về dữ liệu ảnh 200 OK")
    void getMedia_validBucket_returnsImageBytes() throws Exception {
        byte[] fakeImage = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};
        when(minioStorageService.getObject("camera-snapshots", "CAM-01/snapshot.jpg"))
                .thenReturn(new ByteArrayInputStream(fakeImage));

        mockMvc.perform(get("/api/media/camera-snapshots/CAM-01/snapshot.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(fakeImage));
    }

    @Test
    @DisplayName("GET /api/media/{bucket}/** - Bucket không được phép truy cập trả về 403 Forbidden")
    void getMedia_disallowedBucket_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/media/unauthorized-bucket/secret.txt"))
                .andExpect(status().isForbidden());
    }
}
