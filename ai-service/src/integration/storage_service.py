import io
import os
import time
import logging
import cv2
import numpy as np
from typing import Optional
from minio.sse import SseS3
from ..config import settings

logger = logging.getLogger(__name__)

class StorageService:
    """
    Module quản lý lưu trữ đối tượng MinIO S3-compatible (hỗ trợ mã hóa SSE-S3):
    1. Bucket 'face-profiles': Lưu ảnh mặt chính diện của sinh viên / nhân sự ({code}.jpg)
    2. Bucket 'security-evidence': Lưu ảnh & video bằng chứng vi phạm an ninh
    3. Bucket 'camera-recordings': Lưu video ghi hình định kỳ 1 phút/phân đoạn
    """
    def __init__(self):
        self.client = None
        self.is_connected = False
        self.bucket_faces = settings.MINIO_BUCKET_FACES
        self.bucket_evidence = settings.MINIO_BUCKET_EVIDENCE
        self.bucket_recordings = getattr(settings, "MINIO_BUCKET_RECORDINGS", "camera-recordings")
        self._init_minio()

    def _init_minio(self):
        if not settings.MINIO_ENABLED:
            logger.info("MinIO storage đang ở trạng thái TẮT.")
            return

        try:
            import urllib3
            from minio import Minio
            http_client = urllib3.PoolManager(
                timeout=urllib3.Timeout(connect=2.0, read=30.0),
                retries=urllib3.Retry(total=1, backoff_factor=0.2)
            )
            self.client = Minio(
                endpoint=settings.MINIO_ENDPOINT,
                access_key=settings.MINIO_ACCESS_KEY,
                secret_key=settings.MINIO_SECRET_KEY,
                secure=settings.MINIO_SECURE,
                http_client=http_client
            )
            # Kiểm tra và tạo tự động các Buckets nếu chưa có
            for bucket in [self.bucket_faces, self.bucket_evidence, self.bucket_recordings]:
                if not self.client.bucket_exists(bucket):
                    self.client.make_bucket(bucket)
                    logger.info(f"Đã tạo bucket mới: {bucket}")

            self.is_connected = True
            logger.info(f"Kết nối MinIO thành công tới {settings.MINIO_ENDPOINT} (Buckets: {self.bucket_faces}, {self.bucket_evidence}, {self.bucket_recordings})")
        except Exception as e:
            self.is_connected = False
            logger.warning(f"Không thể kết nối tới MinIO ({settings.MINIO_ENDPOINT}): {e}. Chuyển sang lưu ảnh local.")

    def upload_face_image(self, image_bytes: bytes, code: str, angle: str) -> Optional[str]:
        """
        Lưu ảnh khuôn mặt hồ sơ vào Bucket 'face-profiles' (có mã hóa SSE-S3):
        Đường dẫn: face-profiles/{code}/{code}_{angle}.jpg
        """
        if not image_bytes:
            return None

        if not self.is_connected or not self.client:
            self._init_minio()

        file_name = f"{code}_{angle}.jpg"
        object_name = f"{code}/{file_name}"

        if self.is_connected and self.client:
            try:
                self.client.put_object(
                    bucket_name=self.bucket_faces,
                    object_name=object_name,
                    data=io.BytesIO(image_bytes),
                    length=len(image_bytes),
                    content_type="image/jpeg",
                    sse=SseS3()
                )
                schema = "https" if settings.MINIO_SECURE else "http"
                minio_url = f"{schema}://{settings.MINIO_ENDPOINT}/{self.bucket_faces}/{object_name}"
                logger.info(f"[MINIO SSE-S3] Đã lưu ảnh khuôn mặt hồ sơ : {minio_url}")
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

    def delete_face_profile(self, code: str) -> bool:
        """
        Xóa toàn bộ ảnh hồ sơ của mã đối tượng code khỏi Bucket 'face-profiles':
        Ví dụ: face-profiles/{code}/...
        """
        if not self.is_connected or not self.client:
            self._init_minio()

        if self.is_connected and self.client:
            try:
                objects_to_delete = self.client.list_objects(self.bucket_faces, prefix=f"{code}/", recursive=True)
                for obj in objects_to_delete:
                    self.client.remove_object(self.bucket_faces, obj.object_name)
                    logger.info(f"[MINIO] Đã xóa ảnh hồ sơ: {obj.object_name}")
                return True
            except Exception as e:
                logger.error(f"[MINIO LỖI] Xóa ảnh hồ sơ thất bại: {e}")
                return False
        return False

    def upload_frame_evidence(
        self,
        frame: np.ndarray,
        camera_code: str,
        track_id: int,
        event_type: str = "INCIDENT"
    ) -> Optional[str]:
        """
        Lưu ảnh quả tang vi phạm vào Bucket 'security-evidence' (mã hóa SSE-S3):
        Đường dẫn: security-evidence/{YYYY-MM-DD}/{camera_code}/{event_type}_Track{track_id}_{HHmmss}.jpg
        """
        if frame is None or frame.size == 0:
            return None

        ret, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
        if not ret:
            return None

        file_bytes = buf.tobytes()
        date_str = time.strftime("%Y-%m-%d")
        time_str = time.strftime("%H%M%S")
        file_name = f"{event_type}_Track{track_id}_{time_str}.jpg"
        object_name = f"{date_str}/{camera_code}/{file_name}"

        if not self.is_connected or not self.client:
            self._init_minio()

        if self.is_connected and self.client:
            try:
                self.client.put_object(
                    bucket_name=self.bucket_evidence,
                    object_name=object_name,
                    data=io.BytesIO(file_bytes),
                    length=len(file_bytes),
                    content_type="image/jpeg",
                    sse=SseS3()
                )
                object_key = f"{self.bucket_evidence}/{object_name}"
                logger.info(f"[MINIO SSE-S3] Đã lưu ảnh bằng chứng vi phạm: {object_key}")
                return object_key
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

    def upload_video_file(self, file_path: str, bucket_name: str, object_name: str) -> bool:
        """
        Tải tệp video (.mp4) lên MinIO bucket với mã hóa SSE-S3.
        Hỗ trợ Edge-Cloud Architecture lưu trữ video hợp nhất trên MinIO S3.
        """
        if not file_path or not os.path.exists(file_path):
            logger.warning(f"⚠️ [MinIO] File video không tồn tại: {file_path}")
            return False

        if not self.is_connected or not self.client:
            self._init_minio()

        if self.is_connected and self.client:
            try:
                if not self.client.bucket_exists(bucket_name):
                    self.client.make_bucket(bucket_name)

                self.client.fput_object(
                    bucket_name=bucket_name,
                    object_name=object_name,
                    file_path=file_path,
                    content_type="video/mp4",
                    sse=SseS3()
                )
                logger.info(f"☁️ [MinIO SSE-S3] Đã tải video thành công lên bucket [{bucket_name}]: {object_name}")
                return True
            except Exception as e:
                logger.error(f"❌ [MinIO LỖI] Upload video thất bại ({file_path} -> {bucket_name}/{object_name}): {e}")
                return False
        return False

# Global singleton instance for storage service
storage_service = StorageService()
