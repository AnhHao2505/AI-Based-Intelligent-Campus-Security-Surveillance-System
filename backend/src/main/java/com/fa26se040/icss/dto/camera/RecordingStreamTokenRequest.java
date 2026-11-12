package com.fa26se040.icss.dto.camera;

import jakarta.validation.constraints.NotBlank;

/**
 * BE-03: xin token xem một file ghi hình. {@code path} và {@code source} lấy nguyên từ query của
 * {@code videoUrl} / {@code downloadUrl} trong danh sách ghi hình.
 */
public record RecordingStreamTokenRequest(
        @NotBlank(message = "Thiếu đường dẫn video")
        String path,

        String source
) {}
