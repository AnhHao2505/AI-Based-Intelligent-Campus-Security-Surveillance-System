package com.fa26se040.icss.dto.chat;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendChatMessageRequest {

    @NotNull(message = "Người nhận không được để trống")
    private UUID receiverId;

    @Size(max = 2000, message = "Nội dung tin nhắn tối đa 2000 ký tự")
    private String content;

    @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
    private String imageUrl;
}

