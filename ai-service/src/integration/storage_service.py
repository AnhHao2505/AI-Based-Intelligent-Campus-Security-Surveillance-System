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
    Module quản lý lưu trữ ảnh bằng chứng vi phạm lên MinIO S3-compatible Object Storage.
    Có cơ chế lưu file cục bộ (local cache/fallback) nếu MinIO tạm thời chưa khởi động.
    """
    def __init__(self):
        self.client = None
        self.is_connected = False
        self.bucket_name = settings.MINIO_BUCKET_NAME
        self._init_minio()

    def _init_minio(self):
        if not settings.MINIO_ENABLED:
            logger.info("MinIO storage đang ở trạng thái TẮT.")
            return

        try:
            import urllib3
            from minio import Minio
            # Cấu hình timeout ngắn (1s) để không bị block khi chạy local standalone
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
            # Kiểm tra hoặc tạo bucket nếu chưa có
            if not self.client.bucket_exists(self.bucket_name):
                self.client.make_bucket(self.bucket_name)
            self.is_connected = True
            logger.info(f"Kết nối MinIO thành công tới {settings.MINIO_ENDPOINT} (Bucket: {self.bucket_name})")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"Không thể kết nối tới MinIO ({settings.MINIO_ENDPOINT}): {e}. Chuyển sang lưu ảnh local.")

    def upload_frame_evidence(self, frame: np.ndarray, camera_code: str, track_id: int) -> Optional[str]:
        """
        Lưu ảnh vi phạm vào MinIO (hoặc local fallback) và trả về đường dẫn URI.
        """
        if frame is None or frame.size == 0:
            return None

        # Encode frame sang định dạng JPEG
        ret, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
        if not ret:
            return None

        file_bytes = buf.tobytes()
        timestamp_str = int(time.time())
        file_name = f"evidence_{camera_code}_track{track_id}_{timestamp_str}.jpg"
        object_name = f"alerts/{camera_code}/{file_name}"

        if self.is_connected and self.client:
            try:
                self.client.put_object(
                    bucket_name=self.bucket_name,
                    object_name=object_name,
                    data=io.BytesIO(file_bytes),
                    length=len(file_bytes),
                    content_type="image/jpeg"
                )
                minio_url = f"{settings.MINIO_ENDPOINT}/{self.bucket_name}/{object_name}"
                logger.info(f"[MINIO] Đã upload ảnh bằng chứng: {minio_url}")
                return minio_url
            except Exception as e:
                logger.error(f"[MINIO LỖI] Upload thất bại: {e}")

        # Fallback: Lưu vào thư mục local /tmp/evidence
        local_dir = os.path.join(os.getcwd(), "evidence_snapshots")
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

    def upload_face_image(self, image_bytes: bytes, code: str, angle: str) -> Optional[str]:
        """
        Lưu ảnh khuôn mặt mẫu của nhân viên/sinh viên vào MinIO (hoặc local fallback).
        """
        if not image_bytes:
            return None

        timestamp_str = int(time.time())
        file_name = f"{code}_{angle}_{timestamp_str}.jpg"
        object_name = f"faces/{code}/{file_name}"

        if self.is_connected and self.client:
            try:
                self.client.put_object(
                    bucket_name=self.bucket_name,
                    object_name=object_name,
                    data=io.BytesIO(image_bytes),
                    length=len(image_bytes),
                    content_type="image/jpeg"
                )
                minio_url = f"{settings.MINIO_ENDPOINT}/{self.bucket_name}/{object_name}"
                logger.info(f"[MINIO] Đã upload ảnh khuôn mặt: {minio_url}")
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

