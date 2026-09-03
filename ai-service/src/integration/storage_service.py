import io
import os
import time
import logging
import cv2
import numpy as np
from typing import Optional
from ..config import settings

logger = logging.getLogger(__name__)

class StorageService:
    """
    Module quản lý lưu trữ đối tượng MinIO S3-compatible:
    1. Bucket 'face-profiles': Lưu ảnh hồ sơ mẫu 3 góc của sinh viên / nhân sự ({code}/{code}_{angle}.jpg)
    2. Bucket 'security-evidence': Lưu ảnh bằng chứng vi phạm an ninh ({YYYY-MM-DD}/{camera_code}/...)
    """
    def __init__(self):
        self.client = None
        self.is_connected = False
        self.bucket_faces = settings.MINIO_BUCKET_FACES
        self.bucket_evidence = settings.MINIO_BUCKET_EVIDENCE
        self._init_minio()

    def _init_minio(self):
        if not settings.MINIO_ENABLED:
            logger.info("MinIO storage đang ở trạng thái TẮT.")
            return

        try:
            import urllib3
            from minio import Minio
            http_client = urllib3.PoolManager(
                timeout=urllib3.Timeout(connect=1.0, read=2.0),
                retries=urllib3.Retry(total=1, backoff_factor=0.2)
            )
            self.client = Minio(
                endpoint=settings.MINIO_ENDPOINT,
                access_key=settings.MINIO_ACCESS_KEY,
                secret_key=settings.MINIO_SECRET_KEY,
                secure=settings.MINIO_SECURE,
                http_client=http_client
            )
            # Kiểm tra và tạo tự động 2 Buckets nếu chưa có
            for bucket in [self.bucket_faces, self.bucket_evidence]:
                if not self.client.bucket_exists(bucket):
                    self.client.make_bucket(bucket)
                    logger.info(f"Đã tạo bucket mới: {bucket}")

            self.is_connected = True
            logger.info(f"Kết nối MinIO thành công tới {settings.MINIO_ENDPOINT} (Buckets: {self.bucket_faces}, {self.bucket_evidence})")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"Không thể kết nối tới MinIO ({settings.MINIO_ENDPOINT}): {e}. Chuyển sang lưu ảnh local.")

    def upload_face_image(self, image_bytes: bytes, code: str, angle: str) -> Optional[str]:
        """
        Lưu ảnh khuôn mặt hồ sơ vào Bucket 'face-profiles':
        Đường dẫn: face-profiles/{code}/{code}_{angle}.jpg
        """
        if not image_bytes:
            return None

        file_name = f"{code}_{angle}.jpg"
        object_name = f"{code}/{file_name}"

        if self.is_connected and self.client:
            try:
                self.client.put_object(
                    bucket_name=self.bucket_faces,
                    object_name=object_name,
                    data=io.BytesIO(image_bytes),
                    length=len(image_bytes),
                    content_type="image/jpeg"
                )
                schema = "https" if settings.MINIO_SECURE else "http"
                minio_url = f"{schema}://{settings.MINIO_ENDPOINT}/{self.bucket_faces}/{object_name}"
                logger.info(f"[MINIO] Đã lưu ảnh khuôn mặt hồ sơ: {minio_url}")
                return minio_url
            except Exception as e:
                logger.error(f"[MINIO LỖI] Upload ảnh khuôn mặt thất bại: {e}")

        # Local fallback
        local_dir = os.path.join(os.getcwd(), "face_storage", code)
        os.makedirs(local_dir, exist_ok=True)
        local_path = os.path.join(local_dir, file_name)
        try:
            with open(local_path, "wb") as f:
                f.write(image_bytes)
            logger.info(f"[LOCAL STORAGE] Đã lưu ảnh khuôn mặt cục bộ: {local_path}")
            return local_path
        except Exception as e:
            logger.error(f"Lỗi khi ghi ảnh khuôn mặt local: {e}")
            return None

    def upload_frame_evidence(
        self,
        frame: np.ndarray,
        camera_code: str,
        track_id: int,
        event_type: str = "LOITER"
    ) -> Optional[str]:
        """
        Lưu ảnh quả tang vi phạm vào Bucket 'security-evidence':
        Đường dẫn: security-evidence/{YYYY-MM-DD}/{camera_code}/{event_type}_Track{track_id}_{HHmmss}.jpg
        """
        if frame is None or frame.size == 0:
            return None

        # Encode frame sang định dạng JPEG
        ret, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
        if not ret:
            return None

        file_bytes = buf.tobytes()
        date_str = time.strftime("%Y-%m-%d")
        time_str = time.strftime("%H%M%S")
        file_name = f"{event_type}_Track{track_id}_{time_str}.jpg"
        object_name = f"{date_str}/{camera_code}/{file_name}"

        if self.is_connected and self.client:
            try:
                self.client.put_object(
                    bucket_name=self.bucket_evidence,
                    object_name=object_name,
                    data=io.BytesIO(file_bytes),
                    length=len(file_bytes),
                    content_type="image/jpeg"
                )
                schema = "https" if settings.MINIO_SECURE else "http"
                minio_url = f"{schema}://{settings.MINIO_ENDPOINT}/{self.bucket_evidence}/{object_name}"
                logger.info(f"[MINIO] Đã lưu ảnh bằng chứng vi phạm: {minio_url}")
                return minio_url
            except Exception as e:
                logger.error(f"[MINIO LỖI] Upload bằng chứng thất bại: {e}")

        # Local fallback
        local_dir = os.path.join(os.getcwd(), "evidence_snapshots", date_str, camera_code)
        os.makedirs(local_dir, exist_ok=True)
        local_path = os.path.join(local_dir, file_name)
        try:
            with open(local_path, "wb") as f:
                f.write(file_bytes)
            logger.info(f"[LOCAL STORAGE] Đã lưu ảnh bằng chứng cục bộ: {local_path}")
            return local_path
        except Exception as e:
            logger.error(f"Lỗi khi ghi file local: {e}")
            return None
