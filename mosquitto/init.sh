#!/bin/sh
# Tạo file mật khẩu MQTT từ biến môi trường rồi chạy mosquitto (không in giá trị mật khẩu)
set -eu

if [ -z "${MQTT_AI_PASSWORD:-}" ] || [ -z "${MQTT_BACKEND_PASSWORD:-}" ]; then
    echo "init.sh: thiếu MQTT_AI_PASSWORD hoặc MQTT_BACKEND_PASSWORD trong môi trường, dừng mosquitto" >&2
    exit 1
fi

mkdir -p /mosquitto/auth /mosquitto/data
chown -R mosquitto:mosquitto /mosquitto/data || true
mosquitto_passwd -c -b /mosquitto/auth/passwd icss-ai "$MQTT_AI_PASSWORD"
mosquitto_passwd -b /mosquitto/auth/passwd icss-backend "$MQTT_BACKEND_PASSWORD"
chown mosquitto:mosquitto /mosquitto/auth/passwd
chmod 0600 /mosquitto/auth/passwd
cp /mosquitto/config/acl /mosquitto/auth/acl
chown mosquitto:mosquitto /mosquitto/auth/acl
chmod 0600 /mosquitto/auth/acl

# Chuẩn bị file cấu hình chạy runtime (hỗ trợ bật TLS động nếu có chứng chỉ mount vào)
cp /mosquitto/config/mosquitto.conf /mosquitto/auth/mosquitto.conf

CERT_DIR="/mosquitto/certs"
if [ -f "$CERT_DIR/server.crt" ] && [ -f "$CERT_DIR/server.key" ] && [ -f "$CERT_DIR/ca.crt" ]; then
    echo "init.sh: Đã tìm thấy chứng chỉ TLS trong $CERT_DIR, kích hoạt listener 8883 (TLS) và listener 8884 (WSS)..."
    cat << 'EOF' >> /mosquitto/auth/mosquitto.conf

# Listener 8883: MQTT qua TLS
listener 8883 0.0.0.0
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key

# Listener 8884: MQTT qua WebSockets + TLS (WSS)
listener 8884 0.0.0.0
protocol websockets
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
EOF
else
    echo "init.sh: Không tìm thấy đầy đủ chứng chỉ TLS trong $CERT_DIR (cần server.crt, server.key, ca.crt). Broker khởi động ở chế độ TCP thường (listener 1883)."
fi

chown mosquitto:mosquitto /mosquitto/auth/mosquitto.conf
chmod 0600 /mosquitto/auth/mosquitto.conf

exec mosquitto -c /mosquitto/auth/mosquitto.conf
