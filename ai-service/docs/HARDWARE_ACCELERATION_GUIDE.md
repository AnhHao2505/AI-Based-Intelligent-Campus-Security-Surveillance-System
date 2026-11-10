# Hướng dẫn Tối ưu & Tăng tốc Phần cứng cho AI Service (Hardware Acceleration Guide)

Tài liệu này cung cấp hướng dẫn chi tiết về cấu hình phần cứng, chế độ suy luận nửa chính xác (FP16), quản lý luồng CPU, và cách đóng gói container Docker cho CPU vs GPU (CUDA).

---

## 1. Triết lý Thiết kế: Thiết lập An toàn theo Mặc định
Mọi tối ưu phụ thuộc phần cứng trong `ai-service` đều được đưa vào **Settings (biến môi trường)** với **giá trị mặc định an toàn tuyệt đối** (`AI_HALF=False`, tự động dò GPU/CPU fallback). 

Hệ thống **không hard-code** cấu hình của bất kỳ máy thử nghiệm nào, đảm bảo code chạy ổn định trên mọi môi trường từ máy tính cá nhân dev, container CPU trên Kubernetes, cho đến máy chủ Production trang bị GPU chuyên dụng.

---

## 2. Chế độ Suy luận Nửa Chính xác (FP16 / Half Precision) - `AI_HALF`

### 2.1 Cấu hình Setting
- **Biến môi trường:** `AI_HALF` (mặc định: `False`)
- **Phạm vi áp dụng:** Mô hình phát hiện người YOLO26 (`HumanDetector`) chạy trên GPU CUDA.
- **Cơ chế an toàn:** Nếu máy chủ chỉ có CPU (`device='cpu'`), cờ `half` sẽ tự động bị ép về `False` để tránh lỗi tính toán hoặc suy giảm hiệu năng do giả lập phần mềm.

### 2.2 Đánh giá Thực nghiệm & Khuyến nghị theo Kiến trúc GPU
| Kiến trúc GPU | Ví dụ Card | Hỗ trợ Tensor Core? | Khuyến nghị `AI_HALF` | Hiệu năng dự kiến |
| :--- | :--- | :---: | :---: | :--- |
| **Consumer Entry (Turing không TC)** | GTX 1650, GTX 1660 | ❌ Không | **`False` (Mặc định)** | Chậm hơn ~9.1% do giả lập FP16 trên ALU |
| **Pascal / Cũ hơn** | GTX 1060, GTX 1080 | ❌ Không | **`False`** | Không có lợi thế FP16 |
| **Server / Cloud (Turing)** | NVIDIA T4 | ✅ Có Tensor Core | **`True`** | Nhanh hơn 1.8x - 2.2x |
| **Consumer RTX (Ampere / Ada)** | RTX 3060, 3080, 4070 | ✅ Có Tensor Core | **`True`** | Nhanh hơn 1.9x - 2.4x |
| **Data Center (Ampere / Hopper)** | A10, A100, L4, H100 | ✅ Có Tensor Core | **`True`** | Nhanh hơn 2.0x - 3.0x |

> [!IMPORTANT]
> **Quy tắc triển khai Production:** Kết quả đo đạc trên GPU thử nghiệm GTX 1650 (chậm hơn 9.1%) **KHÔNG áp dụng** cho máy chủ Production có Tensor Core (T4/A10/A100). Khi deploy lên host chính thức có Tensor Core, hãy bật `AI_HALF=True` và chạy benchmark đo đạc lại bằng script `tools/perf/test_opt02_gtx1650_fp16.py` trước khi đưa vào vận hành.

---

## 3. ONNX Runtime: CPU vs GPU (CUDA EP)

### 3.1 Cảnh báo Xung đột Thư viện
> [!CAUTION]
> **Tuyệt đối KHÔNG cài đặt cùng lúc `onnxruntime` và `onnxruntime-gpu` trong cùng một môi trường Python / Docker container!**
> Việc cài song song cả hai gói sẽ gây xung đột thư viện động (DLL/SO symbol collision), dẫn đến crash tiến trình bất ngờ hoặc vô hiệu hóa bộ tăng tốc CUDA.

