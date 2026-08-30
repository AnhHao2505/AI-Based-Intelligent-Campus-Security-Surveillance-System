package com.fa26se040.security.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "face_data")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceData {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String code; // MSSV hoặc MSNV

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "image_front_url", nullable = false, length = 512)
    private String imageFrontUrl;

    @Column(name = "image_left_url", nullable = false, length = 512)
    private String imageLeftUrl;

    @Column(name = "image_right_url", nullable = false, length = 512)
    private String imageRightUrl;

    // Lưu vector 512 chiều dưới dạng text và cast sang kiểu vector của PostgreSQL
    @Column(name = "embedding_front", columnDefinition = "vector(512)", nullable = false)
    @ColumnTransformer(write = "?::vector")
    private String embeddingFront;

    @Column(name = "embedding_left", columnDefinition = "vector(512)", nullable = false)
    @ColumnTransformer(write = "?::vector")
    private String embeddingLeft;

    @Column(name = "embedding_right", columnDefinition = "vector(512)", nullable = false)
    @ColumnTransformer(write = "?::vector")
    private String embeddingRight;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
