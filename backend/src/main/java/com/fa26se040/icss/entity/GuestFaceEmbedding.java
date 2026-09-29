package com.fa26se040.icss.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Embedding khuôn mặt khách, bảng RIÊNG, tối đa 1 / khách (BR-GV-16, 17). Không nằm trong face_data.
 */
@Entity
@Table(name = "guest_face_embeddings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestFaceEmbedding {

    @Id
    @Column(name = "guest_id", nullable = false)
    private UUID guestId;

    // Vector 512 chiều lưu dạng text "[a,b,...]" và cast sang pgvector (cùng cách FaceData)
    @Column(name = "embedding", columnDefinition = "vector(512)", nullable = false)
    @ColumnTransformer(write = "?::vector")
    private String embedding;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
