-- ============================================================================
-- V60: Khách G-A — lượt khách, khu vực của lượt, khách, embedding khuôn mặt khách.
-- Khách KHÔNG phải user, KHÔNG dùng AP, KHÔNG nằm trong access_requests, KHÔNG ghi face_data.
-- Số > V59 vì bảng mới tham chiếu areas (V58/V59 đã sửa areas).
-- ============================================================================

-- 1. Lượt khách (BR-GV-01..13, 25, 32)
CREATE TABLE guest_visits (
    id            UUID PRIMARY KEY,
    host_id       UUID         NOT NULL REFERENCES users(id),
    purpose       VARCHAR(500) NOT NULL,
    start_time    TIMESTAMPTZ  NOT NULL,
    end_time      TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    -- BR-GV-32: tăng mỗi lần đổi trạng thái; client gửi version đang xem, lệch -> 409
    version       BIGINT       NOT NULL DEFAULT 0,
    reviewed_by   UUID         NULL REFERENCES users(id),
    reviewed_at   TIMESTAMPTZ  NULL,
    review_reason VARCHAR(500) NULL,
    revoked_by    UUID         NULL REFERENCES users(id),
    revoked_at    TIMESTAMPTZ  NULL,
    revoke_reason VARCHAR(500) NULL,
    cancelled_at  TIMESTAMPTZ  NULL,
    cancel_reason VARCHAR(500) NULL,
    -- thời điểm hệ thống chuyển EXPIRED / COMPLETED
    closed_at     TIMESTAMPTZ  NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_guest_visits_status CHECK (status IN
        ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'REVOKED', 'EXPIRED', 'COMPLETED')),
    CONSTRAINT chk_guest_visits_window CHECK (start_time < end_time),
    CONSTRAINT chk_guest_visits_purpose CHECK (char_length(purpose) BETWEEN 10 AND 500),
    CONSTRAINT chk_guest_visits_reviewed CHECK (status NOT IN ('APPROVED', 'REJECTED', 'REVOKED')
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT chk_guest_visits_reject_reason CHECK (status <> 'REJECTED' OR review_reason IS NOT NULL),
    CONSTRAINT chk_guest_visits_revoked CHECK (status <> 'REVOKED'
        OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)),
    CONSTRAINT chk_guest_visits_cancelled CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
    CONSTRAINT chk_guest_visits_closed CHECK (status NOT IN ('EXPIRED', 'COMPLETED') OR closed_at IS NOT NULL)
);

-- job: PENDING quá start, APPROVED quá end; ẩn danh theo end
CREATE INDEX idx_guest_visits_status_start ON guest_visits (status, start_time);
CREATE INDEX idx_guest_visits_status_end ON guest_visits (status, end_time);
CREATE INDEX idx_guest_visits_host ON guest_visits (host_id, created_at DESC);

-- 2. Lượt <-> khu vực (BR-GV-04)
CREATE TABLE guest_visit_areas (
    visit_id UUID NOT NULL REFERENCES guest_visits(id) ON DELETE CASCADE,
    area_id  UUID NOT NULL REFERENCES areas(id),
    PRIMARY KEY (visit_id, area_id)
);

CREATE INDEX idx_guest_visit_areas_area ON guest_visit_areas (area_id);

-- 3. Khách của lượt (BR-GV-02, 14..16, 27, 29). Không có CCCD / SĐT / địa chỉ / email.
CREATE TABLE guests (
    id                        UUID PRIMARY KEY,
    visit_id                  UUID         NOT NULL REFERENCES guest_visits(id) ON DELETE CASCADE,
    full_name                 VARCHAR(100) NOT NULL,
    organization              VARCHAR(200) NULL,
    biometric_status          VARCHAR(20)  NOT NULL DEFAULT 'NO_PHOTO',
    photo_object_key          VARCHAR(255) NULL,
    consent_confirmed_by      UUID         NULL REFERENCES users(id),
    consent_confirmed_at      TIMESTAMPTZ  NULL,
    consent_notice_version    VARCHAR(50)  NULL,
    photo_attached_at         TIMESTAMPTZ  NULL,
    biometric_deleted_at      TIMESTAMPTZ  NULL,
    -- BR-GV-27: xoá ảnh trên MinIO lỗi -> giữ key ở đây, job thử lại lượt sau (NULL = không còn gì phải xoá)
    pending_delete_object_key VARCHAR(255) NULL,
    anonymized_at             TIMESTAMPTZ  NULL,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_guests_biometric_status CHECK (biometric_status IN ('NO_PHOTO', 'PHOTO_READY', 'PHOTO_ONLY', 'DELETED')),
    CONSTRAINT chk_guests_full_name CHECK (char_length(full_name) BETWEEN 2 AND 100),
    CONSTRAINT chk_guests_photo_consent CHECK (biometric_status NOT IN ('PHOTO_READY', 'PHOTO_ONLY')
        OR (photo_object_key IS NOT NULL AND consent_confirmed_by IS NOT NULL AND consent_confirmed_at IS NOT NULL
            AND consent_notice_version IS NOT NULL AND photo_attached_at IS NOT NULL)),
    CONSTRAINT chk_guests_no_photo CHECK (biometric_status <> 'NO_PHOTO' OR photo_object_key IS NULL),
    CONSTRAINT chk_guests_deleted CHECK (biometric_status <> 'DELETED'
        OR (biometric_deleted_at IS NOT NULL AND photo_object_key IS NULL)),
    CONSTRAINT chk_guests_anonymized CHECK (anonymized_at IS NULL OR organization IS NULL)
);

