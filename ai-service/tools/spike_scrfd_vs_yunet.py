import os
import sys
import time

# Add torch lib to PATH for CUDAExecutionProvider in onnxruntime
site_packages = os.path.join(sys.prefix, 'Lib', 'site-packages')
torch_lib = os.path.join(site_packages, 'torch', 'lib')
if os.path.exists(torch_lib):
    try:
        os.add_dll_directory(torch_lib)
        os.environ['PATH'] = torch_lib + os.pathsep + os.environ['PATH']
    except Exception:
        pass

import cv2
import numpy as np
import onnxruntime as ort

# Add parent path to import project modules
CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
AI_SERVICE_DIR = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
if AI_SERVICE_DIR not in sys.path:
    sys.path.insert(0, AI_SERVICE_DIR)

from src.core.face_detector import FaceDetector

class SCRFDDetector:
    def __init__(self, model_path: str, conf_threshold: float = 0.5, nms_threshold: float = 0.4, use_cuda: bool = True):
        self.model_path = model_path
        self.conf_threshold = conf_threshold
        self.nms_threshold = nms_threshold
        
        providers = ['CPUExecutionProvider']
        if use_cuda and 'CUDAExecutionProvider' in ort.get_available_providers():
            providers.insert(0, 'CUDAExecutionProvider')
            
        self.session = ort.InferenceSession(self.model_path, providers=providers)
        self.input_name = self.session.get_inputs()[0].name
        self.output_names = [o.name for o in self.session.get_outputs()]
        
        self.fmc = 3
        self._feat_stride_fpn = [8, 16, 32]
        self._num_anchors = 2
        self.center_cache = {}

    def _get_anchor_centers(self, height: int, width: int, stride: int):
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

    @staticmethod
    def _distance2bbox(points, distance, max_shape=None):
        x1 = points[:, 0] - distance[:, 0]
        y1 = points[:, 1] - distance[:, 1]
        x2 = points[:, 0] + distance[:, 2]
        y2 = points[:, 1] + distance[:, 3]
        if max_shape is not None:
            x1 = np.clip(x1, 0, max_shape[1])
            y1 = np.clip(y1, 0, max_shape[0])
            x2 = np.clip(x2, 0, max_shape[1])
            y2 = np.clip(y2, 0, max_shape[0])
        return np.stack([x1, y1, x2, y2], axis=-1)

    @staticmethod
    def _distance2kps(points, distance, max_shape=None):
        preds = []
        for i in range(0, distance.shape[1], 2):
            px = points[:, i % 2] + distance[:, i]
            py = points[:, (i % 2) + 1] + distance[:, i + 1]
            if max_shape is not None:
                px = np.clip(px, 0, max_shape[1])
                py = np.clip(py, 0, max_shape[0])
            preds.append(px)
            preds.append(py)
        return np.stack(preds, axis=-1)

    def detect(self, img: np.ndarray, input_size=(640, 640)):
        im_ratio = float(img.shape[0]) / img.shape[1]
        model_ratio = float(input_size[1]) / input_size[0]
        if im_ratio > model_ratio:
            new_height = input_size[1]
            new_width = int(new_height / im_ratio)
        else:
            new_width = input_size[0]
            new_height = int(new_width * im_ratio)
            
        det_scale = float(new_height) / img.shape[0]
        resized_img = cv2.resize(img, (new_width, new_height))
        det_img = np.zeros((input_size[1], input_size[0], 3), dtype=np.uint8)
        det_img[:new_height, :new_width, :] = resized_img
        
        blob = cv2.dnn.blobFromImage(det_img, 1.0 / 128.0, input_size, (127.5, 127.5, 127.5), swapRB=True)
        net_outs = self.session.run(self.output_names, {self.input_name: blob})
        
        scores_list = []
        bboxes_list = []
        kpss_list = []
        
        for idx, stride in enumerate(self._feat_stride_fpn):
            score = net_outs[idx]
            bbox = net_outs[idx + self.fmc] * stride
            kps = net_outs[idx + self.fmc * 2] * stride
            
            height = input_size[1] // stride
            width = input_size[0] // stride
            anchor_centers = self._get_anchor_centers(height, width, stride)
            
            pos_inds = np.where(score >= self.conf_threshold)[0]
            if len(pos_inds) > 0:
                bboxes = self._distance2bbox(anchor_centers, bbox)
                pos_scores = score[pos_inds]
                pos_bboxes = bboxes[pos_inds] / det_scale
                
                scores_list.append(pos_scores)
                bboxes_list.append(pos_bboxes)
                
                keypoints = self._distance2kps(anchor_centers, kps)
                pos_kpss = keypoints[pos_inds] / det_scale
                kpss_list.append(pos_kpss)
                
        if len(scores_list) == 0:
            return np.empty((0, 5)), np.empty((0, 5, 2))
            
        scores = np.vstack(scores_list)
        bboxes = np.vstack(bboxes_list)
        kpss = np.vstack(kpss_list)
        
        order = scores.ravel().argsort()[::-1]
        bboxes = bboxes[order]
        scores = scores[order]
        kpss = kpss[order]
        
        # NMS
        keep = cv2.dnn.NMSBoxes(
            bboxes.tolist(),
            scores.ravel().tolist(),
            self.conf_threshold,
            self.nms_threshold
        )
        
        if len(keep) > 0:
            keep = keep.flatten()
            det = np.hstack([bboxes[keep], scores[keep]])
            kpss = kpss[keep].reshape((-1, 5, 2))
            return det, kpss
        return np.empty((0, 5)), np.empty((0, 5, 2))

