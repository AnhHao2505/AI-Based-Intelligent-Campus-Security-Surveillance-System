CREATE TABLE areas (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    area_level  SMALLINT     NOT NULL REFERENCES area_levels(level),
    building    VARCHAR(50),
    floor       VARCHAR(20),
    description TEXT,
    map_x       NUMERIC(7,2),
    map_y       NUMERIC(7,2),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  INTEGER      REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  INTEGER      REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT ck_areas_map CHECK ((map_x IS NULL) = (map_y IS NULL))
);

CREATE UNIQUE INDEX ux_areas_code  ON areas(code) WHERE deleted_at IS NULL;
CREATE INDEX        ix_areas_level ON areas(area_level);

-- Nhật ký thay đổi khu vực. Chưa có bảng audit_logs chung nên dùng bảng riêng,
-- sau này gộp vào audit chung khi M14 làm.
CREATE TABLE area_change_logs (
    id          SERIAL PRIMARY KEY,
    area_id     INTEGER     NOT NULL REFERENCES areas(id),
    actor_id    INTEGER     REFERENCES users(id),
    action      VARCHAR(20) NOT NULL
                CHECK (action IN ('CREATE','UPDATE','DEACTIVATE')),
    old_value   JSONB,
    new_value   JSONB,
    reason      VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_acl_area ON area_change_logs(area_id, created_at DESC);
