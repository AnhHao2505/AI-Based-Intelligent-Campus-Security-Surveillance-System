from unittest.mock import MagicMock, patch
import numpy as np
import pytest
from fastapi.testclient import TestClient
from src.main import app, default_pipeline, ROIConfigRequest
from src.core.entity import BoundingBox


@pytest.fixture
def client():
    return TestClient(app)


def test_health_check_reflects_actual_state(client):
    """
    Điểm 9: /health phải phản ánh đúng thực tế, bỏ 'or True'.
    Khi yolo model path không tồn tại và pipeline không có model, yolo_model phải là False.
    Phải có các trường pipeline_ready, db_connected, mqtt_connected, minio_connected.
    """
    with patch("os.path.exists", return_value=False), \
         patch("src.main.default_pipeline", None):
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["models"]["yolo_model"] is False, "yolo_model không được có 'or True'"
        assert data["status"] == "DOWN", "Khi thiếu model và pipeline, status phải là DOWN"
        assert "db_connected" in data
        assert "pipeline_ready" in data


def test_configure_camera_does_not_overwrite_default_pipeline_camera_code(client):
    """
    Điểm 9: configure_camera không được ghi đè camera_code của default_pipeline dùng chung
    khi cấu hình camera khác.
    """
    import src.main as main_mod

    # Giả lập default_pipeline với camera_code ban đầu là 'CAM-DEFAULT'
    mock_pipeline = MagicMock()
    mock_pipeline.camera_code = "CAM-DEFAULT"
    main_mod.default_pipeline = mock_pipeline

    headers = {"X-Service-Key": main_mod.settings.INTERNAL_SERVICE_KEY}
    payload = {
        "camera_code": "CAM-SPECIAL-999",
        "roi_polygon": [{"x": 0.1, "y": 0.1}, {"x": 0.9, "y": 0.9}, {"x": 0.1, "y": 0.9}]
    }

    response = client.post("/api/v1/cameras/configure", json=payload, headers=headers)
    assert response.status_code == 200
    # default_pipeline.camera_code KHÔNG được bị đổi thành CAM-SPECIAL-999!
    assert mock_pipeline.camera_code == "CAM-DEFAULT", (
        f"Lỗi: default_pipeline.camera_code đã bị ghi đè thành {mock_pipeline.camera_code}!"
    )


def test_analyze_frame_does_not_dirty_tracker_state():
    """
    Điểm 9: analyze-frame (hoặc process_single_frame) không được làm bẩn tracker state dùng chung.
    Sau khi phân tích ảnh đơn lẻ, active_tracks của pipeline và ByteTrack tracker state
    không bị lưu lại các track của ảnh tĩnh đó.
    """
    import src.main as main_mod
    from src.pipeline.video_pipeline import VideoPipeline

    with patch("src.pipeline.video_pipeline.HumanDetector") as mock_hd_cls, \
         patch("src.pipeline.video_pipeline.FaceDetector") as mock_fd_cls, \
         patch("src.pipeline.video_pipeline.FaceMatcher"), \
         patch("src.pipeline.video_pipeline.StorageService"), \
         patch("src.pipeline.video_pipeline.get_security_mqtt_client"):

        mock_hd = mock_hd_cls.return_value
        bbox = BoundingBox(10.0, 10.0, 50.0, 100.0)
        mock_hd.detect.return_value = [(1, bbox)]

        mock_fd = mock_fd_cls.return_value
        mock_fd.detect_best_face_in_crop.return_value = None

        pipeline = VideoPipeline(camera_code="CAM-TEST")
        # Kiểm tra trước khi phân tích
        assert len(pipeline.analysis_engine.active_tracks) == 0

        # Phân tích frame đơn lẻ
        test_frame = np.full((480, 640, 3), 120, dtype=np.uint8)
        ann, active_persons, alerts = pipeline.process_single_frame(test_frame)

        # Trả về kết quả phân tích ảnh
        assert len(active_persons) == 1
        # Nhưng tracker state dùng chung của pipeline KHÔNG bị bẩn
        assert len(pipeline.analysis_engine.active_tracks) == 0, (
            "process_single_frame không được lưu track vào active_tracks của pipeline dùng chung"
        )
