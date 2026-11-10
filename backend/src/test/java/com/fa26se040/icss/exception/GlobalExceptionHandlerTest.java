package com.fa26se040.icss.exception;

import com.fa26se040.icss.dto.common.ApiResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private GlobalExceptionHandler handler;

    @RestController
    static class DummyStreamingController {

        @GetMapping(value = "/api/test/stream-error", produces = "video/mp4")
        public byte[] streamError() {
            throw new RuntimeException("Simulated video stream reading failure");
        }

        @GetMapping("/api/test/client-abort-async")
        public void clientAbortAsync() throws Exception {
            throw new AsyncRequestNotUsableException(
                    "ServletOutputStream failed to write: java.io.IOException: An established connection was aborted by the software in your host machine"
            );
        }

        @GetMapping("/api/test/client-abort-io")
        public void clientAbortIo() throws IOException {
            throw new IOException("An established connection was aborted by the software in your host machine");
        }

        @GetMapping("/api/test/broken-pipe-io")
        public void brokenPipeIo() throws IOException {
            throw new IOException("Broken pipe");
        }

        @GetMapping("/api/test/general-error")
        public void generalError() {
            throw new RuntimeException("Generic unexpected error");
        }
    }

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(new DummyStreamingController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    @DisplayName("1. Happy Path: Client disconnect/abort during stream (AsyncRequestNotUsable & ClientAbort) is handled gracefully without body")
    void handleClientDisconnect_gracefullyHandledWithoutBody() throws Exception {
        // When client aborts async stream, handler catches it and does not write error JSON body to aborted socket
        mockMvc.perform(get("/api/test/client-abort-async"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        // Direct call check
        ClientAbortException clientAbortException = new ClientAbortException("Client abort connection");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<ApiResponse<Object>> directResult = handler.handleGeneral(clientAbortException, response);
        assertThat(directResult).isNull();
    }

    @Test
    @DisplayName("1b. Happy Path: Client abort with socket IOException (aborted / broken pipe) is handled gracefully without error body")
    void handleClientAbortIOException_gracefullyHandled() throws Exception {
        mockMvc.perform(get("/api/test/client-abort-io"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/test/broken-pipe-io"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("2. Boundary: Preset Content-Type video/mp4 does not throw HttpMessageNotWritableException and overrides to application/json")
    void handleGeneral_withPresetVideoContentType_overridesToApplicationJson() throws Exception {
        // When an exception occurs on an endpoint producing video/mp4,
        // GlobalExceptionHandler must explicitly set application/json so Jackson can serialize ApiResponse
        mockMvc.perform(get("/api/test/stream-error").accept("video/mp4", "application/json"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.httpCode").value(500))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Lỗi hệ thống")));
    }

    @Test
    @DisplayName("2b. Boundary: Committed response returns null to avoid corrupted socket write")
    void handleGeneral_committedResponse_returnsNull() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ResponseEntity<ApiResponse<Object>> result = handler.handleGeneral(new RuntimeException("Late failure"), response);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("3. Exception / Domain Error: Generic unhandled exception returns 500 with tracking errorId")
    void handleGeneral_unhandledException_returns500WithTrackingId() throws Exception {
        mockMvc.perform(get("/api/test/general-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.httpCode").value(500))
                .andExpect(jsonPath("$.errorCode").value(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Mã tra cứu:")));
    }
}
