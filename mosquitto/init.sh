#!/bin/sh
# Tạo file mật khẩu MQTT từ biến môi trường rồi chạy mosquitto (không in giá trị mật khẩu)
set -eu

if [ -z "${MQTT_AI_PASSWORD:-}" ] || [ -z "${MQTT_BACKEND_PASSWORD:-}" ]; then
    echo "init.sh: thiếu MQTT_AI_PASSWORD hoặc MQTT_BACKEND_PASSWORD trong môi trường, dừng mosquitto" >&2
    exit 1
fi

mkdir -p /mosquitto/auth
mosquitto_passwd -c -b /mosquitto/auth/passwd icss-ai "$MQTT_AI_PASSWORD"
mosquitto_passwd -b /mosquitto/auth/passwd icss-backend "$MQTT_BACKEND_PASSWORD"
chown mosquitto:mosquitto /mosquitto/auth/passwd
chmod 0600 /mosquitto/auth/passwd
cp /mosquitto/config/acl /mosquitto/auth/acl
chown mosquitto:mosquitto /mosquitto/auth/acl
chmod 0600 /mosquitto/auth/acl

exec mosquitto -c /mosquitto/config/mosquitto.conf