### 3.2 Cơ chế Tự động Chọn Provider (Auto-Fallback)
Trong module trích xuất đặc trưng khuôn mặt ArcFace (`FaceEmbedder`), hệ thống tự động kiểm tra danh sách provider khả dụng:
1. **Ưu tiên:** `CUDAExecutionProvider` nếu môi trường đã cài đặt `onnxruntime-gpu` và phát hiện GPU CUDA.
2. **Fallback:** Tự động fallback về `CPUExecutionProvider` nếu không có GPU hoặc thiếu driver CUDA.
3. **Logging minh bạch:** Ghi log rõ ràng provider thực tế đang được kích hoạt:
   - `⚡ [FaceEmbedder] Kích hoạt GPU Acceleration qua ONNX CUDAExecutionProvider!`
   - `💻 [FaceEmbedder] Sử dụng ONNX CPUExecutionProvider (CPU inference).`

---

## 4. Hướng dẫn Đóng gói Docker Container

### 4.1 Bản Build CPU (Mặc định / Tiêu chuẩn)
Sử dụng `Dockerfile` gốc của repo với gói `onnxruntime` chuẩn:
```dockerfile
FROM python:3.11-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 libglib2.0-0 libgomp1 curl && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY src/ ./src/
COPY config/ ./config/
COPY models/ ./models/
COPY yolo26n.pt* ./
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
```
*Lệnh build & chạy:*
```bash
docker build -t campus-ai-service:cpu -f Dockerfile .
docker run -d -p 8000:8000 --name ai-cpu campus-ai-service:cpu
```

### 4.2 Bản Build GPU (Tăng tốc CUDA + Tensor Core)
Sử dụng Base Image chính thức từ NVIDIA với CUDA Toolkit:
```dockerfile
FROM nvidia/cuda:12.2.0-runtime-ubuntu22.04

WORKDIR /app

# Cài đặt Python và các thư viện hệ thống
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip python3-dev \
    libgl1 libglib2.0-0 libgomp1 curl && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
# Gỡ bỏ onnxruntime CPU và cài đặt onnxruntime-gpu cùng PyTorch CUDA
RUN grep -v "onnxruntime" requirements.txt > req_gpu.txt && \
    pip3 install --no-cache-dir -r req_gpu.txt && \
    pip3 install --no-cache-dir onnxruntime-gpu>=1.18.0 torch torchvision --index-url https://download.pytorch.org/whl/cu121

COPY src/ ./src/
COPY config/ ./config/
COPY models/ ./models/
COPY yolo26n.pt* ./

ENV AI_DEVICE=0
ENV AI_HALF=true
EXPOSE 8000

CMD ["python3", "-m", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
```
*Lệnh build & chạy với NVIDIA Container Toolkit:*
```bash
docker build -t campus-ai-service:gpu -f Dockerfile.gpu .
docker run -d --gpus all -p 8000:8000 -e AI_HALF=true --name ai-gpu campus-ai-service:gpu
```

---

## 5. Quản lý Luồng CPU (CPU Threading Control)
Để tránh hiện tượng tranh chấp tài nguyên CPU giữa các container trong môi trường Kubernetes hoặc Docker giới hạn CPU quota:
- Cấu hình biến môi trường `AI_NUM_THREADS` hoặc `OMP_NUM_THREADS` tương ứng với số CPU Core được cấp phát cho container (ví dụ `AI_NUM_THREADS=4`).
- Hệ thống sẽ tự động cấu hình đồng bộ:
  - `torch.set_num_threads(n)`
  - `cv2.setNumThreads(n)`
  - `onnxruntime.SessionOptions.intra_op_num_threads = n`
  - `os.environ["OMP_NUM_THREADS"] = str(n)`
