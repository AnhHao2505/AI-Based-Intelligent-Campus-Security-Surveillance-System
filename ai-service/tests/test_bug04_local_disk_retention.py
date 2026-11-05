import os
import time
import tempfile
import shutil
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch
import pytest

from src.pipeline.stream_recorder import (
    mark_upload_active,
    mark_upload_finished,
    is_upload_active,
    enforce_local_recording_retention,
    enforce_emergency_evidence_retention,
    retry_pending_uploads,
    StreamRecorder,
)
from src.pipeline.incident_recorder import IncidentRecorder


def test_evidence_not_deleted_when_minio_offline_even_with_50_files():
    """
    BUG-04 (a): IncidentRecorder KHÔNG được xóa video bằng chứng chưa upload,
    ngay cả khi MinIO offline và tích lũy tới 50 file bằng chứng.
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        evidence_dir = os.path.join(tmp_dir, "evidence")
        cam_dir = os.path.join(evidence_dir, "CAM-EVID-01", "2026", "11", "05")
        os.makedirs(cam_dir, exist_ok=True)

        created_files = []
        base_time = time.time() - 3600
        for i in range(50):
            fp = os.path.join(cam_dir, f"Incident 05-11-2026 - 10-{i:02d}-00.mp4")
            with open(fp, "wb") as f:
                f.write(b"evidence data content " * 100)
            os.utime(fp, (base_time + i * 10, base_time + i * 10))
            created_files.append(fp)

        # Giả lập upload thất bại cho file thứ 50
        incident_rec = IncidentRecorder(
            incident_id="INC-OFFLINE-50",
            camera_code="CAM-EVID-01",
            base_dir=evidence_dir,
        )
        mock_storage = MagicMock()
        mock_storage.upload_video_file.return_value = False  # MinIO offline

        mock_disk = MagicMock()
        mock_disk.free = 8 * 1024 * 1024 * 1024  # 8GB free > 500MB emergency

        with patch("src.integration.storage_service.storage_service", mock_storage), \
             patch("shutil.disk_usage", return_value=mock_disk):
            now = datetime.now(timezone(timedelta(hours=7)))
            incident_rec._schedule_minio_upload(
                file_path=created_files[-1],
                camera_code="CAM-EVID-01",
                start_dt=now - timedelta(seconds=60),
                end_dt=now,
                frames_count=30,
                incident_event_id="INC-OFFLINE-50",
            )
            time.sleep(0.3)

        # Xác nhận toàn bộ 50 file bằng chứng KHÔNG bị xóa
        remaining_files = []
        for root, _, files in os.walk(evidence_dir):
            for f in files:
                if f.endswith(".mp4"):
                    remaining_files.append(os.path.join(root, f))

        assert len(remaining_files) == 50, f"Evidence không được xóa khi chưa upload! Còn {len(remaining_files)} file"


def test_stream_recording_purged_by_disk_usage_quota():
    """
    BUG-04 (b, c): StreamRecorder cắt giảm các file ghi liên tục cũ nhất
    theo hạn ngạch dung lượng toàn cục (MB) đo trên toàn bộ thư mục.
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        recording_dir = os.path.join(tmp_dir, "recording")
        cam_dir = os.path.join(recording_dir, "CAM-01", "2026", "11", "05")
        os.makedirs(cam_dir, exist_ok=True)

        # Tạo 5 file, mỗi file 1 MB = 1048576 bytes
        one_mb = b"X" * 1048576
        file_paths = []
        base_time = time.time() - 1000
        for i in range(5):
            fp = os.path.join(cam_dir, f"seg_{i:02d}.mp4")
            with open(fp, "wb") as f:
                f.write(one_mb)
            os.utime(fp, (base_time + i * 20, base_time + i * 20))
            file_paths.append(fp)

        # Tổng dung lượng hiện tại = 5 MB. Đặt quota max_mb = 3 MB
        removed = enforce_local_recording_retention(recording_dir, max_mb=3)

        # Cần xóa 2 file cũ nhất (seg_00, seg_01) để còn 3 MB
        assert len(removed) == 2
        assert file_paths[0] in removed
        assert file_paths[1] in removed
        assert os.path.exists(file_paths[2])
        assert os.path.exists(file_paths[3])
        assert os.path.exists(file_paths[4])


