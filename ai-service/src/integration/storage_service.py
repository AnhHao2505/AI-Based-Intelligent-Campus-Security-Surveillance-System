import io
import os
import time
import logging
import cv2
import numpy as np
from typing import Optional, List
from minio.sse import SseS3
from ..config import settings

logger = logging.getLogger(__name__)

class StorageService:
    """
    Module quản lý lưu trữ đối tượng MinIO S3-compatible (hỗ trợ mã hóa SSE-S3):
    1. Bucket 'face-profiles': Lưu ảnh mặt chính diện của sinh viên / nhân sự ({code}.jpg)
    2. Bucket 'security-evidence': Lưu ảnh & video bằng chứng vi phạm an ninh
    3. Bucket 'camera-recordings': Lưu video ghi hình định kỳ 1 phút/phân đoạn
    4. Bucket 'camera-snapshots': Lưu ảnh snapshot ROI & trích xuất qua Cloud-Edge MQTT RPC
    """
    def __init__(self):
        self.client = None
        self.is_connected = False
        self.bucket_faces = settings.MINIO_BUCKET_FACES
        self.bucket_evidence = settings.MINIO_BUCKET_EVIDENCE
        self.bucket_recordings = getattr(settings, "MINIO_BUCKET_RECORDINGS", "camera-recordings")
        self.bucket_snapshots = getattr(settings, "MINIO_BUCKET_SNAPSHOTS", "camera-snapshots")
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
            for bucket in [self.bucket_faces, self.bucket_evidence, self.bucket_recordings, self.bucket_snapshots]:
                if not self.client.bucket_exists(bucket):
                    self.client.make_bucket(bucket)
                    logger.info(f"Đã tạo bucket mới: {bucket}")

            self.is_connected = True
            logger.info(
                f"Kết nối MinIO thành công tới {settings.MINIO_ENDPOINT} "
                f"(Buckets: {self.bucket_faces}, {self.bucket_evidence}, {self.bucket_recordings}, {self.bucket_snapshots})"
            )
        except Exception as e:
            self.is_connected = False
            logger.warning(f"Không thể kết nối tới MinIO ({settings.MINIO_ENDPOINT}): {e}. Chuyển sang lưu ảnh local.")

    def upload_bytes(self, bucket_name: str, object_name: str, data: bytes, content_type: str = "image/jpeg") -> bool:
        """Tải dữ liệu bytes lên MinIO với mã hóa SSE-S3 (kèm cơ chế fallback an toàn)."""
        if not data:
            return False
        if not self.is_connected or not self.client:
            self._init_minio()
        if not self.is_connected or not self.client:
            return False

        try:
            if not self.client.bucket_exists(bucket_name):
                self.client.make_bucket(bucket_name)
            try:
                self.client.put_object(
                    bucket_name=bucket_name,
                    object_name=object_name,
                    data=io.BytesIO(data),
                    length=len(data),
                    content_type=content_type,
                    sse=SseS3()
                )
            except Exception as sse_err:
                logger.warning(f"[MINIO] SSE-S3 upload gặp lỗi ({sse_err}), fallback sang lưu trữ tiêu chuẩn")
                self.client.put_object(
                    bucket_name=bucket_name,
                    object_name=object_name,
                    data=io.BytesIO(data),
                    length=len(data),
                    content_type=content_type
                )
            return True
        except Exception as e:
            logger.error(f"[MINIO LỖI] upload_bytes thất bại ({bucket_name}/{object_name}): {e}")
            return False

    def download_bytes(self, bucket_name: str, object_name: str) -> Optional[bytes]:
        """Tải nội dung nhị phân từ MinIO (phục vụ xử lý ảnh staging qua Cloud-Edge MQTT RPC)."""
        if not bucket_name or not object_name:
            return None
        if not self.is_connected or not self.client:
            self._init_minio()
        if not self.is_connected or not self.client:
            return None

        response = None
        try:
            response = self.client.get_object(bucket_name, object_name)
            return response.read()
        except Exception as e:
            logger.warning(f"[MINIO] Không thể tải object {bucket_name}/{object_name}: {e}")
            return None
        finally:
            if response is not None:
                try:
                    response.close()
                    response.release_conn()
                except Exception:
                    pass

    def upload_face_image(self, image_bytes: bytes, code: str, angle: str) -> Optional[str]:
        if not image_bytes:
            return None

        file_name = f"{code}_{angle}.jpg"
        object_name = f"{code}/{file_name}"

        if self.upload_bytes(self.bucket_faces, object_name, image_bytes, "image/jpeg"):
            schema = "https" if settings.MINIO_SECURE else "http"
            minio_url = f"{schema}://{settings.MINIO_ENDPOINT}/{self.bucket_faces}/{object_name}"
            logger.info(f"[MINIO SSE-S3] Đã lưu ảnh khuôn mặt hồ sơ : {minio_url}")
            return minio_url

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

        if self.upload_bytes(self.bucket_evidence, object_name, file_bytes, "image/jpeg"):
            object_key = f"{self.bucket_evidence}/{object_name}"
            logger.info(f"[MINIO SSE-S3] Đã lưu ảnh bằng chứng vi phạm: {object_key}")
            return object_key

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
        if not file_path or not os.path.exists(file_path):
            logger.warning(f"⚠️ [MinIO] File video không tồn tại: {file_path}")
            return False

        if not self.is_connected or not self.client:
            self._init_minio()

        if self.is_connected and self.client:
            try:
                if not self.client.bucket_exists(bucket_name):
                    self.client.make_bucket(bucket_name)

                try:
                    self.client.fput_object(
                        bucket_name=bucket_name,
                        object_name=object_name,
                        file_path=file_path,
                        content_type="video/mp4",
                        sse=SseS3()
                    )
                except Exception as sse_err:
                    logger.warning(f"[MINIO] SSE-S3 fput_object gặp lỗi ({sse_err}), fallback sang upload tiêu chuẩn")
                    self.client.fput_object(
                        bucket_name=bucket_name,
                        object_name=object_name,
                        file_path=file_path,
                        content_type="video/mp4"
                    )
                logger.info(f"☁️ [MinIO SSE-S3] Đã tải video thành công lên bucket [{bucket_name}]: {object_name}")
                return True
            except Exception as e:
                logger.error(f"❌ [MinIO LỖI] Upload video thất bại ({file_path} -> {bucket_name}/{object_name}): {e}")
                return False
        return False

    def enforce_recording_retention(self, camera_code: str, max_segments: int = 30) -> List[str]:
        """
        Tự động xoay vòng lưu trữ (Rolling Retention) cho bucket 'camera-recordings':
        Chỉ giữ lại tối đa `max_segments` phân đoạn 1 phút mới nhất của mỗi camera và trả về danh sách object_name đã xóa.
        """
        deleted_keys: List[str] = []
        if not self.is_connected or not self.client or not camera_code:
            return deleted_keys
        try:
            prefix = f"{camera_code.strip()}/"
            objs = list(self.client.list_objects(self.bucket_recordings, prefix=prefix, recursive=True))
            mp4_objs = [o for o in objs if o.object_name and o.object_name.lower().endswith(".mp4")]
            if len(mp4_objs) <= max_segments:
                return deleted_keys
            mp4_objs.sort(key=lambda x: x.last_modified or 0)
            to_delete = mp4_objs[: len(mp4_objs) - max_segments]
            for old_obj in to_delete:
                self.client.remove_object(self.bucket_recordings, old_obj.object_name)
                deleted_keys.append(old_obj.object_name)
                logger.info(f"♻️ [Retention] Đã xóa video định kỳ cũ trên MinIO: {old_obj.object_name}")
        except Exception as e:
            logger.warning(f"⚠️ [Retention] Không thể dẹp video cũ cho camera [{camera_code}]: {e}")
        return deleted_keys

# Global singleton instance for storage service
storage_service = StorageService()
