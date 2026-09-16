-- Align legacy camera stream columns with the current JPA entity.
ALTER TABLE camera_stream_configurations
    ADD COLUMN IF NOT EXISTS main_stream_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS sub_stream_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS retries_before_alert INT;

UPDATE camera_stream_configurations
SET main_stream_path = COALESCE(main_stream_path, ''),
    retries_before_alert = COALESCE(retries_before_alert, 3);

ALTER TABLE camera_stream_configurations
    ALTER COLUMN main_stream_path SET NOT NULL,
    ALTER COLUMN retries_before_alert SET NOT NULL;