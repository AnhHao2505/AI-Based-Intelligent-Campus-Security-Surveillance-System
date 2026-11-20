import os
import unittest
import numpy as np
import cv2

from src.config import settings
from src.core.face_detector import FaceDetector
from src.core.face_embedder import FaceEmbedder


class TestSCRFDDetector(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Đường dẫn mô hình SCRFD
        cls.scrfd_path = settings.MODEL_SCRFD_PATH
        cls.test_video = "D:/DoAnSE/test_video.mp4"
        
        # Lấy một khung hình mẫu từ test_video nếu có
        cls.sample_frame = None
        if os.path.exists(cls.test_video):
            cap = cv2.VideoCapture(cls.test_video)
            for _ in range(15):
                ret, frame = cap.read()
                if ret and frame is not None:
                    cls.sample_frame = frame
            cap.release()

        # Ảnh chân dung cận mặt độ phân giải lớn (cạnh dài >= 1000 px) để kiểm bẫy mặt to của SCRFD.
        # Không tải từ MinIO, không ghi thông tin đăng nhập hay dùng ảnh người dùng thật trong test:
        # đặt đường dẫn ảnh (tự chụp / đã được đồng ý) qua biến môi trường ICSS_TEST_LARGE_PORTRAIT, thiếu thì bỏ qua.
        cls.large_portrait_image = None
        portrait_path = os.environ.get("ICSS_TEST_LARGE_PORTRAIT")
        if portrait_path and os.path.exists(portrait_path):
            cls.large_portrait_image = cv2.imread(portrait_path)

    def test_scrfd_init_default(self):
        """Khởi tạo FaceDetector mặc định sẽ dùng SCRFD-2.5G"""
        detector = FaceDetector()
        self.assertEqual(detector.backend, "scrfd")
        self.assertIsNotNone(detector.session)

    def test_scrfd_detection_on_sample_frame(self):
        """Kiểm tra SCRFD phát hiện khuôn mặt và trả về đủ 5 điểm mốc landmarks"""
        if self.sample_frame is None:
            self.skipTest("Không tìm thấy test_video.mp4")

        detector = FaceDetector()
        faces = detector.detect_in_image(self.sample_frame)
        
        self.assertGreaterEqual(len(faces), 1, "SCRFD phải phát hiện được ít nhất 1 mặt trên frame mẫu")
        face = faces[0]
        self.assertGreater(face.score, 0.4)
        self.assertIsNotNone(face.bbox)
        self.assertGreater(face.bbox.x2, face.bbox.x1)
        self.assertGreater(face.bbox.y2, face.bbox.y1)
        
        # Xác nhận 5 điểm mốc
        self.assertEqual(len(face.landmarks), 5)
        for pt in face.landmarks:
            self.assertEqual(len(pt), 2)
            self.assertGreaterEqual(pt[0], 0)
            self.assertGreaterEqual(pt[1], 0)

    def test_scrfd_detect_best_face_in_crop_with_offset(self):
        """Kiểm tra detect_best_face_in_crop ánh xạ tọa độ và landmarks chính xác khi có offset"""
        if self.sample_frame is None:
            self.skipTest("Không tìm thấy test_video.mp4")

        detector = FaceDetector()
        # Giả lập crop vùng người quanh vị trí mặt
        h, w = self.sample_frame.shape[:2]
        crop_x1, crop_y1 = 450, 250
        crop_x2, crop_y2 = 650, 450
        crop = self.sample_frame[crop_y1:crop_y2, crop_x1:crop_x2]
        
        best_face = detector.detect_best_face_in_crop(crop, offset_xy=(crop_x1, crop_y1))
        self.assertIsNotNone(best_face)
        # Bbox phải nằm trong khoảng toạ độ tuyệt đối của frame
        self.assertGreaterEqual(best_face.bbox.x1, crop_x1)
        self.assertLessEqual(best_face.bbox.x2, crop_x2)
        self.assertGreaterEqual(best_face.bbox.y1, crop_y1)
        self.assertLessEqual(best_face.bbox.y2, crop_y2)

    def test_scrfd_integration_with_face_embedder(self):
        """Kiểm tra 5 điểm mốc của SCRFD tương thích hoàn toàn với Umeyama alignment của FaceEmbedder"""
        if self.sample_frame is None:
            self.skipTest("Không tìm thấy test_video.mp4")

        detector = FaceDetector()
        embedder = FaceEmbedder()
        
        faces = detector.detect_in_image(self.sample_frame)
        self.assertGreaterEqual(len(faces), 1)
        
        landmarks = faces[0].landmarks
        aligned_face = embedder.align_face(self.sample_frame, landmarks=landmarks)
        self.assertEqual(aligned_face.shape, (112, 112, 3))
        
        embedding = embedder.extract_embedding(self.sample_frame, landmarks=landmarks)
        self.assertEqual(len(embedding), 512)
        norm = np.linalg.norm(embedding)
        self.assertAlmostEqual(norm, 1.0, places=3)

    @staticmethod
    def _capture_input_shapes(detector):
        shapes = []
        real_run = detector.session.run

        def spy(output_names, feeds):
            shapes.append(tuple(next(iter(feeds.values())).shape))
            return real_run(output_names, feeds)

        detector.session.run = spy
        return shapes

    def test_scrfd_pads_small_inputs_to_fixed_size(self):
        """Crop người mỗi frame một cỡ -> input SCRFD phải luôn cùng 1 kích thước (tránh ONNX CUDA lập kế hoạch lại)"""
        detector = FaceDetector(fixed_input_size=480)
        shapes = self._capture_input_shapes(detector)
        for h, w in ((130, 90), (300, 210), (480, 480), (217, 455)):
            detector.detect_in_image(np.zeros((h, w, 3), dtype=np.uint8))
        self.assertEqual(set(shapes), {(1, 3, 480, 480)})

    def test_scrfd_crop_upscale_never_exceeds_fixed_input(self):
        """Crop thấp-rộng cần phóng to (cạnh ngắn < 160) vẫn phải vừa khung cố định, không rơi về input đổi cỡ"""
        detector = FaceDetector(fixed_input_size=480, max_dim=480, upscale_threshold=160)
        shapes = self._capture_input_shapes(detector)
        for h, w in ((80, 250), (60, 300), (300, 70), (120, 120), (700, 300)):
            detector.detect_best_face_in_crop(np.zeros((h, w, 3), dtype=np.uint8), offset_xy=(0, 0))
        self.assertEqual(set(shapes), {(1, 3, 480, 480)})

    def test_scrfd_keeps_dynamic_shape_for_large_images(self):
        """Ảnh lớn hơn khung cố định (vd ảnh đăng ký toàn khung hình) không bị thu nhỏ, chỉ đệm lên bội số 32"""
        detector = FaceDetector(fixed_input_size=480)
        shapes = self._capture_input_shapes(detector)
        detector.detect_in_image(np.zeros((720, 1280, 3), dtype=np.uint8))
        self.assertEqual(shapes, [(1, 3, 736, 1280)])

    def test_scrfd_fixed_size_padding_does_not_change_detections(self):
        """Đệm về khung cố định chỉ thêm vùng đen -> kết quả tương đương đường cũ (đệm bội số 32).
        Không trùng tuyệt đối: pixel đệm sau chuẩn hoá là -1 (không phải 0 như zero-padding của conv) nên ô sát mép lệch nhẹ."""
        if self.sample_frame is None:
            self.skipTest("Không tìm thấy test_video.mp4")
        crop = self.sample_frame[250:450, 450:650]
        fixed = FaceDetector(fixed_input_size=480).detect_in_image(crop)
        dynamic = FaceDetector(fixed_input_size=0).detect_in_image(crop)
        self.assertEqual(len(fixed), len(dynamic))
        for a, b in zip(sorted(fixed, key=lambda f: -f.score), sorted(dynamic, key=lambda f: -f.score)):
            self.assertAlmostEqual(a.score, b.score, delta=0.02)
            for va, vb in zip(a.bbox.to_xyxy(), b.bbox.to_xyxy()):
                self.assertAlmostEqual(va, vb, delta=2.0)

    def test_scrfd_detect_in_image_max_dim_resizes_input_to_model(self):
        """Khi truyền max_dim=640, ảnh lớn 960x1280 phải được thu nhỏ về cạnh dài 640 trước khi đưa vào SCRFD"""
        detector = FaceDetector(fixed_input_size=480)
        shapes = self._capture_input_shapes(detector)
        detector.detect_in_image(np.zeros((960, 1280, 3), dtype=np.uint8), max_dim=640)
        # Cạnh dài 1280 thu về 640, cạnh ngắn 960 thu về 480 -> đệm bội số 32 là (480, 640)
        self.assertEqual(shapes, [(1, 3, 480, 640)])

    def test_detect_in_image_max_dim_coordinate_mapping(self):
        """Kiểm tra quy đổi tọa độ bbox và landmarks ngược về hệ tọa độ ảnh gốc khi dùng max_dim kèm offset_xy"""
        from unittest.mock import patch
        from src.core.entity import BoundingBox, FaceDetectionResult

        detector = FaceDetector()
        mock_face = FaceDetectionResult(
            bbox=BoundingBox(x1=50.0, y1=60.0, x2=150.0, y2=200.0, confidence=0.88),
            score=0.88,
            landmarks=[(70.0, 90.0), (130.0, 90.0), (100.0, 120.0), (80.0, 160.0), (120.0, 160.0)]
        )
        # Ảnh 1000x2000 -> scale xuống max_dim=500 -> tỉ lệ 0.25 (new_w=500, new_h=250)
        img = np.zeros((1000, 2000, 3), dtype=np.uint8)
        with patch.object(detector, "_detect_scrfd", return_value=[mock_face]):
            res = detector.detect_in_image(img, offset_xy=(100, 200), max_dim=500)

        self.assertEqual(len(res), 1)
        r = res[0]
        # x = 50 / 0.25 + 100 = 300, y = 60 / 0.25 + 200 = 440
        self.assertAlmostEqual(r.bbox.x1, 300.0, places=3)
        self.assertAlmostEqual(r.bbox.y1, 440.0, places=3)
        self.assertAlmostEqual(r.bbox.x2, 700.0, places=3)
        self.assertAlmostEqual(r.bbox.y2, 1000.0, places=3)
        self.assertAlmostEqual(r.landmarks[0][0], 380.0, places=3)
        self.assertAlmostEqual(r.landmarks[0][1], 560.0, places=3)

    def test_scrfd_large_portrait_face_trap_and_resolution(self):
        """Kiểm tra trực tiếp ảnh chân dung đăng ký lớn (vd 1280x960, đặt qua ICSS_TEST_LARGE_PORTRAIT):
        - Không có max_dim: SCRFD trượt mặt (0 mặt do anchor receptive field quá nhỏ).
        - Có max_dim=640: SCRFD phát hiện 1 mặt xuất sắc, toạ độ bbox và landmarks ánh xạ về ảnh gốc chính xác."""
        if self.large_portrait_image is None:
            self.skipTest("Chưa đặt ICSS_TEST_LARGE_PORTRAIT (ảnh chân dung lớn để kiểm bẫy mặt to)")

        detector = FaceDetector()
        h, w = self.large_portrait_image.shape[:2]
        self.assertGreaterEqual(max(h, w), 1000, "Ảnh test phải là ảnh lớn (> 1000px)")

        # 1. Bẫy của SCRFD: ảnh gốc to không resize -> trượt mặt
        raw_faces = detector.detect_in_image(self.large_portrait_image, max_dim=None)
        self.assertEqual(len(raw_faces), 0, "Bẫy SCRFD: trên ảnh 1280x960 không resize, mặt to vượt anchor -> 0 mặt")

        # 2. Khắc phục: tự động thu nhỏ về max_dim=640 -> bắt được mặt chuẩn xác
        fixed_faces = detector.detect_in_image(self.large_portrait_image, max_dim=640)
        self.assertEqual(len(fixed_faces), 1, "Với max_dim=640, SCRFD phải phát hiện được đúng 1 mặt")
        best = fixed_faces[0]
        self.assertGreater(best.score, 0.70)

        # BoundingBox phải nằm trong toạ độ ảnh gốc 1280x960 và mặt phải to (> 500px chiều cao)
        bx1, by1, bx2, by2 = best.bbox.to_xyxy()
        self.assertGreaterEqual(bx1, 0)
        self.assertGreaterEqual(by1, 0)
        self.assertLessEqual(bx2, w)
        self.assertLessEqual(by2, h)
        face_height = by2 - by1
        self.assertGreater(face_height, 500.0, f"Mặt gốc phải lớn (> 500px), thực tế: {face_height}")

        # Landmarks phải đủ 5 điểm và nằm trong toạ độ ảnh gốc
        self.assertEqual(len(best.landmarks), 5)
        for lx, ly in best.landmarks:
            self.assertGreaterEqual(lx, 0)
            self.assertLessEqual(lx, w)
            self.assertGreaterEqual(ly, 0)
            self.assertLessEqual(ly, h)

    def test_registration_max_dim_zero_disables_downscale(self):
        """Cấu hình registration_max_dim=0 (hoặc FACE_REGISTRATION_MAX_DIM=0) phải tắt resize, không bị fallback về 640"""
        detector = FaceDetector(registration_max_dim=0)
        self.assertEqual(detector.registration_max_dim, 0)

        # max_dim=0 thì không resize ảnh lớn
        shapes = self._capture_input_shapes(detector)
        detector.detect_in_image(np.zeros((960, 1280, 3), dtype=np.uint8), max_dim=0)
        self.assertEqual(shapes, [(1, 3, 960, 1280)])

    def test_detect_in_image_max_dim_preserves_none_landmarks(self):
        """Khi model phát hiện mặt không có landmarks, kết quả ánh xạ ngược phải giữ landmarks là None"""
        from unittest.mock import patch
        from src.core.entity import BoundingBox, FaceDetectionResult

        detector = FaceDetector()
        mock_face = FaceDetectionResult(
            bbox=BoundingBox(x1=50.0, y1=60.0, x2=150.0, y2=200.0, confidence=0.88),
            score=0.88,
            landmarks=None
        )
        img = np.zeros((1000, 2000, 3), dtype=np.uint8)
        with patch.object(detector, "_detect_scrfd", return_value=[mock_face]):
            res = detector.detect_in_image(img, max_dim=500)

        self.assertEqual(len(res), 1)
        self.assertIsNone(res[0].landmarks)

    def test_align_face_supports_large_portrait_eye_distance(self):
        """Mặt rất lớn trong ảnh 4K/8K (khoảng cách 2 mắt > 700px) vẫn phải căn chỉnh Umeyama thành công, không bị rơi về resize"""
        from src.core.face_embedder import ARCFACE_CANONICAL_5PTS
        embedder = FaceEmbedder()
        # Giả lập 5 điểm mốc với khoảng cách 2 mắt = 800px trên ảnh 4K 3840x2160
        pts = [
            (1500.0, 1000.0),
            (2300.0, 1000.0),
            (1900.0, 1400.0),
            (1600.0, 1800.0),
            (2200.0, 1800.0)
        ]
        img = np.full((2160, 3840, 3), 128, dtype=np.uint8)
        aligned = embedder.align_face(img, landmarks=pts)
        self.assertEqual(aligned.shape, (112, 112, 3))
        # Xác nhận phép biến đổi lstsq đã được áp dụng (scale ~ 0.048 lọt vào ngưỡng [0.005, 20.0])
        M = embedder._similarity_lstsq(np.array(pts, dtype=np.float32), ARCFACE_CANONICAL_5PTS)
        self.assertIsNotNone(M)
        scale = np.sqrt(M[0, 0]**2 + M[0, 1]**2)
        self.assertLess(scale, 0.05)
        self.assertGreaterEqual(scale, 0.005)


if __name__ == "__main__":
    unittest.main()
