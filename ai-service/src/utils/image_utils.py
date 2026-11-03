import io
import logging
import cv2
import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)

# Đăng ký opener cho định dạng HEIC/HEIF của iPhone / Android
try:
    import pillow_heif
    pillow_heif.register_heif_opener()
    logger.info("Đã kích hoạt hỗ trợ giải mã ảnh HEIC/HEIF tự động.")
except ImportError:
    logger.warning("Thư viện pillow-heif chưa được cài đặt. Chỉ hỗ trợ JPG/PNG/WEBP.")

def decode_image_safely(image_bytes: bytes) -> tuple[np.ndarray, bytes]:
    """
    Giải mã an toàn mọi định dạng ảnh (JPG, PNG, WEBP, BMP, HEIC/HEIF).
    Trả về:
    - img_bgr (np.ndarray): Ma trận ảnh BGR chuẩn OpenCV phục vụ nhận diện AI.
    - jpeg_bytes (bytes): Chuỗi byte ảnh JPEG chuẩn phục vụ lưu trữ MinIO/Web.
    """
    if not image_bytes:
        raise ValueError("Dữ liệu ảnh bị rỗng.")

    # 1. Giải mã an toàn và tự động xoay theo EXIF Orientation (chụp dọc bằng iPhone/Android)
    try:
        from PIL import ImageOps
        pil_img = Image.open(io.BytesIO(image_bytes))
        # Tự động xoay ảnh theo góc chụp thực tế của cảm biến điện thoại (Orientation tag 3, 6, 8, ...)
        pil_img = ImageOps.exif_transpose(pil_img)
        pil_img = pil_img.convert("RGB")
        img_bgr = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    except Exception as pil_err:
        # Fallback về OpenCV nếu Pillow gặp trục trặc
        nparr = np.frombuffer(image_bytes, np.uint8)
        img_bgr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img_bgr is None:
            raise ValueError(f"Không thể giải mã định dạng ảnh hoặc file bị hỏng: {pil_err}")

    # 3. Luôn chuyển đổi/chuẩn hóa sang byte JPEG chất lượng cao (92%) để lưu trữ trên MinIO
    ret, buf = cv2.imencode(".jpg", img_bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 92])
    if not ret:
        jpeg_bytes = image_bytes
    else:
        jpeg_bytes = buf.tobytes()

    return img_bgr, jpeg_bytes
