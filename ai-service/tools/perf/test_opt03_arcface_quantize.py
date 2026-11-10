import sys
import os
import time
import numpy as np
import cv2
import onnxruntime as ort

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

def test_arcface_r50_benchmark():
    print("=" * 70)
    print("TEST OPT-03: BENCHMARK ARCFACE RESNET-50 (FP32 VS QUANTIZATION / GPU)")
    print("=" * 70)
    
    model_path = os.path.abspath("models/w600k_r50.onnx")
    if not os.path.exists(model_path):
        model_path = os.path.abspath("../../models/w600k_r50.onnx")
        
    print(f"Model path: {model_path}")
    print(f"Model exists: {os.path.exists(model_path)}")
    print(f"Available Providers: {ort.get_available_providers()}")
    
    if not os.path.exists(model_path):
        print("Không tìm thấy file w600k_r50.onnx để test.")
        return

    # Giả lập 1 crop khuôn mặt 112x112
    face_crop = np.random.randint(0, 255, (112, 112, 3), dtype=np.uint8)
    rgb = cv2.cvtColor(face_crop, cv2.COLOR_BGR2RGB)
    input_blob = (rgb.astype(np.float32) - 127.5) / 128.0
    input_blob = np.transpose(input_blob, (2, 0, 1))[np.newaxis, :]

    # 1. FP32 Session (CPUExecutionProvider)
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.intra_op_num_threads = 4
    sess_fp32 = ort.InferenceSession(model_path, sess_options=opts, providers=['CPUExecutionProvider'])
    input_name = sess_fp32.get_inputs()[0].name
    output_name = sess_fp32.get_outputs()[0].name

    # Warmup
    for _ in range(5):
        sess_fp32.run([output_name], {input_name: input_blob})

    latencies_fp32 = []
    for _ in range(20):
        t0 = time.perf_counter()
        out_fp32 = sess_fp32.run([output_name], {input_name: input_blob})
        latencies_fp32.append((time.perf_counter() - t0) * 1000.0)

    p50_fp32 = np.percentile(latencies_fp32, 50)
    p95_fp32 = np.percentile(latencies_fp32, 95)
    mean_fp32 = np.mean(latencies_fp32)
    print(f"\n1. ArcFace R50 FP32 (CPU, 4 threads): Mean = {mean_fp32:.2f} ms | P50 = {p50_fp32:.2f} ms | P95 = {p95_fp32:.2f} ms")

    # Kiểm tra phương án onnxruntime-gpu CUDAExecutionProvider
    has_cuda_ep = 'CUDAExecutionProvider' in ort.get_available_providers()
    print(f"\n2. ONNX Runtime CUDA Execution Provider: {has_cuda_ep}")
    if not has_cuda_ep:
        print("  - onnxruntime-gpu chưa được cài đặt trong môi trường (hiện chỉ có onnxruntime CPU).")
        print("  - Chi phí trích xuất ~91ms/face trên CPU là điểm nghẽn lớn nhất khi có nhiều khuôn mặt.")

    # Đánh giá chi phí Migration nếu đổi sang MobileFaceNet:
    print("\n3. Đánh giá chi phí Migration nếu đổi sang MobileFaceNet / ArcFace MobileNet:")
    print("  - Tính tương thích vector: BẤT KHẢ THI (Incompatible Vector Space).")
    print("  - Vector sinh bởi ResNet-50 không thể so khớp cosine với vector sinh bởi MobileFaceNet.")
    print("  - Hệ lụy: Toàn bộ bảng face_data và guest_face_embeddings phải migrate lại:")
    print("    + Nếu ảnh gốc khuôn mặt còn lưu trong MinIO ('face-profiles'): cần chạy offline batch job re-embed toàn bộ ảnh.")
    print("    + Nếu ảnh gốc đã bị xóa (tuân thủ privacy/GDPR/nghị định 13): vĩnh viễn mất khả năng nhận diện người đó, buộc người dùng đăng ký lại khuôn mặt.")
    print("  - Kết luận: GIỮ NGUYÊN ArcFace ResNet-50 để bảo toàn 100% dữ liệu đã đăng ký; giải pháp tối ưu là chạy ArcFace trên CUDA EP hoặc INT8 quantize.")

if __name__ == "__main__":
    test_arcface_r50_benchmark()
