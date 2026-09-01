-- ============================================================================
-- V7: Convert primary keys from SERIAL to UUID
-- area_levels giữ nguyên SMALLINT (giá trị nghiệp vụ 1/2/3, không phải id kỹ thuật)
-- ============================================================================

-- 1) Gỡ toàn bộ FK đang trỏ tới users(id) và areas(id)
ALTER TABLE areas            DROP CONSTRAINT areas_created_by_fkey;
ALTER TABLE areas            DROP CONSTRAINT areas_updated_by_fkey;
ALTER TABLE area_change_logs DROP CONSTRAINT area_change_logs_actor_id_fkey;
ALTER TABLE area_change_logs DROP CONSTRAINT area_change_logs_area_id_fkey;

-- 2) Thêm cột UUID mới cho các bảng có khoá chính
ALTER TABLE users                 ADD COLUMN new_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE areas                 ADD COLUMN new_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE area_change_logs      ADD COLUMN new_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE password_reset_tokens ADD COLUMN new_id UUID NOT NULL DEFAULT gen_random_uuid();

-- 3) Thêm cột UUID cho các khoá ngoại
ALTER TABLE areas            ADD COLUMN new_created_by UUID;
ALTER TABLE areas            ADD COLUMN new_updated_by UUID;
ALTER TABLE area_change_logs ADD COLUMN new_actor_id   UUID;
ALTER TABLE area_change_logs ADD COLUMN new_area_id    UUID;

-- 4) Ánh xạ dữ liệu FK theo id cũ
UPDATE areas a SET new_created_by = u.new_id FROM users u WHERE a.created_by = u.id;
UPDATE areas a SET new_updated_by = u.new_id FROM users u WHERE a.updated_by = u.id;
UPDATE area_change_logs l SET new_actor_id = u.new_id FROM users u WHERE l.actor_id = u.id;
UPDATE area_change_logs l SET new_area_id  = ar.new_id FROM areas ar WHERE l.area_id = ar.id;

-- 5) Kiểm tra không mất dữ liệu FK trước khi xoá cột cũ
DO $$
DECLARE orphan_count INT;
BEGIN
    SELECT count(*) INTO orphan_count FROM area_change_logs
     WHERE area_id IS NOT NULL AND new_area_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'Mapping that bai: % dong area_change_logs mat area_id', orphan_count;
    END IF;
END $$;

-- 6) Xoá khoá chính cũ và cột id cũ
ALTER TABLE users                 DROP CONSTRAINT users_pkey;
ALTER TABLE areas                 DROP CONSTRAINT areas_pkey;
ALTER TABLE area_change_logs      DROP CONSTRAINT area_change_logs_pkey;
ALTER TABLE password_reset_tokens DROP CONSTRAINT password_reset_tokens_pkey;

ALTER TABLE areas            DROP COLUMN created_by;
ALTER TABLE areas            DROP COLUMN updated_by;
ALTER TABLE area_change_logs DROP COLUMN actor_id;
ALTER TABLE area_change_logs DROP COLUMN area_id;

ALTER TABLE users                 DROP COLUMN id;
ALTER TABLE areas                 DROP COLUMN id;
ALTER TABLE area_change_logs      DROP COLUMN id;
ALTER TABLE password_reset_tokens DROP COLUMN id;

-- 7) Đổi tên cột mới
ALTER TABLE users                 RENAME COLUMN new_id TO id;
ALTER TABLE areas                 RENAME COLUMN new_id TO id;
ALTER TABLE area_change_logs      RENAME COLUMN new_id TO id;
ALTER TABLE password_reset_tokens RENAME COLUMN new_id TO id;

ALTER TABLE areas            RENAME COLUMN new_created_by TO created_by;
ALTER TABLE areas            RENAME COLUMN new_updated_by TO updated_by;
ALTER TABLE area_change_logs RENAME COLUMN new_actor_id   TO actor_id;
ALTER TABLE area_change_logs RENAME COLUMN new_area_id    TO area_id;

-- 8) Gắn lại khoá chính
ALTER TABLE users                 ADD PRIMARY KEY (id);
ALTER TABLE areas                 ADD PRIMARY KEY (id);
ALTER TABLE area_change_logs      ADD PRIMARY KEY (id);
ALTER TABLE password_reset_tokens ADD PRIMARY KEY (id);

-- 9) Gắn lại NOT NULL cho area_id
ALTER TABLE area_change_logs ALTER COLUMN area_id SET NOT NULL;

-- 10) Gắn lại khoá ngoại, giữ nguyên tên cũ
ALTER TABLE areas ADD CONSTRAINT areas_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE areas ADD CONSTRAINT areas_updated_by_fkey
    FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE area_change_logs ADD CONSTRAINT area_change_logs_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES users(id);
ALTER TABLE area_change_logs ADD CONSTRAINT area_change_logs_area_id_fkey
    FOREIGN KEY (area_id) REFERENCES areas(id);

-- 11) Xoá sequence không còn dùng
DROP SEQUENCE IF EXISTS users_id_seq;
DROP SEQUENCE IF EXISTS areas_id_seq;
DROP SEQUENCE IF EXISTS area_change_logs_id_seq;
DROP SEQUENCE IF EXISTS password_reset_tokens_id_seq;
