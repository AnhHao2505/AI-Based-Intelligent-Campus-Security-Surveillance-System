package com.fa26se040.icss.dto.guest;

/** URL xem ảnh khách có hạn (presigned). Không phải object URL công khai (BR-GV-18). */
public record GuestPhotoUrlResponse(String url, int expiresInSeconds) {
}
