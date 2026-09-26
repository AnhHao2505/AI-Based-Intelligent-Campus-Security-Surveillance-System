-- V54: Bổ sung chế độ sự kiện (open_to_members) cho khu vực và mở rộng action audit log

ALTER TABLE areas ADD COLUMN open_to_members BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE areas ADD COLUMN open_until TIMESTAMPTZ NULL;
ALTER TABLE areas ADD CONSTRAINT chk_areas_open_to_members CHECK (open_to_members = false OR open_until IS NOT NULL);

ALTER TABLE access_control_audit_logs DROP CONSTRAINT IF EXISTS chk_audit_action;
ALTER TABLE access_control_audit_logs ADD CONSTRAINT chk_audit_action CHECK (action::text = ANY (ARRAY['ASSIGN'::character varying, 'UPDATE_VALIDITY'::character varying, 'REVOKE'::character varying, 'UPDATE'::character varying, 'ENABLE_EVENT_MODE'::character varying, 'DISABLE_EVENT_MODE'::character varying]::text[]));