CREATE INDEX idx_guests_visit ON guests (visit_id);
CREATE INDEX idx_guests_biometric_status ON guests (biometric_status);
CREATE INDEX idx_guests_pending_delete ON guests (pending_delete_object_key) WHERE pending_delete_object_key IS NOT NULL;

-- 4. Embedding khuôn mặt khách, bảng RIÊNG (BR-GV-17). Tối đa 1 / khách (BR-GV-16).
CREATE TABLE guest_face_embeddings (
    guest_id   UUID        PRIMARY KEY REFERENCES guests(id) ON DELETE CASCADE,
    embedding  vector(512) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_guest_face_embeddings_expires ON guest_face_embeddings (expires_at);

-- 5. Thông báo: danh sách hiện hành (V56, 16 loại) + 6 loại khách (BR-GV-33)
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type,
    ADD CONSTRAINT chk_notifications_type CHECK (type IN (
        'REQUEST_APPROVED',
        'REQUEST_REJECTED',
        'EXPIRING_SOON',
        'ACCESS_DENIED',
        'ADDED_TO_GROUP',
        'NEW_REQUEST_PENDING',
        'REQUEST_CANCELLED',
        'PENDING_OVERDUE',
        'EVENT_MODE_LIMIT_CHANGED',
        'EVENT_MODE_CHANGED',
        'EVENT_MODE_EXPIRING',
        'EVENT_MODE_SCHEDULED',
        'EVENT_MODE_SCHEDULE_STARTING',
        'EVENT_MODE_SCHEDULE_FAILED',
        'AREA_TYPE_CHANGED',
        'REQUEST_SYSTEM_CANCELLED',
        'GUEST_VISIT_PENDING',
        'GUEST_VISIT_APPROVED',
        'GUEST_VISIT_REJECTED',
        'GUEST_VISIT_REVOKED',
        'GUEST_VISIT_EXPIRED',
        'GUEST_PHOTO_REQUIRED'
    ));

-- 6. Audit (BR-GV-30): module GUEST, chỉ ADMIN đọc
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('GUEST_VISIT', 'CREATE',   'GUEST', 'Tạo lượt khách'),
    ('GUEST_VISIT', 'CANCEL',   'GUEST', 'Huỷ lượt khách'),
    ('GUEST_VISIT', 'APPROVE',  'GUEST', 'Duyệt lượt khách'),
    ('GUEST_VISIT', 'REJECT',   'GUEST', 'Từ chối lượt khách'),
    ('GUEST_VISIT', 'REVOKE',   'GUEST', 'Thu hồi lượt khách'),
    ('GUEST_VISIT', 'EXPIRE',   'GUEST', 'Lượt khách hết hạn chưa duyệt'),
    ('GUEST_VISIT', 'COMPLETE', 'GUEST', 'Lượt khách hoàn tất'),
    ('GUEST', 'ATTACH_PHOTO',     'GUEST', 'Gắn ảnh khách'),
    ('GUEST', 'VIEW_PHOTO',       'GUEST', 'Cấp URL xem ảnh khách'),
    ('GUEST', 'DELETE_BIOMETRIC', 'GUEST', 'Xoá ảnh + embedding khách'),
    ('GUEST', 'ANONYMIZE',        'GUEST', 'Ẩn danh thông tin khách')
ON CONFLICT (target_type, action) DO NOTHING;

INSERT INTO audit_module_roles (module, role) VALUES ('GUEST', 'ADMIN')
ON CONFLICT (module, role) DO NOTHING;

-- 7. Config khách (mặc định TẠM)
INSERT INTO system_configurations (config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable)
VALUES
    ('GUEST_HOST_MIN_LEVEL', '2', 'INTEGER', 1, 3, 'cấp', 'GUEST', 70, '[Khách] Cấp truy cập tối thiểu của người tạo lượt khách', true),
    ('GUEST_MAX_PER_VISIT', '10', 'INTEGER', 1, 50, 'khách', 'GUEST', 71, '[Khách] Số khách tối đa trong một lượt', true),
    ('GUEST_VISIT_MAX_HOURS', '8', 'INTEGER', 1, 24, 'giờ', 'GUEST', 72, '[Khách] Thời lượng tối đa của một lượt khách (giờ)', true),
    ('GUEST_MAX_ADVANCE_DAYS', '14', 'INTEGER', 1, 90, 'ngày', 'GUEST', 73, '[Khách] Số ngày tối đa được đặt lượt khách trước', true),
    ('GUEST_FACE_RETENTION_HOURS', '24', 'INTEGER', 0, 720, 'giờ', 'GUEST', 74, '[Khách] Giữ ảnh + embedding khách sau khi lượt kết thúc (giờ)', true),
    ('GUEST_RECORD_RETENTION_DAYS', '90', 'INTEGER', 1, 3650, 'ngày', 'GUEST', 75, '[Khách] Sau bao nhiêu ngày kể từ khi lượt kết thúc thì ẩn danh họ tên khách', true),
    ('GUEST_PHOTO_URL_TTL_SECONDS', '60', 'INTEGER', 10, 3600, 'giây', 'GUEST', 76, '[Khách] Thời gian sống của URL xem ảnh khách (giây)', true),
    ('GUEST_CONSENT_NOTICE_VERSION', 'v1', 'STRING', NULL, NULL, NULL, 'GUEST', 77, '[Khách] Phiên bản thông báo đồng ý chụp ảnh đang áp dụng', true)
ON CONFLICT (config_key) DO NOTHING;