def run_benchmark():
    yunet_path = os.path.join(AI_SERVICE_DIR, "models", "face_detection_yunet.onnx")
    scrfd_path = os.path.join(AI_SERVICE_DIR, "models", "scrfd_2.5g_bnkps.onnx")
    test_video = "D:/DoAnSE/test_video.mp4"
    
    print("=" * 60)
    print("🚀 SPIKE BENCHMARK: YuNet vs SCRFD-2.5G")
    print(f"YuNet Path: {yunet_path}")
    print(f"SCRFD Path: {scrfd_path}")
    print("=" * 60)
    
    yunet = FaceDetector(model_path=yunet_path)
    scrfd = SCRFDDetector(model_path=scrfd_path, use_cuda=True)
    
    cap = cv2.VideoCapture(test_video)
    frames = []
    for _ in range(50):
        ret, frame = cap.read()
        if not ret:
            break
        frames.append(frame)
    cap.release()
    print(f"Đã nạp {len(frames)} khung hình từ video test.")
    
    # Warmup
    print("Warming up models...")
    for _ in range(5):
        yunet.detect_in_image(frames[0])
        scrfd.detect(frames[0], input_size=(640, 640))
        
    print("\n--- TEST 1: TOÀN BỘ KHUNG HÌNH (Full Frame 854x480) ---")
    yunet_times = []
    yunet_faces = 0
    for f in frames:
        t0 = time.perf_counter()
        res = yunet.detect_in_image(f)
        yunet_times.append((time.perf_counter() - t0) * 1000)
        yunet_faces += len(res)
        
    scrfd_times = []
    scrfd_faces = 0
    for f in frames:
        t0 = time.perf_counter()
        det, kps = scrfd.detect(f, input_size=(640, 640))
        scrfd_times.append((time.perf_counter() - t0) * 1000)
        scrfd_faces += len(det)
        
    print(f"YuNet Full Frame:")
    print(f"  Mean: {np.mean(yunet_times):.2f} ms | Median: {np.median(yunet_times):.2f} ms | P95: {np.percentile(yunet_times, 95):.2f} ms")
    print(f"  Tổng số mặt phát hiện: {yunet_faces}")
    
    print(f"SCRFD-2.5G Full Frame (640x640):")
    print(f"  Mean: {np.mean(scrfd_times):.2f} ms | Median: {np.median(scrfd_times):.2f} ms | P95: {np.percentile(scrfd_times, 95):.2f} ms")
    print(f"  Tổng số mặt phát hiện: {scrfd_faces}")
    
    print("\n--- TEST 2: CROP THÂN TRÊN (Upper-body Crop ~320x320) ---")
    # Lấy crop người mô phỏng
    h, w = frames[0].shape[:2]
    crop = frames[0][int(h*0.1):int(h*0.6), int(w*0.3):int(w*0.7)]
    
    yunet_crop_times = []
    for _ in range(50):
        t0 = time.perf_counter()
        yunet.detect_in_image(crop)
        yunet_crop_times.append((time.perf_counter() - t0) * 1000)
        
    scrfd_crop_times = []
    for _ in range(50):
        t0 = time.perf_counter()
        scrfd.detect(crop, input_size=(320, 320))
        scrfd_crop_times.append((time.perf_counter() - t0) * 1000)
        
    print(f"YuNet trên Crop:")
    print(f"  Mean: {np.mean(yunet_crop_times):.2f} ms | Median: {np.median(yunet_crop_times):.2f} ms | P95: {np.percentile(yunet_crop_times, 95):.2f} ms")
    print(f"SCRFD-2.5G trên Crop (320x320):")
    print(f"  Mean: {np.mean(scrfd_crop_times):.2f} ms | Median: {np.median(scrfd_crop_times):.2f} ms | P95: {np.percentile(scrfd_crop_times, 95):.2f} ms")
    
if __name__ == "__main__":
    run_benchmark()
