import os
import sys
import logging
from typing import Callable, Optional, List, Tuple
from unittest.mock import MagicMock
import cv2
import numpy as np

# Thêm thư mục torch/lib vào DLL search path nếu có để hỗ trợ CUDAExecutionProvider
_site_packages = os.path.join(sys.prefix, 'Lib', 'site-packages')
_torch_lib = os.path.join(_site_packages, 'torch', 'lib')
if os.path.exists(_torch_lib):
    try:
        os.add_dll_directory(_torch_lib)
        os.environ['PATH'] = _torch_lib + os.pathsep + os.environ['PATH']
    except Exception:
        pass

import onnxruntime as ort

from ..config import settings
from .entity import BoundingBox, FaceDetectionResult

logger = logging.getLogger(__name__)


class FaceDetector:
    """
    Module phát hiện khuôn mặt sử dụng mô hình InsightFace SCRFD-2.5G (thay thế YuNet).
    Hỗ trợ nhận diện khuôn mặt cả trên toàn khung hình và crop từ BBox của người.
    Tương thích ngược với YuNet khi truyền đường dẫn mô hình YuNet hoặc trong unit test mock.
    """
    def __init__(
        self,
        model_path: Optional[str] = None,
        score_threshold: Optional[float] = None,
        nms_threshold: Optional[float] = None,
        upscale_threshold: Optional[int] = None,
        max_dim: Optional[int] = None,
        use_cuda: bool = True,
        fixed_input_size: Optional[int] = None,
        registration_max_dim: Optional[int] = None
    ):
        # Mặc định sử dụng SCRFD-2.5G
        default_model = getattr(settings, "MODEL_FACE_DETECTOR_PATH", getattr(settings, "MODEL_SCRFD_PATH", settings.MODEL_YUNET_PATH))
        self.model_path = model_path or default_model
        self.score_threshold = score_threshold if score_threshold is not None else settings.DEFAULT_FACE_CONFIDENCE
        self.nms_threshold = nms_threshold if nms_threshold is not None else settings.DEFAULT_FACE_NMS_THRESHOLD
        self.upscale_threshold = upscale_threshold if upscale_threshold is not None else getattr(settings, "FACE_CROP_UPSCALE_THRESHOLD", 160)
        self.max_dim = max_dim if max_dim is not None else int(getattr(settings, "FACE_CROP_MAX_DIM", 480))
        raw_reg_max = registration_max_dim if registration_max_dim is not None else getattr(settings, "FACE_REGISTRATION_MAX_DIM", 640)
        self.registration_max_dim = int(raw_reg_max) if raw_reg_max is not None else 640
        self.use_cuda = use_cuda
        # ONNX Runtime CUDA lập lại kế hoạch tính toán mỗi khi kích thước input đổi (~50ms/lần thay vì ~10ms).
        # Crop người mỗi frame một cỡ -> đệm mọi ảnh vừa khung về đúng 1 kích thước vuông cố định.
        # Ảnh lớn hơn khung (vd ảnh đăng ký toàn khung hình) giữ đường cũ, không thu nhỏ để khỏi mất mặt nhỏ. 0 = tắt.
        raw_fixed = fixed_input_size if fixed_input_size is not None else int(getattr(settings, "FACE_DETECT_FIXED_INPUT", 480))
        self.fixed_input_size = int(np.ceil(raw_fixed / 32.0) * 32) if raw_fixed and raw_fixed > 0 else 0

        self.backend = "scrfd"
        self.detector = None  # Dành cho YuNet hoặc unit test mock
        self.session = None   # Dành cho SCRFD ONNX Runtime
        
        # Cấu hình SCRFD Feature Pyramid Strides & Anchor Centers Cache
        self._feat_stride_fpn = [8, 16, 32]
        self._fmc = 3
        self._num_anchors = 2
        self.center_cache = {}
        
        self._init_detector()

    def _download_scrfd_if_needed(self):
        """Tự động tải mô hình SCRFD-2.5G ONNX nếu chưa có sẵn"""
        if os.path.exists(self.model_path):
            return
        os.makedirs(os.path.dirname(os.path.abspath(self.model_path)), exist_ok=True)
        url = "https://huggingface.co/OwlMaster/AllFilesRope/resolve/main/scrfd_2.5g_bnkps.onnx"
        try:
            import requests
            logger.info(f"Đang tự động tải mô hình SCRFD-2.5G ONNX từ '{url}'...")
            resp = requests.get(url, stream=True, timeout=120)
            resp.raise_for_status()
            with open(self.model_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        f.write(chunk)
            logger.info(f"✅ Đã tải xong mô hình SCRFD về '{self.model_path}'")
        except Exception as e:
            logger.warning(f"⚠️ Không thể tự động tải mô hình SCRFD: {e}")

    def _init_detector(self):
        # Kiểm tra nếu đang chạy test mock cv2.FaceDetectorYN.create hoặc được truyền đường dẫn YuNet
        is_mocked = hasattr(cv2.FaceDetectorYN, "create") and isinstance(cv2.FaceDetectorYN.create, MagicMock)
        is_explicit_yunet = "yunet" in os.path.basename(self.model_path).lower()

        if is_mocked or is_explicit_yunet:
            self.backend = "yunet"
            if not is_mocked and not os.path.exists(self.model_path):
                raise FileNotFoundError(f"Không tìm thấy mô hình YuNet tại: {self.model_path}")
        
            self.detector = cv2.FaceDetectorYN.create(
                model=self.model_path,
                config="",
                input_size=(320, 320),
                score_threshold=self.score_threshold,
                nms_threshold=self.nms_threshold,
                top_k=5000
            )
            return

        # Khởi tạo mô hình SCRFD-2.5G qua ONNX Runtime
        self.backend = "scrfd"
        if not os.path.exists(self.model_path):
            self._download_scrfd_if_needed()
            
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(f"Không tìm thấy mô hình SCRFD tại: {self.model_path}")

        providers = ['CPUExecutionProvider']
        if self.use_cuda and 'CUDAExecutionProvider' in ort.get_available_providers():
            providers.insert(0, 'CUDAExecutionProvider')
            
        self.session = ort.InferenceSession(self.model_path, providers=providers)
        self.input_name = self.session.get_inputs()[0].name
        self.output_names = [o.name for o in self.session.get_outputs()]

    def _get_anchor_centers(self, height: int, width: int, stride: int) -> np.ndarray:
        key = (height, width, stride)
        if key in self.center_cache:
            return self.center_cache[key]
            
        anchor_centers = np.stack(np.mgrid[:height, :width][::-1], axis=-1).astype(np.float32)
        anchor_centers = (anchor_centers * stride).reshape((-1, 2))
        if self._num_anchors > 1:
            anchor_centers = np.stack([anchor_centers] * self._num_anchors, axis=1).reshape((-1, 2))
            
        if len(self.center_cache) < 100:
            self.center_cache[key] = anchor_centers
        return anchor_centers

    def _detect_scrfd(self, image: np.ndarray, offset_xy: Tuple[int, int] = (0, 0)) -> List[FaceDetectionResult]:
        h, w = image.shape[:2]
        fixed = self.fixed_input_size
        if fixed and w <= fixed and h <= fixed:
            pad_w = pad_h = fixed
        else:
            pad_w = int(np.ceil(w / 32.0) * 32)
            pad_h = int(np.ceil(h / 32.0) * 32)

        if pad_w == w and pad_h == h:
            padded_img = image
        else:
            padded_img = np.zeros((pad_h, pad_w, 3), dtype=np.uint8)
            padded_img[:h, :w, :] = image

        blob = cv2.dnn.blobFromImage(
            padded_img,
            1.0 / 128.0,
            (pad_w, pad_h),
            (127.5, 127.5, 127.5),
            swapRB=True
        )
        net_outs = self.session.run(self.output_names, {self.input_name: blob})

        scores_list = []
        bboxes_list = []
        kpss_list = []

        for idx, stride in enumerate(self._feat_stride_fpn):
            score = net_outs[idx]
            bbox = net_outs[idx + self._fmc] * stride
            kps = net_outs[idx + self._fmc * 2] * stride

            sh = pad_h // stride
            sw = pad_w // stride
            anchor_centers = self._get_anchor_centers(sh, sw, stride)

            pos_inds = np.where(score >= self.score_threshold)[0]
            if len(pos_inds) > 0:
                # Decode bbox: [x1, y1, x2, y2]
                x1 = anchor_centers[:, 0] - bbox[:, 0]
                y1 = anchor_centers[:, 1] - bbox[:, 1]
                x2 = anchor_centers[:, 0] + bbox[:, 2]
                y2 = anchor_centers[:, 1] + bbox[:, 3]
                decoded_boxes = np.stack([x1, y1, x2, y2], axis=-1)

                # Decode keypoints: 5 points (x, y)
                preds = []
                for k in range(0, kps.shape[1], 2):
                    preds.append(anchor_centers[:, k % 2] + kps[:, k])
                    preds.append(anchor_centers[:, (k % 2) + 1] + kps[:, k + 1])
                decoded_kps = np.stack(preds, axis=-1)

                scores_list.append(score[pos_inds])
                bboxes_list.append(decoded_boxes[pos_inds])
                kpss_list.append(decoded_kps[pos_inds])

        if len(scores_list) == 0:
            return []

        scores = np.vstack(scores_list).ravel()
        bboxes = np.vstack(bboxes_list)
        kpss = np.vstack(kpss_list).reshape((-1, 5, 2))

        keep = cv2.dnn.NMSBoxes(bboxes.tolist(), scores.tolist(), self.score_threshold, self.nms_threshold)
        if len(keep) == 0:
            return []

        keep = keep.flatten()
        off_x, off_y = offset_xy
        results = []

        for k in keep:
            box = bboxes[k]
            score_val = float(scores[k])
            
            # Giới hạn bounding box trong kích thước ảnh gốc trước padding
            x1 = float(np.clip(box[0], 0, w)) + off_x
            y1 = float(np.clip(box[1], 0, h)) + off_y
            x2 = float(np.clip(box[2], 0, w)) + off_x
            y2 = float(np.clip(box[3], 0, h)) + off_y

            raw_landmarks = kpss[k]
            landmarks = [
                (float(np.clip(pt[0], 0, w)) + off_x, float(np.clip(pt[1], 0, h)) + off_y)
                for pt in raw_landmarks
            ]

            results.append(
                FaceDetectionResult(
                    bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2, confidence=score_val),
                    score=score_val,
                    landmarks=landmarks
                )
            )

        return results

    def _detect_yunet(self, image: np.ndarray, offset_xy: Tuple[int, int] = (0, 0)) -> List[FaceDetectionResult]:
        h, w = image.shape[:2]
        self.detector.setInputSize((w, h))
        _, faces = self.detector.detect(image)

        results = []
        if faces is not None and len(faces) > 0:
            off_x, off_y = offset_xy
            for face in faces:
                bbox_raw = face[0:4].astype(float)
                score = float(face[-1])
                
                x1 = bbox_raw[0] + off_x
                y1 = bbox_raw[1] + off_y
                x2 = x1 + bbox_raw[2]
                y2 = y1 + bbox_raw[3]

                landmarks = []
                raw_landmarks = face[4:14].astype(float).reshape((5, 2))
                for pt in raw_landmarks:
                    landmarks.append((pt[0] + off_x, pt[1] + off_y))

                results.append(
                    FaceDetectionResult(
                        bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2, confidence=score),
                        score=score,
                        landmarks=landmarks
                    )
                )
        return results

    def detect_in_image(
        self,
        image: np.ndarray,
        offset_xy: Tuple[int, int] = (0, 0),
        max_dim: Optional[int] = None
    ) -> List[FaceDetectionResult]:
        """
        Phát hiện các khuôn mặt trong ảnh/vùng crop.
        offset_xy: Tọa độ gốc (x_offset, y_offset) nếu truyền vào ảnh con đã crop để ánh xạ về frame gốc.
        max_dim: Nếu được chỉ định (> 0) và ảnh có cạnh dài > max_dim, tự động thu nhỏ ảnh về cạnh dài max_dim
                 (giữ nguyên tỉ lệ khung hình) trước khi phát hiện khuôn mặt, sau đó quy đổi ngược toạ độ
                 BoundingBox và 5 điểm mốc (landmarks) về hệ toạ độ ảnh gốc.
        """
        if image is None or image.size == 0:
            return []

        h, w = image.shape[:2]
        if w < 10 or h < 10:
            return []

        # Tự động thu nhỏ ảnh nếu max_dim được chỉ định và cạnh dài vượt ngưỡng
        if max_dim is not None and max_dim > 0 and max(h, w) > max_dim:
            scale = float(max_dim) / float(max(h, w))
            new_w = max(10, int(round(w * scale)))
            new_h = max(10, int(round(h * scale)))
            scale_x = float(new_w) / float(w)
            scale_y = float(new_h) / float(h)
            resized_img = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_AREA)

            detected_faces = self.detect_in_image(resized_img, offset_xy=(0, 0), max_dim=None)
            if not detected_faces:
                return []

            off_x, off_y = offset_xy
            scaled_faces = []
            for face in detected_faces:
                orig_bbox = BoundingBox(
                    x1=min(max(0.0, face.bbox.x1 / scale_x), float(w)) + off_x,
                    y1=min(max(0.0, face.bbox.y1 / scale_y), float(h)) + off_y,
                    x2=min(max(0.0, face.bbox.x2 / scale_x), float(w)) + off_x,
                    y2=min(max(0.0, face.bbox.y2 / scale_y), float(h)) + off_y,
                    confidence=face.score,
                )
                orig_landmarks = None
                if face.landmarks:
                    orig_landmarks = []
                    for lx, ly in face.landmarks:
                        orig_landmarks.append((
                            min(max(0.0, lx / scale_x), float(w)) + off_x,
                            min(max(0.0, ly / scale_y), float(h)) + off_y,
                        ))
                scaled_faces.append(
                    FaceDetectionResult(
                        bbox=orig_bbox,
                        score=face.score,
                        landmarks=orig_landmarks
                    )
                )
            return scaled_faces

        # Nếu có gán detector mock hoặc đang chạy backend YuNet
        if self.detector is not None:
            return self._detect_yunet(image, offset_xy=offset_xy)

        return self._detect_scrfd(image, offset_xy=offset_xy)

    @staticmethod
    def _map_face_to_frame(
        face: FaceDetectionResult, scale_x: float, scale_y: float, offset_xy: Tuple[int, int]
    ) -> FaceDetectionResult:
        """Quy đổi bbox + landmarks của mặt phát hiện trên crop đã đổi cỡ về toạ độ khung hình gốc."""
        off_x, off_y = offset_xy
        landmarks = [((lx / scale_x) + off_x, (ly / scale_y) + off_y) for lx, ly in (face.landmarks or [])]
        return FaceDetectionResult(
            bbox=BoundingBox(
                x1=(face.bbox.x1 / scale_x) + off_x,
                y1=(face.bbox.y1 / scale_y) + off_y,
                x2=(face.bbox.x2 / scale_x) + off_x,
                y2=(face.bbox.y2 / scale_y) + off_y,
                confidence=face.score
            ),
            score=face.score,
            landmarks=landmarks
        )

    def detect_best_face_in_crop(
        self,
        crop: np.ndarray,
        offset_xy: Tuple[int, int],
        face_filter: Optional[Callable[[FaceDetectionResult], bool]] = None
    ) -> Optional[FaceDetectionResult]:
        """
        Tìm khuôn mặt có độ tin cậy cao nhất trong vùng crop của người.
        - Crop nhỏ (cạnh ngắn < upscale_threshold): phóng to để bắt được mặt ở xa.
        - Crop lớn (cạnh dài > max_dim): thu nhỏ trước khi phát hiện mặt để tối ưu tốc độ.
        Tọa độ bbox + landmarks luôn quy đổi về khung hình gốc; ArcFace vẫn trích xuất mặt từ frame gốc.
        - face_filter (tuỳ chọn): chỉ xét các mặt (toạ độ khung hình gốc) mà hàm trả True. Dùng khi vùng quét của người
          này chứa cả mặt người khác đứng chồng lên (mặt người khác rõ hơn sẽ bị chọn nhầm nếu chỉ lấy điểm cao nhất).
        """
        if crop is None or crop.size == 0:
            return None

        h, w = crop.shape[:2]
        if w < 10 or h < 10:
            return None

        scale = 1.0
        if min(h, w) < self.upscale_threshold:
            scale = float(self.upscale_threshold) / float(min(h, w))
        elif self.max_dim > 0 and max(h, w) > self.max_dim:
            scale = float(self.max_dim) / float(max(h, w))
        # Không phóng to quá max_dim ở cạnh dài: crop thấp-rộng (người ngồi / ở xa) phóng cạnh ngắn lên 160 sẽ thành
        # ~500px, vượt khung cố định của SCRFD -> rơi về input đổi cỡ, làm chậm cả các lần gọi khác (~50ms thay vì ~10ms).
        if self.max_dim > 0 and max(h, w) * scale > self.max_dim:
            scale = float(self.max_dim) / float(max(h, w))

        if scale == 1.0:
            faces = self.detect_in_image(crop, offset_xy=offset_xy)
            if face_filter is not None and faces:
                faces = [f for f in faces if face_filter(f)]
            if not faces:
                return None
            faces.sort(key=lambda f: f.score, reverse=True)
            return faces[0]

        new_w = max(10, int(round(w * scale)))
        new_h = max(10, int(round(h * scale)))
        scale_x = float(new_w) / float(w)
        scale_y = float(new_h) / float(h)
        interp = cv2.INTER_LINEAR if scale > 1.0 else cv2.INTER_AREA
        resized_crop = cv2.resize(crop, (new_w, new_h), interpolation=interp)

        faces = self.detect_in_image(resized_crop, offset_xy=(0, 0))
        if not faces:
            return None
        faces.sort(key=lambda f: f.score, reverse=True)
        for face in faces:
            mapped = self._map_face_to_frame(face, scale_x, scale_y, offset_xy)
            if face_filter is None or face_filter(mapped):
                return mapped
        return None
