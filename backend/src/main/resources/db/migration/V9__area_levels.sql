CREATE TABLE area_levels (
    level                     SMALLINT PRIMARY KEY,
    code                      VARCHAR(30)  NOT NULL UNIQUE,
    name                      VARCHAR(100) NOT NULL,
    requires_face_recognition BOOLEAN      NOT NULL DEFAULT TRUE,
    description               TEXT,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO area_levels (level, code, name, requires_face_recognition, description) VALUES
 (1, 'PUBLIC',       'Công cộng',         FALSE,
    'Thư viện, lớp học, sảnh — chỉ phát hiện người, KHÔNG nhận diện khuôn mặt'),
 (2, 'SEMI_PRIVATE', 'Bán hạn chế',       TRUE,
    'Phòng lab, kho thiết bị'),
 (3, 'PRIVATE',      'Hạn chế tuyệt đối', TRUE,
    'Phòng server, phòng ban giám hiệu');
