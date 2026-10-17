import os
import logging
import cv2
import numpy as np
from typing import List, Optional, Tuple, Union

try:
    import onnxruntime as ort
except ImportError:
    ort = None

from ..config import settings

logger = logging.getLogger(__name__)

# Tọa độ 5 điểm mốc chuẩn ArcFace trong khung hình 112x112 (Right Eye, Left Eye, Nose, Right Mouth, Left Mouth)
ARCFACE_CANONICAL_5PTS = np.array([
    [38.2946, 51.6963],
    [73.5318, 51.5014],
    [56.0252, 71.7366],
    [41.5493, 92.3655],
    [70.7299, 92.2041]
], dtype=np.float32)

class FaceEmbedder:
    """
    Module trích xuất đặc trưng khuôn mặt (Face Embedding) dạng Vector 512 chiều.
    Sử dụng mô hình Deep Learning chuẩn quốc tế ArcFace ResNet-50 (w600k_r50.onnx).
    Vector được chuẩn hóa L2 (L2-norm = 1.0) phục vụ so khớp Cosine Similarity / pgvector.
    """
    def __init__(self, model_path: Optional[str] = None, embedding_dim: int = 512):
        self.embedding_dim = embedding_dim
        self.model_path = model_path or getattr(settings, "MODEL_ARCFACE_PATH", "models/w600k_r50.onnx")
        self.session: Optional[any] = None
        self.input_name: Optional[str] = None
        self.output_name: Optional[str] = None
        self._init_session()

    def _init_session(self):
        """Khởi tạo ONNX Runtime InferenceSession cho ArcFace"""
        if not ort:
            logger.warning("⚠️ [FaceEmbedder] onnxruntime chưa được cài đặt, chuyển sang chế độ heuristic fallback.")
            return

        if not os.path.exists(self.model_path):
            logger.info(f"⚠️ [FaceEmbedder] Không tìm thấy file mô hình tại '{self.model_path}', đang tự động tải...")
            self._download_model_if_needed()

        if not os.path.exists(self.model_path):
            logger.warning(f"⚠️ [FaceEmbedder] Không tìm thấy file mô hình ArcFace tại '{self.model_path}', kích hoạt fallback.")
            return

        try:
            # Ưu tiên CPUExecutionProvider (hoặc CUDAExecutionProvider nếu có GPU)
            available_providers = ort.get_available_providers()
            providers = ['CUDAExecutionProvider', 'CPUExecutionProvider'] if 'CUDAExecutionProvider' in available_providers else ['CPUExecutionProvider']
            
            opts = ort.SessionOptions()
            opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            opts.intra_op_num_threads = 4

            self.session = ort.InferenceSession(self.model_path, sess_options=opts, providers=providers)
            self.input_name = self.session.get_inputs()[0].name
            self.output_name = self.session.get_outputs()[0].name
            logger.info(f"✅ [FaceEmbedder] Đã nạp thành công mô hình ArcFace ONNX [{self.model_path}] (Providers: {providers})")
        except Exception as e:
            logger.error(f"❌ [FaceEmbedder] Lỗi khi nạp mô hình ArcFace: {e}")
            self.session = None

    def _download_model_if_needed(self):
        """Tự động tải file mô hình ArcFace ONNX nếu chưa tồn tại"""
        os.makedirs(os.path.dirname(os.path.abspath(self.model_path)), exist_ok=True)
        url = "https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_l/w600k_r50.onnx"
        try:
            import requests
            logger.info(f"Đang tự động tải mô hình ArcFace ONNX từ '{url}'...")
            resp = requests.get(url, stream=True, timeout=120)
            resp.raise_for_status()
            with open(self.model_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        f.write(chunk)
            logger.info(f"✅ Đã tải xong mô hình ArcFace về '{self.model_path}'")
        except Exception as e:
            logger.warning(f"⚠️ Không thể tự động tải mô hình ArcFace: {e}")

    def align_face(
        self,
        face_image: np.ndarray,
        landmarks: Optional[Union[np.ndarray, List[Tuple[float, float]]]] = None
    ) -> np.ndarray:
        """
        Căn chỉnh khuôn mặt (Face Alignment) về chuẩn 112x112 sử dụng 5 điểm mốc (Affine Transformation).
        Nếu không có landmarks hoặc biến đổi thất bại, fallback về cv2.resize thông thường.
        """
        if face_image is None or face_image.size == 0:
            return np.zeros((112, 112, 3), dtype=np.uint8)

        if landmarks is not None and len(landmarks) == 5:
            try:
                pts = np.array(landmarks, dtype=np.float32)
                if pts.shape == (5, 2):
                    M, _ = cv2.estimateAffinePartial2D(pts, ARCFACE_CANONICAL_5PTS)
                    if M is not None:
                        return cv2.warpAffine(face_image, M, (112, 112), borderValue=0.0)
            except Exception as ex:
                logger.debug(f"[FaceEmbedder] Không thể warpAffine landmarks: {ex}")

        return cv2.resize(face_image, (112, 112))

    def extract_embedding(
        self,
        face_image: np.ndarray,
        landmarks: Optional[Union[np.ndarray, List[Tuple[float, float]]]] = None
    ) -> List[float]:
        """
        Trích xuất vector 512 chiều chuẩn hóa L2 từ ảnh khuôn mặt (Face Crop).
        """
        if face_image is None or face_image.size == 0:
            return [0.0] * self.embedding_dim

        # 1. Căn chỉnh mặt về chuẩn 112x112
        aligned = self.align_face(face_image, landmarks=landmarks)

        # 2. Xử lý qua Deep Learning ArcFace ONNX nếu session đã sẵn sàng
        if self.session is not None:
            try:
                # ArcFace yêu cầu định dạng RGB, float32, chuẩn hóa (x - 127.5) / 128.0, định dạng NCHW (1, 3, 112, 112)
                if len(aligned.shape) == 2:
                    rgb = cv2.cvtColor(aligned, cv2.COLOR_GRAY2RGB)
                else:
                    rgb = cv2.cvtColor(aligned, cv2.COLOR_BGR2RGB)

                input_blob = (rgb.astype(np.float32) - 127.5) / 128.0
                input_blob = np.transpose(input_blob, (2, 0, 1))[np.newaxis, :]

                # Chạy Inference qua ONNX Runtime
                preds = self.session.run([self.output_name], {self.input_name: input_blob})[0]
                feat = preds[0].flatten()

                # Chuẩn hóa L2 norm (Unit Vector cho Cosine Similarity)
                norm = np.linalg.norm(feat)
                if norm > 1e-6:
                    feat = feat / norm
                else:
                    feat = np.zeros(self.embedding_dim, dtype=np.float32)

                return [round(float(x), 6) for x in feat[:self.embedding_dim]]
            except Exception as e:
                logger.error(f"[FaceEmbedder] Lỗi khi chạy inference ArcFace: {e}, chuyển sang fallback.")

        # 3. Fallback Heuristic DCT (Dự phòng trường hợp thiếu file model)
        return self._extract_fallback_dct(aligned)

    def _extract_fallback_dct(self, aligned: np.ndarray) -> List[float]:
        """Thuật toán dự phòng trích xuất DCT đa phân giải khi không có model ONNX"""
        gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY) if len(aligned.shape) == 3 else aligned
        h_blocks, w_blocks = 8, 8
        block_h, block_w = 112 // h_blocks, 112 // w_blocks
        
        features = []
        for i in range(h_blocks):
            for j in range(w_blocks):
                block = gray[i * block_h : (i + 1) * block_h, j * block_w : (j + 1) * block_w]
                mean_val = float(np.mean(block))
                std_val = float(np.std(block))
                gx = cv2.Sobel(block, cv2.CV_32F, 1, 0, ksize=3)
                gy = cv2.Sobel(block, cv2.CV_32F, 0, 1, ksize=3)
                mag = np.mean(np.sqrt(gx**2 + gy**2))
                features.extend([mean_val, std_val, float(mag)])

        float_gray = np.float32(gray) / 255.0
        dct = cv2.dct(float_gray)
        dct_flat = dct[:18, :18].flatten()
        features.extend([float(v) for v in dct_flat])
        
        if len(features) < self.embedding_dim:
            features.extend([0.0] * (self.embedding_dim - len(features)))
        else:
            features = features[:self.embedding_dim]

        feat_arr = np.array(features, dtype=np.float32)
        norm = np.linalg.norm(feat_arr)
        if norm > 1e-6:
            feat_arr = feat_arr / norm
        else:
            feat_arr = np.zeros(self.embedding_dim, dtype=np.float32)

        return [round(float(x), 6) for x in feat_arr]