def test_emergency_disk_full_purges_evidence_and_logs_error(caplog):
    """
    BUG-04 (c): Video evidence chỉ bị xóa khi ổ đĩa gần đầy khẩn cấp (< EMERGENCY_DISK_FREE_MIN_MB),
    và khi xóa phải ghi log ERROR.
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        evidence_dir = os.path.join(tmp_dir, "evidence")
        os.makedirs(evidence_dir, exist_ok=True)

        for i in range(3):
            fp = os.path.join(evidence_dir, f"evid_{i}.mp4")
            with open(fp, "wb") as f:
                f.write(b"data")
            os.utime(fp, (time.time() - 100 + i * 10, time.time() - 100 + i * 10))

        # Giả lập đĩa còn 100 MB free (< 500 MB emergency threshold)
        mock_usage = MagicMock()
        mock_usage.free = 100 * 1024 * 1024

        with patch("shutil.disk_usage", return_value=mock_usage):
            with caplog.at_level("ERROR"):
                removed = enforce_emergency_evidence_retention(evidence_dir, emergency_free_mb=500)
                assert len(removed) > 0, "Phải xóa file khi mức đĩa chạm ngưỡng khẩn cấp"
                assert any("EMERGENCY" in record.message for record in caplog.records), "Phải log ERROR khi xóa evidence khẩn cấp"


def test_retry_pending_uploads_when_minio_back_online():
    """
    BUG-04 (d): Job thử upload lại các file tồn khi MinIO hoạt động lại.
    Sau khi upload thành công, xóa file local và phát MQTT telemetry.
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        recording_dir = os.path.join(tmp_dir, "recording")
        cam_dir = os.path.join(recording_dir, "CAM-REC-01", "2026", "11", "05")
        os.makedirs(cam_dir, exist_ok=True)

        pending_file = os.path.join(cam_dir, "05-11-2026 - 08-00-00.mp4")
        with open(pending_file, "wb") as f:
            f.write(b"pending video recording content")
        os.utime(pending_file, (time.time() - 60, time.time() - 60))

        mock_storage = MagicMock()
        mock_storage.upload_video_file.return_value = True  # MinIO đã online lại!
        mock_mqtt = MagicMock()

        uploaded_count = retry_pending_uploads(
            storage_service=mock_storage,
            mqtt_client=mock_mqtt,
            recording_dir=recording_dir,
            evidence_dir=os.path.join(tmp_dir, "empty_evidence"),
            max_batch=5,
        )

        assert uploaded_count == 1
        mock_storage.upload_video_file.assert_called_once()
        mock_mqtt.publish_recording_event.assert_called_once()
        # File cục bộ phải được xóa sau khi upload thành công
        assert not os.path.exists(pending_file), "File local phải được dọn dẹp sau khi retry upload thành công"


def test_retry_pending_uploads_excludes_inprogress_and_active_uploads():
    """
    BUG-04 (b - Đợt 4.6): retry_pending_uploads phải loại trừ:
    - File đang ghi dở có đuôi .recording.mp4
    - File đang ghi dở có mtime vừa được ghi trong vòng < 5s
    - File đang nằm trong active upload set (_ACTIVE_UPLOADS)
    Chỉ upload những file đã hoàn tất, không bị lock, và đã idle > 5s.
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        recording_dir = os.path.join(tmp_dir, "recording")
        cam_dir = os.path.join(recording_dir, "CAM-REC-02", "2026", "11", "05")
        os.makedirs(cam_dir, exist_ok=True)

        now = time.time()

        # 1. File đang ghi dở có đuôi .recording.mp4
        rec_in_progress = os.path.join(cam_dir, "in_progress.recording.mp4")
        with open(rec_in_progress, "wb") as f:
            f.write(b"data recording")
        os.utime(rec_in_progress, (now - 60, now - 60))

        # 2. File vừa được ghi / đang được ghi (mtime chỉ cách hiện tại 1s < 5s)
        recent_file = os.path.join(cam_dir, "just_written.mp4")
        with open(recent_file, "wb") as f:
            f.write(b"recent data")
        os.utime(recent_file, (now - 1.0, now - 1.0))

        # 3. File đang được upload trong luồng khác (active lock)
        active_uploading = os.path.join(cam_dir, "currently_uploading.mp4")
        with open(active_uploading, "wb") as f:
            f.write(b"uploading data")
        os.utime(active_uploading, (now - 60, now - 60))
        mark_upload_active(active_uploading)

        # 4. File hợp lệ, đã xong và sẵn sàng retry upload (mtime > 5s trước)
        ready_file = os.path.join(cam_dir, "ready_for_retry.mp4")
        with open(ready_file, "wb") as f:
            f.write(b"ready data")
        os.utime(ready_file, (now - 60, now - 60))

        try:
            mock_storage = MagicMock()
            mock_storage.upload_video_file.return_value = True
            mock_mqtt = MagicMock()

            uploaded_count = retry_pending_uploads(
                storage_service=mock_storage,
                mqtt_client=mock_mqtt,
                recording_dir=recording_dir,
                evidence_dir=os.path.join(tmp_dir, "empty_evidence"),
                max_batch=10,
                min_age_seconds=5.0,
            )

            # Chỉ đúng 1 file (ready_file) được upload
            assert uploaded_count == 1, f"Chỉ được upload 1 file hợp lệ, thực tế uploaded: {uploaded_count}"
            mock_storage.upload_video_file.assert_called_once()
            called_path = mock_storage.upload_video_file.call_args[0][0]
            assert os.path.normpath(called_path) == os.path.normpath(ready_file)

            # Các file đang ghi dở và đang upload phải vẫn còn trên đĩa
            assert os.path.exists(rec_in_progress), "File .recording.mp4 không được upload hoặc xóa"
            assert os.path.exists(recent_file), "File <5s mtime không được upload hoặc xóa"
            assert os.path.exists(active_uploading), "File đang upload không được đụng vào"
            # File ready_file phải đã được upload và xóa
            assert not os.path.exists(ready_file), "File ready_file phải được dọn sau khi upload"
        finally:
            mark_upload_finished(active_uploading)
