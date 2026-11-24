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

# Chứng chỉ TLS truyền qua biến môi trường (base64) cho nền tảng không mount được thư mục certs/.
# Không có biến nào thì giữ nguyên hành vi cũ (dùng chứng chỉ mount sẵn nếu có). Không in giá trị biến ra log.
if [ -n "${MQTT_TLS_CA_B64:-}" ] || [ -n "${MQTT_TLS_CERT_B64:-}" ] || [ -n "${MQTT_TLS_KEY_B64:-}" ]; then
    if [ -z "${MQTT_TLS_CA_B64:-}" ] || [ -z "${MQTT_TLS_CERT_B64:-}" ] || [ -z "${MQTT_TLS_KEY_B64:-}" ]; then
        echo "init.sh: cần đủ cả MQTT_TLS_CA_B64, MQTT_TLS_CERT_B64 và MQTT_TLS_KEY_B64, dừng mosquitto" >&2
        exit 1
    fi
    mkdir -p "$CERT_DIR"
    # Ghi thử một file: chạy bằng root thì "test -w" không nhận ra thư mục mount read-only
    if ! (: > "$CERT_DIR/.write-test") 2>/dev/null; then
        echo "init.sh: không ghi được vào $CERT_DIR (đang mount read-only?). Dùng MQTT_TLS_*_B64 hoặc mount certs/, không dùng cả hai; dừng mosquitto" >&2
        exit 1
    fi
    rm -f "$CERT_DIR/.write-test"
    # decode_b64 <tên biến> <file đích> <quyền>: ghi ra file tạm rồi đổi tên, tránh để lại file dở dang
    decode_b64() {
        eval "_val=\${$1}"
        _tmp="$2.tmp.$$"
        if ! printf '%s' "$_val" | base64 -d > "$_tmp" 2>/dev/null || [ ! -s "$_tmp" ]; then
            rm -f "$_tmp"
            echo "init.sh: không giải mã được $1 (base64 không hợp lệ hoặc rỗng), dừng mosquitto" >&2
            exit 1
        fi
        chown mosquitto:mosquitto "$_tmp"
        chmod "$3" "$_tmp"
        mv -f "$_tmp" "$2"
        unset _val
    }
    old_umask=$(umask)
    umask 077
    decode_b64 MQTT_TLS_CA_B64 "$CERT_DIR/ca.crt" 0644
    decode_b64 MQTT_TLS_CERT_B64 "$CERT_DIR/server.crt" 0644
    decode_b64 MQTT_TLS_KEY_B64 "$CERT_DIR/server.key" 0600
    umask "$old_umask"
    echo "init.sh: Đã ghi chứng chỉ TLS từ biến môi trường MQTT_TLS_*_B64 vào $CERT_DIR"
fi

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
