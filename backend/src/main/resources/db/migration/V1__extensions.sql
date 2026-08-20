-- ============================================================================
-- V1: Extensions — Kích hoạt extensions cần thiết trước khi tạo bảng
-- ============================================================================

-- btree_gist: Bắt buộc cho Exclusion Constraint (chống overlap thời gian)
-- trong bảng access_permissions. Phải kích hoạt TRƯỚC khi V2 tạo bảng.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- vector (pgvector): Cho phép lưu & so sánh face embedding vector(512)
-- trong bảng user_face_data.
CREATE EXTENSION IF NOT EXISTS vector;
