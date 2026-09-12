# AI Service — Campus Security Surveillance (FA26SE040)

Microservice xử lý AI thời gian thực cho hệ thống giám sát an ninh:
- **Phát hiện người (Human Detection):** YOLOv8 (Class 0: `person`).
- **Theo dõi đối tượng (Human Tracking):** ByteTrack (Gán và duy trì `track_id` liên tục).
- **Phát hiện khuôn mặt (Face Detection):** OpenCV YuNet ONNX.
- **Phân tích hành vi lảng vảng (Loitering Engine):** Đếm thời gian lưu trú trong vùng cấm ROI. Kết hợp giữa Human Tracking và Face Detection để phát hiện kẻ đột nhập/lảng vảng ngay cả khi quay lưng, che mặt hoặc không quét được khuôn mặt.
- **Tích hợp cảnh báo:** Apache Kafka (Real-time Event Stream) & MinIO (Lưu trữ ảnh bằng chứng vi phạm).

---

## Cấu trúc Thư mục

```text
ai-service/
├── models/
│   ├── face_detection_yunet.onnx   # Model YuNet phát hiện khuôn mặt
│   └── yolov8n.pt                  # Model YOLOv8 phát hiện người
├── src/
│   ├── config.py                   # Cấu hình biến môi trường
│   ├── main.py                     # FastAPI REST API
│   ├── core/
│   │   ├── entity.py               # Các cấu trúc dữ liệu (Point, BBox, TrackedPerson, Alert)
│   │   ├── human_detector.py       # Module YOLOv8 Person Detection
│   │   ├── face_detector.py        # Module YuNet Face Detection
│   │   └── loitering_engine.py     # Logic Loitering kết hợp Face & Track ID
│   ├── pipeline/
│   │   └── video_pipeline.py       # Full Video Analysis Pipeline
│   ├── integration/
│   │   ├── kafka_producer.py       # Producer gửi cảnh báo lên Kafka
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
uvicorn src.main:app --host 0.0.0.0 --port 8000 --reload
```
Truy cập tài liệu API tại: `http://localhost:8000/docs`
