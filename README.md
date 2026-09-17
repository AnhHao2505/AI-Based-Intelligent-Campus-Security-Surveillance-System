# ICSSS — AI-Based Intelligent Campus Security Surveillance System

Dự án Hệ thống Giám sát An ninh Thông minh ứng dụng AI — FPTU SecureVision.

## 🛠️ Quy Chuẩn Design System & Pre-commit Hook

Để đảm bảo không xảy ra hiện tượng hồi quy màu sắc (sử dụng mã màu viết cứng thay vì CSS Variables chuẩn của Design System), dự án trang bị script kiểm tra tự động tại `scripts/check-colors.sh`.

### Kích hoạt Git Pre-commit Hook

Để tự động chạy kiểm tra mỗi khi thực hiện `git commit`, hãy chạy lệnh sau một lần duy nhất:

```bash
git config core.hooksPath .githooks
```

Sau khi kích hoạt, mỗi lệnh commit sẽ tự động chạy script `scripts/check-colors.sh`. Nếu phát hiện:
- Mã màu viết cứng `rgba(59, 168, 217, ...)`
- Mã màu chữ tối viết cứng `#0d1117`
- Sử dụng `var(--brand-cyan)` cho thuộc tính `color` hoặc `fill`

Git sẽ từ chối commit và hiển thị thông báo lỗi kèm hướng dẫn bằng tiếng Việt để thay thế bằng token tương ứng trong `frontend/src/styles/theme.css`.

### Chạy thủ công

Bạn có thể chạy kiểm tra thủ công bất kỳ lúc nào bằng lệnh:

```bash
./scripts/check-colors.sh
```
