# Hướng dẫn tạo Chứng chỉ SSL/TLS tự ký cho Môi trường Phát triển (Dev/Test)

Tài liệu này hướng dẫn cách tạo Root CA, chứng chỉ server cho Eclipse Mosquitto, và Java Truststore cho Backend để kiểm thử tính năng **MQTT qua TLS (cổng 8883)** và **MQTT qua WebSockets + TLS / WSS (cổng 8884)**.

---

## 1. Sinh chứng chỉ tự động

### Trên Windows (PowerShell):
```powershell
.\scripts\generate-dev-certs.ps1
```

### Trên Linux / macOS / Git Bash:
```bash
chmod +x ./scripts/generate-dev-certs.sh
./scripts/generate-dev-certs.sh
```

Các file được tạo trong thư mục `mosquitto/certs/`:
- `ca.crt`: Chứng chỉ Root CA (public).
- `ca.key`: Khóa bí mật của Root CA (private, không chia sẻ).
- `server.crt`: Chứng chỉ máy chủ Mosquitto (đã ký bởi Root CA với SAN `localhost`, `127.0.0.1`, `host.docker.internal`).
- `server.key`: Khóa bí mật máy chủ Mosquitto.
- `truststore.jks`: Java Keystore chứa `ca.crt` (mật khẩu mặc định: `changeit`).

> ⚠️ **LƯU Ý:** Thư mục `mosquitto/certs/*` và các file `*.key`, `*.crt`, `*.jks`, `*.p12` đã được cấu hình trong `.gitignore`. **Tuyệt đối không commit các file chứng chỉ và khoá bí mật vào Git repository.**

---

## 2. Cấu hình các thành phần sử dụng TLS

### A. Mosquitto Broker (Docker Compose)
`docker-compose.yml` đã mount thư mục `./mosquitto/certs` vào `/mosquitto/certs:ro`.
Khi khởi động, `mosquitto/init.sh` tự động phát hiện các file chứng chỉ:
- Mở cổng `8883`: MQTTS (MQTT qua TLS).
- Mở cổng `8884`: WSS (MQTT qua WebSockets bảo mật).
- Giữ cổng `1883`: Plain TCP nội bộ (chỉ bind `127.0.0.1` trên Host).

Nếu không có file chứng chỉ trong `mosquitto/certs`, Mosquitto tự động bỏ qua TLS và chỉ chạy cổng `1883` bình thường.

### B. AI Service (Python Edge)
Trong file cấu hình hoặc biến môi trường (`ai-service/.env`):
```properties
MQTT_BROKER_HOST=localhost
MQTT_BROKER_PORT=8883
MQTT_TLS_ENABLED=true
MQTT_TLS_CA_PATH=../mosquitto/certs/ca.crt
MQTT_TLS_INSECURE=false
MQTT_TRANSPORT=tcp
```
- Nếu dùng WebSockets qua cổng 8884:
  `MQTT_BROKER_PORT=8884`
  `MQTT_TRANSPORT=websockets`

### C. Backend (Spring Boot / Java 21)
Trong biến môi trường của Backend:
```properties
MQTT_BROKER_URL=ssl://localhost:8883
MQTT_TRUSTSTORE_PATH=../mosquitto/certs/truststore.jks
MQTT_TRUSTSTORE_PASSWORD=changeit
MQTT_TRUSTSTORE_TYPE=JKS
```
Khi `MQTT_BROKER_URL` bắt đầu bằng `ssl://` và có `MQTT_TRUSTSTORE_PATH`, `MqttConfig` sẽ tự động khởi tạo `SSLSocketFactory` tin tưởng chứng chỉ CA tự ký.
