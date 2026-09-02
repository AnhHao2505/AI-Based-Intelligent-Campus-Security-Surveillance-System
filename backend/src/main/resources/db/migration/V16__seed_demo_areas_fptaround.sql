-- V16: Seed 8 Area demo cho FPT_AROUND tang G
-- Muc dich: cung cap du lieu cho trang ban do /admin/areas/map (B2)
-- geometry = NULL co chu dich: phuc vu flow F2 (chon Area co san -> ve -> luu)
-- Khong xoa/sua du lieu san co, chi INSERT.

INSERT INTO areas (code, name, area_level, building, floor, is_active, geometry)
SELECT 'FPTA-G-GATE', 'Cổng chính', 1, 'FPT_AROUND', 'G', true, NULL
WHERE NOT EXISTS (SELECT 1 FROM areas WHERE code = 'FPTA-G-GATE');

INSERT INTO areas (code, name, area_level, building, floor, is_active, geometry)
SELECT 'FPTA-G-LB01', 'Phòng LB01', 3, 'FPT_AROUND', 'G', true, NULL
WHERE NOT EXISTS (SELECT 1 FROM areas WHERE code = 'FPTA-G-LB01');

INSERT INTO areas (code, name, area_level, building, floor, is_active, geometry)
SELECT 'FPTA-G-LB02', 'Phòng LB02', 3, 'FPT_AROUND', 'G', true, NULL
WHERE NOT EXISTS (SELECT 1 FROM areas WHERE code = 'FPTA-G-LB02');

INSERT INTO areas (code, name, area_level, building, floor, is_active, geometry)
SELECT 'FPTA-G-MED', 'Phòng Y tế', 3, 'FPT_AROUND', 'G', true, NULL
WHERE NOT EXISTS (SELECT 1 FROM areas WHERE code = 'FPTA-G-MED');

INSERT INTO areas (code, name, area_level, building, floor, is_active, geometry)
SELECT 'FPTA-G-LIB', 'Thư viện tầng G', 2, 'FPT_AROUND', 'G', true, NULL
WHERE NOT EXISTS (SELECT 1 FROM areas WHERE code = 'FPTA-G-LIB');

INSERT INTO areas (code, name, area_level, building, floor, is_active, geometry)
SELECT 'FPTA-G-LOTUS', 'Hồ Sen tầng G', 1, 'FPT_AROUND', 'G', true, NULL
WHERE NOT EXISTS (SELECT 1 FROM areas WHERE code = 'FPTA-G-LOTUS');

