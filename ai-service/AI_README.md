# AI Service — Campus Security Surveillance (FA26SE040)

Microservice xử lý AI thời gian thực cho hệ thống giám sát an ninh:
- **Phát hiện người (Human Detection):** YOLO11 (Class 0: `person`).
- **Theo dõi đối tượng (Human Tracking):** ByteTrack (Gán và duy trì `track_id` liên tục).
- **Phát hiện khuôn mặt (Face Detection):** OpenCV YuNet ONNX (kèm 5 điểm Landmarks căn chỉnh).
- **Trích xuất đặc trưng khuôn mặt (Face Recognition):** Deep Learning ArcFace ResNet-50 ONNX (vector 512D chuẩn hóa L2).
- **Phân tích khung hình & Xâm nhập vùng cấm (Frame Analysis Engine):** Kiểm tra đối tượng trong vùng ROI, đối soát danh tính khuôn mặt và cảnh báo ngoài giờ hoạt động.
- **Tích hợp cảnh báo:** Eclipse Mosquitto MQTT (Real-time Event Stream) & MinIO (Lưu trữ ảnh bằng chứng vi phạm).

---

## Cấu trúc Thư mục

```text
ai-service/
├── models/
│   ├── face_detection_yunet.onnx   # Model YuNet phát hiện khuôn mặt
│   ├── w600k_r50.onnx              # Model ArcFace ResNet-50 trích xuất vector 512D
│   └── yolo11n.pt                  # Model YOLO11 phát hiện người
├── src/
│   ├── config.py                   # Cấu hình biến môi trường
│   ├── main.py                     # FastAPI REST API
│   ├── core/
│   │   ├── entity.py               # Các cấu trúc dữ liệu (Point, BBox, TrackedPerson, Alert)
│   │   ├── human_detector.py       # Module YOLO11 Person Detection + ByteTrack
│   │   ├── face_detector.py        # Module YuNet Face Detection
│   │   ├── face_embedder.py        # Trích xuất đặc trưng khuôn mặt ArcFace 512D
│   │   ├── face_matcher.py         # So khớp khuôn mặt Cosine Similarity In-Memory RAM
│   │   └── frame_analysis_engine.py# Logic phân tích vi phạm an ninh ROI & Time Rules
│   ├── pipeline/
│   │   ├── stream_worker.py        # Worker chạy ngầm theo dõi camera RTSP
│   │   └── video_pipeline.py       # Full Video Analysis Pipeline
│   ├── integration/
│   │   ├── mqtt_client.py          # Client gửi cảnh báo lên MQTT Broker
│   │   └── storage_service.py      # Upload ảnh vi phạm lên MinIO
│   └── utils/
│       ├── geometry.py             # Thuật toán Point-in-Polygon (Ray Casting)
│       └── visualizer.py           # Vẽ BBox, Track ID, ROI, Overlay lên frame
├── test_pipeline.py                # Script kiểm thử độc lập (Auto & Webcam)
├── Dockerfile
└── requirements.txt
```

---

## Hướng dẫn Kiểm thử trên Local

### 1. Cài đặt môi trường
```bash
pip install -r requirements.txt
```
```
pip freeze > requirement.txt
```
### 2. Chạy Test Tự Động (Synthetic Mode — Không cần Camera)
```bash
python test_pipeline.py --mode auto
```

### 3. Chạy Test Trực Tiếp bằng Camera / Webcam
```bash
python test_pipeline.py --mode webcam
```
*(Góc phải màn hình sẽ có vùng cấm màu đỏ; bước vào và che mặt quá 5 giây hệ thống sẽ tự động kích hoạt cảnh báo `LOITERING_UNIDENTIFIED_PERSON`).*

### 4. Khởi chạy FastAPI Server
```bash
python -m uvicorn src.main:app --host 0.0.0.0 --port 8000 --reload
or
uvicorn src.main:app --host 0.0.0.0 --port 8000 --reload
```
Truy cập tài liệu API tại: `http://localhost:8000/docs`
